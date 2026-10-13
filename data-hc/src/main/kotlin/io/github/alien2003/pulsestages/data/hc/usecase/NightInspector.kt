package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.export.CsvExporter
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.metrics.StageMinutes
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.di.EstimationDispatcher
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * A night re-estimated for the detail screen with the current settings, without writing anything.
 *
 * @property night the ledger row (what was written: stages, version, hash).
 * @property input the inputs re-read from Health Connect.
 * @property params the parameters of the estimate (the current settings).
 * @property outcome the estimate, with per-epoch posteriors and features, or why the night is not estimable.
 * @property paramsHash the current params hash.
 */
data class NightInspection(
    val night: EstimatedNightEntity,
    val input: NightInput,
    val params: AlgoParams,
    val outcome: EstimationOutcome,
    val paramsHash: String,
) {
    /** The estimate, or null when the night is not estimable with the current parameters. */
    val estimate: NightEstimate? get() = (outcome as? EstimationOutcome.Estimated)?.estimate

    /**
     * The written output was made with another algorithm version or params hash (it may differ from [estimate]); the
     * hash also covers what the input is read from (`hrSources`, the optional channels enabled and granted), so a
     * re-estimate from other inputs counts as stale too.
     */
    val writtenIsStale: Boolean get() = night.isStale(ALGO_VERSION, paramsHash)
}

/**
 * Agreement of two hypnograms of the same night, compared in 30-s chunks ([IntervalScoring]).
 *
 * @property confusion rows: the reference ("primary") hypnogram, columns: the other one.
 * @property kappa Cohen's kappa, or null when undefined (no chunks, or a single identical class on both sides).
 * @property accuracy share of chunks with the same stage, or null without chunks.
 * @property primaryMinutes minutes per stage of the primary hypnogram over the compared chunks.
 * @property otherMinutes minutes per stage of the other hypnogram over the same chunks.
 */
data class StageAgreement(
    val confusion: ConfusionMatrix,
    val kappa: Double?,
    val accuracy: Double?,
    val primaryMinutes: StageMinutes,
    val otherMinutes: StageMinutes,
) {
    /** Calculation. */
    companion object {
        /** The agreement of [other] with [primary] (UNKNOWN chunks on either side are skipped). */
        fun between(
            primary: List<StageInterval>,
            other: List<StageInterval>,
        ): StageAgreement {
            val score = IntervalScoring.score(primary, other.sortedBy { it.startMs })
            return StageAgreement(
                confusion = score.confusion,
                kappa = score.confusion.kappa.takeIf { it.isFinite() },
                accuracy = score.confusion.accuracy.takeIf { it.isFinite() },
                primaryMinutes = score.truthMinutes,
                otherMinutes = score.predictedMinutes,
            )
        }
    }
}

/**
 * The configured estimator and another one on the same input ("Compare with RULES").
 *
 * @property primary the estimate of the configured estimator.
 * @property other the estimate of the other estimator (without coverage gates).
 * @property agreement agreement of [other] with [primary].
 */
data class EstimatorComparison(
    val primary: NightEstimate,
    val other: NightEstimate,
    val agreement: StageAgreement,
)

/**
 * A per-epoch CSV export of a night.
 *
 * @property fileName suggested file name (`pulsestages-yyyyMMdd-HHmm.csv`, local time of the session start).
 * @property content the CSV document ([CsvExporter] format).
 */
data class CsvExport(
    val fileName: String,
    val content: String,
)

/**
 * The night detail screen's view of one night: re-reads the night's inputs from Health Connect (the session, package
 * and fragments recorded in the ledger, the current `hrSources` policy and settings) and runs the estimator WITHOUT
 * writing anything. [inspect] returns the full estimate (per-epoch posteriors and features), [compareWith] runs a
 * second estimator (RULES by default) on the same input and measures the agreement, [exportCsv] produces the
 * per-epoch CSV. Reads in the foreground (the screen is visible). Never throws except for cancellation.
 */
class NightInspector
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val inputs: LedgerNightInputs,
        private val estimator: NightEstimator,
        @param:EstimationDispatcher private val estimationDispatcher: CoroutineDispatcher,
        private val logger: AppLogger,
    ) {
        /** Re-estimates the night [clientRecordId] with the current settings. */
        suspend fun inspect(clientRecordId: String): UseCaseResult<NightInspection> =
            runUseCase(inputs.guard, logger, TAG, clientRecordId) {
                val night = inputs.night(clientRecordId) ?: return@runUseCase UseCaseResult.NotFound
                val settings = settingsRepository.settings.first()
                val access = inputs.guard.check(inBackground = false)
                val blocked = access.blockedReason
                if (blocked != null) return@runUseCase UseCaseResult.Blocked(blocked, access.detail)
                val input = inputs.read(night, settings, access.granted)
                val outcome = withContext(estimationDispatcher) { estimator.estimate(input, settings.algo) }
                val hash = estimator.paramsHash(settings.algo, OutputOptions.hashExtra(settings, access.granted))
                UseCaseResult.Success(NightInspection(night, input, settings.algo, outcome, hash))
            }

        /** Runs [type] on the input of [inspection] and compares it with the inspection's estimate. */
        suspend fun compareWith(
            inspection: NightInspection,
            type: EstimatorType = EstimatorType.RULES,
        ): UseCaseResult<EstimatorComparison> =
            when (val outcome = inspection.outcome) {
                is EstimationOutcome.NotEstimated -> {
                    UseCaseResult.NotEstimated(outcome)
                }

                is EstimationOutcome.Estimated -> {
                    runUseCase(inputs.guard, logger, TAG, inspection.night.clientRecordId) {
                        val other = withContext(estimationDispatcher) { estimator.estimateWith(type, inspection.input, inspection.params) }
                        val primary = outcome.estimate
                        UseCaseResult.Success(EstimatorComparison(primary, other, StageAgreement.between(primary.stages, other.stages)))
                    }
                }
            }

        /** [inspect]s the night [clientRecordId] and compares it with [type]. */
        suspend fun compareWith(
            clientRecordId: String,
            type: EstimatorType = EstimatorType.RULES,
        ): UseCaseResult<EstimatorComparison> = inspect(clientRecordId).andThen { compareWith(it, type) }

        /** The per-epoch CSV of [inspection]. */
        fun exportCsv(inspection: NightInspection): UseCaseResult<CsvExport> =
            when (val outcome = inspection.outcome) {
                is EstimationOutcome.NotEstimated -> {
                    UseCaseResult.NotEstimated(outcome)
                }

                is EstimationOutcome.Estimated -> {
                    UseCaseResult.Success(
                        CsvExport(fileName(inspection.night), CsvExporter.export(outcome.estimate)),
                    )
                }
            }

        /** [inspect]s the night [clientRecordId] and exports it as CSV. */
        suspend fun exportCsv(clientRecordId: String): UseCaseResult<CsvExport> = inspect(clientRecordId).andThen { exportCsv(it) }

        private fun fileName(night: EstimatedNightEntity): String {
            val offset = ZoneOffset.ofTotalSeconds(night.startZoneOffsetSec)
            val local = Instant.ofEpochMilli(night.sessionStartMs).atOffset(offset)
            return "pulsestages-${FILE_TIME.format(local)}.csv"
        }

        private companion object {
            const val TAG = "NightInspector"
            val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        }
    }
