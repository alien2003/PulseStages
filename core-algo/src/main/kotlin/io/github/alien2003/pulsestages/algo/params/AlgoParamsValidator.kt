package io.github.alien2003.pulsestages.algo.params

/**
 * One invalid parameter value.
 *
 * @property path path of the value inside [AlgoParams], e.g. `preprocess.epochSec`.
 * @property value the rejected value as text.
 * @property constraint what would be accepted, e.g. `one of 30, 60, 120`, `odd, 3..31` or `>= 0.0`.
 */
data class ParamProblem(
    val path: String,
    val value: String,
    val constraint: String,
) {
    /** One-line English description, e.g. `preprocess.epochSec = 0 (expected one of 30, 60, 120)`. */
    override fun toString(): String = "$path = $value (expected $constraint)"
}

/**
 * Range checks of [AlgoParams]: values the estimators cannot run with (a non-positive epoch length, negative windows,
 * σ ≤ 0, …) and values outside the ranges the app's settings accept. Ranges are inclusive and follow the settings
 * validator of :data-local, plus the fields it does not cover. The estimators themselves do not validate (they
 * clamp where a value would make them fail); callers that take parameters from users or files validate first.
 */
object AlgoParamsValidator {
    /** Allowed epoch lengths: each divides an hour and is at least 30 s. */
    val EPOCH_SEC: Set<Int> = setOf(EPOCH_30_SEC, EPOCH_60_SEC, EPOCH_120_SEC)

    /** Every problem of [params] in declaration order; empty when the parameters are valid. */
    fun problems(params: AlgoParams): List<ParamProblem> {
        val checker = Checker()
        checker.preprocess(params.preprocess)
        checker.features(params.features)
        checker.hmm(params.hmm, params.preprocess.epochSec / SECONDS_PER_MINUTE)
        checker.rules(params.rules)
        checker.hybrid(params.hybrid)
        checker.post(params.post)
        checker.personal(params.personal)
        return checker.problems.toList()
    }

    private const val SECONDS_PER_MINUTE = 60.0
    private const val EPOCH_30_SEC = 30
    private const val EPOCH_60_SEC = 60
    private const val EPOCH_120_SEC = 120
}

private const val PERCENT_MAX = 100.0
private const val ODD_WINDOW_MAX = 31
private const val HAMPEL_WINDOW_MIN = 3
private const val HALF_WINDOW_MAX = 60
private const val ITERATIONS_MAX = 50
private val UNIT = 0.0..1.0
private val PERCENTILE = 0.0..PERCENT_MAX
private val ODD_WINDOW = 1..ODD_WINDOW_MAX
private val HAMPEL_WINDOW = HAMPEL_WINDOW_MIN..ODD_WINDOW_MAX
private val HALF_WINDOW = 1..HALF_WINDOW_MAX
private val ITERATIONS = 0..ITERATIONS_MAX
private const val MAX_INTERP_GAP_MIN = 60.0
private const val MAX_CONTEXT_MARGIN_MIN = 240
private const val MIN_BASELINE_NIGHTS = 3
private const val MAX_BASELINE_NIGHTS = 365
private const val MAX_AWAKE_STEP_THRESHOLD = 10_000
private const val MIN_MAX_BOUT_MIN = 10
private const val MAX_MAX_BOUT_MIN = 600
private const val MAX_EM_EVERY_NIGHTS = 3650

