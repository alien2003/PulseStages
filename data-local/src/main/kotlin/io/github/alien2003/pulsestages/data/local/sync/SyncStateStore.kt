package io.github.alien2003.pulsestages.data.local.sync

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
 * The sync request that PulseStages owes Sleep as Android (`write.requestSleepAsAndroidSync`). A request is sent only
 * once the source sessions Sleep as Android would import are complete and PulseStages has brought its records of them
 * up to date ("settled") for a while, so what is owed and how long the nights have looked settled are kept here
 * between runs. Nothing is owed while [dueAtMs] is null; a stored [followUp] without it is read as false.
 *
 * @property dueAtMs a request is owed, not before this time; null when nothing is owed.
 * @property followUp after the owed request is sent, one more is owed `SleepAsAndroidSyncRequests.FOLLOW_UP_DELAY_MS`
 *   later, so that Sleep as Android adds heart rate and SpO2 to the nights the first request imported.
 * @property owedSinceMs the latest write or send that left a request owed; an owed request is dropped when it is too old.
 * @property settledKey the key of the source-session versions of the current settled streak (the nights looked settled at
 *   every evaluation since [settledSinceMs]), or null when there is no streak.
 * @property settledSinceMs the first settled evaluation of the current streak, or null when there is no streak.
 * @property lastSentAtMs when a request was last sent, or null if none was sent yet.
 * @property sentKey [settledKey] when the last request was sent: a later version of one of its sessions with another
 *   start or end may not reach Sleep as Android, which is logged once; null when there is nothing to check. The nights
 *   PulseStages changes are compared through [sentNightsKey].
 * @property sentNightsKey the WRITTEN nights of the ledger linked to the sessions of the last request when it was sent,
 *   as `clientRecordId:sessionStartMs:sessionEndMs` sorted and joined by `;`: a later night of one of them with another
 *   start or end is notified (Sleep as Android keeps the version it imported); null when there is none.
 * @property noticedKey the version of those nights that each notice shown since the last request now shows, in the same
 *   format (a version that a newer notice replaced or that was withdrawn since is no longer in it); null when there is
 *   none.
 */
data class SleepAsAndroidRequestState(
    val dueAtMs: Long? = null,
    val followUp: Boolean = false,
    val owedSinceMs: Long? = null,
    val settledKey: String? = null,
    val settledSinceMs: Long? = null,
    val lastSentAtMs: Long? = null,
    val sentKey: String? = null,
    val sentNightsKey: String? = null,
    val noticedKey: String? = null,
)

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
 * @property sleepAsAndroidRequest the sync request owed to Sleep as Android and how long the nights have looked settled.
 * @property lateDataPassAtMs start of the run whose late-data pass (the retry and the refresh of recent nights, at most
 *   every `NewNightsSync.MIN_RECHECK_GAP_MS`) last completed, or null if none has yet.
 * @property lateDataPassRequestedAtMs the time at which a late-data pass was last asked for, or null if none was: a run
 *   that starts at or after it runs the pass even within `NewNightsSync.MIN_RECHECK_GAP_MS` of the last one, unless a
 *   pass of a run that started at or after it has already completed.
 */
