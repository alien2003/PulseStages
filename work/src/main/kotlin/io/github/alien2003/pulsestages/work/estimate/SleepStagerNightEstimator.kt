package io.github.alien2003.pulsestages.work.estimate

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalModelStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production [NightEstimator]: core-algo's [SleepStager] with the bundled hybrid model ([HybridModelProvider];
 * absent or invalid → null → HYBRID falls back to HMM with the HYBRID_MODEL_UNAVAILABLE warning) and the personal model
 * that is active in the ledger ([PersonalModelStore]; applied by the stager when `personal.enabled`).
 *
 * The active personal model is looked up on every call (one single-row query) and the stager is rebuilt only when the
 * bundle or the active model changed, so [paramsHash] always describes the configuration the next [estimate] uses: the
 * hash covers the parameters, the bundle fingerprint, the personal-model fingerprint (when enabled) and the output
 * options passed as `extra` (`awakeAsLight`, and `startBeforeSource` when it is on, see
 * [io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions]).
 */
@Singleton
class SleepStagerNightEstimator
    @Inject
    constructor(
        private val hybridModels: HybridModelProvider,
        private val personalModels: PersonalModelStore,
    ) : NightEstimator {
        private val mutex = Mutex()

        @Volatile
        private var current: Configuration? = null

        /** What the cached stager was built from. */
        private data class Key(
            val modelFingerprint: String?,
            val personalRowId: Long?,
            val personalFingerprint: String?,
        )

        private class Configuration(
            val key: Key,
            val stager: SleepStager,
        )

        /** Id of the bundled hybrid model, or null when there is none or nothing was estimated / hashed yet. */
        override val modelId: String? get() = current?.stager?.modelId

        override suspend fun estimate(
            input: NightInput,
            params: AlgoParams,
        ): EstimationOutcome = configuration().stager.run(input, params)

        override suspend fun estimateWith(
            type: EstimatorType,
            input: NightInput,
            params: AlgoParams,
        ): NightEstimate = configuration().stager.estimateWith(type, input, params)

        override suspend fun paramsHash(
            params: AlgoParams,
            extra: String,
        ): String = configuration().stager.paramsHash(params, extra)

        private suspend fun configuration(): Configuration {
            val bundle = hybridModels.hybridModel()
            val active = personalModels.active()
            val key = Key(bundle?.fingerprint(), active?.id, active?.fingerprint)
            current?.takeIf { it.key == key }?.let { return it }
            return mutex.withLock {
                current?.takeIf { it.key == key }
                    ?: Configuration(key, SleepStager(bundle, active?.let(personalModels::parse))).also { current = it }
            }
        }
    }
