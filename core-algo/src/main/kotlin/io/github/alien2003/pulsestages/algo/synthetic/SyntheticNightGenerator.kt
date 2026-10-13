package io.github.alien2003.pulsestages.algo.synthetic

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.StepsSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Options of [SyntheticNightGenerator.generate]. The defaults give a regular night as described in section 7 of
 * the specification; the other fields produce the edge-case variants.
 *
 * @property sessionStartMs session start (epoch ms).
 * @property durationMin if set, the hypnogram is extended with further cycles (or cut) to exactly this many
 *   minutes (e.g. a 14-hour session).
 * @property longGapMin if positive, heart-rate samples are removed in a gap of this length centred in the session.
 * @property constantBpm if set, every heart-rate sample has this value (no noise, no outliers).
 * @property singleSample keep only one heart-rate sample, in the middle of the session.
 * @property withSteps add step records during some awakenings, the final wake and the pre-sleep context.
 * @property contextMarginMin heart rate is generated this long before and after the session.
 * @property dropRate probability that a heart-rate sample is missing.
 * @property outlierRate probability that a heart-rate sample is a ±40 bpm spike.
 * @property person the simulated person's heart-rate profile (the default is the specification's generator).
 */
data class SyntheticOptions(
    val sessionStartMs: Long = DEFAULT_SESSION_START_MS,
    val durationMin: Int? = null,
    val longGapMin: Int = 0,
    val constantBpm: Double? = null,
    val singleSample: Boolean = false,
    val withSteps: Boolean = true,
    val contextMarginMin: Int = 30,
    val dropRate: Double = 0.03,
    val outlierRate: Double = 0.01,
    val person: SyntheticPerson = SyntheticPerson(),
) {
    /** Defaults. */
    companion object {
        /** 2026-01-15T22:30:00Z. */
        const val DEFAULT_SESSION_START_MS: Long = 1_768_516_200_000L
    }
}

/**
 * Heart-rate profile of a simulated person. The defaults reproduce the generator of the specification; other values
 * simulate a person who differs from it (e.g. to test personalization). The random sequence does not depend on
 * these values.
 *
 * @property baselineBpm fixed resting baseline; null draws it uniformly from 50–65 bpm.
 * @property deepOffsetBpm mean DEEP offset from the baseline (per-bout sd 1 bpm).
 * @property remOffsetBpm mean REM offset (per-bout sd 2 bpm, plus the REM noise).
 * @property awakeOffsetBpm mean AWAKE offset (per-bout sd 5 bpm, at least 5 bpm).
 */
data class SyntheticPerson(
    val baselineBpm: Double? = null,
    val deepOffsetBpm: Double = DEFAULT_DEEP_OFFSET_BPM,
    val remOffsetBpm: Double = DEFAULT_REM_OFFSET_BPM,
    val awakeOffsetBpm: Double = DEFAULT_AWAKE_OFFSET_BPM,
) {
    /** The specification's offsets. */
    companion object {
        /** DEEP −5 bpm. */
        const val DEFAULT_DEEP_OFFSET_BPM: Double = -5.0

        /** REM +6 bpm. */
        const val DEFAULT_REM_OFFSET_BPM: Double = 6.0

        /** AWAKE +15 bpm. */
        const val DEFAULT_AWAKE_OFFSET_BPM: Double = 15.0
    }
}

/**
 * A generated night.
 *
 * @property input the estimator input (heart rate and steps include the context margin).
 * @property truth the hypnogram the heart rate was generated from, covering exactly the session.
 * @property baselineBpm the person's resting baseline used for generation.
 */
data class SyntheticNight(
    val input: NightInput,
    val truth: List<StageInterval>,
    val baselineBpm: Double,
)

/**
 * Seeded generator of synthetic nights for unit and regression tests (never used to choose defaults: the data come
 * from the same model family the HMM assumes and overstate its accuracy).
 *
 * Hypnogram: sleep-onset latency 5–20 min AWAKE; 4–5 cycles of 90–110 min (LIGHT, DEEP, LIGHT, REM); DEEP 20–40 min
 * in cycles 1–2, ≤ 10 min in cycle 3, none later; REM from 5–10 min in cycle 1 growing to 25–40 min in the last;
 * 2–4 awakenings of 2–10 min inserted between bouts (half of them with 5–30 steps); final wake 0–10 min.
 * Heart rate (one value per minute): baseline 50–65 bpm; per-bout stage offsets DEEP −5±1, LIGHT 0, REM +6±2,
 * AWAKE +15±5; a linear downward drift of 2–4 bpm over the session; a first-order lag (τ = 2 min) on stage changes;
 * AR(1) noise (φ 0.8, σ 1.2) plus white REM noise (σ 2.5). Samples every 120 ± 10 s (rounded to whole bpm) with
 * 3 % dropped and 1 % ±40 bpm outliers, over the session plus the context margin (AWAKE) on both sides.
 */
