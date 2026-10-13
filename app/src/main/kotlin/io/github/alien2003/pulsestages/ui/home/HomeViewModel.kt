package io.github.alien2003.pulsestages.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.pipeline.MyDataEraser
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.hc.usecase.HomeStatus
import io.github.alien2003.pulsestages.data.hc.usecase.HomeStatusAggregator
import io.github.alien2003.pulsestages.data.hc.usecase.LastRun
import io.github.alien2003.pulsestages.data.hc.usecase.PermissionStatus
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.platform.AppInfoResolver
import io.github.alien2003.pulsestages.ui.common.JobUi
import io.github.alien2003.pulsestages.ui.common.JobWatch
import io.github.alien2003.pulsestages.ui.common.RunSubject
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.WorkResultText
import io.github.alien2003.pulsestages.work.schedule.WorkJob
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import io.github.alien2003.pulsestages.work.worker.JobProgress
import io.github.alien2003.pulsestages.work.worker.WorkerProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * An enabled sleep source.
 *
 * @property packageName its package.
 * @property label its label from PackageManager, or null when the app is not visible (the screen falls back to a
 *   known name or the package name).
 */
@Immutable
data class SourceLabel(
    val packageName: String,
    val label: String?,
)

/** A confirmation dialog of the Home screen. */
enum class HomeDialog {
    /** "Recompute all nights?" */
    RECOMPUTE_ALL,

    /** "Delete all my sessions from Health Connect?" */
    DELETE_ALL,
}

/**
 * UI state of the Home screen.
 *
 * @property loading the status has not been read yet.
 * @property failed the status could not be read (retry offered).
 * @property availability Health Connect availability, null before the first check.
 * @property permissions the permission state; null when Health Connect is unavailable or the permissions are unknown.
 * @property permissionDialogBlocked the permission request was cancelled twice in a row: Health Connect no longer shows
 *   its dialog, the permissions must be granted in Health Connect's settings.
 * @property sources enabled sleep sources in priority order.
 * @property hrSources where heart rate and steps are read from.
 * @property overwritePolicy the overwrite policy (NEVER: recomputing does not rewrite nights that have a session).
 * @property lastRun the last new-nights sync.
 * @property nextRunMs when the periodic check or the daily run comes next (the earlier one), or null.
 * @property counters source sessions found / estimated / skipped / failed and stale nights.
 * @property estimateNow the "Estimate new" job.
 * @property backfill the history backfill job.
 * @property backfillState the stored backfill state (survives restarts), or null when none was ever started.
 * @property recompute the recompute-all / recompute-stale job.
 * @property erasing "Delete my sessions" is running.
 * @property activeModel kind of the active personal model, or null for the global models.
 * @property dialog the open confirmation dialog, or null.
 * @property messages queued Snackbar messages.
 */
@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val availability: HcAvailability? = null,
    val permissions: PermissionStatus? = null,
    val permissionDialogBlocked: Boolean = false,
    val sources: List<SourceLabel> = emptyList(),
    val hrSources: HrSourcesMode = HrSourcesMode.SAME_AS_SLEEP,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.SKIP_SAME_VERSION,
    val lastRun: LastRun? = null,
    val nextRunMs: Long? = null,
    val counters: HomeCounters? = null,
    val estimateNow: JobUi = JobUi(),
    val backfill: JobUi = JobUi(),
    val backfillState: BackfillStateEntity? = null,
    val recompute: JobUi = JobUi(),
    val erasing: Boolean = false,
    val activeModel: PersonalModelKind? = null,
    val dialog: HomeDialog? = null,
    val messages: List<UiMessage> = emptyList(),
) {
    /** Health Connect is available and every required permission is granted: the jobs can run. */
    val ready: Boolean get() = availability == HcAvailability.AVAILABLE && permissions?.allRequiredGranted == true

    /** Some permission can still be requested (the "Grant" button is shown). */
    val canRequestMore: Boolean get() = availability == HcAvailability.AVAILABLE && permissions?.requestable?.isNotEmpty() == true

    /** Progress of the backfill: of the running work, else of a stored backfill that will resume; null otherwise. */
    val backfillProgress: JobProgress?
        get() = backfill.progress ?: backfillState?.takeIf { it.status in RESUMABLE }?.let(WorkerProgress::of)

    /** A stored backfill is unfinished (interrupted or waiting for a retry) and the button resumes it. */
    val backfillResumable: Boolean get() = !backfill.active && backfillState?.status in RESUMABLE

    private companion object {
        val RESUMABLE = setOf(BackfillStatus.RUNNING, BackfillStatus.WAITING_RETRY)
    }
}

