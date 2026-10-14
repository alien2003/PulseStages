package io.github.alien2003.pulsestages.e2e.shadow

import android.os.SystemClock
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's real estimator ([delegate], the SleepStager adapter with the bundled model) with the time of every
 * [estimate] recorded per session start, for the report.
 */
class TimedEstimator(
    private val delegate: NightEstimator,
) : NightEstimator {
    private val timings = ConcurrentHashMap<Long, Long>()

    /** Milliseconds of the last estimate of the session starting at [sessionStartMs], if any. */
    fun millisFor(sessionStartMs: Long): Long? = timings[sessionStartMs]

    override val modelId: String? get() = delegate.modelId

    override suspend fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): EstimationOutcome {
        val started = SystemClock.elapsedRealtime()
        return delegate.estimate(input, params).also { timings[input.sessionStartMs] = SystemClock.elapsedRealtime() - started }
    }

    override suspend fun estimateWith(
        type: EstimatorType,
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate = delegate.estimateWith(type, input, params)

    override suspend fun paramsHash(
        params: AlgoParams,
        extra: String,
    ): String = delegate.paramsHash(params, extra)
}