private fun Checker.preprocess(v: PreprocessParams) {
    val p = "preprocess"
    greaterThan("$p.minBpm", v.minBpm, 0.0)
    greaterThan("$p.maxBpm", v.maxBpm, 0.0)
    notAbove("$p.minBpm", v.minBpm, "$p.maxBpm", v.maxBpm)
    int("$p.hampelWindow", v.hampelWindow, HAMPEL_WINDOW, odd = true)
    greaterThan("$p.hampelThreshold", v.hampelThreshold, 0.0)
    if (v.epochSec !in AlgoParamsValidator.EPOCH_SEC) {
        add("$p.epochSec", v.epochSec.toString(), "one of ${AlgoParamsValidator.EPOCH_SEC.sorted().joinToString()}")
    }
    inRange("$p.maxInterpGapMin", v.maxInterpGapMin, 0.0..MAX_INTERP_GAP_MIN)
    int("$p.medianWindowEpochs", v.medianWindowEpochs, ODD_WINDOW, odd = true)
    int("$p.smoothWindowEpochs", v.smoothWindowEpochs, ODD_WINDOW, odd = true)
    int("$p.contextMarginMin", v.contextMarginMin, 0..MAX_CONTEXT_MARGIN_MIN)
    inRange("$p.minCoverage", v.minCoverage, UNIT)
    inRange("$p.rulesMinCoverage", v.rulesMinCoverage, UNIT)
    notAbove("$p.rulesMinCoverage", v.rulesMinCoverage, "$p.minCoverage", v.minCoverage)
    atLeast("$p.hampelMinScaleBpm", v.hampelMinScaleBpm, 0.0)
    atLeast("$p.minSessionMin", v.minSessionMin, 0.0)
}

private fun Checker.features(v: FeatureParams) {
    val p = "features"
    inRange("$p.normLowPercentile", v.normLowPercentile, PERCENTILE)
    inRange("$p.normHighPercentile", v.normHighPercentile, PERCENTILE)
    notAbove("$p.normLowPercentile", v.normLowPercentile, "$p.normHighPercentile", v.normHighPercentile)
    greaterThan("$p.minNormRangeBpm", v.minNormRangeBpm, 0.0)
    int("$p.varHalfWindowEpochs", v.varHalfWindowEpochs, HALF_WINDOW)
    int("$p.slopeHalfWindowEpochs", v.slopeHalfWindowEpochs, HALF_WINDOW)
    greaterThan("$p.cycleMinDistanceMin", v.cycleMinDistanceMin, 0.0)
    atLeast("$p.cycleMinProminenceBpm", v.cycleMinProminenceBpm, 0.0)
    int("$p.baselineNights", v.baselineNights, MIN_BASELINE_NIGHTS..MAX_BASELINE_NIGHTS)
    atLeast("$p.elevatedNightPct", v.elevatedNightPct, 0.0)
    atLeast("$p.elevatedAwakeShift", v.elevatedAwakeShift, 0.0)
    inRange("$p.elevatedConfidenceFactor", v.elevatedConfidenceFactor, UNIT)
    atLeast("$p.spo2DropPct", v.spo2DropPct, 0.0)
    atLeast("$p.spo2AwakeLightFactor", v.spo2AwakeLightFactor, 0.0)
    greaterThan("$p.hrvWeight", v.hrvWeight, 0.0)
    atLeast("$p.respiratoryRateWeight", v.respiratoryRateWeight, 0.0)
    greaterThan("$p.spo2MedianWindowMin", v.spo2MedianWindowMin, 0.0)
    atLeast("$p.hrvMaxDistanceMin", v.hrvMaxDistanceMin, 0.0)
    int("$p.respiratoryHalfWindowEpochs", v.respiratoryHalfWindowEpochs, HALF_WINDOW)
}

