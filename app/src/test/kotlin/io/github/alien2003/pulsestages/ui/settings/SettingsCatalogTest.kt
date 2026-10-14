package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SettingsCatalogTest {
    private val defaults = AppSettings()

    @Test
    fun `every leaf of the settings JSON is edited by exactly one spec or on the Sources screen`() {
        val leaves = leaves(encode(defaults)).keys
        val editable = SettingsCatalog.specsByPath.keys + SettingsCatalog.SOURCES_SCREEN_PATHS
        assertEquals(emptySet<String>(), leaves - editable, "settings without an editor")
        assertEquals(emptySet<String>(), editable - leaves, "editors of unknown paths")
    }

    @Test
    fun `no path is edited by two specs`() {
        val paths = SettingsCatalog.sections.flatMap { it.items.specs() }.flatMap { it.paths }
        assertEquals(paths.size, paths.toSet().size, "duplicates: ${paths.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun `sections have unique ids and are not empty`() {
        val ids = SettingsCatalog.sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        SettingsCatalog.sections.forEach { assertTrue(it.items.isNotEmpty(), it.id) }
    }

    @Test
    fun `every spec reads the value at its path and writes exactly its paths`() {
        val before = leaves(encode(defaults))
        SettingsCatalog.sections.flatMap { it.items.specs() }.forEach { spec ->
            val changed = changeValue(spec, defaults)
            val after = leaves(encode(changed))
            val diff = after.keys.filter { after[it] != before[it] }.toSet()
            assertEquals(spec.paths.toSet(), diff, "write of ${spec.path} changed $diff")
            assertReadBack(spec, changed)
        }
    }

    @Test
    fun `int specs declare the validator's ranges and the default lies inside`() {
        SettingsCatalog.specsByPath.values.filterIsInstance<IntSpec>().forEach { spec ->
            val range = checkNotNull(spec.range) { "${spec.path} has no range" }
            assertTrue(spec.read(defaults) in range, "${spec.path} default outside $range")
            val outside = SettingsChecks.validate(spec.write(defaults, range.last + 1)).map { it.path }
            assertTrue(spec.path in outside, "${spec.path}: ${range.last + 1} is accepted by the validator")
        }
    }

    /** A different value for every spec type (validity does not matter here, only which paths change). */
    private fun changeValue(
        spec: SettingSpec,
        s: AppSettings,
    ): AppSettings =
        when (spec) {
            is SwitchSpec -> spec.write(s, !spec.read(s))
            is ChoiceSpec -> spec.write(s, spec.options.first { it.key != spec.read(s) }.key)
            is IntSpec -> spec.write(s, spec.read(s) + 2)
            is DoubleSpec -> spec.write(s, spec.read(s) + 0.25)
            is OptionalDoubleSpec -> spec.write(s, (spec.read(s) ?: spec.inherited(s)) + 0.25)
            is TimeSpec -> spec.write(s, (spec.read(s) + 1) % MINUTES_PER_DAY)
            is TextSpec -> spec.write(s, "Stages {version}")
            is RangeSpec -> spec.write(s, spec.readMin(s) - 0.01, spec.readMax(s) + 0.01)
        }

    private fun assertReadBack(
        spec: SettingSpec,
        changed: AppSettings,
    ) {
        when (spec) {
            is SwitchSpec -> assertEquals(!spec.read(defaults), spec.read(changed), spec.path)
            is ChoiceSpec -> assertNotEquals(spec.read(defaults), spec.read(changed), spec.path)
            is IntSpec -> assertEquals(spec.read(defaults) + 2, spec.read(changed), spec.path)
            is DoubleSpec -> assertTrue(abs(spec.read(defaults) + 0.25 - spec.read(changed)) < 1e-12, spec.path)
            is OptionalDoubleSpec -> assertTrue(abs(spec.inherited(defaults) + 0.25 - checkNotNull(spec.read(changed))) < 1e-12, spec.path)
            is TimeSpec -> assertEquals((spec.read(defaults) + 1) % MINUTES_PER_DAY, spec.read(changed), spec.path)
            is TextSpec -> assertEquals("Stages {version}", spec.read(changed), spec.path)
            is RangeSpec -> assertTrue(spec.readMin(changed) < spec.readMin(defaults) && spec.readMax(changed) > spec.readMax(defaults))
        }
    }

    private fun encode(settings: AppSettings): JsonElement = SettingsJson.export.encodeToJsonElement(AppSettings.serializer(), settings)

    /** Every leaf (primitive, null or array) of [element] by its dotted path. */
    private fun leaves(
        element: JsonElement,
        prefix: String = "",
    ): Map<String, JsonElement> =
        when (element) {
            is JsonObject -> {
                element.entries.fold(emptyMap()) { acc, (key, value) ->
                    acc +
                        leaves(value, if (prefix.isEmpty()) key else "$prefix.$key")
                }
            }

            is JsonArray, is JsonPrimitive -> {
                mapOf(prefix to element)
            }
        }

    @Test
    fun `the nap overrides are empty by default and inherit the night's values`() {
        val overrides = SettingsCatalog.specsByPath.values.filterIsInstance<OptionalDoubleSpec>()
        assertTrue(overrides.size >= 9, "every numeric nap override has an optional field: ${overrides.map { it.path }}")
        overrides.forEach { spec ->
            assertTrue(spec.path.startsWith("algo.napMode."), spec.path)
            assertEquals(null, spec.read(defaults), spec.path)
            val night = spec.path.removePrefix("algo.napMode.").substringBefore('.')
            assertTrue(night == "post" || night == "hybrid", spec.path)
        }
        val choices =
            SettingsCatalog.specsByPath.values
                .filterIsInstance<ChoiceSpec>()
                .filter { it.path.startsWith("algo.napMode.") }
        assertEquals(
            setOf(
                "algo.napMode.outputPolicy",
                "algo.napMode.estimator",
                "algo.napMode.post.lowConfidencePolicy",
                "algo.napMode.hybrid.durationModel",
            ),
            choices.map { it.path }.toSet(),
        )
        assertEquals(
            SAME_AS_NIGHTS_KEY,
            checkNotNull(SettingsCatalog.spec("algo.napMode.hybrid.durationModel") as ChoiceSpec).read(defaults),
        )
        assertTrue(SettingsCatalog.spec("algo.features.baselineMinSessionMin") == null, "the old thresholds are gone")
        assertTrue(SettingsCatalog.spec("algo.hybrid.shortSessionMaxMin") == null)
        assertTrue(SettingsCatalog.spec("algo.post.proportionsMinSessionMin") == null)
    }

    private companion object {
        const val MINUTES_PER_DAY = 1440
    }
}
