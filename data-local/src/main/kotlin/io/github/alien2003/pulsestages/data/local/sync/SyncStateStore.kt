package io.github.alien2003.pulsestages.data.local.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the Preferences DataStore that backs [SyncStateStore]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SyncStatePreferences

/**
 * A Health Connect changes token and the package set it was created for.
 *
 * @property token the token to pass to the next `getChanges` call.
 * @property packages sorted, distinct package names of the token's data-origin filter.
 * @property createdAtMs when a token for this package set was first obtained with `getChangesToken`.
 * @property updatedAtMs when the token was last replaced (by `nextChangesToken` of a processed page).
 */
data class ChangesTokenState(
    val token: String,
    val packages: List<String>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** How the last new-nights sync run ended. */
enum class SyncOutcome {
    /** Every night was processed. */
    SUCCESS,

    /** The run completed but some nights failed (see the log). */
    PARTIAL_FAILURE,

    /** Health Connect rate limit / quota hit; the run will be retried with backoff. */
    QUOTA_EXCEEDED,

    /** A required Health Connect permission is missing. */
    PERMISSION_DENIED,

    /** Health Connect is not available. */
    HEALTH_CONNECT_UNAVAILABLE,

    /** A background run without READ_HEALTH_DATA_IN_BACKGROUND: nothing was read. */
    BACKGROUND_READ_BLOCKED,

    /** The run stopped because of an unexpected error (see the log). */
    FAILED,

    /** The run was cancelled. */
    CANCELLED,
}

/**
 * Everything in [SyncStateStore].
 *
 * @property changesToken the stored changes token, or null when there is none.
 * @property lastRunStartedAtMs start of the last run, null if never run; a start after [lastRunFinishedAtMs] means
 *   the run is in progress or the process died during it.
 * @property lastRunFinishedAtMs end of the last finished run, null if none finished yet.
 * @property lastRunOutcome outcome of the last finished run.
 * @property lastRunDetail English detail for the log / debug screen, or null.
 * @property lastEmAdaptationNightCount number of estimated nights at the last population-level EM adaptation.
 */
data class SyncState(
    val changesToken: ChangesTokenState? = null,
    val lastRunStartedAtMs: Long? = null,
    val lastRunFinishedAtMs: Long? = null,
    val lastRunOutcome: SyncOutcome? = null,
    val lastRunDetail: String? = null,
    val lastEmAdaptationNightCount: Int = 0,
)

/**
 * Small persistent state of the new-nights sync. A changes token is only valid for the exact package set it was
 * created for: [tokenFor] returns null for any other set, so a change of enabled sources always starts with a new
 * token (and the caller's re-read of recent nights).
 */
@Singleton
class SyncStateStore
    @Inject
    constructor(
        @param:SyncStatePreferences private val dataStore: DataStore<Preferences>,
        private val clock: Clock,
    ) {
        /** The whole state; emits on every change. */
        val state: Flow<SyncState> = dataStore.data.map { it.toSyncState() }

        /** The stored token if it was created for exactly [packages] (order and duplicates do not matter). */
        suspend fun tokenFor(packages: Collection<String>): ChangesTokenState? {
            val stored = dataStore.data.first().readToken() ?: return null
            return stored.takeIf { it.packages == normalize(packages) }
        }

        /** Stores [token] just obtained with `getChangesToken` for [packages], replacing any other token. */
        suspend fun saveNewToken(
            packages: Collection<String>,
            token: String,
        ) {
            val now = clock.millis()
            dataStore.edit { it.writeToken(token, normalize(packages), createdAtMs = now, updatedAtMs = now) }
        }

        /**
         * Stores [token] (`nextChangesToken` of a processed page) for [packages]. Keeps the creation time when the
         * stored token belongs to the same package set; otherwise behaves like [saveNewToken].
         */
        suspend fun advanceToken(
            packages: Collection<String>,
            token: String,
        ) {
            val now = clock.millis()
            val normalized = normalize(packages)
            dataStore.edit { prefs ->
                val createdAt = prefs.readToken()?.takeIf { it.packages == normalized }?.createdAtMs ?: now
                prefs.writeToken(token, normalized, createdAtMs = createdAt, updatedAtMs = now)
            }
        }

        /** Forgets the token (e.g. after `changesTokenExpired`). */
        suspend fun clearToken() {
            dataStore.edit { prefs ->
                prefs.remove(TOKEN)
                prefs.remove(TOKEN_PACKAGES)
                prefs.remove(TOKEN_CREATED_AT)
                prefs.remove(TOKEN_UPDATED_AT)
            }
        }

        /** Records the start of a run at the current time. */
        suspend fun markRunStarted() {
            val now = clock.millis()
            dataStore.edit { it[RUN_STARTED_AT] = now }
        }

        /** Records the end of a run at the current time. */
        suspend fun markRunFinished(
            outcome: SyncOutcome,
            detail: String? = null,
        ) {
            val now = clock.millis()
            dataStore.edit { prefs ->
                prefs[RUN_FINISHED_AT] = now
                prefs[RUN_OUTCOME] = outcome.name
                if (detail == null) prefs.remove(RUN_DETAIL) else prefs[RUN_DETAIL] = detail
            }
        }

        /** Stores the night count at which population-level EM adaptation last ran. */
        suspend fun setLastEmAdaptationNightCount(count: Int) {
            dataStore.edit { it[EM_NIGHT_COUNT] = count }
        }

        /** Factory and keys. */
        companion object {
            /** File name passed to `preferencesDataStoreFile`. */
            const val FILE_NAME: String = "sync_state"

            private val TOKEN = stringPreferencesKey("changes_token")
            private val TOKEN_PACKAGES = stringSetPreferencesKey("changes_token_packages")
            private val TOKEN_CREATED_AT = longPreferencesKey("changes_token_created_at")
            private val TOKEN_UPDATED_AT = longPreferencesKey("changes_token_updated_at")
            private val RUN_STARTED_AT = longPreferencesKey("last_run_started_at")
            private val RUN_FINISHED_AT = longPreferencesKey("last_run_finished_at")
            private val RUN_OUTCOME = stringPreferencesKey("last_run_outcome")
            private val RUN_DETAIL = stringPreferencesKey("last_run_detail")
            private val EM_NIGHT_COUNT = intPreferencesKey("last_em_adaptation_night_count")

            /**
             * Creates the Preferences DataStore over [produceFile] (the file name must end in `.preferences_pb`).
             * A corrupted file is replaced by an empty state: the next run simply obtains a new token.
             */
            fun createDataStore(
                scope: CoroutineScope,
                produceFile: () -> File,
            ): DataStore<Preferences> =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = produceFile,
                )

            private fun normalize(packages: Collection<String>): List<String> = packages.distinct().sorted()

            private fun Preferences.readToken(): ChangesTokenState? {
                val token = this[TOKEN]
                val packages = this[TOKEN_PACKAGES]
                return if (token == null || packages == null) {
                    null
                } else {
                    ChangesTokenState(
                        token = token,
                        packages = packages.sorted(),
                        createdAtMs = this[TOKEN_CREATED_AT] ?: 0L,
                        updatedAtMs = this[TOKEN_UPDATED_AT] ?: 0L,
                    )
                }
            }

            private fun MutablePreferences.writeToken(
                token: String,
                packages: List<String>,
                createdAtMs: Long,
                updatedAtMs: Long,
            ) {
                this[TOKEN] = token
                this[TOKEN_PACKAGES] = packages.toSet()
                this[TOKEN_CREATED_AT] = createdAtMs
                this[TOKEN_UPDATED_AT] = updatedAtMs
            }

            private fun Preferences.toSyncState(): SyncState =
                SyncState(
                    changesToken = readToken(),
                    lastRunStartedAtMs = this[RUN_STARTED_AT],
                    lastRunFinishedAtMs = this[RUN_FINISHED_AT],
                    lastRunOutcome = this[RUN_OUTCOME]?.let { name -> SyncOutcome.entries.firstOrNull { it.name == name } },
                    lastRunDetail = this[RUN_DETAIL],
                    lastEmAdaptationNightCount = this[EM_NIGHT_COUNT] ?: 0,
                )
        }
    }
