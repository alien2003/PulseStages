package io.github.alien2003.pulsestages.data.local.settings

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.HybridParams
import io.github.alien2003.pulsestages.algo.params.NapModeParams
import io.github.alien2003.pulsestages.algo.params.NapPostprocessParams
import io.github.alien2003.pulsestages.algo.params.PersonalParams
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import io.github.alien2003.pulsestages.algo.params.RulesParams

/** Machine-readable kind of a [SettingsError]; the UI maps it to localized text. */
enum class SettingsErrorCode {
    /** The text is not JSON or does not match the settings structure (wrong type, unknown enum value, ...). */
    MALFORMED_JSON,

    /** A number lies outside its allowed range ([SettingsError.constraint] says which). */
    OUT_OF_RANGE,

    /** A window length must be odd. */
    NOT_ODD,

    /** The value is not one of a fixed set of allowed values. */
    NOT_ALLOWED,

    /** A number is NaN or infinite. */
    NOT_FINITE,

    /** A lower bound is greater than the matching upper bound. */
    MIN_ABOVE_MAX,

    /** Not a valid Android package name. */
    INVALID_PACKAGE_NAME,

    /** A text value is blank (use `null` for the default). */
    BLANK,

    /** A text value is too long. */
    TOO_LONG,
}

/**
 * One validation problem.
 *
 * @property path JSON path of the offending value, e.g. `algo.preprocess.epochSec` or `sources.sourcePackages[1]`;
 *   `$` for the whole document.
 * @property code what is wrong.
 * @property value the rejected value as text (empty for [SettingsErrorCode.MALFORMED_JSON]).
 * @property constraint what would be accepted, in a language-neutral notation (`10..720`, `odd, 1..31`, `> 0.0`,
 *   `<= algo.preprocess.minCoverage (0.6)`); for [SettingsErrorCode.MALFORMED_JSON] the parser's message.
 */
data class SettingsError(
    val path: String,
    val code: SettingsErrorCode,
    val value: String,
    val constraint: String,
) {
    /** English one-line description for the log. */
    val message: String
        get() =
            if (code == SettingsErrorCode.MALFORMED_JSON) {
                "invalid settings JSON: $constraint"
            } else {
                "$path = $value: $code (expected $constraint)"
            }
}

/**
 * Result of [SettingsValidator.validate].
 *
 * @property settings the input after [SettingsValidator.repair]; only meaningful to store when [isValid].
 * @property errors every problem found, in a stable order (settings declaration order).
 */
