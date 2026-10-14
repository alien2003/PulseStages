package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.algo.params.NapHybridParams
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.NapModels
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Proof that the night / nap modes change no decode while the bundle has no nap sub-model: whole synthetic nights,
 * synthetic sessions of 45–181 min and nap proxies cut from the nights (the first L minutes, 30 min of context before,
 * nothing after the end, like tools/train's `nap_proxy`) are estimated under several configurations, and every
 * result (stages, posteriors, confidence, coverage, warnings, flags, nadir) must equal the digest recorded with the
 * build before the modes existed (commit 78bcc71, file `nap-mode-regression.tsv`). The bundle is a fixed copy of the
 * bundle shipped then (`nap-mode-regression-bundle.json`, `pulsestages-walch+bidsleep-mlp-naps-final`), so the test
 * keeps proving the same thing after a new bundle is installed. The threshold variants map the old three thresholds
 * (`features.baselineMinSessionMin`, `hybrid.shortSessionMaxMin`, `post.proportionsMinSessionMin`, all set to the same
 * value) onto `napMode.maxSessionMin`, and an estimator variant sets only `estimator`, as a settings file of the
 * recording build does (there `estimator` applied to every session; now naps follow it while `napMode.estimator` is
 * null). The params hash is not compared: the parameters changed shape, so every hash
 * changed by design; the bundle fingerprint is compared and must be the one the old build computed. The parameters are
 * the defaults of the recording build ([PREVIOUS_DEFAULTS]: no nap decoder overrides, every stage written), so a later
 * trained nap default (TrainedDefaults `NAP_HYBRID_*`, `NAP_OUTPUT_POLICY`) does not change what is proved here.
 */
class NapModeRegressionTest {
    private val bundled: HybridModel by lazy {
        HybridModel.parse(checkNotNull(javaClass.getResource("/$BUNDLE")) { "missing $BUNDLE" }.readText())
    }

    /** One configuration: a stager and the parameters, optionally with a baseline given to every session. */
    private class Case(
        val name: String,
        val stager: SleepStager,
        val params: AlgoParams,
        val baseline: Double? = null,
    )

    private fun cases(): List<Case> {
        val base = PREVIOUS_DEFAULTS
        val stager = SleepStager(bundled)
        return listOf(
            Case("bundled/default", stager, base),
            Case("bundled/noShortBias", SleepStager(bundled.copy(stageLogBiasShort = null)), base),
            Case("bundled/geometric", stager, base.copy(hybrid = base.hybrid.copy(durationModel = DurationModel.GEOMETRIC))),
            Case("bundled/threshold0", stager, base.withNapThreshold(0.0)),
            Case("bundled/threshold120", stager, base.withNapThreshold(120.0)),
            Case("bundled/calibrate", stager, base.copy(post = base.post.copy(calibrateProportions = true))),
            Case("bundled/lowConfidenceLight", stager, base.copy(post = base.post.copy(lowConfidencePolicy = LowConfidencePolicy.LIGHT))),
            Case("bundled/elevated", stager, base, baseline = LOW_BASELINE),
            Case("bundled/elevatedThreshold0", stager, base.withNapThreshold(0.0), baseline = LOW_BASELINE),
            Case("synthetic/default", SleepStager(SyntheticHybridModels.default), base),
            // The recording build had one estimator for every session: its settings set only `estimator`.
            Case("rules", stager, base.copy(estimator = EstimatorType.RULES)),
            Case("hmm", stager, base.copy(estimator = EstimatorType.HMM)),
            Case("hmm/elevated", stager, base.copy(estimator = EstimatorType.HMM), baseline = LOW_BASELINE),
        )
    }

    /** The sessions: whole nights, nap proxies of them, and generated sessions of fixed lengths. */
    private fun sessions(): List<Pair<String, NightInput>> =
        buildList {
            for (seed in NIGHT_SEEDS) {
                val night = SyntheticNightGenerator(seed).generate().input
                add("night-$seed" to night)
                for (minutes in PROXY_MINUTES) add("night-$seed-nap$minutes" to proxy(night, minutes))
            }
            for (minutes in SESSION_MINUTES) {
                add(
                    "session-$minutes" to
                        SyntheticNightGenerator(SESSION_SEED + minutes).generate(SyntheticOptions(durationMin = minutes)).input,
                )
            }
        }

    /** The first [minutes] of [night]: heart rate of [start − 30 min, end], steps ending in (start − 30 min, end]. */
    private fun proxy(
        night: NightInput,
        minutes: Int,
    ): NightInput {
        val start = night.sessionStartMs
        val end = start + minutes * MINUTE_MS
        val context = start - CONTEXT_MS
        return NightInput(
            sessionStartMs = start,
            sessionEndMs = end,
            heartRate = night.heartRate.filter { it.timeMs in context..end },
            steps = night.steps.filter { it.endMs > context && it.endMs <= end },
        )
    }

    /** Canonical text of one outcome (exact doubles: the same code path gives bit-identical results). */
    private fun describe(outcome: EstimationOutcome): String =
        when (outcome) {
            is EstimationOutcome.NotEstimated -> {
                "N|${outcome.reason}|${outcome.coverage}"
            }

            is EstimationOutcome.Estimated -> {
                val e = outcome.estimate
                buildString {
                    append("E|${e.estimator}|${e.coverage}|${e.confidence}|${e.nadirBpm}|${e.hrSampleCount}|${e.flags}|")
                    append(e.warnings.joinToString(",") { it.code.name })
                    e.stages.forEach { append("|${it.startMs},${it.endMs},${it.stage}") }
                    e.perEpoch.forEach { epoch ->
                        val p = epoch.posterior
                        append("|${epoch.stage}:${p.awake},${p.light},${p.deep},${p.rem}")
                    }
                }
            }
        }

    private fun digest(
        case: Case,
        input: NightInput,
    ): String = ParamsHasher.sha256Hex(describe(case.stager.run(input.copy(baselineNadirBpm = case.baseline), case.params)))

    private fun digests(): List<String> =
        cases().flatMap { case -> sessions().map { (id, input) -> "${case.name}\t$id\t${digest(case, input)}" } }

    private fun recorded(): List<String> =
        checkNotNull(javaClass.getResource("/$GOLDEN")) { "missing $GOLDEN" }
            .readText()
            .lines()
            .filter { it.isNotBlank() }

    @Test
    fun `without a nap sub-model every night and nap decodes exactly as before the modes`() {
        val actual = digests()
        if (System.getenv(RECORD_ENV) == "1") {
            val target = File(checkNotNull(System.getProperty("pulsestages.coreAlgo.mainSrc"))).resolve("../test/resources/$GOLDEN")
            target.writeText(actual.joinToString("\n", postfix = "\n"))
            return
        }
        val expected = recorded()
        assertEquals(expected.size, actual.size, "number of decoded sessions")
        val differing = expected.zip(actual).filter { (want, got) -> want != got }.map { it.second.substringBeforeLast('\t') }
        assertTrue(differing.isEmpty(), "decodes differ from the recorded build: $differing")
    }

    @Test
    fun `a bundle without a nap section keeps the fingerprint the build before the modes computed`() {
        assertEquals(RECORDED_FINGERPRINT, bundled.fingerprint())
        assertEquals(null, bundled.nap)
    }

    @Test
    fun `a nap section changes the naps only, every night decodes as recorded`() {
        val nap = NapModels.from(SyntheticHybridModels.default)
        val withNap = bundled.copy(nap = nap)
        val case = Case("bundled/default", SleepStager(withNap), PREVIOUS_DEFAULTS)
        val expected = recorded().filter { it.startsWith("${case.name}\t") }.associate { line -> line.split('\t').let { it[1] to it[2] } }
        var naps = 0
        var changedNaps = 0
        for ((id, input) in sessions()) {
            val got = digest(case, input)
            if (AlgoParams().modeOf(input.sessionEndMs - input.sessionStartMs) == SessionMode.NIGHT) {
                assertEquals(expected.getValue(id), got, "night $id must not change with a nap section")
            } else {
                naps++
                if (got != expected.getValue(id)) changedNaps++
            }
        }
        assertTrue(naps > 0 && changedNaps == naps, "every nap is decoded by the nap model: $changedNaps of $naps changed")
    }

    @Test
    fun `a nap section with the main model's components and its short-session bias decodes like no nap section`() {
        val same = bundled.copy(nap = NapModels.from(bundled, stageLogBias = bundled.stageLogBiasShort))
        val case = Case("bundled/default", SleepStager(same), PREVIOUS_DEFAULTS)
        val expected = recorded().filter { it.startsWith("${case.name}\t") }.associate { line -> line.split('\t').let { it[1] to it[2] } }
        for ((id, input) in sessions()) assertEquals(expected.getValue(id), digest(case, input), id)
        assertTrue(same.fingerprint() != bundled.fingerprint(), "the nap section is part of the fingerprint")
    }

    private companion object {
        const val GOLDEN = "nap-mode-regression.tsv"
        const val BUNDLE = "nap-mode-regression-bundle.json"

        /** `HybridModel.fingerprint()` of [BUNDLE] computed by the build before the modes (78bcc71). */
        const val RECORDED_FINGERPRINT = "ef07353edf3d48505982d5ac03fbfbbd7f372a5efe8cb00b08a420872646604d"
        const val RECORD_ENV = "PULSESTAGES_RECORD_NAP_GOLDEN"
        const val MINUTE_MS = 60_000L
        const val CONTEXT_MS = 30 * MINUTE_MS
        const val LOW_BASELINE = 30.0
        const val SESSION_SEED = 7_000L
        val NIGHT_SEEDS = 501L..506L
        val PROXY_MINUTES = listOf(30, 60, 90, 120, 150, 179, 180, 181)
        val SESSION_MINUTES = listOf(45, 120, 179, 180, 181)

        /**
         * The defaults of the recording build: today's defaults without nap decoder overrides and without a nap
         * estimator of its own (naps follow `estimator`), naps written as STAGES.
         */
        val PREVIOUS_DEFAULTS: AlgoParams =
            AlgoParams().let {
                it.copy(napMode = it.napMode.copy(estimator = null, outputPolicy = NapOutputPolicy.STAGES, hybrid = NapHybridParams()))
            }

        /**
         * The single nap threshold [minutes] in this build's parameters. The recording build (before the modes) set its
         * three thresholds instead: `features.baselineMinSessionMin`, `hybrid.shortSessionMaxMin` and
         * `post.proportionsMinSessionMin`.
         */
        fun AlgoParams.withNapThreshold(minutes: Double): AlgoParams = copy(napMode = napMode.copy(maxSessionMin = minutes))
    }
}
