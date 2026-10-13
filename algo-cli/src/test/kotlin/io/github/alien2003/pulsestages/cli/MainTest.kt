package io.github.alien2003.pulsestages.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTest {
    @Test
    fun `stages command lists all stages`() {
        val out = StringBuilder()
        val err = StringBuilder()

        val code = run(listOf("stages"), out, err)

        assertEquals(ExitCodes.OK, code)
        assertEquals("AWAKE,LIGHT,DEEP,REM", out.toString().trim())
        assertTrue(err.isEmpty())
    }

    @Test
    fun `unknown command is a usage error`() {
        val err = StringBuilder()

        val code = run(listOf("bogus"), StringBuilder(), err)

        assertEquals(ExitCodes.USAGE, code)
        assertTrue(err.contains("Unknown command: bogus"))
    }
}