data class SettingsValidation(
    val settings: AppSettings,
    val errors: List<SettingsError>,
) {
    /** True when there are no [errors]. */
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Validates complete [AppSettings]. Pure and deterministic. Everything that is written to the settings store and
 * everything that is imported goes through [validate]; invalid settings are never stored.
 *
 * Ranges are inclusive and defined in [SettingsLimits].
 */
object SettingsValidator {
    /** Android package names as accepted in the source lists. */
    val packageNameRegex: Regex = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /**
     * Repairs what can be repaired without guessing: removes duplicate package names (keeping the first
     * occurrence) and appends enabled and manually added packages that are missing from `sourcePriority`, so that
     * `sourcePackages ⊆ sourcePriority` always holds. Returns [settings] itself when nothing changes.
     */
    fun repair(settings: AppSettings): AppSettings {
        val sources = settings.sources
        val packages = sources.sourcePackages.distinct()
        val manual = sources.manualPackages.distinct()
        val priority = (sources.sourcePriority + packages + manual).distinct()
        val repaired = sources.copy(sourcePackages = packages, sourcePriority = priority, manualPackages = manual)
        return if (repaired == sources) settings else settings.copy(sources = repaired)
    }

    /** [Repairs][repair] [settings] and validates the result against every rule. */
    fun validate(settings: AppSettings): SettingsValidation {
        val repaired = repair(settings)
        val checker = Checker()
        checker.checkSources(repaired.sources)
        checker.checkSchedule(repaired.schedule)
        checker.checkWrite(repaired.write)
        checker.checkAlgo(repaired.algo)
        return SettingsValidation(repaired, checker.errors.toList())
    }
}

private const val ALGO = "algo"
private const val SECONDS_PER_MINUTE = 60.0

private fun Checker.checkSources(s: SourceSettings) {
    int("sources.minSessionMin", s.minSessionMin, SettingsLimits.minSessionMin)
    int("sources.mergeGapMin", s.mergeGapMin, SettingsLimits.mergeGapMin)
    int("sources.backfillDays", s.backfillDays, SettingsLimits.backfillDays)
    s.sourcePackages.forEachIndexed { i, name -> packageName("sources.sourcePackages[$i]", name) }
    s.sourcePriority.forEachIndexed { i, name -> packageName("sources.sourcePriority[$i]", name) }
    s.manualPackages.forEachIndexed { i, name -> packageName("sources.manualPackages[$i]", name) }
}

private fun Checker.checkSchedule(s: ScheduleSettings) {
    int("schedule.dailyTimeMinutes", s.dailyTimeMinutes, SettingsLimits.dailyTimeMinutes)
    int("schedule.periodicHours", s.periodicHours, SettingsLimits.periodicHours)
}

private fun Checker.checkWrite(w: WriteSettings) {
    text("write.titleTemplate", w.titleTemplate, SettingsLimits.TITLE_TEMPLATE_MAX_LENGTH)
}

private fun Checker.checkAlgo(a: AlgoParams) {
    checkPreprocess("$ALGO.preprocess", a.preprocess)
    checkFeatures("$ALGO.features", a.features)
    checkEmissions("$ALGO.hmm.emissions", a.hmm.emissions)
    checkMeanBouts("$ALGO.hmm.meanBouts", a.hmm, epochMin = a.preprocess.epochSec / SECONDS_PER_MINUTE)
    checkTransitions("$ALGO.hmm.transitions", a.hmm)
    checkHmm("$ALGO.hmm", a.hmm)
    checkRules("$ALGO.rules", a.rules)
    checkHybrid("$ALGO.hybrid", a.hybrid)
    checkPost("$ALGO.post", a.post)
    checkPersonal("$ALGO.personal", a.personal)
    checkNapMode("$ALGO.napMode", a.napMode, a.post)
    a.napMode.contextMarginMin?.let { checkNapContextMargin("$ALGO.napMode.contextMarginMin", it, a.preprocess.contextMarginMin) }
}

/**
 * The context margin of naps, when set: at least 0 ([SettingsErrorCode.OUT_OF_RANGE]) and at most the night's
 * `algo.preprocess.contextMarginMin` ([SettingsErrorCode.MIN_ABOVE_MAX]), the margin the app reads heart rate and steps
 * with, so that a nap never uses more context than it receives.
 */
private fun Checker.checkNapContextMargin(
    path: String,
    margin: Int,
    nightMargin: Int,
) {
    if (margin < 0) {
        int(path, margin, 0..Int.MAX_VALUE, constraint = ">= 0")
    } else {
        val constraint = "<= $ALGO.preprocess.contextMarginMin ($nightMargin)"
        int(path, margin, Int.MIN_VALUE..nightMargin, constraint, SettingsErrorCode.MIN_ABOVE_MAX)
    }
}

private fun Checker.checkPreprocess(
    p: String,
    v: PreprocessParams,
) {
    greaterThan("$p.minBpm", v.minBpm, 0.0)
    greaterThan("$p.maxBpm", v.maxBpm, 0.0)
    notAbove("$p.minBpm", v.minBpm, "$p.maxBpm", v.maxBpm)
    oddInt("$p.hampelWindow", v.hampelWindow, SettingsLimits.hampelWindow)
    greaterThan("$p.hampelThreshold", v.hampelThreshold, 0.0)
    intOneOf("$p.epochSec", v.epochSec, SettingsLimits.epochSec)
    inRange("$p.maxInterpGapMin", v.maxInterpGapMin, SettingsLimits.maxInterpGapMin)
    oddInt("$p.medianWindowEpochs", v.medianWindowEpochs, SettingsLimits.medianWindowEpochs)
    oddInt("$p.smoothWindowEpochs", v.smoothWindowEpochs, SettingsLimits.smoothWindowEpochs)
    int("$p.contextMarginMin", v.contextMarginMin, SettingsLimits.contextMarginMin)
    inRange("$p.minCoverage", v.minCoverage, SettingsLimits.unitInterval)
    inRange("$p.rulesMinCoverage", v.rulesMinCoverage, SettingsLimits.unitInterval)
    notAbove("$p.rulesMinCoverage", v.rulesMinCoverage, "$p.minCoverage", v.minCoverage)
}

private fun Checker.checkFeatures(
    p: String,
    v: FeatureParams,
) {
    inRange("$p.normLowPercentile", v.normLowPercentile, SettingsLimits.percentile)
    inRange("$p.normHighPercentile", v.normHighPercentile, SettingsLimits.percentile)
    notAbove("$p.normLowPercentile", v.normLowPercentile, "$p.normHighPercentile", v.normHighPercentile)
    greaterThan("$p.minNormRangeBpm", v.minNormRangeBpm, 0.0)
    int("$p.varHalfWindowEpochs", v.varHalfWindowEpochs, SettingsLimits.halfWindowEpochs)
    int("$p.slopeHalfWindowEpochs", v.slopeHalfWindowEpochs, SettingsLimits.halfWindowEpochs)
    greaterThan("$p.cycleMinDistanceMin", v.cycleMinDistanceMin, 0.0)
    atLeast("$p.cycleMinProminenceBpm", v.cycleMinProminenceBpm, 0.0)
    int("$p.baselineNights", v.baselineNights, SettingsLimits.baselineNights)
    atLeast("$p.elevatedNightPct", v.elevatedNightPct, 0.0)
    atLeast("$p.elevatedAwakeShift", v.elevatedAwakeShift, 0.0)
    inRange("$p.elevatedConfidenceFactor", v.elevatedConfidenceFactor, SettingsLimits.unitInterval)
    atLeast("$p.spo2DropPct", v.spo2DropPct, 0.0)
    atLeast("$p.spo2AwakeLightFactor", v.spo2AwakeLightFactor, 0.0)
    greaterThan("$p.hrvWeight", v.hrvWeight, 0.0)
    atLeast("$p.respiratoryRateWeight", v.respiratoryRateWeight, 0.0)
}

private fun Checker.checkEmissions(
    p: String,
    table: EmissionTable,
) {
    listOf("awake" to table.awake, "light" to table.light, "deep" to table.deep, "rem" to table.rem)
        .forEach { (state, emission) ->
            listOf("hrNorm" to emission.hrNorm, "hrVar" to emission.hrVar, "hrSlope" to emission.hrSlope)
                .forEach { (feature, gaussian) ->
                    finite("$p.$state.$feature.mean", gaussian.mean)
                    greaterThan("$p.$state.$feature.sd", gaussian.sd, 0.0)
                }
        }
}

private fun Checker.checkMeanBouts(
    p: String,
    hmm: HmmParams,
    epochMin: Double,
) {
    val bouts = hmm.meanBouts
    greaterThan("$p.awakeMin", bouts.awakeMin, epochMin)
    greaterThan("$p.lightMin", bouts.lightMin, epochMin)
    greaterThan("$p.deepMin", bouts.deepMin, epochMin)
    greaterThan("$p.remMin", bouts.remMin, epochMin)
}

private fun Checker.checkTransitions(
    p: String,
    hmm: HmmParams,
) {
    val t = hmm.transitions
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
    ).forEach { (name, weight) -> atLeast("$p.$name", weight, 0.0) }
}

