package io.github.alien2003.pulsestages.data.local.settings

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.Gaussian
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.MeanBouts
import io.github.alien2003.pulsestages.algo.params.PersonalParams
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

class SettingsValidatorTest {
    private val defaults = AppSettings()

    private fun sources(transform: SourceSettings.() -> SourceSettings) = defaults.copy(sources = defaults.sources.transform())

    private fun schedule(transform: ScheduleSettings.() -> ScheduleSettings) = defaults.copy(schedule = defaults.schedule.transform())

    private fun algo(transform: AlgoParams.() -> AlgoParams) = defaults.copy(algo = defaults.algo.transform())

    private fun hmm(transform: HmmParams.() -> HmmParams) = algo { copy(hmm = hmm.transform()) }

    private fun assertValid(settings: AppSettings) {
        val errors = SettingsValidator.validate(settings).errors
        assertTrue(errors.isEmpty(), "expected valid, got $errors")
    }

    private fun assertRejected(
        settings: AppSettings,
        path: String,
        code: SettingsErrorCode = SettingsErrorCode.OUT_OF_RANGE,
    ) {
        val validation = SettingsValidator.validate(settings)
        assertTrue(!validation.isValid, "expected $path to be rejected")
        assertTrue(validation.errors.any { it.path == path && it.code == code }, "expected $path $code in ${validation.errors}")
    }

    @Test
    fun `defaults are valid`() {
        assertValid(defaults)
    }

    @Test
    fun `source ranges are inclusive and enforced`() {
        assertAll(
            { assertValid(sources { copy(minSessionMin = 10, mergeGapMin = 0, backfillDays = 0) }) },
            { assertValid(sources { copy(minSessionMin = 720, mergeGapMin = 240, backfillDays = 36_500) }) },
            { assertRejected(sources { copy(minSessionMin = 9) }, "sources.minSessionMin") },
            { assertRejected(sources { copy(minSessionMin = 721) }, "sources.minSessionMin") },
            { assertRejected(sources { copy(mergeGapMin = -1) }, "sources.mergeGapMin") },
            { assertRejected(sources { copy(mergeGapMin = 241) }, "sources.mergeGapMin") },
            { assertRejected(sources { copy(backfillDays = -1) }, "sources.backfillDays") },
            { assertRejected(sources { copy(backfillDays = 36_501) }, "sources.backfillDays") },
        )
    }

    @Test
    fun `package names must be valid Android package names`() {
        val bad = listOf("com", "1com.example", "com..example", "com.example-app", "", "com.example.", ".com.example", "com.1example")
        assertAll(
            { assertValid(sources { copy(sourcePackages = listOf("a.b_c.D9"), sourcePriority = listOf("a.b_c.D9")) }) },
            *bad
                .map { name ->
                    {
                        assertRejected(
                            sources { copy(sourcePackages = listOf(OHEALTH_PACKAGE, name)) },
                            "sources.sourcePackages[1]",
                            SettingsErrorCode.INVALID_PACKAGE_NAME,
                        )
                    }
                }.toTypedArray(),
            {
                assertRejected(
                    sources { copy(sourcePriority = listOf("bad")) },
                    "sources.sourcePriority[0]",
                    SettingsErrorCode.INVALID_PACKAGE_NAME,
                )
            },
            {
                assertRejected(
                    sources {
                        copy(manualPackages = listOf("bad name"))
                    },
                    "sources.manualPackages[0]",
                    SettingsErrorCode.INVALID_PACKAGE_NAME,
                )
            },
        )
    }

    @Test
    fun `sourcePriority is repaired so that it contains every enabled and manual package`() {
        val settings =
            sources {
                copy(
                    sourcePackages = listOf("com.example.b", "com.example.a", "com.example.b"),
                    sourcePriority = listOf("com.example.c", "com.example.a", "com.example.c"),
                    manualPackages = listOf("com.example.d", "com.example.d"),
                )
            }

        val validation = SettingsValidator.validate(settings)

        assertTrue(validation.isValid)
        val repaired = validation.settings.sources
        assertEquals(listOf("com.example.b", "com.example.a"), repaired.sourcePackages)
        assertEquals(listOf("com.example.c", "com.example.a", "com.example.b", "com.example.d"), repaired.sourcePriority)
        assertEquals(listOf("com.example.d"), repaired.manualPackages)
        assertTrue(repaired.sourcePriority.containsAll(repaired.sourcePackages))
        assertSame(defaults, SettingsValidator.repair(defaults), "nothing to repair returns the same instance")
    }

