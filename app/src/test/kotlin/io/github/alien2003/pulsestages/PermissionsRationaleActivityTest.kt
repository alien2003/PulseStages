package io.github.alien2003.pulsestages

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Intent flag constants are compile-time constants: no Android runtime is needed. */
class PermissionsRationaleActivityTest {
    @Test
    fun `Open PulseStages reuses the existing MainActivity of the app's task`() {
        val flags = PermissionsRationaleActivity.OPEN_APP_FLAGS
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "started from Health Connect's task")
        // Without CLEAR_TOP + SINGLE_TOP, a component-only intent differs from the launcher's root intent and the
        // system stacks a second MainActivity on the existing task.
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0, "activities above MainActivity are cleared")
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0, "the existing MainActivity receives the intent")
    }
}
