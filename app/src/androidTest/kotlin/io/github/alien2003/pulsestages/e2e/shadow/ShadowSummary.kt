package io.github.alien2003.pulsestages.e2e.shadow

import java.time.Instant

/** The human-readable summary of a [ShadowReport] (`e2e/shadow/summary.md`). */
object ShadowSummary {
    private const val PERCENT = 100

    /** Markdown of [report]. */
    fun markdown(report: ShadowReport): String =
        buildString {
            appendLine("# Shadow pipeline over the real history")
            appendLine()
            appendLine("Run `${report.runId}`: **${if (report.passed) "PASSED" else "FAILED"}**. Nothing was written to Health Connect")
            appendLine(
                "(writes that reached Health Connect: ${report.healthConnectWrites}; " +
                    "refused by the safety guard: ${report.safetyViolations}).",
            )
            appendLine()
            report.census?.let { census(it) }
            appendLine("Reads: ${report.readCallsCharged} call(s) charged in this run, ${report.cacheHits} served from the cache, ")
            appendLine(
                "${report.cacheMisses} from Health Connect; rate-limit errors ${report.platformQuotaErrors}, " +
                    "refused by the budget ${report.budgetRefusals}.",
            )
            appendLine()
            appendLine("## Scenarios")
            appendLine()
            appendLine("| Scenario | Outcome | Found | Written | Deleted | Not estimated | Failed | Time | Problems |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            report.scenarios.forEach { s ->
                appendLine(
                    "| ${s.name} | ${s.outcome} | ${s.found} | ${s.written} | ${s.deleted} | ${s.notEstimated} | ${s.failed} | " +
                        "${s.durationMs / MS_PER_S} s | ${s.problems.size} |",
                )
            }
            appendLine()
            report.scenarios.filter { it.problems.isNotEmpty() || it.notes.isNotEmpty() }.forEach { s ->
                appendLine("### ${s.name}")
                appendLine()
                appendLine(s.description)
                appendLine()
                s.problems.take(MAX_LINES).forEach { appendLine("- PROBLEM: $it") }
                if (s.problems.size > MAX_LINES) appendLine("- … ${s.problems.size - MAX_LINES} more (report.json)")
                s.notes.forEach { appendLine("- $it") }
                appendLine()
            }
            sessions("Sessions with the user's settings", report.sessions)
            if (report.warningsLogged.isNotEmpty()) {
                appendLine("## Warnings logged by the pipeline")
                appendLine()
                report.warningsLogged.take(MAX_LINES).forEach { appendLine("- $it") }
                appendLine()
            }
        }

    private fun StringBuilder.census(census: Census) {
        appendLine("## History")
        appendLine()
        appendLine("- Settings: ${census.settingsSource}; sources ${census.sourcePackages}; model ${census.modelId}")
        appendLine(
            "- ${census.sessions} sleep session(s), ${census.nights} night(s) of which ${census.naps} nap(s), " +
                "${census.skippedGroups} skipped group(s)",
        )
        appendLine("- Oldest session: ${census.oldestSessionStartMs?.let { Instant.ofEpochMilli(it).toString() } ?: "-"}")
        val covered = if (census.historyDays == 0) "all history" else "the last ${census.historyDays} days"
        appendLine("- Covered: $covered (${census.coveredNights} night(s))${if (census.capped) ", CAPPED by the read budget" else ""}")
        appendLine(
            "- Estimated read calls for all history: ${census.estimatedReadCalls}; policy scenarios: the last ${census.policyDays} days",
        )
        appendLine()
    }

    private fun StringBuilder.sessions(
        title: String,
        sessions: List<SessionReport>,
    ) {
        appendLine("## $title")
        appendLine()
        appendLine(
            "| Date | Start | Length | Mode | Outcome | Estimator | Coverage | Confidence | Deep / REM / Light / Awake | " +
                "HR samples | HR interval | Estimate | Same as the app's record |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        sessions.sortedByDescending { it.start }.forEach { s ->
            val shares =
                listOf("deep", "rem", "light", "awake").joinToString(" / ") { key ->
                    s.shares[key]?.let { "${(it * PERCENT).toInt()} %" }
                        ?: "-"
                }
            appendLine(
                "| ${s.date} | ${s.start.takeLast(TIME_LENGTH)} | ${s.lengthMin} min | ${s.mode} | ${s.outcome} | ${s.estimator} | " +
                    "${percent(
                        s.coverage,
                    )} | ${percent(s.confidence)} | $shares | ${s.hrSamples} | ${s.hrMedianIntervalSec?.let { "$it s" } ?: "-"} | " +
                    "${s.estimateMs?.let { "$it ms" } ?: "-"} | ${s.matchesAppRecord?.toString() ?: "-"} |",
            )
        }
        appendLine()
    }

    private fun percent(value: Double): String = "${(value * PERCENT).toInt()} %"

    private const val MS_PER_S = 1000
    private const val MAX_LINES = 50
    private const val TIME_LENGTH = 5
}