    @Test
    fun `schedule ranges are inclusive and enforced`() {
        assertAll(
            { assertValid(schedule { copy(dailyTimeMinutes = 0, periodicHours = 1) }) },
            { assertValid(schedule { copy(dailyTimeMinutes = 1439, periodicHours = 24) }) },
            { assertRejected(schedule { copy(dailyTimeMinutes = -1) }, "schedule.dailyTimeMinutes") },
            { assertRejected(schedule { copy(dailyTimeMinutes = 1440) }, "schedule.dailyTimeMinutes") },
            { assertRejected(schedule { copy(periodicHours = 0) }, "schedule.periodicHours") },
            { assertRejected(schedule { copy(periodicHours = 25) }, "schedule.periodicHours") },
        )
    }

    @Test
    fun `title template must be null or non-blank and at most 1000 characters`() {
        fun title(value: String?) = defaults.copy(write = defaults.write.copy(titleTemplate = value))
        assertAll(
            { assertValid(title(null)) },
            { assertValid(title("x".repeat(1000))) },
            { assertRejected(title("   "), "write.titleTemplate", SettingsErrorCode.BLANK) },
            { assertRejected(title("x".repeat(1001)), "write.titleTemplate", SettingsErrorCode.TOO_LONG) },
        )
    }

    @Test
    fun `preprocessing epochs, windows and coverage are enforced`() {
        fun pre(transform: PreprocessParams.() -> PreprocessParams) = algo { copy(preprocess = preprocess.transform()) }
        val p = "algo.preprocess"
        assertAll(
            { listOf(30, 60, 120).forEach { assertValid(pre { copy(epochSec = it) }) } },
            {
                listOf(
                    0,
                    15,
                    45,
                    90,
                    180,
                ).forEach { assertRejected(pre { copy(epochSec = it) }, "$p.epochSec", SettingsErrorCode.NOT_ALLOWED) }
            },
            { assertValid(pre { copy(smoothWindowEpochs = 1, medianWindowEpochs = 31, hampelWindow = 3) }) },
            { assertValid(pre { copy(smoothWindowEpochs = 31, medianWindowEpochs = 1, hampelWindow = 31) }) },
            { assertRejected(pre { copy(smoothWindowEpochs = 4) }, "$p.smoothWindowEpochs", SettingsErrorCode.NOT_ODD) },
            { assertRejected(pre { copy(smoothWindowEpochs = 33) }, "$p.smoothWindowEpochs") },
            { assertRejected(pre { copy(medianWindowEpochs = 0) }, "$p.medianWindowEpochs") },
            { assertRejected(pre { copy(medianWindowEpochs = 2) }, "$p.medianWindowEpochs", SettingsErrorCode.NOT_ODD) },
            { assertRejected(pre { copy(hampelWindow = 1) }, "$p.hampelWindow") },
            { assertRejected(pre { copy(hampelWindow = 4) }, "$p.hampelWindow", SettingsErrorCode.NOT_ODD) },
            { assertRejected(pre { copy(hampelWindow = 33) }, "$p.hampelWindow") },
            { assertValid(pre { copy(maxInterpGapMin = 0.0) }) },
            { assertValid(pre { copy(maxInterpGapMin = 60.0) }) },
            { assertRejected(pre { copy(maxInterpGapMin = 60.5) }, "$p.maxInterpGapMin") },
            { assertRejected(pre { copy(maxInterpGapMin = -0.5) }, "$p.maxInterpGapMin") },
            { assertValid(pre { copy(minCoverage = 1.0, rulesMinCoverage = 1.0) }) },
            { assertValid(pre { copy(minCoverage = 0.0, rulesMinCoverage = 0.0) }) },
            { assertRejected(pre { copy(minCoverage = 1.01) }, "$p.minCoverage") },
            { assertRejected(pre { copy(rulesMinCoverage = -0.1) }, "$p.rulesMinCoverage") },
            {
                assertRejected(
                    pre { copy(minCoverage = 0.5, rulesMinCoverage = 0.6) },
                    "$p.rulesMinCoverage",
                    SettingsErrorCode.MIN_ABOVE_MAX,
                )
            },
            { assertRejected(pre { copy(minBpm = 100.0, maxBpm = 90.0) }, "$p.minBpm", SettingsErrorCode.MIN_ABOVE_MAX) },
            { assertRejected(pre { copy(minBpm = 0.0) }, "$p.minBpm") },
            { assertRejected(pre { copy(hampelThreshold = 0.0) }, "$p.hampelThreshold") },
            { assertRejected(pre { copy(contextMarginMin = -1) }, "$p.contextMarginMin") },
            { assertRejected(pre { copy(minCoverage = Double.NaN) }, "$p.minCoverage", SettingsErrorCode.NOT_FINITE) },
        )
    }