/**
 * The Home screen: Health Connect availability and permissions, sources, last / next run, counters, and the jobs
 * (estimate new, backfill, recompute all, delete my sessions). The status comes from [HomeStatusAggregator]; jobs are
 * started through [WorkScheduler] and followed through its status flows; a job the user started here reports its
 * result in a Snackbar when it finishes. "Delete my sessions" runs [MyDataEraser] directly (foreground).
 */
@Suppress("TooManyFunctions") // One function per user action of the screen.
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val homeStatus: HomeStatusAggregator,
        private val scheduler: WorkScheduler,
        private val eraser: MyDataEraser,
        private val appInfo: AppInfoResolver,
        private val logger: AppLogger,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val mutableState =
            MutableStateFlow(HomeUiState(permissionDialogBlocked = (savedState.get<Int>(KEY_REFUSALS) ?: 0) >= REFUSALS_UNTIL_BLOCKED))
        private val watches = WATCHED_JOBS.associateWith { JobWatch() }
        private val nextRuns = mutableMapOf<WorkJob, Long?>()
        private val labels = mutableMapOf<String, String?>()
        private var statusJob: Job? = null

        /**
         * The permission-request bookkeeping lives in the [SavedStateHandle]: Health Connect's permission screen is
         * another app, and the result must be judged correctly when PulseStages' process died meanwhile.
         */
        private var consecutiveRefusals: Int
            get() = savedState.get<Int>(KEY_REFUSALS) ?: 0
            set(value) {
                savedState[KEY_REFUSALS] = value
            }

        /** The current UI state. */
        val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

        init {
            collectStatus()
            WorkJob.entries.forEach { job -> viewModelScope.launch { scheduler.status(job).collect { onWork(job, it) } } }
        }

        /** Reads availability and permissions again (on resume, after a permission request); retries a failed read. */
        fun refresh() {
            if (mutableState.value.failed) {
                mutableState.update { it.copy(loading = true, failed = false) }
                collectStatus()
            }
            homeStatus.refresh()
        }

        /**
         * The permission request for [permissions] is about to be launched; what is requested and what is granted now
         * are kept until the result arrives.
         */
        fun onPermissionRequestStarted(permissions: Set<String>) {
            savedState[KEY_REQUESTED] = ArrayList(permissions)
            savedState[KEY_GRANTED_BEFORE] =
                ArrayList(
                    mutableState.value.permissions
                        ?.granted
                        .orEmpty(),
                )
        }

        /**
         * Handles the result of the Health Connect permission request. When nothing that was asked for got granted
         * twice in a row, Health Connect stops showing its dialog (its documented behaviour); the screen then offers
         * Health Connect's settings instead.
         */
        fun onPermissionsResult(granted: Set<String>) {
            val requested = savedState.get<ArrayList<String>>(KEY_REQUESTED).orEmpty().toSet()
            // The grants when the request started (the status may not be loaded yet after a process restart).
            val before =
                savedState.get<ArrayList<String>>(KEY_GRANTED_BEFORE)?.toSet()
                    ?: mutableState.value.permissions
                        ?.granted
                        .orEmpty()
            savedState.remove<ArrayList<String>>(KEY_REQUESTED)
            savedState.remove<ArrayList<String>>(KEY_GRANTED_BEFORE)
            val newlyGranted = requested.filter { it in granted && it !in before }
            consecutiveRefusals = if (requested.isNotEmpty() && newlyGranted.isEmpty()) consecutiveRefusals + 1 else 0
            val missing = HealthPermissions.missingRequired(before + granted)
            val text =
                when {
                    consecutiveRefusals >= REFUSALS_UNTIL_BLOCKED -> UiText.of(R.string.home_permissions_blocked)
                    missing.isEmpty() -> UiText.of(R.string.home_permissions_result_ok)
                    else -> UiText.plural(R.plurals.home_permissions_result_missing, missing.size)
                }
            mutableState.update {
                it.copy(permissionDialogBlocked = consecutiveRefusals >= REFUSALS_UNTIL_BLOCKED, messages = it.messages + UiMessage(text))
            }
            homeStatus.refresh()
        }

        /** "Estimate new": expedited run now; the result is reported when it finishes. */
        fun estimateNow() {
            watch(WorkJob.ESTIMATE_NOW)
            viewModelScope.launch { scheduler.estimateNow() }
        }

        /** "Backfill history": starts or resumes the backfill. */
        fun startBackfill() {
            watch(WorkJob.BACKFILL)
            scheduler.startBackfill()
            message(UiText.of(R.string.home_backfill_started))
        }

        /** Cancels the backfill (it stops before its next night). */
        fun cancelBackfill() {
            viewModelScope.launch {
                scheduler.cancelBackfill()
                message(UiText.of(R.string.home_backfill_cancelling))
            }
        }

        /** Opens the "Recompute all" confirmation. */
        fun requestRecomputeAll() {
            mutableState.update { it.copy(dialog = HomeDialog.RECOMPUTE_ALL) }
        }

        /** Confirmed: recomputes every night. */
        fun confirmRecomputeAll() {
            mutableState.update { it.copy(dialog = null) }
            recompute(RecomputeScope.ALL)
        }

        /** Recomputes the stale nights (no confirmation: they are offered because they are stale). */
        fun recomputeStale() {
            recompute(RecomputeScope.STALE)
        }

        /** Opens the "Delete my sessions" confirmation. */
        fun requestDeleteAll() {
            mutableState.update { it.copy(dialog = HomeDialog.DELETE_ALL) }
        }

        /** Confirmed: deletes every session PulseStages wrote to Health Connect. */
        fun confirmDeleteAll() {
            mutableState.update { it.copy(dialog = null, erasing = true) }
            viewModelScope.launch {
                val text =
                    try {
                        val report = eraser.eraseAll()
                        if (report.deleted == 0) {
                            UiText.of(R.string.home_delete_none)
                        } else {
                            UiText.plural(R.plurals.home_delete_done, report.deleted)
                        }
                    } catch (e: HealthDataException) {
                        logger.w(TAG, "deleting our sessions failed: ${e.message}", throwable = e)
                        healthErrorText(e)
                    }
                mutableState.update { it.copy(erasing = false, messages = it.messages + UiMessage(text)) }
            }
        }

        /** Closes the open confirmation dialog. */
        fun dismissDialog() {
            mutableState.update { it.copy(dialog = null) }
        }

        /** Reports that the screen could not open another app (Play Store, Health Connect settings). */
        fun onExternalScreenUnavailable() {
            message(UiText.of(R.string.error_no_app_for_action))
        }

        /** Reports that notifications were not allowed (the backfill still runs, without a visible notification). */
        fun onNotificationsDenied() {
            message(UiText.of(R.string.home_notifications_denied))
        }

        /** Removes a shown Snackbar message. */
        fun messageShown(id: Long) {
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        private fun collectStatus() {
            statusJob?.cancel()
            statusJob =
                viewModelScope.launch {
                    homeStatus
                        .status()
                        .catch { failure ->
                            logger.e(TAG, "cannot read the home status", throwable = failure)
                            mutableState.update { it.copy(loading = false, failed = true) }
                        }.collect { onStatus(it) }
                }
        }

        private fun onStatus(status: HomeStatus) {
            val permissions = status.access.permissions
            if (permissions != null && permissions.requestable.isEmpty() && consecutiveRefusals != 0) consecutiveRefusals = 0
            mutableState.update { current ->
                current.copy(
                    loading = false,
                    failed = false,
                    availability = status.access.availability,
                    permissions = permissions,
                    permissionDialogBlocked = current.permissionDialogBlocked && permissions?.requestable?.isNotEmpty() == true,
                    sources = status.enabledSources.map { SourceLabel(it, labels[it]) },
                    hrSources = status.hrSources,
                    overwritePolicy = status.overwritePolicy,
                    lastRun = status.lastRun,
                    counters = status.counters,
                    backfillState = status.backfill,
                    activeModel = status.activeModel?.kind,
                )
            }
            resolveLabels(status.enabledSources)
        }

        private fun resolveLabels(packages: List<String>) {
            val unknown = packages.filterNot { it in labels }
            if (unknown.isEmpty()) return
            unknown.forEach { labels[it] = null }
            viewModelScope.launch {
                unknown.forEach { name -> labels[name] = appInfo.resolve(name).label }
                mutableState.update { current ->
                    current.copy(sources = current.sources.map { SourceLabel(it.packageName, labels[it.packageName]) })
                }
            }
        }

        private fun onWork(
            job: WorkJob,
            status: WorkStatus,
        ) {
            val finished = watches[job]?.onStatus(status)
            mutableState.update { current ->
                val updated =
                    when (job) {
                        WorkJob.ESTIMATE_NOW -> current.copy(estimateNow = JobUi.of(status))
                        WorkJob.BACKFILL -> current.copy(backfill = JobUi.of(status))
                        WorkJob.RECOMPUTE_ALL -> current.copy(recompute = JobUi.of(status))
                        WorkJob.ESTIMATE_PERIODIC, WorkJob.ESTIMATE_DAILY -> nextRun(current, job, status)
                    }
                val text = finished?.let { WorkResultText.of(subjectOf(job), it) }
                if (text == null) updated else updated.copy(messages = updated.messages + UiMessage(text))
            }
        }

        private fun nextRun(
            current: HomeUiState,
            job: WorkJob,
            status: WorkStatus,
        ): HomeUiState {
            nextRuns[job] = status.nextScheduleTimeMillis
            return current.copy(nextRunMs = nextRuns.values.filterNotNull().minOrNull())
        }

        private fun recompute(scope: RecomputeScope) {
            watch(WorkJob.RECOMPUTE_ALL)
            scheduler.recomputeAll(scope)
            val started = if (scope == RecomputeScope.ALL) R.string.home_recompute_all_started else R.string.home_recompute_stale_started
            message(UiText.of(started))
        }

        private fun subjectOf(job: WorkJob): RunSubject =
            when (job) {
                WorkJob.BACKFILL -> RunSubject.BACKFILL
                WorkJob.RECOMPUTE_ALL -> RunSubject.RECOMPUTE
                WorkJob.ESTIMATE_NOW, WorkJob.ESTIMATE_PERIODIC, WorkJob.ESTIMATE_DAILY -> RunSubject.ESTIMATE
            }

        private fun watch(job: WorkJob) {
            watches[job]?.request()
        }

        private fun message(text: UiText) {
            mutableState.update { it.copy(messages = it.messages + UiMessage(text)) }
        }

        private companion object {
            const val TAG = "HomeViewModel"

            /** Health Connect stops showing its permission dialog after the user cancelled it twice in a row. */
            const val REFUSALS_UNTIL_BLOCKED = 2

            const val KEY_REQUESTED = "home.permissions.requested"
            const val KEY_GRANTED_BEFORE = "home.permissions.granted_before"
            const val KEY_REFUSALS = "home.permissions.refusals"

            /** Jobs started from this screen whose result is reported. */
            val WATCHED_JOBS = listOf(WorkJob.ESTIMATE_NOW, WorkJob.BACKFILL, WorkJob.RECOMPUTE_ALL)
        }
    }

/** Snackbar text of a Health Connect error of an action started on screen. */
internal fun healthErrorText(error: HealthDataException): UiText =
    when (error) {
        is HealthDataException.PermissionDenied -> UiText.of(R.string.error_hc_permission)
        is HealthDataException.Unavailable -> UiText.of(R.string.error_hc_unavailable)
        is HealthDataException.QuotaExceeded -> UiText.of(R.string.error_hc_quota)
        is HealthDataException.Transient -> UiText.of(R.string.error_hc_transient)
        is HealthDataException.InvalidData -> UiText.of(R.string.error_hc_invalid)
    }
