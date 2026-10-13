package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightBaselineDao
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import javax.inject.Inject

/**
 * The session of a night whose estimation input is read: `[startMs, endMs)` of the sleep sessions of [packageName],
 * with the [forcedAwake] gaps between merged fragments.
 *
 * @property startMs session start, epoch ms.
 * @property endMs session end, epoch ms.
 * @property packageName package that wrote the sleep session(s).
 * @property forcedAwake gaps between fragments (AWAKE).
 */
data class NightSpan(
    val startMs: Long,
    val endMs: Long,
    val packageName: String,
    val forcedAwake: List<TimeSpan> = emptyList(),
) {
    /** Length of the session (end − start, gaps between fragments included). */
    val durationMs: Long get() = endMs - startMs

    /** Constructors. */
    companion object {
        /** The span the pipeline estimates for [candidate]. */
        fun of(candidate: NightCandidate): NightSpan =
            NightSpan(candidate.startMs, candidate.endMs, candidate.packageName, candidate.forcedAwake)

        /**
         * The span of the ledger night [night] as the pipeline estimated it: its session boundaries and package, and
         * the gaps between the fragments recorded in `source_sessions` ([fragments]; rows of other nights and rows
         * outside the session are ignored).
         */
        fun of(
            night: EstimatedNightEntity,
            fragments: List<SourceSessionEntity>,
        ): NightSpan {
            val own =
                fragments
                    .filter { it.uuid in night.sourceUuids && it.endMs > it.startMs }
                    .map { TimeSpan(maxOf(it.startMs, night.sessionStartMs), minOf(it.endMs, night.sessionEndMs)) }
                    .filter { it.endMs > it.startMs }
                    .sortedWith(compareBy({ it.startMs }, { it.endMs }))
            return NightSpan(night.sessionStartMs, night.sessionEndMs, night.sourcePackage, gapsBetween(own))
        }
    }
}

/**
 * Reads the estimation input of a night from Health Connect exactly as the pipeline does: heart rate and steps (plus
 * SpO2 / respiratory rate / HRV when enabled in the parameters and granted) over `[start − contextMarginMin, end +
 * contextMarginMin]` from the night's package (`hrSources = SAME_AS_SLEEP`) or any origin (`ANY`), the forced-awake
 * gaps, and the rolling baseline nadir of the nights before it. Throws
 * [io.github.alien2003.pulsestages.data.hc.HealthDataException] on Health Connect errors.
 *
 * Naps and the baseline (`features.baselineMinSessionMin`, 180 min by default): a session shorter than that gets no
 * baseline (it is never compared with it, so it is never flagged "elevated heart rate"), and the baseline of a longer
 * one is the median nadir of the previous sessions that were at least that long (a nap's higher daytime nadir would
 * inflate it). Every reader of an estimation input (pipeline, night detail, reference comparison, personalization)
 * goes through this class, so the rule is the same everywhere.
 */
class NightInputReader
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val baselines: NightBaselineDao,
    ) {
        /** The input of [span] with the heart-rate origins of the `hrSources` setting. */
        suspend fun read(
            span: NightSpan,
            settings: AppSettings,
            granted: Set<String>,
        ): NightInput = readFrom(span, settings, granted, heartRatePackages(span.packageName, settings))

        /** The input of [span] with heart rate, steps and optional channels from [packages] (null = any origin). */
        suspend fun readFrom(
            span: NightSpan,
            settings: AppSettings,
            granted: Set<String>,
            packages: Set<String>?,
        ): NightInput {
            val params = settings.algo
            val marginMs = params.preprocess.contextMarginMin * MINUTE_MS
            val fromMs = span.startMs - marginMs
            val toMs = span.endMs + marginMs
            val selection = InputSelection.of(settings, granted)
            val spo2 = selection.spo2
            val respiratory = selection.respiratoryRate
            val hrv = selection.hrv
            return NightInput(
                sessionStartMs = span.startMs,
                sessionEndMs = span.endMs,
                heartRate = repository.readHeartRate(fromMs, toMs, packages),
                steps = repository.readSteps(fromMs, toMs, packages),
                spo2 = if (spo2) repository.readSpo2(fromMs, toMs, packages) else emptyList(),
                respiratoryRate = if (respiratory) repository.readRespiratoryRate(fromMs, toMs, packages) else emptyList(),
                hrv = if (hrv) repository.readHrv(fromMs, toMs, packages) else emptyList(),
                forcedAwake = span.forcedAwake,
                baselineNadirBpm = baselineFor(span, params.features),
            )
        }

        /** The baseline nadir for [span], or null for a nap (see the class documentation) or without enough nights. */
        private suspend fun baselineFor(
            span: NightSpan,
            features: FeatureParams,
        ): Double? =
            if (features.countsForBaseline(span.durationMs)) {
                baselines.baselineBefore(span.startMs, features.baselineNights, features.baselineMinSessionMs)
            } else {
                null
            }

        /** Constants and the origin rule. */
        companion object {
            private const val MINUTE_MS = 60_000L

            /** Origins of heart rate and steps for a night of [packageName]: that package, or any origin (null). */
            fun heartRatePackages(
                packageName: String,
                settings: AppSettings,
            ): Set<String>? = if (settings.sources.hrSources == HrSourcesMode.SAME_AS_SLEEP) setOf(packageName) else null
        }
    }
