package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshots
import io.github.alien2003.pulsestages.e2e.support.DemoFlagBackup
import io.github.alien2003.pulsestages.e2e.support.E2eFiles
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 1 of the run: a fresh e2e directory and the snapshot of everything the suite could touch
 * ([io.github.alien2003.pulsestages.e2e.safety.SafetySnapshot]). READ-ONLY, except that a demo-mode flag an interrupted
 * earlier run left flipped is first put back from its backup ([DemoFlagBackup]), so that the snapshot holds the real
 * value. The real write of [RealWriteE2eTest] refuses to run without this snapshot of the same run.
 */
@RunWith(AndroidJUnit4::class)
class E2eSnapshotBeforeTest {
    @get:Rule
    val status = E2eStatusRule()

    @Test
    fun snapshotBefore() =
        runBlocking {
            E2eFiles.resetOutputDir()
            DemoFlagBackup.restoreIfPresent()?.let { E2e.progress("restored the demo-mode flag an interrupted run left: $it") }
            val snapshot = SafetySnapshots(E2eHealth.budgetedRepository(E2eLogger())).take("before")
            E2e.writeJson(E2eFiles.SNAPSHOT_BEFORE, snapshot)
            E2e.progress(
                "before: ${snapshot.appRecords.size} own session(s) of the app, ${snapshot.testRecords.size} of the suite; " +
                    "other apps' sleep ${snapshot.otherSleep.mapValues { it.value.count }}; heart rate " +
                    "${snapshot.heartRate.mapValues { it.value.count }}; steps ${snapshot.steps.mapValues { it.value.count }}",
            )
            assertEquals("the installed app", E2e.APP_PACKAGE, E2e.app.packageName)
        }
}
