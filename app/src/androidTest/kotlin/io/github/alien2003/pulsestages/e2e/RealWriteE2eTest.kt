package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.e2e.realwrite.RealWriteSequence
import io.github.alien2003.pulsestages.e2e.realwrite.RealWriteStep
import io.github.alien2003.pulsestages.e2e.realwrite.TestNight
import io.github.alien2003.pulsestages.e2e.safety.CleanupReport
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.NamespaceCleaner
import io.github.alien2003.pulsestages.e2e.safety.PendingTestIds
import io.github.alien2003.pulsestages.e2e.safety.ReadBudget
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshot
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshots
import io.github.alien2003.pulsestages.e2e.support.AppSettingsFile
import io.github.alien2003.pulsestages.e2e.support.E2eFiles
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The suite's ONLY write to the real Health Connect, kept as short as possible (asserted under a minute): one sleep
 * session of a real night that ended at least 30 days ago and that no record of the app overlaps, estimated from the
 * real data with the app's estimator, under a fresh clientRecordId `ps-e2e:<uuid>` (so the safety guard lets it pass)
 * and with a title that marks it as a test record. Requires the "before" snapshot of the same run.
 *
 * Everything is prepared before the first insert, and nothing is written unless Health Connect has room for the reads
 * that follow: the run's budget must have [WRITE_HEADROOM] calls left, the 15-minute window must have room for them,
 * and a read right before the insert must succeed (otherwise the test is skipped and nothing is written). The id is
 * recorded in [PendingTestIds] (synchronously) before the insert. The reads while the record exists are never held back
 * by the suite's budget ([E2eHealth.recordedRepository]), so the record lives seconds. It is deleted by clientRecordId
 * at the end and, whatever happens, in `finally` by [NamespaceCleaner] (which deletes the pending id first, without a
 * read, and then verifies) and again by the runner's cleanup step. The steps ([RealWriteSequence]):
 * 1. insert v1 (the app's VerifiedUpsert) → exactly one own record with that id; stages, title, notes, start, end and
 *    zone offsets as written;
 * 2. insert the same v1 again → still one record, same id, stages unchanged;
 * 3. insert v2 (version + 1, other stages) → one record, version 2, the new stages, the same metadata.id;
 * 4. a plain insertRecords of v1 → Health Connect ignores the lower version: version 2 and its stages stay;
 * 5. the app's VerifiedUpsert of v1 → it finds the higher stale version and replaces it (delete + insert): version 1;
 * 6. delete by clientRecordId → no record left.
 * The app's own `ps:` records must be unchanged afterwards. Writes `e2e/realwrite.json`.
 */
@RunWith(AndroidJUnit4::class)
class RealWriteE2eTest {
    @get:Rule
    val status = E2eStatusRule()

    /**
     * The report.
     *
     * @property clientRecordId the test id, or null when nothing was written.
     * @property nightStartMs start of the night.
     * @property nightEndMs end of the night.
     * @property sourcePackage its source.
     * @property estimator the estimator.
     * @property steps every step.
     * @property aliveMs time from the first insert to the confirmed delete.
     * @property sameIdAfterUpdate metadata.id stayed the same through the upserts.
     * @property failure what failed during the sequence, if anything.
     * @property cleanup the final cleanup.
     * @property cleanupFailure why the final cleanup could not run, if it could not.
     */
    @Serializable
    data class Report(
        val clientRecordId: String?,
        val nightStartMs: Long,
        val nightEndMs: Long,
        val sourcePackage: String,
        val estimator: String,
        val steps: List<RealWriteStep>,
        val aliveMs: Long?,
        val sameIdAfterUpdate: Boolean?,
        val failure: String?,
        val cleanup: CleanupReport?,
        val cleanupFailure: String?,
    )

    // The sequence's failure (any Throwable, assertions included) is kept while the cleanup runs, then rethrown.
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    @Test
    fun upsertVersionsAndDeleteOfOneShortLivedTestRecord() =
        runBlocking {
            val before = E2e.readJson<SafetySnapshot>(E2eFiles.SNAPSHOT_BEFORE)
            assertNotNull("refusing to write: there is no snapshot of Health Connect before the run", before)
            assertEquals("refusing to write: the snapshot belongs to another run", E2e.runId, checkNotNull(before).runId)
            val logger = E2eLogger()
            val budget = ReadBudget.shared()
            val budgeted = E2eHealth.budgetedRepository(logger)
            // Reads that must never wait or be refused: the cleanup and the sequence while the record exists.
            val recorded = E2eHealth.recordedRepository(logger)
            val cleaner = NamespaceCleaner(recorded)
            val earlier = cleaner.cleanup()
            assertTrue("records of an earlier run could not be removed: $earlier", earlier.confirmed)
            val ownBefore = SafetySnapshots(budgeted).ownRecords()
            val settings = AppSettingsFile.read().settings
            val night =
                try {
                    TestNight.find(budgeted, settings, ownBefore, logger, System.currentTimeMillis())
                } catch (e: HealthDataException.QuotaExceeded) {
                    throw AssumptionViolatedException("no room left to read for the preparation of the write: nothing is written", e)
                }
            assumeTrue("no night that ended 30+ days ago without a record of the app could be estimated: nothing is written", night != null)
            val chosen = checkNotNull(night)
            val sequence = RealWriteSequence(recorded, chosen, settings)
            requireRoomForTheSequence(budget, recorded, chosen)
            PendingTestIds.add(sequence.clientRecordId)
            E2e.progress("real write: ${sequence.clientRecordId} for the night ${chosen.candidate.startMs}..${chosen.candidate.endMs}")
            var failure: Throwable? = null
            var cleanup: CleanupReport? = null
            var cleanupFailure: Throwable? = null
            try {
                sequence.run()
            } catch (e: Throwable) {
                failure = e
            } finally {
                // Never lets a failure of the cleanup hide the sequence's own failure, and always writes the report.
                try {
                    cleanup = cleaner.cleanup()
                } catch (e: Throwable) {
                    cleanupFailure = e
                }
                val candidate = chosen.candidate
                E2e.writeJson(
                    "realwrite.json",
                    Report(
                        clientRecordId = sequence.clientRecordId,
                        nightStartMs = candidate.startMs,
                        nightEndMs = candidate.endMs,
                        sourcePackage = candidate.packageName,
                        estimator = chosen.estimate.estimator.name,
                        steps = sequence.steps,
                        aliveMs = sequence.aliveMs,
                        sameIdAfterUpdate = sequence.sameIdAfterUpdate,
                        failure = failure?.let { "${it.javaClass.simpleName}: ${it.message}" },
                        cleanup = cleanup,
                        cleanupFailure = cleanupFailure?.let { "${it.javaClass.simpleName}: ${it.message}" },
                    ),
                )
            }
            failure?.let { original ->
                cleanupFailure?.let(original::addSuppressed)
                throw original
            }
            cleanupFailure?.let { throw it }
            val confirmed = checkNotNull(cleanup)
            assertTrue("the cleanup after the write could not confirm that no test record is left: $confirmed", confirmed.confirmed)
            val alive = checkNotNull(sequence.aliveMs)
            assertTrue("the test record lived $alive ms (limit $MAX_ALIVE_MS ms)", alive < MAX_ALIVE_MS)
            val ownAfter = SafetySnapshots(E2eHealth.reservedRepository(logger)).ownRecords()
            assertEquals("the app's own records changed", ownBefore.filterNot { it.isTestRecord }, ownAfter.filterNot { it.isTestRecord })
            assertTrue("a record of the suite is left", ownAfter.none { it.isTestRecord })
            E2e.progress("real write: alive $alive ms, ${sequence.steps.size} steps, cleaned up")
        }

    /**
     * Skips the test (nothing is written) unless the reads after the first insert can run at once: [WRITE_HEADROOM]
     * calls left in the run's budget, room for them in the 15-minute window (waited for here, before anything is
     * written), and a successful own-package read right before the insert (Health Connect's own window is not used up).
     */
    private suspend fun requireRoomForTheSequence(
        budget: ReadBudget,
        recorded: HealthRepository,
        night: TestNight,
    ) {
        assumeTrue(
            "only ${budget.remaining()} read calls left in the run's budget, $WRITE_HEADROOM needed: nothing is written",
            budget.remaining() >= WRITE_HEADROOM,
        )
        budget.awaitHeadroom(WRITE_HEADROOM, "real write")
        val probe = runCatching { recorded.readOwnSessions(night.candidate.startMs - DAY_MS, night.candidate.endMs + DAY_MS) }
        assumeTrue(
            "Health Connect refused a read right before the write (${probe.exceptionOrNull()?.message}): nothing is written",
            probe.isSuccess,
        )
    }

    private companion object {
        /** The test record must not live longer than this. */
        const val MAX_ALIVE_MS = 60_000L

        /**
         * Read calls kept free for the sequence (6 verification reads; the app's VerifiedUpsert reads back each of its
         * inserts, uncharged) and the cleanup after it (2 to 4 reads), with a wide margin.
         */
        const val WRITE_HEADROOM = 50

        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