private fun Checker.hmm(
    v: HmmParams,
    epochMin: Double,
) {
    val p = "hmm"
    val e = v.emissions
    for ((state, emission) in listOf("awake" to e.awake, "light" to e.light, "deep" to e.deep, "rem" to e.rem)) {
        for ((dim, g) in listOf("hrNorm" to emission.hrNorm, "hrVar" to emission.hrVar, "hrSlope" to emission.hrSlope)) {
            finite("$p.emissions.$state.$dim.mean", g.mean)
            greaterThan("$p.emissions.$state.$dim.sd", g.sd, 0.0)
        }
    }
    val b = v.meanBouts
    for ((name, value) in listOf("awakeMin" to b.awakeMin, "lightMin" to b.lightMin, "deepMin" to b.deepMin, "remMin" to b.remMin)) {
        greaterThan("$p.meanBouts.$name", value, epochMin)
    }
    val t = v.transitions
    listOf(
        "awakeToLight" to t.awakeToLight,
        "awakeToDeep" to t.awakeToDeep,
        "awakeToRem" to t.awakeToRem,
        "lightToAwake" to t.lightToAwake,
        "lightToDeep" to t.lightToDeep,
        "lightToRem" to t.lightToRem,
        "deepToAwake" to t.deepToAwake,
        "deepToLight" to t.deepToLight,
        "deepToRem" to t.deepToRem,
        "remToAwake" to t.remToAwake,
        "remToLight" to t.remToLight,
        "remToDeep" to t.remToDeep,
    ).forEach { (name, weight) -> atLeast("$p.transitions.$name", weight, 0.0) }
    hmmScalars(v)
}

private fun Checker.hmmScalars(v: HmmParams) {
    val p = "hmm"
    greaterThan("$p.epsilon", v.epsilon, 0.0)
    if (v.epsilon > 0.0) inRange("$p.epsilon", v.epsilon, UNIT)
    atLeast("$p.deepTimeIntercept", v.deepTimeIntercept, 0.0)
    atLeast("$p.deepTimeSlope", v.deepTimeSlope, 0.0)
    atLeast("$p.deepTimeFloor", v.deepTimeFloor, 0.0)
    atLeast("$p.remTimeIntercept", v.remTimeIntercept, 0.0)
    atLeast("$p.remTimeSlope", v.remTimeSlope, 0.0)
    atLeast("$p.remLockoutMin", v.remLockoutMin, 0.0)
    inRange("$p.remLockoutFactor", v.remLockoutFactor, UNIT)
    atLeast("$p.initialAwake", v.initialAwake, 0.0)
    atLeast("$p.initialLight", v.initialLight, 0.0)
    atLeast("$p.cyclePriorWeight", v.cyclePriorWeight, 0.0)
    inRange("$p.cycleDeepPhase", v.cycleDeepPhase, UNIT)
    inRange("$p.cycleRemPhase", v.cycleRemPhase, UNIT)
    int("$p.awakeStepThreshold", v.awakeStepThreshold, 1..MAX_AWAKE_STEP_THRESHOLD)
    atLeast("$p.fewStepsAwakeFactor", v.fewStepsAwakeFactor, 0.0)
    int("$p.emIterations", v.emIterations, ITERATIONS)
    atLeast("$p.emPriorStrength", v.emPriorStrength, 0.0)
}

private fun Checker.rules(v: RulesParams) {
    val p = "rules"
    finite("$p.deepMaxHrNorm", v.deepMaxHrNorm)
    finite("$p.deepMaxHrVar", v.deepMaxHrVar)
    finite("$p.awakeMinHrNorm", v.awakeMinHrNorm)
    finite("$p.remMinHrNorm", v.remMinHrNorm)
    finite("$p.remMinHrVar", v.remMinHrVar)
    inRange("$p.remMinT", v.remMinT, UNIT)
    inRange("$p.pseudoPosterior", v.pseudoPosterior, UNIT)
    atLeast("$p.calibrationThresholdShift", v.calibrationThresholdShift, 0.0)
}

private fun Checker.hybrid(v: HybridParams) {
    val p = "hybrid"
    atLeast("$p.timePriorWeight", v.timePriorWeight, 0.0)
    int("$p.maxBoutMin", v.maxBoutMin, MIN_MAX_BOUT_MIN..MAX_MAX_BOUT_MIN)
    atLeast("$p.emissionWeight", v.emissionWeight, 0.0)
    atLeast("$p.elevatedAwakeFactor", v.elevatedAwakeFactor, 0.0)
    atLeast("$p.priorExponent", v.priorExponent, 0.0)
}