data class SyncState(
    val changesToken: ChangesTokenState? = null,
    val lastRunStartedAtMs: Long? = null,
    val lastRunFinishedAtMs: Long? = null,
    val lastRunOutcome: SyncOutcome? = null,
    val lastRunDetail: String? = null,
    val lastEmAdaptationNightCount: Int = 0,
    val sleepAsAndroidRequest: SleepAsAndroidRequestState = SleepAsAndroidRequestState(),
    val lateDataPassAtMs: Long? = null,
    val lateDataPassRequestedAtMs: Long? = null,
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

        /** Stores that the late-data pass of the run started at [atMs] completed (replacing the time of an earlier one). */
        suspend fun markLateDataPass(atMs: Long) {
            dataStore.edit { it[LATE_DATA_PASS_AT] = atMs }
        }

        /**
         * Replaces the Sleep as Android request state with [transform] of the stored one in one edit, which also removes
         * the follow-up time of 0.4.0 (a state read from it is stored under the new keys). A request for a late-data pass
         * at [requestLateDataPassAtMs] is stored in the same edit and replaces a stored one: the requests are made at the
         * current time, so a stored request is later only when the clock was set back, and it would then force no pass
         * until the clock reached it.
         */
        suspend fun updateSleepAsAndroidRequest(
            requestLateDataPassAtMs: Long? = null,
            transform: (SleepAsAndroidRequestState) -> SleepAsAndroidRequestState,
        ) {
            dataStore.edit { prefs ->
                prefs.writeSleepAsAndroidRequest(transform(prefs.readSleepAsAndroidRequest()))
                if (requestLateDataPassAtMs != null) prefs[LATE_DATA_PASS_REQUESTED_AT] = requestLateDataPassAtMs
            }
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
             * Due time of the follow-up request of 0.4.0, which sent its first request right after a write. While
             * [SAA_REQUEST_DUE_AT] is absent it is read as an owed request without a follow-up, so that a pending one
             * waits for complete nights too; [updateSleepAsAndroidRequest] removes it rather than keeping the owed time
             * there, because after a downgrade 0.4.0 would send a request it finds there without waiting for them.
             */
            private val SAA_FOLLOW_UP_DUE_AT = longPreferencesKey("sleep_as_android_follow_up_due_at")
            private val SAA_REQUEST_DUE_AT = longPreferencesKey("sleep_as_android_request_due_at")
            private val SAA_REQUEST_FOLLOW_UP = booleanPreferencesKey("sleep_as_android_request_follow_up")
            private val SAA_REQUEST_OWED_SINCE = longPreferencesKey("sleep_as_android_request_owed_since")
            private val SAA_SETTLED_KEY = stringPreferencesKey("sleep_as_android_settled_key")
            private val SAA_SETTLED_SINCE = longPreferencesKey("sleep_as_android_settled_since")
            private val SAA_LAST_SENT_AT = longPreferencesKey("sleep_as_android_last_sent_at")
            private val SAA_SENT_KEY = stringPreferencesKey("sleep_as_android_sent_key")
            private val SAA_SENT_NIGHTS = stringPreferencesKey("sleep_as_android_sent_nights")
            private val SAA_NOTICED = stringPreferencesKey("sleep_as_android_noticed")
            private val LATE_DATA_PASS_AT = longPreferencesKey("late_data_pass_at")
            private val LATE_DATA_PASS_REQUESTED_AT = longPreferencesKey("late_data_pass_requested_at")

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

            /**
             * The stored request state. Without [SAA_REQUEST_DUE_AT], a 0.4.0 follow-up time ([SAA_FOLLOW_UP_DUE_AT]) is
             * read as a request owed since then, without a follow-up and without a settled streak (it stands for a
             * write, which starts a new one).
             */
            private fun Preferences.readSleepAsAndroidRequest(): SleepAsAndroidRequestState {
                val stored =
                    SleepAsAndroidRequestState(
                        dueAtMs = this[SAA_REQUEST_DUE_AT],
                        followUp = this[SAA_REQUEST_FOLLOW_UP] ?: false,
                        owedSinceMs = this[SAA_REQUEST_OWED_SINCE],
                        settledKey = this[SAA_SETTLED_KEY],
                        settledSinceMs = this[SAA_SETTLED_SINCE],
                        lastSentAtMs = this[SAA_LAST_SENT_AT],
                        sentKey = this[SAA_SENT_KEY],
                        sentNightsKey = this[SAA_SENT_NIGHTS],
                        noticedKey = this[SAA_NOTICED],
                    )
                val legacyDueAt = this[SAA_FOLLOW_UP_DUE_AT]
                val state =
                    if (stored.dueAtMs == null && legacyDueAt != null) {
                        stored.copy(
                            dueAtMs = legacyDueAt,
                            followUp = false,
                            owedSinceMs = legacyDueAt,
                            settledKey = null,
                            settledSinceMs = null,
                        )
                    } else {
                        stored
                    }
                return if (state.followUp && state.dueAtMs == null) state.copy(followUp = false) else state
            }

            /** Stores [state] (a null value removes its key) and removes the 0.4.0 follow-up time. */
            private fun MutablePreferences.writeSleepAsAndroidRequest(state: SleepAsAndroidRequestState) {
                setOrRemove(SAA_REQUEST_DUE_AT, state.dueAtMs)
                setOrRemove(SAA_REQUEST_FOLLOW_UP, state.followUp.takeIf { it })
                setOrRemove(SAA_REQUEST_OWED_SINCE, state.owedSinceMs)
                setOrRemove(SAA_SETTLED_KEY, state.settledKey)
                setOrRemove(SAA_SETTLED_SINCE, state.settledSinceMs)
                setOrRemove(SAA_LAST_SENT_AT, state.lastSentAtMs)
                setOrRemove(SAA_SENT_KEY, state.sentKey)
                setOrRemove(SAA_SENT_NIGHTS, state.sentNightsKey)
                setOrRemove(SAA_NOTICED, state.noticedKey)
                remove(SAA_FOLLOW_UP_DUE_AT)
            }

            private fun <T> MutablePreferences.setOrRemove(
                key: Preferences.Key<T>,
                value: T?,
            ) {
                if (value == null) remove(key) else this[key] = value
            }

            private fun Preferences.toSyncState(): SyncState =
                SyncState(
                    changesToken = readToken(),
                    lastRunStartedAtMs = this[RUN_STARTED_AT],
                    lastRunFinishedAtMs = this[RUN_FINISHED_AT],
                    lastRunOutcome = this[RUN_OUTCOME]?.let { name -> SyncOutcome.entries.firstOrNull { it.name == name } },
                    lastRunDetail = this[RUN_DETAIL],
                    lastEmAdaptationNightCount = this[EM_NIGHT_COUNT] ?: 0,
                    sleepAsAndroidRequest = readSleepAsAndroidRequest(),
                    lateDataPassAtMs = this[LATE_DATA_PASS_AT],
                    lateDataPassRequestedAtMs = this[LATE_DATA_PASS_REQUESTED_AT],
                )
        }
    }
