package io.github.alien2003.pulsestages.algo.synthetic

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyntheticNightGeneratorTest {
    private val minute = 60_000L

    private fun minutes(
        truth: List<StageInterval>,
        stage: SleepStage,
    ) = truth.filter { it.stage == stage }.sumOf { it.endMs - it.startMs } / minute

    @Test
    fun `same seed gives the same night and different seeds differ`() {
        assertEquals(SyntheticNightGenerator(7).generate(), SyntheticNightGenerator(7).generate())
        assertNotEquals(SyntheticNightGenerator(7).generate(), SyntheticNightGenerator(8).generate())
    }

    @Test
    fun `truth covers the session contiguously and follows the hypnogram rules`() {
        repeat(30) { seed ->
            val night = SyntheticNightGenerator(seed.toLong()).generate()
            val truth = night.truth
            val input = night.input

            assertEquals(input.sessionStartMs, truth.first().startMs)
            assertEquals(input.sessionEndMs, truth.last().endMs)
            truth.zipWithNext().forEach { (a, b) ->
                assertEquals(a.endMs, b.startMs)
                assertNotEquals(a.stage, b.stage)
            }
            assertEquals(SleepStage.AWAKE, truth.first().stage)
            val total = (input.sessionEndMs - input.sessionStartMs) / minute
            assertTrue(total in 4 * 90 + 5 + 4..5 * 110 + 20 + 40 + 10, "seed=$seed total=$total")
            assertTrue(minutes(truth, SleepStage.DEEP) in 40..90, "seed=$seed")
            assertTrue(minutes(truth, SleepStage.REM) >= 5 + 25, "seed=$seed")
            assertTrue(night.baselineBpm in 50.0..65.0)
        }
    }

    @Test
    fun `heart rate is sampled about every two minutes including the context`() {
        val night = SyntheticNightGenerator(3).generate()
        val hr = night.input.heartRate
        val windowMin = (night.input.sessionEndMs - night.input.sessionStartMs) / minute + 60

        assertTrue(hr.first().timeMs < night.input.sessionStartMs - 25 * minute)
        assertTrue(hr.last().timeMs > night.input.sessionEndMs + 25 * minute)
        assertTrue(hr.size in (windowMin / 2 * 0.9).toInt()..(windowMin / 2 * 1.02).toInt(), "size=${hr.size}")
        hr.zipWithNext().forEach { (a, b) -> assertTrue(b.timeMs - a.timeMs >= 110_000) }
        assertTrue(hr.all { it.bpm == Math.rint(it.bpm) })
    }

    @Test
    fun `steps occur only while awake`() {
        repeat(20) { seed ->
            val night = SyntheticNightGenerator(seed.toLong()).generate()
            night.input.steps
                .filter { it.startMs >= night.input.sessionStartMs }
                .forEach { steps ->
                    val stage = night.truth.first { steps.startMs >= it.startMs && steps.startMs < it.endMs }.stage
                    assertEquals(SleepStage.AWAKE, stage, "seed=$seed")
                    assertTrue(steps.count in 5..30)
                }
        }
    }

    @Test
    fun `variants`() {
        val long = SyntheticNightGenerator(1).generate(SyntheticOptions(durationMin = 14 * 60))
        assertEquals(14 * 60 * minute, long.input.sessionEndMs - long.input.sessionStartMs)
        assertEquals(long.input.sessionEndMs, long.truth.last().endMs)

        val gap = SyntheticNightGenerator(1).generate(SyntheticOptions(longGapMin = 40))
        val mid = (gap.input.sessionStartMs + gap.input.sessionEndMs) / 2
        assertTrue(gap.input.heartRate.none { it.timeMs >= mid - 20 * minute && it.timeMs < mid + 20 * minute })

        val flat = SyntheticNightGenerator(1).generate(SyntheticOptions(constantBpm = 58.0))
        assertTrue(flat.input.heartRate.all { it.bpm == 58.0 })

        val single = SyntheticNightGenerator(1).generate(SyntheticOptions(singleSample = true))
        assertEquals(1, single.input.heartRate.size)

        val noSteps = SyntheticNightGenerator(1).generate(SyntheticOptions(withSteps = false))
        assertTrue(noSteps.input.steps.isEmpty())
    }
}
