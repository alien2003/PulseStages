package io.github.alien2003.pulsestages.work.estimate

import android.content.res.AssetManager
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.hybrid.HybridModelException
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.work.di.WorkIoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Reads a bundled text asset; abstracted so that tests need no Android assets. */
fun interface ModelAssetSource {
    /** The UTF-8 text of the asset at [path], or null when there is no such asset. Throws [IOException] on read errors. */
    fun read(path: String): String?
}

/** [ModelAssetSource] over the app's [AssetManager]. */
class AndroidModelAssetSource(
    private val assets: AssetManager,
) : ModelAssetSource {
    override fun read(path: String): String? =
        try {
            assets.open(path).use { it.readBytes().decodeToString() }
        } catch (ignored: FileNotFoundException) {
            null
        }
}

/**
 * The hybrid model bundle shipped in the app's assets ([ASSET_PATH]), loaded and validated once (lazily, on the IO
 * dispatcher) and cached for the process. An absent, unreadable or invalid bundle gives null — HYBRID then falls back to
 * the HMM with the HYBRID_MODEL_UNAVAILABLE warning — and is logged once. A bundle built for another feature set is
 * returned as is: core-algo's `SleepStager` detects the incompatibility and falls back the same way (for naps also
 * when only the bundle's nap model does not match). The log line says how naps are decoded (nap model or the main model
 * with its short-session bias).
 */
@Singleton
class BundledHybridModelProvider
    @Inject
    constructor(
        private val source: ModelAssetSource,
        private val logger: AppLogger,
        @param:WorkIoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : HybridModelProvider {
        private val mutex = Mutex()

        @Volatile
        private var loaded: Loaded? = null

        /** The result of the one load (the model may be null). */
        private class Loaded(
            val model: HybridModel?,
        )

        override suspend fun hybridModel(): HybridModel? = (loaded ?: mutex.withLock { loaded ?: load().also { loaded = it } }).model

        private suspend fun load(): Loaded {
            val json =
                try {
                    withContext(ioDispatcher) { source.read(ASSET_PATH) }
                } catch (e: IOException) {
                    logger.w(TAG, "the hybrid model bundle cannot be read; HYBRID falls back to HMM", throwable = e)
                    null
                }
            if (json == null) {
                logger.w(TAG, "no hybrid model bundle at $ASSET_PATH; HYBRID falls back to HMM")
                return Loaded(null)
            }
            return Loaded(parse(json))
        }

        private fun parse(json: String): HybridModel? =
            try {
                HybridModel.parse(json).also { model ->
                    val problem = model.compatibilityProblem()
                    val napProblem = model.napCompatibilityProblem()
                    val naps =
                        when {
                            model.nap == null -> "naps: the main model with its short-session bias"
                            napProblem == null -> "naps: its nap model"
                            else -> "its nap model does not match this build ($napProblem); naps fall back to HMM"
                        }
                    if (problem == null) {
                        logger.i(TAG, "hybrid model ${model.modelId} loaded ($naps)")
                    } else {
                        logger.w(TAG, "hybrid model ${model.modelId} does not match this build ($problem); HYBRID falls back to HMM")
                    }
                }
            } catch (e: HybridModelException) {
                logger.w(TAG, "the hybrid model bundle is invalid; HYBRID falls back to HMM", throwable = e)
                null
            } catch (e: IllegalArgumentException) {
                logger.w(TAG, "the hybrid model bundle is invalid; HYBRID falls back to HMM", throwable = e)
                null
            }

        /** Constants. */
        companion object {
            /** Location of the bundle in the app's assets. */
            const val ASSET_PATH: String = "models/hybrid_model.json"

            private const val TAG = "HybridModel"
        }
    }