private fun Checker.checkHmm(
    p: String,
    v: HmmParams,
) {
    greaterThan("$p.epsilon", v.epsilon, 0.0)
    if (v.epsilon > 0.0) inRange("$p.epsilon", v.epsilon, SettingsLimits.unitInterval)
    atLeast("$p.deepTimeIntercept", v.deepTimeIntercept, 0.0)
    atLeast("$p.deepTimeSlope", v.deepTimeSlope, 0.0)
    atLeast("$p.deepTimeFloor", v.deepTimeFloor, 0.0)
    atLeast("$p.remTimeIntercept", v.remTimeIntercept, 0.0)
    atLeast("$p.remTimeSlope", v.remTimeSlope, 0.0)
    atLeast("$p.remLockoutMin", v.remLockoutMin, 0.0)
    inRange("$p.remLockoutFactor", v.remLockoutFactor, SettingsLimits.unitInterval)
    atLeast("$p.initialAwake", v.initialAwake, 0.0)
    atLeast("$p.initialLight", v.initialLight, 0.0)
    atLeast("$p.cyclePriorWeight", v.cyclePriorWeight, 0.0)
    inRange("$p.cycleDeepPhase", v.cycleDeepPhase, SettingsLimits.unitInterval)
    inRange("$p.cycleRemPhase", v.cycleRemPhase, SettingsLimits.unitInterval)
    int("$p.awakeStepThreshold", v.awakeStepThreshold, SettingsLimits.awakeStepThreshold)
    atLeast("$p.fewStepsAwakeFactor", v.fewStepsAwakeFactor, 0.0)
    int("$p.emIterations", v.emIterations, SettingsLimits.iterations)
    atLeast("$p.emPriorStrength", v.emPriorStrength, 0.0)
}

