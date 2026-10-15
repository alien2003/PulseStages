package io.github.alien2003.pulsestages.data.local.db

import io.github.alien2003.pulsestages.data.local.testutil.night
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InputUpdateTest {
    @Test
    fun `the reason holds the code, the change, both counts and an English explanation`() {
        val update = InputUpdate(InputChange.MORE_HEART_RATE, 46, 57)

        assertEquals("INPUT_UPDATED: MORE_HEART_RATE 46 57 - more heart rate arrived (46 -> 57 heart-rate samples)", update.reason())
        assertEquals("more heart rate arrived (46 -> 57 heart-rate samples)", update.describe())
        assertEquals(
            "the heart rate changed (46 -> 40 heart-rate samples)",
            InputUpdate(InputChange.HEART_RATE, 46, 40).describe(),
        )
        assertEquals("steps or other input data changed", InputUpdate(InputChange.OTHER_DATA, 46, 46).describe())
    }

    @Test
    fun `every reason of a written night parses back to its update`() {
        InputChange.entries.forEach { change ->
            listOf(InputUpdate(change, 0, 0), InputUpdate(change, 46, 57), InputUpdate(change, 1200, 3)).forEach { update ->
                assertEquals(update, InputUpdate.of(NightStatus.WRITTEN, update.reason()))
            }
        }
        assertEquals(
            InputUpdate(InputChange.HEART_RATE, 5, 3),
            InputUpdate.of(NightStatus.WRITTEN, "INPUT_UPDATED: HEART_RATE 5 3"),
            "the explanation is not needed",
        )
    }

    @Test
    fun `only a written night's reason is parsed`() {
        val reason = InputUpdate(InputChange.MORE_HEART_RATE, 46, 57).reason()

        NightStatus.entries.filter { it != NightStatus.WRITTEN }.forEach { status ->
            assertNull(InputUpdate.of(status, reason), "$status")
        }
    }

    @Test
    fun `other reasons and garbage are not an update`() {
        listOf(
            null,
            "",
            "superseded by ps:x",
            "NAP_SKIPPED: nap of 60 min",
            "INPUT_UPDATED:",
            "INPUT_UPDATED: ",
            "INPUT_UPDATED: MORE_HEART_RATE",
            "INPUT_UPDATED: MORE_HEART_RATE 46",
            "INPUT_UPDATED: MORE_HEART_RATE x 57",
            "INPUT_UPDATED: MORE_HEART_RATE 46 5.5",
            "INPUT_UPDATED: MORE_STEPS 46 57",
            "INPUT_UPDATED: more_heart_rate 46 57",
            "input_updated: MORE_HEART_RATE 46 57",
            " INPUT_UPDATED: MORE_HEART_RATE 46 57",
        ).forEach { reason ->
            assertNull(InputUpdate.of(NightStatus.WRITTEN, reason), "$reason")
        }
    }

    @Test
    fun `the entity exposes the update of its last write and is neither stale nor a skipped nap because of it`() {
        val update = InputUpdate(InputChange.MORE_HEART_RATE, 46, 57)
        val refreshed = night("ps:a", startMs = 0, algoVersion = 2, paramsHash = "h").copy(statusReason = update.reason())

        assertEquals(update, refreshed.inputUpdate)
        assertFalse(refreshed.isStale(2, "h"))
        assertFalse(refreshed.skippedNap)
        assertNull(night("ps:b", startMs = 0).inputUpdate, "a regular write has no reason")
        assertNull(refreshed.copy(status = NightStatus.SUPERSEDED).inputUpdate)
        assertEquals(INPUT_UPDATED_REASON_PREFIX, update.reason().substringBefore(' '))
    }
}
