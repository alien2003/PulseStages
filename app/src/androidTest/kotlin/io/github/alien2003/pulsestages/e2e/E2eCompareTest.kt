package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.safety.ComparisonReport
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshot
import io.github.alien2003.pulsestages.e2e.safety.SnapshotComparison
import io.github.alien2003.pulsestages.e2e.support.E2eFiles
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The verdict of the run: the "before" and "after" snapshots compared ([SnapshotComparison]): other apps' records
 * unchanged, the app's own `ps:` records byte-identical, no `ps-e2e:` record left. Reads only the two JSON files (no
 * Health Connect call). Writes `e2e/compare.json` and `e2e/compare.md`.
 */
@RunWith(AndroidJUnit4::class)
class E2eCompareTest {
    @get:Rule
    val status = E2eStatusRule()

    @Test
    fun healthConnectIsAsBefore() {
        val before = E2e.readJson<SafetySnapshot>(E2eFiles.SNAPSHOT_BEFORE)
        val after = E2e.readJson<SafetySnapshot>(E2eFiles.SNAPSHOT_AFTER)
        assertNotNull("no ${E2eFiles.SNAPSHOT_BEFORE}", before)
        assertNotNull("no ${E2eFiles.SNAPSHOT_AFTER}", after)
        val report = SnapshotComparison.compare(checkNotNull(before), checkNotNull(after))
        E2e.writeJson("compare.json", report)
        E2e.writeText("compare.md", markdown(report, before, after))
        report.warnings.forEach { E2e.progress("compare warning: $it") }
        report.failures.forEach { E2e.progress("compare FAILURE: $it") }
        assertTrue("Health Connect differs from before the run:\n" + (report.failures + report.hints).joinToString("\n"), report.passed)
    }

    private fun markdown(
        report: ComparisonReport,
        before: SafetySnapshot,
        after: SafetySnapshot,
    ): String =
        buildString {
            appendLine("# Health Connect before and after the run")
            appendLine()
            appendLine("Run `${before.runId}`: **${if (report.passed) "PASSED" else "FAILED"}**")
            appendLine()
            appendLine("| | Before | After |")
            appendLine("|---|---|---|")
            appendLine("| The app's own sessions (outside `ps-e2e:`) | ${report.appRecordsBefore} | ${report.appRecordsAfter} |")
            appendLine("| Sessions of the suite (`ps-e2e:`) | ${before.testRecords.size} | ${after.testRecords.size} |")
            (before.otherSleep.keys + after.otherSleep.keys).sorted().forEach { pkg ->
                appendLine(
                    "| Sleep of `$pkg` (all history) | ${before.otherSleep[pkg]?.count ?: 0} | ${after.otherSleep[pkg]?.count ?: 0} |",
                )
            }
            (before.heartRate.keys + after.heartRate.keys).sorted().forEach { pkg ->
                appendLine(
                    "| Heart rate of `$pkg` (30 days) | ${before.heartRate[pkg]?.count ?: 0} | ${after.heartRate[pkg]?.count ?: 0} |",
                )
            }
            (before.steps.keys + after.steps.keys).sorted().forEach { pkg ->
                appendLine("| Steps of `$pkg` (30 days) | ${before.steps[pkg]?.count ?: 0} | ${after.steps[pkg]?.count ?: 0} |")
            }
            appendLine()
            section("Failures", report.failures)
            section("Warnings", report.warnings)
            section("Hints", report.hints)
        }

    private fun StringBuilder.section(
        title: String,
        lines: List<String>,
    ) {
        appendLine("## $title")
        appendLine()
        if (lines.isEmpty()) appendLine("None.") else lines.forEach { appendLine("- $it") }
        appendLine()
    }
}
