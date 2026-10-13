package io.github.alien2003.pulsestages.algo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class SmoothingTest {
    @Test
    fun `averages over a trailing window`() {
        val result = trailingMovingAverage(listOf(1.0, 2.0, 3.0, 4.0), window = 2)

        assertEquals(listOf(1.0, 1.5, 2.5, 3.5), result)
    }

    @Test
    fun `rejects non-positive window`() {
        assertThrows<IllegalArgumentException> { trailingMovingAverage(listOf(1.0), window = 0) }
    }

    @Test
    fun `matches naive implementation for random inputs`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val values = List(random.nextInt(0, 50)) { random.nextDouble(40.0, 120.0) }
            val window = random.nextInt(1, 10)

            val naive =
                values.indices.map { i ->
                    val from = maxOf(0, i - window + 1)
                    values.subList(from, i + 1).average()
                }

            val actual = trailingMovingAverage(values, window)
            assertEquals(naive.size, actual.size, "seed=$seed")
            naive.zip(actual).forEach { (expected, got) -> assertEquals(expected, got, 1e-9, "seed=$seed") }
        }
    }
}
