package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.TrainedDefaults
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.data.local.settings.SettingsErrorCode
import io.github.alien2003.pulsestages.data.local.settings.SettingsJson
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.ui.common.UiText
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

    @Test
    fun `the check interval offers exactly the allowed minutes in order, each valid and labelled in minutes or hours`() {
        val spec = SettingsCatalog.spec("schedule.periodicMinutes") as ChoiceSpec

        assertEquals(ChoiceStyle.DROPDOWN, spec.style)
        assertEquals(SettingsLimits.periodicMinutes.map { it.toString() }, spec.options.map { it.key })
        assertEquals("60", spec.read(defaults))
        spec.options.forEach { option ->
            val written = spec.write(defaults, option.key)
            assertEquals(option.key.toInt(), written.schedule.periodicMinutes)
            assertEquals(emptyList<SettingsError>(), SettingsChecks.validate(written), option.key)
        }
        val labels = spec.options.associate { it.key to it.label }
        assertEquals(UiText.of(R.string.format_value_unit, "15", UiText.of(R.string.unit_min)), labels["15"])
        assertEquals(UiText.of(R.string.format_value_unit, "30", UiText.of(R.string.unit_min)), labels["30"])
        assertEquals(UiText.of(R.string.format_value_unit, "1", UiText.of(R.string.unit_hours)), labels["60"])
        assertEquals(UiText.of(R.string.format_value_unit, "24", UiText.of(R.string.unit_hours)), labels["1440"])
        val notAnOption = SettingsChecks.validate(spec.write(defaults, "45")).single()
        assertEquals("schedule.periodicMinutes" to SettingsErrorCode.NOT_ALLOWED, notAnOption.path to notAnOption.code)
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
            is OptionalIntSpec -> spec.write(s, (spec.read(s) ?: spec.inherited(s)) - 1)
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
            is OptionalIntSpec -> assertEquals((spec.read(defaults) ?: spec.inherited(defaults)) - 1, spec.read(changed), spec.path)
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
    fun `the nap overrides are empty by default, except the trained nap decoder, and inherit the night's values`() {
        val overrides = SettingsCatalog.specsByPath.values.filterIsInstance<OptionalDoubleSpec>()
        assertTrue(overrides.size >= 9, "every numeric nap override has an optional field: ${overrides.map { it.path }}")
        // The nap decoder defaults come from the pre-registered nap-model experiment (TrainedDefaults); null = nights'.
        val trained: Map<String, Double?> =
            mapOf(
                "algo.napMode.hybrid.emissionWeight" to TrainedDefaults.NAP_HYBRID_EMISSION_WEIGHT,
                "algo.napMode.hybrid.timePriorWeight" to TrainedDefaults.NAP_HYBRID_TIME_PRIOR_WEIGHT,
            )
        overrides.forEach { spec ->
            assertTrue(spec.path.startsWith("algo.napMode."), spec.path)
            assertEquals(trained[spec.path], spec.read(defaults), spec.path)
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
            TrainedDefaults.NAP_HYBRID_DURATION_MODEL?.name ?: SAME_AS_NIGHTS_KEY,
            checkNotNull(SettingsCatalog.spec("algo.napMode.hybrid.durationModel") as ChoiceSpec).read(defaults),
        )
        assertEquals(
            TrainedDefaults.NAP_OUTPUT_POLICY.name,
            checkNotNull(SettingsCatalog.spec("algo.napMode.outputPolicy") as ChoiceSpec).read(defaults),
        )
        // The nap estimator follows the night's by default ("Same as nights"), as a settings file of 0.1.0 expects.
        val estimator = checkNotNull(SettingsCatalog.spec("algo.napMode.estimator") as ChoiceSpec)
        assertEquals(TrainedDefaults.NAP_ESTIMATOR?.name ?: SAME_AS_NIGHTS_KEY, estimator.read(defaults))
        val hmm = defaults.copy(algo = defaults.algo.withEstimator(EstimatorType.HMM))
        assertEquals(
            null,
            estimator
                .write(hmm, SAME_AS_NIGHTS_KEY)
                .algo.napMode.estimator,
        )
        assertEquals(
            EstimatorType.RULES,
            estimator
                .write(defaults, "RULES")
                .algo.napMode.estimator,
        )
        // Long labels ("Asleep / awake", "Same as nights") are dropdowns, not segments.
        assertEquals(ChoiceStyle.DROPDOWN, estimator.style)
        assertEquals(ChoiceStyle.DROPDOWN, (SettingsCatalog.spec("algo.napMode.outputPolicy") as ChoiceSpec).style)
        assertTrue(SettingsCatalog.spec("algo.features.baselineMinSessionMin") == null, "the old thresholds are gone")
        assertTrue(SettingsCatalog.spec("algo.hybrid.shortSessionMaxMin") == null)
        assertTrue(SettingsCatalog.spec("algo.post.proportionsMinSessionMin") == null)
    }

    @Test
    fun `only the everyday groups start expanded, the nap overrides are in a collapsed algorithm group`() {
        val expanded = SettingsCatalog.sections.filter { it.expandedByDefault }.map { it.id }
        assertEquals(listOf("sources", "schedule", "write", "estimator", "naps", "debug"), expanded)
        val naps = SettingsCatalog.sections.single { it.id == "naps" }
        assertEquals(
            listOf("algo.napMode.maxSessionMin", "algo.napMode.outputPolicy", "algo.napMode.estimator"),
            naps.items.specs().map { it.path },
        )
        val algorithm = SettingsCatalog.sections.single { it.id == "naps_algorithm" }
        val algorithmPaths = algorithm.items.specs().map { it.path }
        assertEquals("algo.napMode.contextMarginMin", algorithmPaths.first())
        assertTrue(algorithmPaths.drop(1).all { it.startsWith("algo.napMode.post.") || it.startsWith("algo.napMode.hybrid.") })
    }

    @Test
    fun `the nap context margin is an optional whole number of minutes, at most the night's margin`() {
        val spec = checkNotNull(SettingsCatalog.spec("algo.napMode.contextMarginMin")) as OptionalIntSpec
        assertEquals(TrainedDefaults.NAP_CONTEXT_MARGIN_MIN, spec.read(defaults), "the trained default (null = the night's)")
        assertEquals(defaults.algo.preprocess.contextMarginMin, spec.inherited(defaults))
        assertEquals(0..defaults.algo.preprocess.contextMarginMin, spec.range(defaults))
        val wider = defaults.copy(algo = defaults.algo.copy(preprocess = defaults.algo.preprocess.copy(contextMarginMin = 60)))
        assertEquals(0..60, spec.range(wider), "the accepted range follows the night's margin")
        assertEquals(60, spec.inherited(wider))
        // The range is the validators' range.
        for (base in listOf(defaults, wider)) {
            val range = spec.range(base)
            assertTrue(SettingsChecks.validate(spec.write(base, range.first)).isEmpty())
            assertTrue(SettingsChecks.validate(spec.write(base, range.last)).isEmpty())
            assertTrue(SettingsChecks.validate(spec.write(base, null)).isEmpty())
            assertEquals(listOf(spec.path), SettingsChecks.validate(spec.write(base, range.last + 1)).map { it.path })
            assertEquals(listOf(spec.path), SettingsChecks.validate(spec.write(base, range.first - 1)).map { it.path })
        }
        assertEquals(UNIT_MIN, spec.unit)
    }

    private companion object {
        const val MINUTES_PER_DAY = 1440
    }
}
