package io.github.alien2003.pulsestages.demo

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNight
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticPerson
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Package shown as the source of the demo nights (no such app exists; the data are synthetic). */
internal const val DEMO_SOURCE_PACKAGE: String = "io.github.alien2003.pulsestages.demo.watch"

/** Package shown as the writer of the demo reference nights. */
internal const val DEMO_REFERENCE_PACKAGE: String = "io.github.alien2003.pulsestages.demo.tracker"

/** How a demo night differs from a regular one, so that every marker and state of the screens can be shown. */
internal enum class DemoVariant {
    /** A regular night. */
    REGULAR,

    /** The night's nadir is well above the baseline of the previous nights ("elevated heart rate"). */
    ELEVATED_HR,

    /** Heart rate is missing for over an hour in the middle of the night (an UNKNOWN gap). */
    DATA_GAP,

    /** Written with other settings (the "stale" marker). */
    STALE,

    /** A single heart-rate sample: the night is not estimated. */
    NO_HEART_RATE,
}

/**
 * The synthetic demo data (debug builds only): nights from core-algo's [SyntheticNightGenerator] with fixed seeds,
 * starting at 23:00 local time on the last [NIGHT_COUNT] days, and reference nights whose "real" stages are the
 * generator's true hypnograms.
 */
internal object DemoData {
    /** Number of demo nights. */
    const val NIGHT_COUNT: Int = 14

    /** Number of demo reference nights. */
    const val REFERENCE_COUNT: Int = 8

    /** The nights that differ from a regular one, by days ago. */
    private val VARIANTS =
        mapOf(
            ELEVATED_HR_DAYS_AGO to DemoVariant.ELEVATED_HR,
            DATA_GAP_DAYS_AGO to DemoVariant.DATA_GAP,
            STALE_DAYS_AGO to DemoVariant.STALE,
            NO_HEART_RATE_DAYS_AGO to DemoVariant.NO_HEART_RATE,
        )

    /** Hash written with the stale demo night (any value other than the current hash). */
    const val STALE_HASH: String = "demo-older-settings"

    private const val ELEVATED_HR_DAYS_AGO = 2
    private const val DATA_GAP_DAYS_AGO = 3
    private const val STALE_DAYS_AGO = 5
    private const val NO_HEART_RATE_DAYS_AGO = 9
    private const val NIGHT_SEED = 20_261_013L
    private const val REFERENCE_SEED = 31_415L
    private const val BEDTIME_HOUR = 23
    private const val JITTER_MINUTES = 37L
    private const val JITTER_RANGE = 50L
    private const val GAP_MINUTES = 75
    private const val ELEVATED_BASELINE_BPM = 70.0
    private const val ELEVATED_REFERENCE_NADIR_BPM = 52.0
    private const val NADIR_BELOW_BASELINE_BPM = 6.0
    private const val MILLIS_PER_MINUTE = 60_000L

    /** The reference nights come from a person whose heart rate differs from the generator's default. */
    private val REFERENCE_PERSON = SyntheticPerson(deepOffsetBpm = -7.0, remOffsetBpm = 4.0, awakeOffsetBpm = 18.0)

    /** The variant of the night that started [daysAgo] days ago. */
    fun variantOf(daysAgo: Int): DemoVariant = VARIANTS[daysAgo] ?: DemoVariant.REGULAR

