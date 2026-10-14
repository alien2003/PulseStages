package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.safety.CleanupReport
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.NamespaceCleaner
import io.github.alien2003.pulsestages.e2e.shadow.ShadowEnvironment
import io.github.alien2003.pulsestages.e2e.support.DemoFlag
import io.github.alien2003.pulsestages.e2e.support.DemoFlagBackup
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Puts back everything an interrupted run could leave behind, and verifies it:
 * - the demo-mode flag, from the backup the UI tour wrote before it touched the switch ([DemoFlagBackup]);
 * - every record of the suite (`ps-e2e:`) in the real Health Connect, deleted by clientRecordId: first the ids recorded
 *   before their insert (no read needed), then every one a read of the app's own sessions finds; a verification read
 *   must find none ([NamespaceCleaner], which waits out Health Connect's rate limit for up to 16 minutes and is never
 *   held back by the suite's own read budget);
 * - the shadow scenarios' temporary directory below the app's cache.
 * Idempotent and safe at any time; the runner always runs it in its exit trap (and again after a pause when it fails),
 * and on its own with `run-device-tests.sh cleanup-only`. Needs no screen. Writes `e2e/cleanup.json`.
 */
@RunWith(AndroidJUnit4::class)
class CleanupE2eTest {
    @get:Rule
    val status = E2eStatusRule()

    /**
     * The report.
     *
     * @property demoFlagRestored the demo-mode flag put back from a backup, if there was one.
     * @property demoFlagFailure why it could not be put back, if it could not.
     * @property healthConnect the cleanup of Health Connect.
     */
    @Serializable
    data class Report(
        val demoFlagRestored: DemoFlag?,
        val demoFlagFailure: String?,
        val healthConnect: CleanupReport,
    )

    @Test
    fun nothingOfTheSuiteIsLeft() =
        runBlocking {
            // Temporary files of shadow scenarios that a killed run could not delete (only the suite's own directory).
            runCatching { ShadowEnvironment.removeLeftovers() }
            val demo = runCatching { DemoFlagBackup.restoreIfPresent() }
            val report = NamespaceCleaner(E2eHealth.recordedRepository(E2eLogger())).cleanup()
            E2e.writeJson("cleanup.json", Report(demo.getOrNull(), demo.exceptionOrNull()?.message, report))
            demo.getOrNull()?.let { E2e.progress("cleanup: demo-mode flag restored to $it") }
            E2e.progress(
                "cleanup: pending ${report.pending.size}, found ${report.found.size}, deleted ${report.deleted.size}, " +
                    "left ${if (report.verified) report.remaining.size.toString() else "unknown (no read succeeded)"}",
            )
            assertNull("the demo-mode flag could not be restored", demo.exceptionOrNull())
            assertTrue("no read could verify the cleanup: ${report.errors}", report.verified)
            assertTrue("records of the suite are still in Health Connect: ${report.remaining}", report.remaining.isEmpty())
        }
}
