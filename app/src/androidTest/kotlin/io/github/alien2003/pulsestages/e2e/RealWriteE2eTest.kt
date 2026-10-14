package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.realwrite.RealWriteSequence
import io.github.alien2003.pulsestages.e2e.realwrite.RealWriteStep
import io.github.alien2003.pulsestages.e2e.realwrite.TestNight
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.NamespaceCleaner
import io.github.alien2003.pulsestages.e2e.safety.PendingTestIds
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The suite's ONLY write to the real Health Connect, kept as short as possible (asserted under a minute): one sleep
 * session of a real night that ended at least 30 days ago and that no record of the app overlaps, estimated from the
 * real data with the app's estimator, under a fresh clientRecordId `ps-e2e:<uuid>` (so the safety guard lets it pass)
 * and with a title that marks it as a test record. Everything is prepared before the first insert; the id is recorded
 * in [PendingTestIds] before it; the record is deleted by clientRecordId at the end and, whatever happens, in `finally`
 * by [NamespaceCleaner] (and again by the runner's cleanup step); a verification read must find no `ps-e2e:` record
 * afterwards. Requires the "before" snapshot of the same run. The steps ([RealWriteSequence]):
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
     * @property cleanupRemaining test records left after the final cleanup (must be empty).
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
        val cleanupRemaining: List<String>,
    )

    @Test
    fun upsertVersionsAndDeleteOfOneShortLivedTestRecord() =
        runBlocking {
            val before = E2e.readJson<SafetySnapshot>(E2eFiles.SNAPSHOT_BEFORE)
            assertNotNull("refusing to write: there is no snapshot of Health Connect before the run", before)
            assertEquals("refusing to write: the snapshot belongs to another run", E2e.runId, checkNotNull(before).runId)
            val logger = E2eLogger()
            val repository = E2eHealth.budgetedRepository(logger)
            val cleaner = NamespaceCleaner(repository)
            assertTrue("records of an earlier run could not be removed", cleaner.cleanup().remaining.isEmpty())
            val snapshots = SafetySnapshots(repository)
            val ownBefore = snapshots.ownRecords()
            val settings = AppSettingsFile.read().settings
            val night = TestNight.find(repository, settings, ownBefore, logger, System.currentTimeMillis())
            assumeTrue("no night that ended 30+ days ago without a record of the app could be estimated: nothing is written", night != null)
            val chosen = checkNotNull(night)
            val sequence = RealWriteSequence(repository, chosen, settings)
            var remaining: List<String>
            PendingTestIds.add(sequence.clientRecordId)
            E2e.progress("real write: ${sequence.clientRecordId} for the night ${chosen.candidate.startMs}..${chosen.candidate.endMs}")
            try {
                sequence.run()
            } finally {
                remaining = cleaner.cleanup().remaining
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
                        cleanupRemaining = remaining,
                    ),
                )
            }
            assertTrue("test records left: $remaining", remaining.isEmpty())
            val alive = checkNotNull(sequence.aliveMs)
            assertTrue("the test record lived $alive ms (limit $MAX_ALIVE_MS ms)", alive < MAX_ALIVE_MS)
            val ownAfter = snapshots.ownRecords()
            assertEquals("the app's own records changed", ownBefore.filterNot { it.isTestRecord }, ownAfter.filterNot { it.isTestRecord })
            assertTrue("a record of the suite is left", ownAfter.none { it.isTestRecord })
            E2e.progress("real write: alive $alive ms, ${sequence.steps.size} steps, cleaned up")
        }

    private companion object {
        /** The test record must not live longer than this. */
        const val MAX_ALIVE_MS = 60_000L
    }
}