    /** Start of the session [daysAgo] days before [today], at about 23:00 in [zone]. */
    fun sessionStart(
        daysAgo: Int,
        today: LocalDate,
        zone: ZoneId,
    ): Long {
        val jitter = (daysAgo * JITTER_MINUTES) % JITTER_RANGE
        return today
            .minusDays(daysAgo.toLong())
            .atTime(LocalTime.of(BEDTIME_HOUR, 0))
            .plusMinutes(jitter)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /** The estimation input of the demo night [daysAgo] starting at [startMs]. */
    fun nightInput(
        daysAgo: Int,
        startMs: Long,
    ): NightInput {
        val variant = variantOf(daysAgo)
        val person = if (variant == DemoVariant.ELEVATED_HR) SyntheticPerson(baselineBpm = ELEVATED_BASELINE_BPM) else SyntheticPerson()
        val options =
            SyntheticOptions(
                sessionStartMs = startMs,
                longGapMin = if (variant == DemoVariant.DATA_GAP) GAP_MINUTES else 0,
                singleSample = variant == DemoVariant.NO_HEART_RATE,
                person = person,
            )
        val night = SyntheticNightGenerator(NIGHT_SEED + daysAgo).generate(options)
        val baseline =
            if (variant == DemoVariant.ELEVATED_HR) ELEVATED_REFERENCE_NADIR_BPM else night.baselineBpm - NADIR_BELOW_BASELINE_BPM
        return night.input.copy(baselineNadirBpm = baseline)
    }

    /** The [index]-th reference night (0 = oldest) starting at [startMs]; its person differs a little from the default. */
    fun referenceNight(
        index: Int,
        startMs: Long,
    ): SyntheticNight =
        SyntheticNightGenerator(REFERENCE_SEED + index).generate(
            SyntheticOptions(
                sessionStartMs = startMs,
                person = REFERENCE_PERSON,
            ),
        )

    /** Zone offset of [epochMs] in [zone], seconds. */
    fun offsetSec(
        epochMs: Long,
        zone: ZoneId,
    ): Int = zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds

    /**
     * The ledger-like row of the demo night [daysAgo] estimated with [outcome]; [paramsHash] is the current hash (the
     * stale variant gets [STALE_HASH]), [configured] the configured estimator (shown for nights not estimated).
     */
    @Suppress("LongParameterList") // One row of the demo ledger.
    fun entity(
        daysAgo: Int,
        input: NightInput,
        outcome: EstimationOutcome,
        paramsHash: String,
        configured: EstimatorType,
        modelId: String?,
        zone: ZoneId,
        nowMs: Long,
        recomputeCount: Int = 0,
    ): EstimatedNightEntity {
        val base =
            EstimatedNightEntity(
                id = daysAgo.toLong(),
                clientRecordId = "ps:demo-$daysAgo",
                sourceUuids = listOf("demo-session-$daysAgo"),
                sourcePackage = DEMO_SOURCE_PACKAGE,
                sessionStartMs = input.sessionStartMs,
                sessionEndMs = input.sessionEndMs,
                startZoneOffsetSec = offsetSec(input.sessionStartMs, zone),
                endZoneOffsetSec = offsetSec(input.sessionEndMs, zone),
                algoVersion = ALGO_VERSION,
                paramsHash = if (variantOf(daysAgo) == DemoVariant.STALE && recomputeCount == 0) STALE_HASH else paramsHash,
                estimator = configured,
                status = NightStatus.NOT_ESTIMATED,
                recomputeCount = recomputeCount,
                hrSamples = input.heartRate.size,
                createdAt = nowMs,
                updatedAt = nowMs,
            )
        return when (outcome) {
            is EstimationOutcome.NotEstimated -> {
                base.copy(statusReason = "${outcome.reason}: ${outcome.detail}", coverage = outcome.coverage)
            }

            is EstimationOutcome.Estimated -> {
                val estimate = outcome.estimate
                val minutes = minutesOf(estimate.stages)
                base.copy(
                    status = NightStatus.WRITTEN,
                    estimator = estimate.estimator,
                    modelId = if (estimate.estimator == EstimatorType.HYBRID) modelId else null,
                    coverage = estimate.coverage,
                    confidence = estimate.confidence,
                    awakeMin = minutes.getValue(SleepStage.AWAKE),
                    lightMin = minutes.getValue(SleepStage.LIGHT),
                    deepMin = minutes.getValue(SleepStage.DEEP),
                    remMin = minutes.getValue(SleepStage.REM),
                    unknownMin = minutes.getValue(SleepStage.UNKNOWN),
                    elevatedHr = estimate.flags.elevatedHr,
                    lowConfidence = estimate.flags.lowConfidence,
                    calibrated = estimate.flags.calibrated,
                    rulesFallback = estimate.flags.rulesFallback,
                    warnings = estimate.warnings.map { it.code }.distinct(),
                    nadirBpm = estimate.nadirBpm,
                    hrSamples = estimate.hrSampleCount,
                    stages = estimate.stages,
                )
            }
        }
    }

    /** Minutes per stage of [stages]. */
    fun minutesOf(stages: List<StageInterval>): Map<SleepStage, Double> =
        SleepStage.entries.associateWith { stage ->
            stages.filter { it.stage == stage }.sumOf { it.endMs - it.startMs } / MILLIS_PER_MINUTE.toDouble()
        }

    /** The list projection of [night]. */
    fun summaryOf(night: EstimatedNightEntity): NightSummary =
        NightSummary(
            id = night.id,
            clientRecordId = night.clientRecordId,
            sourcePackage = night.sourcePackage,
            sessionStartMs = night.sessionStartMs,
            sessionEndMs = night.sessionEndMs,
            startZoneOffsetSec = night.startZoneOffsetSec,
            endZoneOffsetSec = night.endZoneOffsetSec,
            status = night.status,
            statusReason = night.statusReason,
            estimator = night.estimator,
            algoVersion = night.algoVersion,
            paramsHash = night.paramsHash,
            coverage = night.coverage,
            confidence = night.confidence,
            awakeMin = night.awakeMin,
            lightMin = night.lightMin,
            deepMin = night.deepMin,
            remMin = night.remMin,
            unknownMin = night.unknownMin,
            elevatedHr = night.elevatedHr,
            lowConfidence = night.lowConfidence,
            calibrated = night.calibrated,
            rulesFallback = night.rulesFallback,
            updatedAt = night.updatedAt,
        )
}
