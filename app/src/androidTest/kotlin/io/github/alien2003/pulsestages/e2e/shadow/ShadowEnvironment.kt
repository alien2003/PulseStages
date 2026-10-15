package io.github.alien2003.pulsestages.e2e.shadow

import androidx.room.Room
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillEngine
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.pipeline.NightCandidateBuilder
import io.github.alien2003.pulsestages.data.hc.pipeline.NightLedger
import io.github.alien2003.pulsestages.data.hc.pipeline.NightPipeline
import io.github.alien2003.pulsestages.data.hc.pipeline.NightRecomputer
import io.github.alien2003.pulsestages.data.hc.pipeline.NightWindowReader
import io.github.alien2003.pulsestages.data.hc.pipeline.ResourceDefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.SystemZoneOffsetProvider
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalModelStore
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.AppSettingsDataStoreFactory
import io.github.alien2003.pulsestages.data.local.settings.DataStoreSettingsRepository
import io.github.alien2003.pulsestages.data.local.settings.SettingsUpdateResult
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.work.estimate.SleepStagerNightEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * One isolated copy of the app's pipeline for a shadow scenario, wired like the app's Hilt graph but with:
 * - an in-memory Room ledger of the app's own database class ([PulseStagesDatabase]); the app's database file is never
 *   opened;
 * - settings and sync-state DataStores on fresh files below the app's cache directory
 *   (`cache/ps-e2e-shadow/<scenario>-<uuid>/`), deleted in [close]; the app's DataStore files are never opened;
 * - [ShadowWriteRepository] over [reads] (the real Health Connect for reads): no write or delete leaves the process;
 * - the real estimator (SleepStager with the bundled hybrid model from the app's assets, [hybridModels]) and the
 *   personal-model store of the in-memory ledger (empty: the global models);
 * - the suite's [logger] (the app's log table is never written);
 * - the system clock, which a scenario may move forward to simulate a later run ([advanceClock]).
 */
@Suppress("InjectDispatcher") // The suite wires the pipeline itself, like the app's Hilt modules.
class ShadowEnvironment(
    /** Name of the scenario (also the phase of the captured writes). */
    val name: String,
    reads: HealthRepository,
    hybridModels: HybridModelProvider,
    private val logger: E2eLogger,
) : Closeable {
    private val ownPackage = E2e.app.packageName
    private val clock = ShiftedClock(Clock.systemUTC())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The scenario's private directory (DataStore files). */
    val dir: File = File(root, "$name-${UUID.randomUUID()}").also { it.mkdirs() }

    /** The in-memory ledger. */
    val database: PulseStagesDatabase = Room.inMemoryDatabaseBuilder(E2e.app, PulseStagesDatabase::class.java).build()

    /** Settings on a temporary DataStore (validated like the app's). */
    val settings: DataStoreSettingsRepository =
        DataStoreSettingsRepository(
            AppSettingsDataStoreFactory.create(scope, onCorruption = {}) { File(dir, AppSettingsDataStoreFactory.FILE_NAME) },
        )

    /** Changes token and run state on a temporary DataStore. */
    val syncState: SyncStateStore =
        SyncStateStore(SyncStateStore.createDataStore(scope) { File(dir, "${SyncStateStore.FILE_NAME}.preferences_pb") }, clock)

    /** Health Connect as the pipeline sees it: real reads, captured writes. */
    val health: ShadowWriteRepository = ShadowWriteRepository(reads, ownPackage).also { it.phase = name }

    /** The real estimator, timed. */
    val estimator: TimedEstimator =
        TimedEstimator(SleepStagerNightEstimator(hybridModels, PersonalModelStore(database.personalModelDao(), clock, logger)))

    private val windowReader = NightWindowReader(health, NightCandidateBuilder(ownPackage, SystemZoneOffsetProvider), ownPackage)

    /** The pipeline. */
    val pipeline: NightPipeline =
        NightPipeline(
            repository = health,
            estimator = estimator,
            ledger = NightLedger(database.estimatedNightDao(), database.sourceSessionDao(), database.nightBaselineDao()),
            titles = ResourceDefaultTitleProvider(E2e.app),
            logger = logger,
            clock = clock,
            estimationDispatcher = Dispatchers.Default,
        )

    /** The history backfill. */
    val backfill: BackfillEngine = BackfillEngine(health, settings, database.backfillStateDao(), windowReader, pipeline, logger, clock)

    /** "Recompute all". */
    val recomputer: NightRecomputer =
        NightRecomputer(settings, database.estimatedNightDao(), windowReader, pipeline, HealthAccessGuard(health), logger, clock)

    /** The Changes-API sync of new nights. */
    val sync: NewNightsSync = NewNightsSync(health, settings, syncState, windowReader, pipeline, logger, clock)

    /** The time every component of this environment sees: the system time, moved forward by [advanceClock]. */
    fun nowMs(): Long = clock.millis()

    /**
     * Moves the time every component of this environment sees [byMs] forward, as if a later run happened (e.g. the
     * next hourly sync). Never backwards; Health Connect's own times are not affected.
     */
    fun advanceClock(byMs: Long) {
        require(byMs >= 0) { "the clock only moves forward: $byMs ms" }
        clock.advance(byMs)
    }

    /** Stores [value] as this scenario's settings; fails when the app's validator rejects them. */
    suspend fun useSettings(value: AppSettings): AppSettings {
        val result = settings.update { value }
        check(result is SettingsUpdateResult.Applied) { "the settings of scenario $name are invalid: $result" }
        return result.settings
    }

    override fun close() {
        database.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    /** The directory of every scenario. */
    companion object {
        /** `cache/ps-e2e-shadow/` of the app: only the suite writes there. */
        val root: File get() = File(E2e.app.cacheDir, "ps-e2e-shadow")

        /** Removes what scenarios of an interrupted run left behind (only [root]). */
        fun removeLeftovers() {
            root.deleteRecursively()
        }
    }
}

/** [base] moved forward by [offsetMs] ([advance]); the clocks [withZone] returns share the offset. */
private class ShiftedClock(
    private val base: Clock,
    private val offsetMs: AtomicLong = AtomicLong(0L),
) : Clock() {
    fun advance(byMs: Long) {
        offsetMs.addAndGet(byMs)
    }

    override fun getZone(): ZoneId = base.zone

    override fun withZone(zone: ZoneId): Clock = ShiftedClock(base.withZone(zone), offsetMs)

    override fun instant(): Instant = base.instant().plusMillis(offsetMs.get())

    override fun millis(): Long = base.millis() + offsetMs.get()
}
