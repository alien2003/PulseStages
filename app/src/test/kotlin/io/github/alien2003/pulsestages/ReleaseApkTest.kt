package io.github.alien2003.pulsestages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks the minified RELEASE build that would ship: it has no demo mode, neither a demo class (R8's mapping file lists
 * every class of the APK) nor a demo resource or text (aapt2's dump of the APK), so no switch either. The APK
 * directory, the mapping file and aapt2 are passed in by app/build.gradle.kts (`-Dpulsestages.release.*`, from the
 * Variant API artifacts), so the test task depends on the release build. The debug build's single launcher icon is
 * checked by MergedManifestTest.
 */
class ReleaseApkTest {
    @Test
    fun `the minified release has no demo class`() {
        val mapping = File(property("mapping"))
        assertTrue(mapping.isFile, "mapping file missing: $mapping")
        // Class lines are "original.Name -> obfuscated:"; member lines are indented, comments start with '#'.
        val classes =
            mapping
                .readLines()
                .filter { it.isNotEmpty() && !it.first().isWhitespace() && !it.startsWith("#") && it.endsWith(":") }
                .map { it.substringBefore(" -> ") }
        assertTrue("$APPLICATION_ID.MainActivity" in classes, "the mapping lists the app's classes")
        val demo = classes.filter { it.startsWith("$APPLICATION_ID.demo.") || (it.startsWith(APPLICATION_ID) && "Demo" in it) }
        assertEquals(emptyList<String>(), demo)
    }

    @Test
    fun `the release APK has no demo resource or text`() {
        val directory = File(property("apkDir"))
        val apk = directory.listFiles { file -> file.extension == "apk" }?.singleOrNull()
        checkNotNull(apk) { "no single APK in $directory" }
        val resources = aapt2("dump", "resources", apk.absolutePath)
        assertTrue("string/app_name" in resources, "aapt2 lists the resources")
        assertFalse("string/demo_" in resources, "the release APK has demo string resources")
        val strings = aapt2("dump", "strings", apk.absolutePath)
        assertTrue("PulseStages" in strings, "aapt2 lists the string pool")
        val texts = DEMO_WORDS + demoTexts()
        texts.forEach { text -> assertFalse(strings.contains(text, ignoreCase = true), "the release APK contains \"$text\"") }
    }

    /** Every value of the debug build's demo strings (switch, hint, banner) in every language, from src/debug/res. */
    private fun demoTexts(): List<String> {
        val files = File("src/debug/res").walkTopDown().filter { it.name == "strings_demo.xml" }.toList()
        assertTrue(files.size >= 2, "the demo strings exist in English and in the translation: $files")
        val value = Regex("<string name=\"[^\"]+\">([^<]+)</string>")
        return files
            .flatMap { file -> value.findAll(file.readText()).map { it.groupValues[1].trim() }.toList() }
            .filter { it.isNotEmpty() }
    }

    private fun property(name: String): String =
        checkNotNull(System.getProperty("pulsestages.release.$name")) { "pulsestages.release.$name not passed to the test" }

    private fun aapt2(vararg arguments: String): String {
        val process = ProcessBuilder(property("aapt2"), *arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(AAPT2_TIMEOUT_SECONDS, TimeUnit.SECONDS), "aapt2 ${arguments.toList()} did not finish")
        assertEquals(0, process.exitValue(), "aapt2 ${arguments.toList()} failed: $output")
        return output
    }

    private companion object {
        const val APPLICATION_ID = "io.github.alien2003.pulsestages"
        const val AAPT2_TIMEOUT_SECONDS = 60L

        /** Words of the debug build's demo texts; the full texts in every language come from [demoTexts]. */
        val DEMO_WORDS = listOf("demo", "synthetic nights")
    }
}