    @Test
    fun `feature parameters are enforced`() {
        fun feat(transform: FeatureParams.() -> FeatureParams) = algo { copy(features = features.transform()) }
        val p = "algo.features"
        assertAll(
            {
                assertRejected(
                    feat {
                        copy(normLowPercentile = 60.0, normHighPercentile = 50.0)
                    },
                    "$p.normLowPercentile",
                    SettingsErrorCode.MIN_ABOVE_MAX,
                )
            },
            { assertRejected(feat { copy(normHighPercentile = 101.0) }, "$p.normHighPercentile") },
            { assertRejected(feat { copy(minNormRangeBpm = 0.0) }, "$p.minNormRangeBpm") },
            { assertRejected(feat { copy(varHalfWindowEpochs = 0) }, "$p.varHalfWindowEpochs") },
            { assertRejected(feat { copy(baselineNights = 2) }, "$p.baselineNights") },
            { assertValid(feat { copy(baselineNights = 3) }) },
            { assertRejected(feat { copy(elevatedNightPct = -1.0) }, "$p.elevatedNightPct") },
            { assertRejected(feat { copy(elevatedConfidenceFactor = 1.5) }, "$p.elevatedConfidenceFactor") },
            { assertRejected(feat { copy(hrvWeight = 0.0) }, "$p.hrvWeight") },
            { assertRejected(feat { copy(respiratoryRateWeight = -0.1) }, "$p.respiratoryRateWeight") },
        )
    }

    @Test
    fun `the nap thresholds accept 0 to 720 minutes`() {
        fun thresholds(
            baseline: Double,
            proportions: Double,
        ) = algo {
            copy(
                features = features.copy(baselineMinSessionMin = baseline),
                post = post.copy(proportionsMinSessionMin = proportions),
            )
        }
        assertAll(
            { assertValid(thresholds(0.0, 0.0)) },
            { assertValid(thresholds(720.0, 720.0)) },
            { assertValid(thresholds(30.0, 90.5)) },
            { assertRejected(thresholds(-1.0, 180.0), "algo.features.baselineMinSessionMin") },
            { assertRejected(thresholds(720.5, 180.0), "algo.features.baselineMinSessionMin") },
            { assertRejected(thresholds(Double.NaN, 180.0), "algo.features.baselineMinSessionMin", SettingsErrorCode.NOT_FINITE) },
            { assertRejected(thresholds(180.0, -0.5), "algo.post.proportionsMinSessionMin") },
            { assertRejected(thresholds(180.0, 721.0), "algo.post.proportionsMinSessionMin") },
        )
    }

