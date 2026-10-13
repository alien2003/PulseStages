package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.post.LabelRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Output invariants every estimate must satisfy (spec: stages cover [start, end] without gaps or overlaps, …). */
object Invariants {
    fun check(
        estimate: NightEstimate,
        input: NightInput,
        context: String = "",
    ) {
        val epochMs = estimate.params.preprocess.epochSec * 1000L
        val duration = input.sessionEndMs - input.sessionStartMs
        val stages = estimate.stages

        assertTrue(stages.isNotEmpty(), context)
        assertEquals(input.sessionStartMs, stages.first().startMs, "first.start $context")
        assertEquals(input.sessionEndMs, stages.last().endMs, "last.end $context")
        stages.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endMs, b.startMs, "contiguous $context")
            assertNotEquals(a.stage, b.stage, "merged $context")
        }
        val sessionHasFullEpoch = duration >= epochMs
        stages.forEach { interval ->
            assertTrue(interval.endMs > interval.startMs, "positive length $context")
            if (sessionHasFullEpoch) assertTrue(interval.endMs - interval.startMs >= epochMs, "shorter than an epoch: $interval $context")
        }
        if (duration < epochMs) assertEquals(1, stages.size, context)

        val epochs = ((duration + epochMs - 1) / epochMs).toInt()
        assertEquals(epochs, estimate.perEpoch.size, "perEpoch size $context")
        estimate.perEpoch.forEachIndexed { k, epoch ->
            assertEquals(k, epoch.index)
            assertEquals(input.sessionStartMs + k * epochMs, epoch.startMs)
            val p = epoch.posterior.toList()
            assertEquals(1.0, p.sum(), 1e-9, "posterior sum at $k $context")
            assertTrue(p.all { it in 0.0..1.0 }, "posterior range at $k $context")
            val covering = stages.first { epoch.startMs >= it.startMs && epoch.startMs < it.endMs }
            assertEquals(covering.stage, epoch.stage, "perEpoch stage matches the interval at $k $context")
            assertEquals(estimate.featureNames.size, epoch.features.size)
        }
        assertTrue(estimate.confidence in 0.0..1.0, context)
        assertTrue(estimate.coverage in 0.0..1.0, context)
        checkForcedAwake(estimate, input, epochMs, context)
        checkMinimumBouts(estimate, input, context)
    }

    /**
     * Minimum bout lengths of the specification (DEEP ≥ minDeepMin, REM ≥ minRemMin, AWAKE inside sleep ≥
     * minAwakeMin), in whole epochs, for every bout without a forced epoch that has a scored (non-UNKNOWN) neighbour.
     */
    private fun checkMinimumBouts(
        estimate: NightEstimate,
        input: NightInput,
        context: String,
    ) {
        val post = estimate.params.post
        val epochMin = estimate.params.preprocess.epochSec / 60.0
        val night = NightPreparer.prepare(input, estimate.params)
        val stages = estimate.perEpoch.map { it.stage }.toTypedArray()
        val bouts = LabelRules.bouts(stages)
        val sleep = setOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM)
        val firstSleep = stages.indexOfFirst { it in sleep }
        val lastSleep = stages.indexOfLast { it in sleep }
        bouts.forEachIndexed { i, bout ->
            val minimum =
                when (bout.stage) {
                    SleepStage.DEEP -> post.minDeepMin
                    SleepStage.REM -> post.minRemMin
                    SleepStage.AWAKE -> if (firstSleep in 0 until bout.from && lastSleep > bout.to) post.minAwakeMin else 0.0
                    else -> 0.0
                }
            val scoredNeighbour = listOfNotNull(bouts.getOrNull(i - 1), bouts.getOrNull(i + 1)).any { it.stage != SleepStage.UNKNOWN }
            val forced = (bout.from..bout.to).any { night.isForcedAwake(it) }
            if (scoredNeighbour && !forced) {
                assertTrue(
                    bout.length * epochMin >= minimum - 1e-9,
                    "${bout.stage} bout of ${bout.length} epochs at ${bout.from} is below $minimum min $context",
                )
            }
        }
    }

    /** Epochs whose midpoint is inside a forcedAwake span are AWAKE (the merged partial last epoch excepted). */
    private fun checkForcedAwake(
        estimate: NightEstimate,
        input: NightInput,
        epochMs: Long,
        context: String,
    ) {
        estimate.perEpoch.dropLast(1).forEach { epoch ->
            val mid = epoch.startMs + epochMs / 2
            if (input.forcedAwake.any { mid >= it.startMs && mid < it.endMs }) {
                assertEquals(SleepStage.AWAKE, epoch.stage, "forced epoch ${epoch.index} $context")
            }
        }
    }
}
