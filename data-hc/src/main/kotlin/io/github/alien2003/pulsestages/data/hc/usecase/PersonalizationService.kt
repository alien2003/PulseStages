package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.personal.CalibrationResult
import io.github.alien2003.pulsestages.algo.personal.PersonalCalibrator
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PopulationEmAdapter
import io.github.alien2003.pulsestages.algo.personal.PopulationEmResult
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.di.EstimationDispatcher
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Result of [PersonalizationService.calibrate]. */
sealed interface CalibrationOutcome {
    /**
     * A CALIBRATED personal model was stored and is now active.
     *
     * @property modelRowId its row in `personal_models`.
     * @property result the calibration, with kappa before / after on the held-out nights (null without held-out
     *   nights, i.e. with a single reference night).
     */
    data class Calibrated(
        val modelRowId: Long,
        val result: CalibrationResult,
    ) : CalibrationOutcome

    /**
     * Nothing was calibrated (no reference nights, or none with labelled epochs that have heart rate).
     *
     * @property detail English reason.
     */
    data class NotCalibrated(
        val detail: String,
    ) : CalibrationOutcome

    /**
     * Personalization is switched off (`personal.enabled = false`): the estimators would never apply a calibrated
     * model (nor would its kappa improvement show in any estimate), so nothing was calibrated or stored. Switch the
     * personal model on first.
     */
    data object PersonalizationDisabled : CalibrationOutcome
}

/** Result of [PersonalizationService.maybeRunPopulationEm]. */
sealed interface EmRunResult {
    /** Personalization is switched off (`personal.enabled = false`). */
    data object Disabled : EmRunResult

    /**
     * The configured estimator does not use the HMM emissions that EM adapts: RULES, or HYBRID with a compatible model
     * bundle (only its classifier bias deltas are personal). An EM model would change no estimate, only the params hash
     * (and with it mark every written night stale), so EM is not run; the counter is left alone, so EM runs as soon as
     * the HMM is used again (estimator HMM, or HYBRID falling back to it without a usable bundle).
     */
    data object NotUsed : EmRunResult

    /**
     * Not yet due.
     *
     * @property newNights nights estimated since the last adaptation.
     * @property every adaptation period in nights (`personal.emEveryNights`).
     */
    data class NotDue(
        val newNights: Int,
        val every: Int,
    ) : EmRunResult

    /** Skipped: a CALIBRATED model is active (supervised calibration is kept; the counter still advances). */
    data object SkippedCalibrated : EmRunResult

    /**
     * An EM personal model was stored and is now active.
     *
     * @property modelRowId its row in `personal_models`.
     * @property result the adaptation.
     */
    data class Adapted(
        val modelRowId: Long,
        val result: PopulationEmResult,
    ) : EmRunResult

    /**
     * EM ran but produced no model (no usable night, or the first iteration broke the stage order); the current model
     * is kept and the counter advances.
     *
     * @property result the adaptation.
     */
    data class NotAdapted(
        val result: PopulationEmResult,
    ) : EmRunResult

    /**
     * Nothing was read (Health Connect unavailable, permission missing, background read not allowed); tried again by a
     * later run.
     *
     * @property reason why.
     */
    data class Blocked(
        val reason: BlockedReason,
    ) : EmRunResult

    /**
     * Reading the nights failed (quota, transient or invalid data); tried again by a later run.
     *
     * @property detail English detail.
     * @property retryable quota / transient error.
     */
    data class Interrupted(
        val detail: String,
        val retryable: Boolean,
    ) : EmRunResult
}

/**
 * Personalization of the global models (section 10 of the specification).
 *
 * - [calibrate]: supervised calibration on reference nights with core-algo's [PersonalCalibrator] (classifier bias
 *   deltas for the bundled hybrid model, HMM emission means); the model is stored as the active CALIBRATED model and
 *   the kappa before / after on the held-out (latest) reference nights is returned. Refused while personalization is
 *   switched off (the model would not be applied). Reference naps (sessions shorter than `napMode.maxSessionMin`) are
 *   left out: the calibration fits the night model.
 * - [resetToGlobal]: deactivates every personal model.
 * - [maybeRunPopulationEm]: when `personal.enabled` and the estimates use the HMM emissions (estimator HMM, or HYBRID
 *   without a compatible bundle, which falls back to the HMM), every `personal.emEveryNights` newly estimated nights
 *   (naps neither count nor take part; counted against [SyncStateStore]'s `lastEmAdaptationNightCount`) the inputs
 *   of the latest [EM_MAX_NIGHTS] estimated nights are re-read from Health Connect and core-algo's
 *   [PopulationEmAdapter] adapts the HMM emissions (starting from the active EM model, shrunk towards the global
 *   table); the result is stored as the active EM model. While a CALIBRATED
 *   model is active, EM is skipped: the supervised calibration is more trustworthy than unsupervised EM, and EM would
 *   move its means away from the labelled data (and σ adapted around other means would not fit the calibrated means).
 *   When the counter is due but the reads fail (quota, permission), the counter is not advanced, so the next run tries
 *   again; once EM ran (with or without a model) it advances.
 * - [activeModel]: the active personal model.
 *
 * A new active model changes the params hash (its fingerprint is part of it): nights estimated before become stale and
 * are offered for recomputation, never recomputed silently.
 */