private fun Checker.checkRules(
    p: String,
    v: RulesParams,
) {
    finite("$p.deepMaxHrNorm", v.deepMaxHrNorm)
    finite("$p.deepMaxHrVar", v.deepMaxHrVar)
    finite("$p.awakeMinHrNorm", v.awakeMinHrNorm)
    finite("$p.remMinHrNorm", v.remMinHrNorm)
    finite("$p.remMinHrVar", v.remMinHrVar)
    inRange("$p.remMinT", v.remMinT, SettingsLimits.unitInterval)
}

private fun Checker.checkHybrid(
    p: String,
    v: HybridParams,
) {
    atLeast("$p.timePriorWeight", v.timePriorWeight, 0.0)
    int("$p.maxBoutMin", v.maxBoutMin, SettingsLimits.maxBoutMin)
    atLeast("$p.emissionWeight", v.emissionWeight, 0.0)
    atLeast("$p.elevatedAwakeFactor", v.elevatedAwakeFactor, 0.0)
    atLeast("$p.priorExponent", v.priorExponent, 0.0)
}

private fun Checker.checkPost(
    p: String,
    v: PostprocessParams,
) {
    val unit = SettingsLimits.unitInterval
    atLeast("$p.minDeepMin", v.minDeepMin, 0.0)
    atLeast("$p.minRemMin", v.minRemMin, 0.0)
    atLeast("$p.minAwakeMin", v.minAwakeMin, 0.0)
    inRange("$p.sanityMinCoverage", v.sanityMinCoverage, unit)
    inRange("$p.sanityDeepMax", v.sanityDeepMax, unit)
    inRange("$p.sanityRemMax", v.sanityRemMax, unit)
    inRange("$p.sanityDeepMin", v.sanityDeepMin, unit)
    notAbove("$p.sanityDeepMin", v.sanityDeepMin, "$p.sanityDeepMax", v.sanityDeepMax)
    inRange("$p.sanityConfidenceFactor", v.sanityConfidenceFactor, unit)
    inRange("$p.targetDeepMin", v.targetDeepMin, unit)
    inRange("$p.targetDeepMax", v.targetDeepMax, unit)
    notAbove("$p.targetDeepMin", v.targetDeepMin, "$p.targetDeepMax", v.targetDeepMax)
    inRange("$p.targetRemMin", v.targetRemMin, unit)
    inRange("$p.targetRemMax", v.targetRemMax, unit)
    notAbove("$p.targetRemMin", v.targetRemMin, "$p.targetRemMax", v.targetRemMax)
    inRange("$p.targetAwakeMax", v.targetAwakeMax, unit)
    inRange("$p.calibrationStep", v.calibrationStep, unit)
    int("$p.calibrationMaxIterations", v.calibrationMaxIterations, SettingsLimits.iterations)
    inRange("$p.lowConfidenceThreshold", v.lowConfidenceThreshold, unit)
}

/**
 * The nap mode. Overrides are checked when set; the DEEP corridor of naps (overrides, else the night's values) must
 * not be reversed and is reported at the override.
 */
private fun Checker.checkNapMode(
    p: String,
    v: NapModeParams,
    night: PostprocessParams,
) {
    val unit = SettingsLimits.unitInterval
    inRange("$p.maxSessionMin", v.maxSessionMin, SettingsLimits.napMaxSessionMin)
    val post = v.post
    post.minDeepMin?.let { atLeast("$p.post.minDeepMin", it, 0.0) }
    post.minRemMin?.let { atLeast("$p.post.minRemMin", it, 0.0) }
    post.minAwakeMin?.let { atLeast("$p.post.minAwakeMin", it, 0.0) }
    post.sanityDeepMin?.let { inRange("$p.post.sanityDeepMin", it, unit) }
    post.sanityDeepMax?.let { inRange("$p.post.sanityDeepMax", it, unit) }
    post.sanityRemMax?.let { inRange("$p.post.sanityRemMax", it, unit) }
    checkNapDeepCorridor(p, post, night)
    post.lowConfidenceThreshold?.let { inRange("$p.post.lowConfidenceThreshold", it, unit) }
    v.hybrid.emissionWeight?.let { atLeast("$p.hybrid.emissionWeight", it, 0.0) }
    v.hybrid.timePriorWeight?.let { atLeast("$p.hybrid.timePriorWeight", it, 0.0) }
}

/**
 * The DEEP corridor of naps (overrides, else the night's values) must not be reversed; reported at the override: a nap
 * minimum above the maximum as MIN_ABOVE_MAX, a nap maximum below the night's minimum as OUT_OF_RANGE.
 */