class SyntheticNightGenerator(
    seed: Long,
) {
    private val random = Random(seed)

    private class Bout(
        val stage: SleepStage,
        var minutes: Int,
        val withSteps: Boolean = false,
    )

    /** Generates the next night of this generator's deterministic sequence. */
    fun generate(options: SyntheticOptions = SyntheticOptions()): SyntheticNight {
        val bouts = hypnogram(options)
        val sessionMin = bouts.sumOf { it.minutes }
        val start = options.sessionStartMs
        val end = start + sessionMin * MINUTE_MS
        val drawnBaseline = uniform(BASELINE_MIN, BASELINE_MAX)
        val baseline = options.person.baselineBpm ?: drawnBaseline
        val perMinute = heartRatePerMinute(bouts, baseline, options.contextMarginMin, options.person)
        val input =
            NightInput(
                sessionStartMs = start,
                sessionEndMs = end,
                heartRate = samples(perMinute, start - options.contextMarginMin * MINUTE_MS, options, start, end),
                steps = if (options.withSteps) steps(bouts, start) else emptyList(),
            )
        return SyntheticNight(input, truth(bouts, start), baseline)
    }

    private fun hypnogram(options: SyntheticOptions): MutableList<Bout> {
        val bouts = mutableListOf(Bout(SleepStage.AWAKE, uniformInt(ONSET_MIN, ONSET_MAX)))
        val nominal = uniformInt(MIN_CYCLES, MAX_CYCLES)
        val target = options.durationMin
        var cycle = 0
        while (cycle < nominal || (target != null && bouts.sumOf { it.minutes } < target)) {
            bouts += cycleBouts(cycle, nominal)
            cycle++
        }
        insertAwakenings(bouts)
        val finalWake = uniformInt(0, FINAL_WAKE_MAX)
        if (finalWake > 0) bouts += Bout(SleepStage.AWAKE, finalWake, withSteps = random.nextBoolean())
        if (target != null) fitToDuration(bouts, target)
        return bouts
    }

    private fun cycleBouts(
        cycle: Int,
        nominal: Int,
    ): List<Bout> {
        val length = uniformInt(CYCLE_MIN, CYCLE_MAX)
        val deep =
            when (cycle) {
                0, 1 -> uniformInt(DEEP_EARLY_MIN, DEEP_EARLY_MAX)
                2 -> uniformInt(0, DEEP_THIRD_MAX)
                else -> 0
            }
        val f = (cycle.toDouble() / (nominal - 1)).coerceAtMost(1.0)
        val rem = uniformInt(lerp(REM_FIRST_MIN, REM_LAST_MIN, f), lerp(REM_FIRST_MAX, REM_LAST_MAX, f))
        val light = length - deep - rem
        val lightBefore = (light * uniform(LIGHT_SPLIT_MIN, LIGHT_SPLIT_MAX)).roundToInt()
        return buildList {
            add(Bout(SleepStage.LIGHT, lightBefore))
            if (deep > 0) add(Bout(SleepStage.DEEP, deep))
            add(Bout(SleepStage.LIGHT, light - lightBefore))
            add(Bout(SleepStage.REM, rem))
        }
    }

    private fun insertAwakenings(bouts: MutableList<Bout>) {
        val count = uniformInt(MIN_AWAKENINGS, MAX_AWAKENINGS)
        val candidates = (2 until bouts.size).filter { bouts[it - 1].stage != SleepStage.AWAKE && bouts[it].stage != SleepStage.AWAKE }
        val chosen = candidates.shuffled(random).take(count).sortedDescending()
        for (index in chosen) {
            bouts.add(index, Bout(SleepStage.AWAKE, uniformInt(AWAKENING_MIN, AWAKENING_MAX), withSteps = random.nextBoolean()))
        }
    }

    private fun fitToDuration(
        bouts: MutableList<Bout>,
        target: Int,
    ) {
        var total = bouts.sumOf { it.minutes }
        while (total > target) {
            val last = bouts.last()
            val cut = minOf(last.minutes, total - target)
            last.minutes -= cut
            total -= cut
            if (last.minutes == 0) bouts.removeAt(bouts.size - 1)
        }
        if (total < target) bouts.last().minutes += target - total
    }

    /** Heart rate per minute over context + session + context. */
    private fun heartRatePerMinute(
        bouts: List<Bout>,
        baseline: Double,
        contextMin: Int,
        person: SyntheticPerson,
    ): DoubleArray {
        val sessionMin = bouts.sumOf { it.minutes }
        val offsets = DoubleArray(sessionMin + 2 * contextMin)
        val rem = BooleanArray(offsets.size)
        val before = awakeOffset(person)
        val after = awakeOffset(person)
        for (m in 0 until contextMin) {
            offsets[m] = before
            offsets[offsets.size - 1 - m] = after
        }
        var m = contextMin
        for (bout in bouts) {
            val offset = stageOffset(bout.stage, person)
            repeat(bout.minutes) {
                offsets[m] = offset
                rem[m] = bout.stage == SleepStage.REM
                m++
            }
        }
        val drift = uniform(DRIFT_MIN, DRIFT_MAX)
        val alpha = 1 - exp(-1 / LAG_TAU_MIN)
        var level = baseline + offsets[0]
        var ar = 0.0
        return DoubleArray(offsets.size) { i ->
            val progress = ((i - contextMin).toDouble() / sessionMin).coerceIn(0.0, 1.0)
            val target = baseline + offsets[i] - drift * progress
            level += alpha * (target - level)
            ar = AR_PHI * ar + gaussian(AR_SIGMA)
            level + ar + if (rem[i]) gaussian(REM_NOISE_SIGMA) else 0.0
        }
    }

    private fun stageOffset(
        stage: SleepStage,
        person: SyntheticPerson,
    ): Double =
        when (stage) {
            SleepStage.DEEP -> person.deepOffsetBpm + gaussian(DEEP_OFFSET_SD)
            SleepStage.REM -> person.remOffsetBpm + gaussian(REM_OFFSET_SD)
            SleepStage.AWAKE -> awakeOffset(person)
            SleepStage.LIGHT, SleepStage.UNKNOWN -> 0.0
        }

    private fun awakeOffset(person: SyntheticPerson): Double =
        (person.awakeOffsetBpm + gaussian(AWAKE_OFFSET_SD)).coerceAtLeast(AWAKE_OFFSET_MIN)

    private fun samples(
        perMinute: DoubleArray,
        windowStart: Long,
        options: SyntheticOptions,
        sessionStart: Long,
        sessionEnd: Long,
    ): List<HrSample> {
        val windowEnd = windowStart + (perMinute.size - 1) * MINUTE_MS
        val gapStart = (sessionStart + sessionEnd) / 2 - options.longGapMin * MINUTE_MS / 2
        val gapEnd = gapStart + options.longGapMin * MINUTE_MS
        val out = ArrayList<HrSample>()
        var t = windowStart + random.nextLong(0, SAMPLE_INTERVAL_MS)
        while (t < windowEnd) {
            val drop = random.nextDouble() < options.dropRate
            val outlier = random.nextDouble() < options.outlierRate
            val spike = if (random.nextBoolean()) SPIKE_BPM else -SPIKE_BPM
            val inGap = options.longGapMin > 0 && t >= gapStart && t < gapEnd
            if (!drop && !inGap) {
                val value =
                    options.constantBpm ?: (interpolate(perMinute, windowStart, t) + if (outlier) spike else 0.0).roundToInt().toDouble()
                out += HrSample(t, value)
            }
            t += SAMPLE_INTERVAL_MS + random.nextLong(-SAMPLE_JITTER_MS, SAMPLE_JITTER_MS + 1)
        }
        if (options.singleSample) {
            val mid = (sessionStart + sessionEnd) / 2
            return listOf(out.minBy { abs(it.timeMs - mid) })
        }
        return out
    }

    private fun interpolate(
        perMinute: DoubleArray,
        windowStart: Long,
        t: Long,
    ): Double {
        val position = (t - windowStart).toDouble() / MINUTE_MS
        val i = position.toInt().coerceIn(0, perMinute.size - 2)
        val f = position - i
        return perMinute[i] + f * (perMinute[i + 1] - perMinute[i])
    }

    private fun steps(
        bouts: List<Bout>,
        sessionStart: Long,
    ): List<StepsSample> {
        val out = ArrayList<StepsSample>()
        if (random.nextBoolean()) {
            val at = sessionStart - PRE_SLEEP_STEPS_MIN * MINUTE_MS
            out += StepsSample(at, at + MINUTE_MS, uniformInt(PRE_SLEEP_STEPS_MIN_COUNT, PRE_SLEEP_STEPS_MAX_COUNT).toLong())
        }
        var minute = 0
        for (bout in bouts) {
            if (bout.withSteps && bout.stage == SleepStage.AWAKE) {
                val at = sessionStart + (minute + bout.minutes / 2) * MINUTE_MS
                out += StepsSample(at, at + MINUTE_MS, uniformInt(MIN_STEPS, MAX_STEPS).toLong())
            }
            minute += bout.minutes
        }
        return out
    }

    private fun truth(
        bouts: List<Bout>,
        start: Long,
    ): List<StageInterval> {
        val out = ArrayList<StageInterval>()
        var t = start
        for (bout in bouts) {
            if (bout.minutes <= 0) continue
            val end = t + bout.minutes * MINUTE_MS
            val previous = out.lastOrNull()
            if (previous != null &&
                previous.stage == bout.stage
            ) {
                out[out.size - 1] = previous.copy(endMs = end)
            } else {
                out += StageInterval(t, end, bout.stage)
            }
            t = end
        }
        return out
    }

    private fun uniform(
        from: Double,
        to: Double,
    ): Double = from + (to - from) * random.nextDouble()

    private fun uniformInt(
        from: Int,
        toInclusive: Int,
    ): Int = random.nextInt(from, toInclusive + 1)

    private fun lerp(
        a: Int,
        b: Int,
        f: Double,
    ): Int = (a + (b - a) * f).roundToInt()

    /** Normal variate N(0, sd²) by the Box–Muller transform. */
    private fun gaussian(sd: Double): Double {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return sd * sqrt(-ln(u1) * 2) * cos(2 * PI * u2)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val SAMPLE_INTERVAL_MS = 120_000L
        const val SAMPLE_JITTER_MS = 10_000L
        const val SPIKE_BPM = 40.0
        const val BASELINE_MIN = 50.0
        const val BASELINE_MAX = 65.0
        const val ONSET_MIN = 5
        const val ONSET_MAX = 20
        const val MIN_CYCLES = 4
        const val MAX_CYCLES = 5
        const val CYCLE_MIN = 90
        const val CYCLE_MAX = 110
        const val DEEP_EARLY_MIN = 20
        const val DEEP_EARLY_MAX = 40
        const val DEEP_THIRD_MAX = 10
        const val REM_FIRST_MIN = 5
        const val REM_FIRST_MAX = 10
        const val REM_LAST_MIN = 25
        const val REM_LAST_MAX = 40
        const val LIGHT_SPLIT_MIN = 0.4
        const val LIGHT_SPLIT_MAX = 0.7
        const val MIN_AWAKENINGS = 2
        const val MAX_AWAKENINGS = 4
        const val AWAKENING_MIN = 2
        const val AWAKENING_MAX = 10
        const val FINAL_WAKE_MAX = 10
        const val MIN_STEPS = 5
        const val MAX_STEPS = 30
        const val PRE_SLEEP_STEPS_MIN = 20
        const val PRE_SLEEP_STEPS_MIN_COUNT = 20
        const val PRE_SLEEP_STEPS_MAX_COUNT = 100
        const val DEEP_OFFSET_SD = 1.0
        const val REM_OFFSET_SD = 2.0
        const val AWAKE_OFFSET_SD = 5.0
        const val AWAKE_OFFSET_MIN = 5.0
        const val DRIFT_MIN = 2.0
        const val DRIFT_MAX = 4.0
        const val LAG_TAU_MIN = 2.0
        const val AR_PHI = 0.8
        const val AR_SIGMA = 1.2
        const val REM_NOISE_SIGMA = 2.5
    }
}