class PersonalizationService
    @Inject
    constructor(
        private val store: PersonalModelStore,
        private val settingsRepository: SettingsRepository,
        private val syncState: SyncStateStore,
        private val inputs: LedgerNightInputs,
        private val hybridModels: HybridModelProvider,
        @param:EstimationDispatcher private val estimationDispatcher: CoroutineDispatcher,
        private val logger: AppLogger,
    ) {
        /** The active personal model, or null when the global models are used. */
        fun activeModel(): Flow<PersonalModel?> = store.activeModel()

        /** The active model's row (kind, kappas, creation time), or null. */
        fun activeModelRow(): Flow<PersonalModelEntity?> = store.activeRow()

        /**
         * Calibrates on [referenceNights] with the current parameters and activates the result;
         * [CalibrationOutcome.PersonalizationDisabled] while `personal.enabled` is off.
         */
        suspend fun calibrate(referenceNights: List<ReferenceNight>): CalibrationOutcome {
            val params = settingsRepository.settings.first().algo
            return when {
                !params.personal.enabled -> CalibrationOutcome.PersonalizationDisabled
                referenceNights.isEmpty() -> CalibrationOutcome.NotCalibrated("no reference nights")
                else -> calibrateOn(referenceNights, params)
            }
        }

        private suspend fun calibrateOn(
            referenceNights: List<ReferenceNight>,
            params: AlgoParams,
        ): CalibrationOutcome {
            val bundle = hybridModels.hybridModel()
            val nights =
                referenceNights
                    .filter { params.modeOf(it.input.sessionEndMs - it.input.sessionStartMs) == SessionMode.NIGHT }
                    .map { it.toCalibrationNight() }
            if (nights.isEmpty()) {
                return CalibrationOutcome.NotCalibrated(
                    "no reference nights: every reference session is a nap (shorter than napMode.maxSessionMin = " +
                        "${params.napMode.maxSessionMin} min), and naps are not used for calibration",
                )
            }
            return try {
                val result = withContext(estimationDispatcher) { PersonalCalibrator(bundle).calibrate(nights, params) }
                val id = store.saveActive(result.model, PersonalModelKind.CALIBRATED)
                logger.i(
                    TAG,
                    "calibrated on ${result.trainingNights} night(s), held out ${result.heldOutNights}: " +
                        "kappa ${result.kappaBefore} -> ${result.kappaAfter} (bias deltas ${result.biasDeltasFitted}, " +
                        "HMM means ${result.hmmMeansFitted})",
                )
                CalibrationOutcome.Calibrated(id, result)
            } catch (e: IllegalArgumentException) {
                logger.w(TAG, "calibration not possible: ${e.message}", throwable = e)
                CalibrationOutcome.NotCalibrated(e.message.orEmpty())
            }
        }

        /** Back to the global models: deactivates every personal model. */
        suspend fun resetToGlobal() {
            store.deactivateAll()
            logger.i(TAG, "personal model reset; using the global models")
        }

        /**
         * Runs population-level EM when it is due (see the class documentation); [inBackground] is true for WorkManager
         * runs (the background read rule applies). Never throws except for cancellation.
         */
        suspend fun maybeRunPopulationEm(inBackground: Boolean): EmRunResult {
            val settings = settingsRepository.settings.first()
            val estimated = inputs.writtenNightCount()
            return notDueReason(settings, estimated) ?: adapt(settings, inBackground, estimated)
        }

        /** Why EM does not run now, or null when it is due. */
        private suspend fun notDueReason(
            settings: AppSettings,
            estimated: Int,
        ): EmRunResult? {
            val personal = settings.algo.personal
            val last = syncState.state.first().lastEmAdaptationNightCount
            return when {
                !personal.enabled -> {
                    EmRunResult.Disabled
                }

                !usesHmmEmissions(settings.algo, hybridModels.hybridModel()) -> {
                    EmRunResult.NotUsed
                }

                estimated < last -> {
                    // Nights were deleted: count from here on.
                    syncState.setLastEmAdaptationNightCount(estimated)
                    EmRunResult.NotDue(0, personal.emEveryNights)
                }

                estimated - last < personal.emEveryNights -> {
                    EmRunResult.NotDue(estimated - last, personal.emEveryNights)
                }

                store.active()?.kind == PersonalModelKind.CALIBRATED -> {
                    syncState.setLastEmAdaptationNightCount(estimated)
                    logger.i(TAG, "population EM skipped: a calibrated personal model is active")
                    EmRunResult.SkippedCalibrated
                }

                else -> {
                    null
                }
            }
        }

        private suspend fun adapt(
            settings: AppSettings,
            inBackground: Boolean,
            estimated: Int,
        ): EmRunResult =
            when (val read = readLatestInputs(settings, inBackground)) {
                is LatestInputs.NotRead -> read.result
                is LatestInputs.Read -> adaptOn(read.inputs, settings, estimated)
            }

        private suspend fun adaptOn(
            nights: List<NightInput>,
            settings: AppSettings,
            estimated: Int,
        ): EmRunResult {
            val base = store.active()?.let(store::parse)
            val result = withContext(estimationDispatcher) { PopulationEmAdapter.adapt(nights, settings.algo, base) }
            syncState.setLastEmAdaptationNightCount(estimated)
            val model = result.model
            return if (model == null) {
                logger.i(
                    TAG,
                    "population EM produced no model (${result.nightsUsed} usable night(s), order violated ${result.orderViolated})",
                )
                EmRunResult.NotAdapted(result)
            } else {
                val id = store.saveActive(model, PersonalModelKind.EM)
                logger.i(TAG, "population EM adapted on ${result.nightsUsed} night(s) in ${result.iterations} iteration(s)")
                EmRunResult.Adapted(id, result)
            }
        }

        /** The inputs of the latest estimated nights, or why they were not read. */
        private sealed interface LatestInputs {
            class Read(
                val inputs: List<NightInput>,
            ) : LatestInputs

            class NotRead(
                val result: EmRunResult,
            ) : LatestInputs
        }

        private suspend fun readLatestInputs(
            settings: AppSettings,
            inBackground: Boolean,
        ): LatestInputs =
            try {
                val access = inputs.guard.check(inBackground)
                val blocked = access.blockedReason
                if (blocked != null) {
                    LatestInputs.NotRead(EmRunResult.Blocked(blocked))
                } else {
                    LatestInputs.Read(
                        inputs.latestWrittenNights(EM_MAX_NIGHTS).map { night ->
                            currentCoroutineContext().ensureActive()
                            inputs.read(night, settings, access.granted)
                        },
                    )
                }
            } catch (e: HealthDataException) {
                logger.w(TAG, "population EM postponed: ${e.message}", throwable = e)
                LatestInputs.NotRead(interrupted(e))
            }

        private suspend fun interrupted(error: HealthDataException): EmRunResult =
            when (error) {
                is HealthDataException.PermissionDenied -> {
                    EmRunResult.Blocked(inputs.guard.reasonForDenial())
                }

                is HealthDataException.Unavailable -> {
                    EmRunResult.Blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE)
                }

                is HealthDataException.QuotaExceeded, is HealthDataException.Transient -> {
                    EmRunResult.Interrupted(
                        error.message.orEmpty(),
                        true,
                    )
                }

                is HealthDataException.InvalidData -> {
                    EmRunResult.Interrupted(error.message.orEmpty(), false)
                }
            }

        /** Constants and the estimator rule. */
        companion object {
            private const val TAG = "Personalization"

            /**
             * Whether estimates with [params] use the HMM emission μ/σ that population EM adapts: the estimator of the
             * nights or of the naps (`napMode.estimator`) is HMM, or HYBRID when [bundle] is missing or incompatible with
             * this build (HYBRID then falls back to the HMM). RULES and HYBRID with a usable bundle never use them. EM
             * itself learns from nights only (naps are left out, see [PopulationEmAdapter]).
             */
            fun usesHmmEmissions(
                params: AlgoParams,
                bundle: HybridModel?,
            ): Boolean =
                listOf(params.estimator, params.napMode.estimator).distinct().any { estimator ->
                    when (estimator) {
                        EstimatorType.HMM -> true
                        EstimatorType.HYBRID -> bundle == null || bundle.compatibilityProblem() != null
                        EstimatorType.RULES -> false
                    }
                }

            /** Population EM uses at most this many of the latest estimated nights. */
            const val EM_MAX_NIGHTS: Int = 90
        }
    }
