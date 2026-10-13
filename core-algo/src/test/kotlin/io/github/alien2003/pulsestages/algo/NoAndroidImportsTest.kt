package io.github.alien2003.pulsestages.algo

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * :core-algo must stay a pure JVM module so the same code runs in the app, in :algo-cli and in the Python training
 * pipeline. The Gradle build also rejects Android plugins and Android artifacts; this test guards the sources.
 */
class NoAndroidImportsTest {
    private val androidImport = Regex("""^\s*import\s+(android|androidx|com\.android|com\.google\.android)\.""")

    @Test
    fun `main sources do not import Android APIs`() {
        val mainSrc = File(checkNotNull(System.getProperty("pulsestages.coreAlgo.mainSrc")) { "system property not set by Gradle" })
        assertTrue(mainSrc.isDirectory, "missing source dir $mainSrc")

        val sourceFiles = mainSrc.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        assertTrue(sourceFiles.isNotEmpty(), "no sources found under $mainSrc")

        val offenders =
            sourceFiles.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (androidImport.containsMatchIn(line)) "${file.relativeTo(mainSrc)}:${index + 1}: ${line.trim()}" else null
                }
            }
        assertTrue(offenders.isEmpty(), "Android imports found in :core-algo:\n" + offenders.joinToString("\n"))
    }
}
