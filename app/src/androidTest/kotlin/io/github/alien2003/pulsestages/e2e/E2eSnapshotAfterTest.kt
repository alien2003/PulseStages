package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshot
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshots
import io.github.alien2003.pulsestages.e2e.support.E2eFiles
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The snapshot after the run, over exactly the ranges of the "before" snapshot (so that no record moves in or out of a
 * window in between). READ-ONLY. [E2eCompareTest] compares the two.
 */
@RunWith(AndroidJUnit4::class)
class E2eSnapshotAfterTest {
    @get:Rule
    val status = E2eStatusRule()

    @Test
    fun snapshotAfter() =
        runBlocking {
            val before = E2e.readJson<SafetySnapshot>(E2eFiles.SNAPSHOT_BEFORE)
            assertNotNull("no ${E2eFiles.SNAPSHOT_BEFORE}: run E2eSnapshotBeforeTest first", before)
            val snapshot = SafetySnapshots(E2eHealth.budgetedRepository(E2eLogger())).take("after", checkNotNull(before).window)
            E2e.writeJson(E2eFiles.SNAPSHOT_AFTER, snapshot)
            E2e.progress("after: ${snapshot.appRecords.size} own session(s) of the app, ${snapshot.testRecords.size} of the suite")
        }
}
