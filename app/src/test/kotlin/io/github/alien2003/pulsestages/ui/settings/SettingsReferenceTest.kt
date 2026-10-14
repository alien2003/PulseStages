package io.github.alien2003.pulsestages.ui.settings

import android.app.Application
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * docs/settings-reference.md is generated from the code by [SettingsReference]; this test fails when the committed file
 * differs from what the code produces now. With `-PrecordSettingsReference` it writes the file instead (see
 * app/build.gradle.kts):
 *
 * ```
 * mise exec -- ./gradlew :app:testDebugUnitTest --tests '*SettingsReferenceTest' -PrecordSettingsReference
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class SettingsReferenceTest {
    private val file: File = File(System.getProperty(FILE_PROPERTY) ?: "../docs/settings-reference.md")
    private val reference: SettingsReference by lazy {
        val docs = checkNotNull(file.absoluteFile.parentFile) { "$file has no directory" }
        val algorithm = File(checkNotNull(docs.parentFile) { "$docs has no parent" }, "ALGORITHM.md")
        SettingsReference(RuntimeEnvironment.getApplication().resources, algorithm.readText())
    }

    @Test
    fun `docs settings-reference md is up to date`() {
        val expected = reference.render()
        if (System.getProperty(RECORD_PROPERTY) == "true") {
            file.writeText(expected)
            return
        }
        val actual = if (file.exists()) file.readText() else ""
        if (actual == expected) return
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val line =
            expectedLines.indices.firstOrNull { it >= actualLines.size || expectedLines[it] != actualLines[it] } ?: expectedLines.size
        fail(
            "$file is out of date (first difference in line ${line + 1}).\n" +
                "  generated: ${expectedLines.getOrNull(line)}\n" +
                "  committed: ${actualLines.getOrNull(line)}\n" +
                "Regenerate it with: mise exec -- ./gradlew :app:testDebugUnitTest --tests '*SettingsReferenceTest' " +
                "-PrecordSettingsReference",
        )
    }

    @Test
    fun `every value of the settings JSON is documented, and nothing else`() {
        val leaves = leaves(SettingsJson.export.encodeToJsonElement(AppSettings.serializer(), AppSettings()))
        assertEquals(leaves, reference.keys())
    }

    @Test
    fun `the params hash column follows the pipeline's hash`() {
        val keys = reference.keys()
        keys.filter { it.startsWith("algo.") }.forEach { assertEquals(it, true, reference.inParamsHash(it)) }
        // The output option and the input selection are part of the hash, the title template deliberately is not.
        assertEquals(true, reference.inParamsHash("write.awakeAsLight"))
        assertEquals(true, reference.inParamsHash("sources.hrSources"))
        listOf("write.titleTemplate", "write.overwritePolicy", "sources.minSessionMin", "schedule.periodicHours", "debug.logLevel")
            .forEach { assertEquals(it, false, reference.inParamsHash(it)) }
    }

    @Test
    fun `the reference links only to headings that exist and states the validators' ranges`() {
        val text = reference.render()
        assertTrue(text.contains("](../ALGORITHM.md#18-night-and-nap-modes-napmode)"))
        assertTrue("the nap threshold's range", text.contains("| `algo.napMode.maxSessionMin` | `180.0` min | 0–720 |"))
        assertTrue("the minimum session's range", text.contains("| `sources.minSessionMin` | `90` min | 10–720 (whole number) |"))
        assertTrue("an odd window", text.contains("odd, 1–31 (whole number)"))
        assertTrue("a relation", text.contains("> 0; ≤ `algo.preprocess.maxBpm`"))
    }

    /** Every leaf (primitive, null or array) of [element] by its dotted path. */
    private fun leaves(
        element: JsonElement,
        prefix: String = "",
    ): Set<String> =
        when (element) {
            is JsonObject -> {
                element.entries.flatMap { (key, value) -> leaves(value, if (prefix.isEmpty()) key else "$prefix.$key") }.toSet()
            }

            is JsonArray, is JsonPrimitive -> {
                setOf(prefix)
            }
        }

    private companion object {
        const val FILE_PROPERTY = "pulsestages.settingsReference.file"
        const val RECORD_PROPERTY = "pulsestages.settingsReference.record"
    }
}