    @Test
    fun `every Gaussian sd must be positive and every mean finite`() {
        val physiology = EmissionTable.PHYSIOLOGY
        val p = "algo.hmm.emissions"
        val zeroSd = physiology.copy(deep = physiology.deep.copy(hrVar = Gaussian(mean = 0.6, sd = 0.0)))
        val negativeSd = physiology.copy(awake = physiology.awake.copy(hrSlope = Gaussian(mean = 0.0, sd = -1.0)))
        val nanMean = physiology.copy(rem = physiology.rem.copy(hrNorm = Gaussian(mean = Double.NaN, sd = 0.5)))
        val allZero =
            physiology.copy(
                light = physiology.light.copy(hrNorm = Gaussian(1.0, 0.0), hrVar = Gaussian(0.9, 0.0), hrSlope = Gaussian(0.0, 0.0)),
            )
        assertAll(
            { assertRejected(hmm { copy(emissions = zeroSd) }, "$p.deep.hrVar.sd") },
            { assertRejected(hmm { copy(emissions = negativeSd) }, "$p.awake.hrSlope.sd") },
            { assertRejected(hmm { copy(emissions = nanMean) }, "$p.rem.hrNorm.mean", SettingsErrorCode.NOT_FINITE) },
            {
                assertEquals(
                    listOf("$p.light.hrNorm.sd", "$p.light.hrVar.sd", "$p.light.hrSlope.sd"),
                    SettingsValidator.validate(hmm { copy(emissions = allZero) }).errors.map { it.path },
                )
            },
        )
    }

    @Test
    fun `mean bouts must be longer than one epoch`() {
        fun bouts(
            epochSec: Int,
            meanBouts: MeanBouts,
        ) = algo { copy(preprocess = preprocess.copy(epochSec = epochSec), hmm = hmm.copy(meanBouts = meanBouts)) }
        val p = "algo.hmm.meanBouts"
        assertAll(
            { assertValid(bouts(60, MeanBouts(awakeMin = 1.01))) },
            { assertRejected(bouts(60, MeanBouts(awakeMin = 1.0)), "$p.awakeMin") },
            { assertRejected(bouts(120, MeanBouts(lightMin = 2.0)), "$p.lightMin") },
            { assertValid(bouts(120, MeanBouts(lightMin = 2.5))) },
            { assertRejected(bouts(30, MeanBouts(deepMin = 0.5)), "$p.deepMin") },
            { assertRejected(bouts(30, MeanBouts(remMin = -3.0)), "$p.remMin") },
        )
    }

    @Test
    fun `probabilities and weights must be non-negative`() {
        val p = "algo.hmm"
        assertAll(
            { assertValid(hmm { copy(transitions = transitions.copy(awakeToDeep = 0.0, remToDeep = 0.0), cyclePriorWeight = 0.0) }) },
            { assertRejected(hmm { copy(transitions = transitions.copy(lightToDeep = -0.01)) }, "$p.transitions.lightToDeep") },
            { assertRejected(hmm { copy(transitions = transitions.copy(remToLight = -1.0)) }, "$p.transitions.remToLight") },
            { assertRejected(hmm { copy(initialAwake = -0.5) }, "$p.initialAwake") },
            { assertRejected(hmm { copy(cyclePriorWeight = -0.5) }, "$p.cyclePriorWeight") },
            { assertRejected(hmm { copy(remLockoutFactor = 1.5) }, "$p.remLockoutFactor") },
            { assertRejected(hmm { copy(epsilon = 0.0) }, "$p.epsilon") },
            { assertRejected(hmm { copy(fewStepsAwakeFactor = -2.0) }, "$p.fewStepsAwakeFactor") },
            { assertRejected(hmm { copy(awakeStepThreshold = 0) }, "$p.awakeStepThreshold") },
            { assertRejected(hmm { copy(emIterations = -1) }, "$p.emIterations") },
            { assertRejected(algo { copy(hybrid = hybrid.copy(timePriorWeight = -1.0)) }, "algo.hybrid.timePriorWeight") },
            { assertRejected(algo { copy(hybrid = hybrid.copy(emissionWeight = -1.0)) }, "algo.hybrid.emissionWeight") },
            { assertRejected(algo { copy(hybrid = hybrid.copy(priorExponent = -0.25)) }, "algo.hybrid.priorExponent") },
            { assertRejected(algo { copy(hybrid = hybrid.copy(shortSessionMaxMin = -1.0)) }, "algo.hybrid.shortSessionMaxMin") },
            { assertRejected(algo { copy(rules = rules.copy(remMinT = 1.5)) }, "algo.rules.remMinT") },
            {
                assertRejected(
                    algo {
                        copy(rules = rules.copy(deepMaxHrNorm = Double.POSITIVE_INFINITY))
                    },
                    "algo.rules.deepMaxHrNorm",
                    SettingsErrorCode.NOT_FINITE,
                )
            },
        )
    }

