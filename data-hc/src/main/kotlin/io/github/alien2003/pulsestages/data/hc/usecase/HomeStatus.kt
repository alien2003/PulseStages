package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcFeature
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.NightPipeline
import io.github.alien2003.pulsestages.data.local.db.BackfillStateDao
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.db.SourceSessionDao
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.data.local.sync.SyncState
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** State of a special, feature-gated Health Connect permission. */
enum class SpecialAccess {
    /** Granted. */
    GRANTED,

    /** Can be requested but is not granted. */
    NOT_GRANTED,

    /** This Health Connect version does not support it (cannot be requested). */
    NOT_AVAILABLE,
}

/**
 * Granted permissions vs what the app needs.
 *
 * @property granted every granted Health Connect permission.
 * @property missingRequired required permissions (READ_SLEEP, WRITE_SLEEP, READ_HEART_RATE, READ_STEPS) not granted.
 * @property missingOptional optional channels (SpO2, respiratory rate, HRV) not granted.
 * @property history READ_HEALTH_DATA_HISTORY: without it Health Connect returns only about 30 days of history.
 * @property background READ_HEALTH_DATA_IN_BACKGROUND: without it the periodic runs cannot read.
 */
data class PermissionStatus(
    val granted: Set<String>,
    val missingRequired: Set<String>,
    val missingOptional: Set<String>,
    val history: SpecialAccess,
    val background: SpecialAccess,
) {
    /** Every required permission is granted. */
    val allRequiredGranted: Boolean get() = missingRequired.isEmpty()

    /** Permissions to request now: missing required and optional ones plus the requestable missing special ones. */
    val requestable: Set<String>
        get() =
            linkedSetOf<String>().apply {
                addAll(missingRequired)
                addAll(missingOptional)
                if (history == SpecialAccess.NOT_GRANTED) add(HealthPermissions.READ_HEALTH_DATA_HISTORY)
                if (background == SpecialAccess.NOT_GRANTED) add(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            }
}

/**
 * Health Connect as the app sees it right now.
 *
 * @property availability installed / needs an update / not supported.
 * @property permissions the permission state, or null when Health Connect is not available or the permissions could
 *   not be read.
 */
data class HealthAccess(
    val availability: HcAvailability,
    val permissions: PermissionStatus?,
)

/**
 * The last new-nights sync.
 *
 * @property startedAtMs start of the last run, or null when it never ran.
 * @property finishedAtMs end of the last finished run, or null.
 * @property outcome how the last finished run ended, or null.
 * @property detail English detail of the last finished run, or null.
 */
data class LastRun(
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
    val outcome: SyncOutcome?,
    val detail: String?,
) {
    /** A run is in progress (or the process died during one). */
    val inProgress: Boolean get() = startedAtMs != null && (finishedAtMs == null || startedAtMs > finishedAtMs)

    /** Constructors. */
    companion object {
        /** The last run recorded in [state]. */
        fun of(state: SyncState): LastRun =
            LastRun(state.lastRunStartedAtMs, state.lastRunFinishedAtMs, state.lastRunOutcome, state.lastRunDetail)
    }
}

/**
 * Everything the Home screen shows (the next scheduled run comes from :work's WorkScheduler).
 *
 * @property access Health Connect availability and permissions.
 * @property enabledSources enabled sleep sources in priority order.
 * @property hrSources origin of heart rate and steps.
 * @property overwritePolicy what runs do with nights that already have a session (NEVER: recomputes do not rewrite
 *   them either).
 * @property lastRun the last new-nights sync.
 * @property counters source sessions found / estimated / skipped / failed and nights stale with the CURRENT params hash.
 * @property paramsHash the current params hash.
 * @property backfill the backfill state, or null when no backfill was ever started.
 * @property activeModel the active personal model, or null when the global models are used.
 */
data class HomeStatus(
    val access: HealthAccess,
    val enabledSources: List<String>,
    val hrSources: HrSourcesMode,
    val overwritePolicy: OverwritePolicy,
    val lastRun: LastRun,
    val counters: HomeCounters,
    val paramsHash: String,
    val backfill: BackfillStateEntity?,
    val activeModel: PersonalModelEntity?,
)

/**
 * Aggregates the Home screen's [HomeStatus] from Health Connect (availability, permissions, features), the settings,
 * [SyncStateStore], the ledger counters (stale counted with the current params hash, which follows the settings, the
 * granted optional channels and the active personal model) and the backfill state. Availability and permissions cannot
 * be observed: they are read when
 * [status] is first collected and again after every [refresh] (call it on resume and after a permission request).
 */
@Singleton
class HomeStatusAggregator
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val settingsRepository: SettingsRepository,
        private val syncState: SyncStateStore,
        private val sourceDao: SourceSessionDao,
        private val backfillDao: BackfillStateDao,
        private val models: PersonalModelStore,
        private val pipeline: NightPipeline,
    ) {
        private val refreshes = MutableStateFlow(0)

        /** Re-reads availability and permissions for every collector of [status]. */
        fun refresh() {
            refreshes.update { it + 1 }
        }

        /** The Home screen state; emits on every change of its parts. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun status(): Flow<HomeStatus> = refreshes.mapLatest { readAccess() }.flatMapLatest { health -> statusWith(health) }

        /**
         * The Home screen state with [health] (read once per refresh). The params hash of the stale counter depends on
         * the granted optional channels; while the permissions are unknown (Health Connect unavailable) the optional
         * channels count as granted, as on a fully set-up device.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun statusWith(health: HealthAccess): Flow<HomeStatus> {
            val granted = health.permissions?.granted ?: HealthPermissions.OPTIONAL
            val counters =
                combine(settingsRepository.settings, models.activeRow()) { settings, model -> HashKey(settings, model?.fingerprint) }
                    .distinctUntilChanged()
                    .mapLatest { key -> pipeline.currentParamsHash(key.settings, granted) }
                    .distinctUntilChanged()
                    .flatMapLatest { hash -> sourceDao.observeHomeCounters(ALGO_VERSION, hash).map { HashedCounters(hash, it) } }
            val local =
                combine(
                    settingsRepository.settings,
                    syncState.state,
                    backfillDao.observe(),
                    models.activeRow(),
                ) { settings, sync, backfill, model ->
                    LocalParts(settings, LastRun.of(sync), backfill, model)
                }
            return combine(counters, local) { hashed, parts ->
                HomeStatus(
                    access = health,
                    enabledSources = enabledInPriorityOrder(parts.settings),
                    hrSources = parts.settings.sources.hrSources,
                    overwritePolicy = parts.settings.write.overwritePolicy,
                    lastRun = parts.lastRun,
                    counters = hashed.counters,
                    paramsHash = hashed.hash,
                    backfill = parts.backfill,
                    activeModel = parts.model,
                )
            }
        }

        /** Availability and permissions now; never throws. */
        suspend fun readAccess(): HealthAccess {
            val availability = repository.availability()
            val granted = if (availability == HcAvailability.AVAILABLE) grantedOrNull() else null
            return HealthAccess(availability, granted?.let(::permissionStatus))
        }

        private suspend fun grantedOrNull(): Set<String>? =
            try {
                repository.grantedPermissions()
            } catch (ignored: HealthDataException) {
                // Shown as "permissions unknown"; the next refresh tries again.
                null
            }

        private fun permissionStatus(granted: Set<String>): PermissionStatus =
            PermissionStatus(
                granted = granted,
                missingRequired = HealthPermissions.missingRequired(granted),
                missingOptional = HealthPermissions.OPTIONAL.filterNotTo(linkedSetOf()) { it in granted },
                history = special(HealthPermissions.READ_HEALTH_DATA_HISTORY, HcFeature.READ_HISTORY, granted),
                background = special(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND, HcFeature.READ_IN_BACKGROUND, granted),
            )

        private fun special(
            permission: String,
            feature: HcFeature,
            granted: Set<String>,
        ): SpecialAccess =
            when {
                permission in granted -> SpecialAccess.GRANTED
                repository.isFeatureAvailable(feature) -> SpecialAccess.NOT_GRANTED
                else -> SpecialAccess.NOT_AVAILABLE
            }

        private fun enabledInPriorityOrder(settings: AppSettings): List<String> {
            val enabled = settings.sources.sourcePackages
            return (settings.sources.sourcePriority.filter { it in enabled } + enabled).distinct()
        }

        /** What the params hash depends on besides the algorithm version. */
        private data class HashKey(
            val settings: AppSettings,
            val modelFingerprint: String?,
        )

        private class HashedCounters(
            val hash: String,
            val counters: HomeCounters,
        )

        private class LocalParts(
            val settings: AppSettings,
            val lastRun: LastRun,
            val backfill: BackfillStateEntity?,
            val model: PersonalModelEntity?,
        )
    }