private fun Checker.checkNapDeepCorridor(
    p: String,
    post: NapPostprocessParams,
    night: PostprocessParams,
) {
    val low = post.sanityDeepMin ?: night.sanityDeepMin
    val high = post.sanityDeepMax ?: night.sanityDeepMax
    when {
        post.sanityDeepMin != null -> {
            val maxPath = if (post.sanityDeepMax != null) "$p.post.sanityDeepMax" else "$ALGO.post.sanityDeepMax"
            notAbove("$p.post.sanityDeepMin", low, maxPath, high)
        }

        post.sanityDeepMax != null && low.isFinite() -> {
            atLeast("$p.post.sanityDeepMax", high, low)
        }
    }
}

private fun Checker.checkPersonal(
    p: String,
    v: PersonalParams,
) {
    int("$p.emEveryNights", v.emEveryNights, SettingsLimits.emEveryNights)
    atLeast("$p.emPriorStrength", v.emPriorStrength, 0.0)
    atLeast("$p.calibrationRegularization", v.calibrationRegularization, 0.0)
    inRange("$p.calibrationHoldout", v.calibrationHoldout, SettingsLimits.unitInterval)
}

/** Collects [SettingsError]s; every check is independent so that one import reports all problems at once. */
private class Checker {
    val errors = mutableListOf<SettingsError>()

    /** [value] in [range]; otherwise [code] with [constraint] (by default the range). */
    fun int(
        path: String,
        value: Int,
        range: IntRange,
        constraint: String = "${range.first}..${range.last}",
        code: SettingsErrorCode = SettingsErrorCode.OUT_OF_RANGE,
    ) {
        if (value !in range) add(path, code, value.toString(), constraint)
    }

    fun oddInt(
        path: String,
        value: Int,
        range: IntRange,
    ) {
        val constraint = "odd, ${range.first}..${range.last}"
        when {
            value !in range -> add(path, SettingsErrorCode.OUT_OF_RANGE, value.toString(), constraint)
            value % 2 == 0 -> add(path, SettingsErrorCode.NOT_ODD, value.toString(), constraint)
        }
    }

    fun intOneOf(
        path: String,
        value: Int,
        allowed: Set<Int>,
    ) {
        if (value !in allowed) add(path, SettingsErrorCode.NOT_ALLOWED, value.toString(), "one of ${allowed.sorted().joinToString()}")
    }

    fun inRange(
        path: String,
        value: Double,
        range: ClosedFloatingPointRange<Double>,
    ) {
        if (finite(path, value) && value !in range) {
            add(path, SettingsErrorCode.OUT_OF_RANGE, value.toString(), "${range.start}..${range.endInclusive}")
        }
    }

    fun atLeast(
        path: String,
        value: Double,
        min: Double,
    ) {
        if (finite(path, value) && value < min) add(path, SettingsErrorCode.OUT_OF_RANGE, value.toString(), ">= $min")
    }

    fun greaterThan(
        path: String,
        value: Double,
        bound: Double,
    ) {
        if (finite(path, value) && value <= bound) add(path, SettingsErrorCode.OUT_OF_RANGE, value.toString(), "> $bound")
    }

    /** Returns true when [value] is finite; records [SettingsErrorCode.NOT_FINITE] otherwise. */
    fun finite(
        path: String,
        value: Double,
    ): Boolean {
        val ok = value.isFinite()
        if (!ok) add(path, SettingsErrorCode.NOT_FINITE, value.toString(), "finite number")
        return ok
    }

    fun notAbove(
        lowPath: String,
        low: Double,
        highPath: String,
        high: Double,
    ) {
        if (low.isFinite() && high.isFinite() && low > high) {
            add(lowPath, SettingsErrorCode.MIN_ABOVE_MAX, low.toString(), "<= $highPath ($high)")
        }
    }

    fun packageName(
        path: String,
        value: String,
    ) {
        if (!SettingsValidator.packageNameRegex.matches(value)) {
            add(path, SettingsErrorCode.INVALID_PACKAGE_NAME, value, SettingsValidator.packageNameRegex.pattern)
        }
    }

    fun text(
        path: String,
        value: String?,
        maxLength: Int,
    ) {
        when {
            value == null -> Unit
            value.isBlank() -> add(path, SettingsErrorCode.BLANK, "\"$value\"", "non-blank text or null")
            value.length > maxLength -> add(path, SettingsErrorCode.TOO_LONG, "${value.length} characters", "<= $maxLength characters")
        }
    }

    private fun add(
        path: String,
        code: SettingsErrorCode,
        value: String,
        constraint: String,
    ) {
        errors += SettingsError(path, code, value, constraint)
    }
}
