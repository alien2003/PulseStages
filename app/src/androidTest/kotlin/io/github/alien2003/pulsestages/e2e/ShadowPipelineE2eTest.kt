package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.ReadBudget
import io.github.alien2003.pulsestages.e2e.safety.SafetyGuardRepository
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshot
import io.github.alien2003.pulsestages.e2e.shadow.CachingReadRepository
import io.github.alien2003.pulsestages.e2e.shadow.ShadowEnvironment
import io.github.alien2003.pulsestages.e2e.shadow.ShadowReport
import io.github.alien2003.pulsestages.e2e.shadow.ShadowRun
import io.github.alien2003.pulsestages.e2e.shadow.ShadowSummary
import io.github.alien2003.pulsestages.e2e.support.E2eFiles
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import io.github.alien2003.pulsestages.work.estimate.AndroidModelAssetSource
import io.github.alien2003.pulsestages.work.estimate.BundledHybridModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's whole pipeline (backfill engine, Changes-API sync, recompute, the real SleepStager with the bundled model)
 * over the user's real Health Connect history, WITHOUT writing anything ([ShadowRun] has the scenarios):
 * - reads go to the real HealthConnectRepository (behind the safety guard, charged to the read budget, cached);
 * - writes and deletes are captured by [io.github.alien2003.pulsestages.e2e.shadow.ShadowWriteRepository] and never
 *   forwarded;
 * - the ledger is an in-memory Room database of the app's database class, the DataStores are temporary files below
 *   the app's cache directory; the user's settings are copied read-only from the app's settings file.
 * Writes `e2e/shadow/report.json` and `e2e/shadow/summary.md`.
 */
@RunWith(AndroidJUnit4::class)
class ShadowPipelineE2eTest {
    @get:Rule
    val status = E2eStatusRule()

    @Suppress("InjectDispatcher") // The suite wires the model provider itself, like the app's WorkModule.
    @Test
    fun theWholePipelineOverTheRealHistoryWritesNothing() =
        runBlocking {
            val logger = E2eLogger()
            val budget = ReadBudget.shared()
            val reads = CachingReadRepository(E2eHealth.budgetedRepository(logger, budget))
            val models = BundledHybridModelProvider(AndroidModelAssetSource(E2e.app.assets), logger, Dispatchers.IO)
            val run = ShadowRun(reads, models, logger, budget, reserveForLaterSteps())
            val forwardedBefore = SafetyGuardRepository.forwardedWrites.get()
            ShadowEnvironment.removeLeftovers()
            try {
                run.runAll()
            } finally {
                ShadowEnvironment.removeLeftovers()
                val forwarded = SafetyGuardRepository.forwardedWrites.get() - forwardedBefore
                val report =
                    ShadowReport(
                        runId = E2e.runId,
                        passed = run.problems.isEmpty() && SafetyGuardRepository.violations.get() == 0 && forwarded == 0,
                        census = run.census,
                        scenarios = run.scenarios,
                        sessions = run.sessions,
                        sessionsWithDefaults = run.sessionsWithDefaults,
                        readCallsCharged = budget.spent(),
                        cacheHits = reads.hits.get(),
                        cacheMisses = reads.misses.get(),
                        platformQuotaErrors = budget.platformQuotaErrors,
                        budgetRefusals = budget.refusedCalls,
                        safetyViolations = SafetyGuardRepository.violations.get(),
                        healthConnectWrites = forwarded,
                        warningsLogged =
                            logger
                                .atLeast(LogLevel.WARN)
                                .map { "[${it.tag}] ${it.message}" }
                                .distinct()
                                .take(MAX_WARNINGS),
                    )
                E2e.writeJson("shadow/report.json", report)
                E2e.writeText("shadow/summary.md", ShadowSummary.markdown(report))
            }
            assertEquals("writes and deletes refused by the safety guard", 0, SafetyGuardRepository.violations.get())
            assertEquals("writes and deletes that reached Health Connect", 0, SafetyGuardRepository.forwardedWrites.get() - forwardedBefore)
            assertTrue("the shadow pipeline found problems:\n" + run.problems.take(MAX_SHOWN).joinToString("\n"), run.problems.isEmpty())
        }

    /**
     * Read calls kept for the steps after this one: the "after" snapshot (as large as the "before" one), the UI tour and
     * the real write.
     */
    private fun reserveForLaterSteps(): Int {
        val snapshot = E2e.readJson<SafetySnapshot>(E2eFiles.SNAPSHOT_BEFORE)?.readCallsCharged ?: DEFAULT_SNAPSHOT_COST
        return snapshot + UI_AND_WRITE_RESERVE
    }

    private companion object {
        const val DEFAULT_SNAPSHOT_COST = 400
        const val UI_AND_WRITE_RESERVE = 900
        const val MAX_WARNINGS = 200
        const val MAX_SHOWN = 40
    }
}
