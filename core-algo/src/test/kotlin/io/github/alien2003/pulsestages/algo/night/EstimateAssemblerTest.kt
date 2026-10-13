package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.hmm.HmmEstimator
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EstimateAssemblerTest {
    private val minute = 60_000L

    @Test
    fun `night confidence is the mean max posterior over data epochs times coverage and the penalties`() {
        val synthetic = SyntheticNightGenerator(14).generate()
        val start = synthetic.input.sessionStartMs
        val gapped =
            synthetic.input.copy(
                heartRate =
                    synthetic.input.heartRate.filter {
                        it.timeMs !in
                            start + 100 * minute until start + 140 * minute
                    },
            )
        val params = AlgoParams()
        val failSanity = params.copy(post = params.post.copy(sanityDeepMax = 0.0))
        val cases =
            listOf(
                gapped to params,
                gapped.copy(baselineNadirBpm = 30.0) to params,
                gapped to failSanity,
                gapped.copy(baselineNadirBpm = 30.0) to failSanity,
            )
        val seen = mutableSetOf<Pair<Boolean, Boolean>>()
        for ((input, p) in cases) {
            val estimate = HmmEstimator().estimate(input, p)
            val elevated = estimate.flags.elevatedHr
            val sanity = estimate.warnings.any { it.code.name.startsWith("SANITY_") }
            seen += elevated to sanity
            val withData = estimate.perEpoch.filter { it.hasData }
            var expected = withData.map { e -> e.posterior.toList().max() }.average() * estimate.coverage
            if (elevated) expected *= p.features.elevatedConfidenceFactor
            if (sanity) expected *= p.post.sanityConfidenceFactor
            assertTrue(estimate.coverage < 0.95, "coverage ${estimate.coverage}")
            assertEquals(expected, estimate.confidence, 1e-12, "elevated=$elevated sanity=$sanity")
        }
        assertTrue(seen.containsAll(listOf(true to true, false to true)), "cases: $seen")
        assertTrue(seen.any { !it.second } && seen.any { it.first && it.second })
    }
}