    @Test
    fun `hybrid maxBoutMin range is inclusive and enforced`() {
        fun maxBout(value: Int) = algo { copy(hybrid = hybrid.copy(maxBoutMin = value)) }
        assertAll(
            { assertValid(maxBout(10)) },
            { assertValid(maxBout(600)) },
            { assertRejected(maxBout(9), "algo.hybrid.maxBoutMin") },
            { assertRejected(maxBout(601), "algo.hybrid.maxBoutMin") },
        )
    }

    @Test
    fun `post-processing thresholds and target ranges are enforced`() {
        fun post(transform: PostprocessParams.() -> PostprocessParams) = algo { copy(post = post.transform()) }
        val p = "algo.post"
        assertAll(
            { assertValid(post { copy(lowConfidenceThreshold = 0.0, targetDeepMin = 0.2, targetDeepMax = 0.2) }) },
            { assertValid(post { copy(lowConfidenceThreshold = 1.0, targetRemMin = 0.0, targetRemMax = 1.0, targetAwakeMax = 1.0) }) },
            { assertRejected(post { copy(lowConfidenceThreshold = 1.01) }, "$p.lowConfidenceThreshold") },
            { assertRejected(post { copy(lowConfidenceThreshold = -0.01) }, "$p.lowConfidenceThreshold") },
            {
                assertRejected(
                    post { copy(targetDeepMin = 0.3, targetDeepMax = 0.2) },
                    "$p.targetDeepMin",
                    SettingsErrorCode.MIN_ABOVE_MAX,
                )
            },
            { assertRejected(post { copy(targetRemMin = 0.4, targetRemMax = 0.3) }, "$p.targetRemMin", SettingsErrorCode.MIN_ABOVE_MAX) },
            { assertRejected(post { copy(targetRemMax = 1.2) }, "$p.targetRemMax") },
            { assertRejected(post { copy(targetAwakeMax = -0.1) }, "$p.targetAwakeMax") },
            {
                assertRejected(
                    post { copy(sanityDeepMin = 0.5, sanityDeepMax = 0.4) },
                    "$p.sanityDeepMin",
                    SettingsErrorCode.MIN_ABOVE_MAX,
                )
            },
            { assertRejected(post { copy(minDeepMin = -1.0) }, "$p.minDeepMin") },
            { assertRejected(post { copy(calibrationMaxIterations = 51) }, "$p.calibrationMaxIterations") },
        )
    }

    @Test
    fun `personalization parameters are enforced`() {
        fun personal(transform: PersonalParams.() -> PersonalParams) = algo { copy(personal = personal.transform()) }
        val p = "algo.personal"
        assertAll(
            { assertRejected(personal { copy(emEveryNights = 0) }, "$p.emEveryNights") },
            { assertRejected(personal { copy(calibrationHoldout = 1.5) }, "$p.calibrationHoldout") },
            { assertRejected(personal { copy(emPriorStrength = -1.0) }, "$p.emPriorStrength") },
        )
    }

    @Test
    fun `all problems are reported at once in declaration order`() {
        val settings =
            defaults.copy(
                sources = defaults.sources.copy(minSessionMin = 0, sourcePackages = listOf("bad")),
                schedule = defaults.schedule.copy(periodicHours = 0),
                algo = defaults.algo.copy(preprocess = defaults.algo.preprocess.copy(epochSec = 7)),
            )

        val errors = SettingsValidator.validate(settings).errors

        assertEquals(
            listOf(
                "sources.minSessionMin",
                "sources.sourcePackages[0]",
                // "bad" was appended to sourcePriority by the repair step, so it is reported there too.
                "sources.sourcePriority[1]",
                "schedule.periodicHours",
                "algo.preprocess.epochSec",
            ),
            errors.map { it.path },
        )
        assertEquals("sources.minSessionMin = 0: OUT_OF_RANGE (expected 10..720)", errors.first().message)
    }
}
