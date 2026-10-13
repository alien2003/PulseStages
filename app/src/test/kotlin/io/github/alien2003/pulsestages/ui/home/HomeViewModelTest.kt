package io.github.alien2003.pulsestages.ui.home

import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcFeature
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.testutil.FakeAppInfoResolver
import io.github.alien2003.pulsestages.testutil.FakeWorkScheduler
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.clear
import io.github.alien2003.pulsestages.testutil.harnessTest
import io.github.alien2003.pulsestages.ui.common.JobPhase
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.RunSummary
import io.github.alien2003.pulsestages.work.schedule.JobState
import io.github.alien2003.pulsestages.work.schedule.WorkJob
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import io.github.alien2003.pulsestages.work.worker.JobProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @TempDir
    lateinit var dir: File

    private val scheduler = FakeWorkScheduler()

    /**
     * The ViewModel once its first status arrived. The harness's SyncStateStore is a real DataStore that does its file
     * I/O outside virtual time, so the tests wait for states instead of only advancing the test scheduler.
     */
    private suspend fun TestScope.viewModel(
        h: PipelineHarness,
        labels: Map<String, String> = emptyMap(),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): HomeViewModel =
        newViewModel(h, labels, savedState).also { vm ->
            advanceUntilIdle()
            vm.awaitState { !it.loading }
        }

    private fun newViewModel(
        h: PipelineHarness,
        labels: Map<String, String> = emptyMap(),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): HomeViewModel = HomeViewModel(h.homeStatus, scheduler, h.eraser, FakeAppInfoResolver(labels), h.logger, savedState)

    private suspend fun HomeViewModel.awaitState(predicate: (HomeUiState) -> Boolean): HomeUiState = state.first(predicate)

    private fun HomeViewModel.texts(): List<UiText> = state.value.messages.map { it.text }

    @Test
    fun `the status maps Health Connect, permissions, sources, last run and counters`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.settings.set {
                it.copy(
                    sources =
                        it.sources.copy(
                            sourcePackages = listOf(OTHER_APP, OHEALTH),
                            sourcePriority = listOf(OHEALTH, OTHER_APP),
                            hrSources = HrSourcesMode.ANY,
                        ),
                )
            }
            h.sync.run(inBackground = false)
            val vm = viewModel(h, labels = mapOf(OTHER_APP to "Other tracker"))

            val state = vm.awaitState { it.counters?.estimated == 2 && it.sources.any { source -> source.label != null } }
            assertFalse(state.loading)
            assertEquals(HcAvailability.AVAILABLE, state.availability)
            assertTrue(state.ready)
            assertFalse(state.canRequestMore, "everything is granted")
            assertEquals(listOf(SourceLabel(OHEALTH, null), SourceLabel(OTHER_APP, "Other tracker")), state.sources)
            assertEquals(HrSourcesMode.ANY, state.hrSources)
            assertEquals(SyncOutcome.SUCCESS, state.lastRun?.outcome)
            val counters = checkNotNull(state.counters)
            assertEquals(2, counters.found)
            assertEquals(2, counters.estimated)
            assertEquals(0, counters.stale)
            clear(vm)
        }

    @Test
    fun `stale nights are counted after a settings change`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val vm = viewModel(h)
            assertEquals(0, vm.awaitState { it.counters?.estimated == 1 }.counters?.stale)

            h.settings.set { it.copy(write = it.write.copy(awakeAsLight = true)) }

            assertEquals(1, vm.awaitState { it.counters?.stale != 0 }.counters?.stale)
            clear(vm)
        }

    @Test
    fun `Health Connect needing an update is shown without permissions`() =
        harnessTest(main, dir) { h ->
            h.repository.availability = HcAvailability.PROVIDER_UPDATE_REQUIRED
            val vm = viewModel(h)

            val state = vm.state.value
            assertEquals(HcAvailability.PROVIDER_UPDATE_REQUIRED, state.availability)
            assertNull(state.permissions)
            assertFalse(state.ready)
            assertFalse(state.canRequestMore)
            clear(vm)
        }

    @Test
    fun `missing permissions are requestable, history only where the feature exists`() =
        harnessTest(main, dir) { h ->
            h.repository.revoke(HealthPermissions.READ_STEPS)
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.features -= HcFeature.READ_IN_BACKGROUND
            val vm = viewModel(h)

            val permissions = checkNotNull(vm.state.value.permissions)
            assertEquals(setOf(HealthPermissions.READ_STEPS), permissions.missingRequired)
            assertFalse(vm.state.value.ready)
            assertTrue(vm.state.value.canRequestMore)
            assertTrue(HealthPermissions.READ_HEALTH_DATA_HISTORY in permissions.requestable)
            assertFalse(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND in permissions.requestable)
            clear(vm)
        }

    @Test
    fun `permissions that cannot be read leave the permissions unknown`() =
        harnessTest(main, dir) { h ->
            h.repository.failOn(FakeHealthRepository.Operation.GRANTED_PERMISSIONS, HealthDataException.Transient("ipc"))
            val vm = viewModel(h)

            assertEquals(HcAvailability.AVAILABLE, vm.state.value.availability)
            assertNull(vm.state.value.permissions)
            clear(vm)
        }

    @Test
    fun `two refused permission requests in a row offer Health Connect's settings, a grant resets it`() =
        harnessTest(main, dir) { h ->
            h.repository.revoke(HealthPermissions.READ_SLEEP)
            val vm = viewModel(h)

            vm.onPermissionRequestStarted(setOf(HealthPermissions.READ_SLEEP))
            vm.onPermissionsResult(emptySet())
            advanceUntilIdle()
            assertFalse(vm.state.value.permissionDialogBlocked)
            assertEquals(UiText.Plural(R.plurals.home_permissions_result_missing, 1, listOf(1)), vm.texts().last())

            vm.onPermissionRequestStarted(setOf(HealthPermissions.READ_SLEEP))
            vm.onPermissionsResult(emptySet())
            advanceUntilIdle()
            assertTrue(vm.state.value.permissionDialogBlocked)
            assertEquals(UiText.of(R.string.home_permissions_blocked), vm.texts().last())

            h.repository.grant(HealthPermissions.READ_SLEEP)
            vm.onPermissionRequestStarted(setOf(HealthPermissions.READ_SLEEP))
            vm.onPermissionsResult(setOf(HealthPermissions.READ_SLEEP))
            advanceUntilIdle()
            assertFalse(vm.state.value.permissionDialogBlocked)
            assertEquals(UiText.of(R.string.home_permissions_result_ok), vm.texts().last())
            assertTrue(vm.awaitState { it.ready }.ready, "the status was read again")
            clear(vm)
        }

    @Test
    fun `a permission result that arrives after a process restart is judged against the grants before the request`() =
        harnessTest(main, dir) { h ->
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            val savedState = SavedStateHandle()
            val before = viewModel(h, savedState = savedState)
            assertTrue(before.state.value.ready)
            before.onPermissionRequestStarted(setOf(HealthPermissions.READ_HEALTH_DATA_HISTORY))
            clear(before)

            // The process died while Health Connect's screen was open; the restored ViewModel gets the result before
            // its first status.
            h.repository.grant(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            val restored = newViewModel(h, savedState = savedState)
            restored.onPermissionsResult(setOf(HealthPermissions.READ_HEALTH_DATA_HISTORY))
            advanceUntilIdle()

            assertEquals(UiText.of(R.string.home_permissions_result_ok), restored.texts().last(), "no required permission is missing")
            clear(restored)
        }

    @Test
    fun `refused permission requests are counted across a process restart`() =
        harnessTest(main, dir) { h ->
            h.repository.revoke(HealthPermissions.READ_SLEEP)
            val savedState = SavedStateHandle()
            val first = viewModel(h, savedState = savedState)
            first.onPermissionRequestStarted(setOf(HealthPermissions.READ_SLEEP))
            first.onPermissionsResult(emptySet())
            first.onPermissionRequestStarted(setOf(HealthPermissions.READ_SLEEP))
            clear(first)

            val restored = newViewModel(h, savedState = savedState)
            restored.onPermissionsResult(emptySet())
            advanceUntilIdle()

            assertTrue(restored.state.value.permissionDialogBlocked)
            assertEquals(UiText.of(R.string.home_permissions_blocked), restored.texts().last())
            clear(restored)
            val again = viewModel(h, savedState = savedState)
            assertTrue(again.state.value.permissionDialogBlocked, "the hint stays after a restart")
            clear(again)
        }

    @Test
    fun `the never-overwrite policy is shown on Home`() =
        harnessTest(main, dir) { h ->
            val vm = viewModel(h)
            assertEquals(OverwritePolicy.SKIP_SAME_VERSION, vm.state.value.overwritePolicy)
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }
            assertEquals(OverwritePolicy.NEVER, vm.awaitState { it.overwritePolicy == OverwritePolicy.NEVER }.overwritePolicy)
            clear(vm)
        }

    @Test
    fun `the next run is the earlier of the periodic and the daily run`() =
        harnessTest(main, dir) { h ->
            val vm = viewModel(h)
            assertNull(vm.state.value.nextRunMs)

            scheduler.statuses.getValue(WorkJob.ESTIMATE_PERIODIC).value = WorkStatus(JobState.ENQUEUED, nextScheduleTimeMillis = 5_000)
            assertEquals(5_000L, vm.awaitState { it.nextRunMs != null }.nextRunMs)
            scheduler.statuses.getValue(WorkJob.ESTIMATE_DAILY).value = WorkStatus(JobState.ENQUEUED, nextScheduleTimeMillis = 3_000)

            assertEquals(3_000L, vm.awaitState { it.nextRunMs != 5_000L }.nextRunMs)
            clear(vm)
        }

    @Test
    fun `estimate new runs the expedited job and reports its result once it finished`() =
        harnessTest(main, dir) { h ->
            val now = scheduler.statuses.getValue(WorkJob.ESTIMATE_NOW)
            now.value = WorkStatus(JobState.SUCCEEDED, lastOutput = completed(RunSummary(found = 9, estimated = 9)))
            val vm = viewModel(h)

            vm.estimateNow()
            scheduler.awaitCall("estimateNow")
            assertEquals(listOf("estimateNow"), scheduler.calls)
            assertTrue(vm.texts().isEmpty(), "the result of an earlier run is not reported")

            now.value = WorkStatus(JobState.RUNNING)
            assertEquals(JobPhase.RUNNING, vm.awaitState { it.estimateNow.active }.estimateNow.phase)

            now.value = WorkStatus(JobState.SUCCEEDED, lastOutput = completed(RunSummary(found = 3, estimated = 2, skipped = 1)))
            val state = vm.awaitState { !it.estimateNow.active && it.messages.isNotEmpty() }
            val expected =
                UiText.of(R.string.result_summary_estimate, UiText.plural(R.plurals.result_estimated, 2), 3, 1)
            assertEquals(listOf(expected), state.messages.map { it.text })
            clear(vm)
        }

    @Test
    fun `a blocked run reports why`() =
        harnessTest(main, dir) { h ->
            val now = scheduler.statuses.getValue(WorkJob.ESTIMATE_NOW)
            val vm = viewModel(h)
            vm.estimateNow()
            now.value = WorkStatus(JobState.ENQUEUED)
            vm.awaitState { it.estimateNow.active }
            now.value =
                WorkStatus(
                    JobState.SUCCEEDED,
                    lastOutput =
                        RunReport(
                            OutcomeKind.BLOCKED,
                            summary = null,
                            blockedReason = BlockedReason.BACKGROUND_PERMISSION_MISSING,
                            retryCause = null,
                            detail = null,
                        ),
                )

            val state = vm.awaitState { it.messages.isNotEmpty() }
            assertEquals(listOf(UiText.of(R.string.result_blocked_background)), state.messages.map { it.text })
            clear(vm)
        }

    @Test
    fun `backfill starts, shows its progress and cancels through the scheduler`() =
        harnessTest(main, dir) { h ->
            val vm = viewModel(h)
            vm.startBackfill()
            val backfill = scheduler.statuses.getValue(WorkJob.BACKFILL)
            backfill.value =
                WorkStatus(JobState.RUNNING, progress = JobProgress(done = 3, total = 10, nightsFound = 12, nightsEstimated = 11))
            val running = vm.awaitState { it.backfill.active }

            assertEquals(JobPhase.RUNNING, running.backfill.phase)
            assertEquals(JobProgress(3, 10, 12, 11), running.backfillProgress)

            vm.cancelBackfill()
            scheduler.awaitCall("cancelBackfill")
            assertEquals(listOf("startBackfill", "cancelBackfill"), scheduler.calls)

            backfill.value =
                WorkStatus(JobState.SUCCEEDED, lastOutput = completed(RunSummary(found = 12, estimated = 11, cancelled = true)))
            val done = vm.awaitState { !it.backfill.active && it.messages.size == 3 }
            assertEquals(UiText.of(R.string.result_cancelled), done.messages.last().text)
            clear(vm)
        }

    @Test
    fun `an interrupted stored backfill can be resumed and shows its stored progress`() =
        harnessTest(main, dir) { h ->
            h.ledger.backfillDao.save(
                BackfillStateEntity(
                    status = BackfillStatus.WAITING_RETRY,
                    rangeStartMs = 0,
                    rangeEndMs = 1,
                    cursorMs = 0,
                    windowsTotal = 8,
                    windowsDone = 5,
                    nightsFound = 30,
                    nightsEstimated = 28,
                    startedAt = 0,
                    updatedAt = 0,
                ),
            )
            val vm = viewModel(h)
            val state = vm.awaitState { it.backfillState != null }

            assertTrue(state.backfillResumable)
            assertEquals(JobProgress(5, 8, 30, 28), state.backfillProgress)
            clear(vm)
        }

    @Test
    fun `recompute all needs a confirmation, recompute stale does not`() =
        harnessTest(main, dir) { h ->
            val vm = viewModel(h)

            vm.requestRecomputeAll()
            assertEquals(HomeDialog.RECOMPUTE_ALL, vm.state.value.dialog)
            assertTrue(scheduler.calls.isEmpty(), "nothing runs before the confirmation")
            vm.dismissDialog()
            assertNull(vm.state.value.dialog)
            assertTrue(scheduler.calls.isEmpty())

            vm.requestRecomputeAll()
            vm.confirmRecomputeAll()
            assertNull(vm.state.value.dialog)
            vm.recomputeStale()

            assertEquals(listOf("recomputeAll:ALL", "recomputeAll:STALE"), scheduler.calls)
            assertEquals(
                listOf(UiText.of(R.string.home_recompute_all_started), UiText.of(R.string.home_recompute_stale_started)),
                vm.texts(),
            )
            clear(vm)
        }

    @Test
    fun `delete my sessions needs a confirmation and reports how many were deleted`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            assertEquals(2, h.repository.ownSessions().size)
            val vm = viewModel(h)

            vm.requestDeleteAll()
            assertEquals(HomeDialog.DELETE_ALL, vm.state.value.dialog)
            assertEquals(2, h.repository.ownSessions().size, "nothing deleted before the confirmation")

            vm.confirmDeleteAll()
            val state = vm.awaitState { !it.erasing && it.messages.isNotEmpty() }

            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(listOf(UiText.plural(R.plurals.home_delete_done, 2)), state.messages.map { it.text })
            clear(vm)
        }

    @Test
    fun `a failed deletion is reported and nothing is marked`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            h.repository.failOn(FakeHealthRepository.Operation.DELETE, HealthDataException.QuotaExceeded("quota"))
            val vm = viewModel(h)

            vm.requestDeleteAll()
            vm.confirmDeleteAll()
            val state = vm.awaitState { !it.erasing && it.messages.isNotEmpty() }

            assertEquals(listOf(UiText.of(R.string.error_hc_quota)), state.messages.map { it.text })
            assertEquals(1, h.repository.ownSessions().size)
            clear(vm)
        }

    @Test
    fun `messages are removed once shown`() =
        harnessTest(main, dir) { h ->
            val vm = viewModel(h)
            vm.onExternalScreenUnavailable()
            vm.onNotificationsDenied()
            assertEquals(
                listOf(UiText.of(R.string.error_no_app_for_action), UiText.of(R.string.home_notifications_denied)),
                vm.texts(),
            )

            vm.messageShown(
                vm.state.value.messages
                    .first()
                    .id,
            )
            assertEquals(listOf(UiText.of(R.string.home_notifications_denied)), vm.texts())
            clear(vm)
        }

    private fun completed(summary: RunSummary) =
        RunReport(OutcomeKind.COMPLETED, summary, blockedReason = null, retryCause = null, detail = null)
}