private fun Checker.post(v: PostprocessParams) {
    val p = "post"
    atLeast("$p.minDeepMin", v.minDeepMin, 0.0)
    atLeast("$p.minRemMin", v.minRemMin, 0.0)
    atLeast("$p.minAwakeMin", v.minAwakeMin, 0.0)
    inRange("$p.sanityMinCoverage", v.sanityMinCoverage, UNIT)
    inRange("$p.sanityDeepMax", v.sanityDeepMax, UNIT)
    inRange("$p.sanityRemMax", v.sanityRemMax, UNIT)
    inRange("$p.sanityDeepMin", v.sanityDeepMin, UNIT)
    notAbove("$p.sanityDeepMin", v.sanityDeepMin, "$p.sanityDeepMax", v.sanityDeepMax)
    inRange("$p.sanityConfidenceFactor", v.sanityConfidenceFactor, UNIT)
    inRange("$p.targetDeepMin", v.targetDeepMin, UNIT)
    inRange("$p.targetDeepMax", v.targetDeepMax, UNIT)
    notAbove("$p.targetDeepMin", v.targetDeepMin, "$p.targetDeepMax", v.targetDeepMax)
    inRange("$p.targetRemMin", v.targetRemMin, UNIT)
    inRange("$p.targetRemMax", v.targetRemMax, UNIT)
    notAbove("$p.targetRemMin", v.targetRemMin, "$p.targetRemMax", v.targetRemMax)
    inRange("$p.targetAwakeMax", v.targetAwakeMax, UNIT)
    inRange("$p.calibrationStep", v.calibrationStep, UNIT)
    int("$p.calibrationMaxIterations", v.calibrationMaxIterations, ITERATIONS)
    inRange("$p.lowConfidenceThreshold", v.lowConfidenceThreshold, UNIT)
}

private fun Checker.personal(v: PersonalParams) {
    val p = "personal"
    int("$p.emEveryNights", v.emEveryNights, 1..MAX_EM_EVERY_NIGHTS)
    atLeast("$p.emPriorStrength", v.emPriorStrength, 0.0)
    atLeast("$p.calibrationRegularization", v.calibrationRegularization, 0.0)
    inRange("$p.calibrationHoldout", v.calibrationHoldout, UNIT)
}

/** Collects [ParamProblem]s; every check is independent so that one call reports every problem. */
private class Checker {
    val problems = mutableListOf<ParamProblem>()

    fun add(
        path: String,
        value: String,
        constraint: String,
    ) {
        problems += ParamProblem(path, value, constraint)
    }

    fun int(
        path: String,
        value: Int,
        range: IntRange,
        odd: Boolean = false,
    ) {
        val constraint = (if (odd) "odd, " else "") + "${range.first}..${range.last}"
        if (value !in range || (odd && value % 2 == 0)) add(path, value.toString(), constraint)
    }

    /** Returns true when [value] is finite; records a problem otherwise. */
    fun finite(
        path: String,
        value: Double,
    ): Boolean {
        val ok = value.isFinite()
        if (!ok) add(path, value.toString(), "a finite number")
        return ok
    }

    fun inRange(
        path: String,
        value: Double,
        range: ClosedFloatingPointRange<Double>,
    ) {
        if (finite(path, value) && value !in range) add(path, value.toString(), "${range.start}..${range.endInclusive}")
    }

    fun atLeast(
        path: String,
        value: Double,
        min: Double,
    ) {
        if (finite(path, value) && value < min) add(path, value.toString(), ">= $min")
    }

    fun greaterThan(
        path: String,
        value: Double,
        bound: Double,
    ) {
        if (finite(path, value) && value <= bound) add(path, value.toString(), "> $bound")
    }

    fun notAbove(
        lowPath: String,
        low: Double,
        highPath: String,
        high: Double,
    ) {
        if (low.isFinite() && high.isFinite() && low > high) add(lowPath, low.toString(), "<= $highPath ($high)")
    }
}
