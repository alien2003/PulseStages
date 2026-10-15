package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.model.NightInput

/**
 * The input restriction of `napMode.contextMarginMin` (ALGORITHM.md section 18): a nap with the override set m sees
 * only the data of `[start − m, end + m]`, whatever the caller read around the session, so that an estimate does not
 * depend on the reader's margin and training cuts and the app give the same features.
 */
internal object NapContext {
    private const val MINUTE_MS = 60_000L

    /**
     * [input] restricted to the margin [marginMin] (minutes, ≥ 0) on both sides of its session `[s0, s1]`: heart-rate,
     * SpO2, respiratory-rate and HRV samples with `s0 − m ≤ t ≤ s1 + m` (both ends inclusive) and step records ending in
     * `(s0 − m, s1 + m]`, the cut convention of tools/train (`napmodel.cut_window`, algo-cli's nap proxies). The
     * session bounds, the forced-awake spans and the baseline nadir are kept.
     */
    fun restrict(
        input: NightInput,
        marginMin: Int,
    ): NightInput {
        require(marginMin >= 0) { "the nap context margin must not be negative, was $marginMin" }
        val from = input.sessionStartMs - marginMin * MINUTE_MS
        val to = input.sessionEndMs + marginMin * MINUTE_MS
        val inside = { timeMs: Long -> timeMs in from..to }
        return input.copy(
            heartRate = input.heartRate.filter { inside(it.timeMs) },
            steps = input.steps.filter { it.endMs > from && it.endMs <= to },
            spo2 = input.spo2.filter { inside(it.timeMs) },
            respiratoryRate = input.respiratoryRate.filter { inside(it.timeMs) },
            hrv = input.hrv.filter { inside(it.timeMs) },
        )
    }
}
