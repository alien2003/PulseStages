package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.NamespaceCleaner
import io.github.alien2003.pulsestages.e2e.shadow.ShadowEnvironment
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deletes every record of the suite (`ps-e2e:`) from the real Health Connect and verifies that none is left
 * ([NamespaceCleaner]); also removes the shadow scenarios' temporary directory below the app's cache. Idempotent and
 * safe at any time; the runner always runs it in its exit trap, and on its own with `run-device-tests.sh cleanup-only`.
 */
@RunWith(AndroidJUnit4::class)
class CleanupE2eTest {
    @get:Rule
    val status = E2eStatusRule()

    @Test
    fun noRecordOfTheSuiteIsLeft() =
        runBlocking {
            // Temporary files of shadow scenarios that a killed run could not delete (only the suite's own directory).
            ShadowEnvironment.removeLeftovers()
            val report = NamespaceCleaner(E2eHealth.budgetedRepository(E2eLogger())).cleanup()
            E2e.writeJson("cleanup.json", report)
            E2e.progress(
                "cleanup: found ${report.found.size}, pending ${report.pending.size}, deleted ${report.deleted.size}, " +
                    "left ${report.remaining.size}",
            )
            assertTrue("records of the suite are still in Health Connect: ${report.remaining}", report.remaining.isEmpty())
        }
}
