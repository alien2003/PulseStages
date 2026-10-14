package io.github.alien2003.pulsestages.ui.settings

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.data.local.settings.SettingsErrorCode
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Resolves every text the settings catalog can produce against the app's real resources, in English and Russian:
 * section titles and summaries, sub-headings, titles, hints, units, placeholders, option labels, the accepted-values
 * texts, the HMM table labels and every validation message [SettingsChecks] reports for invalid values. A text whose
 * resource is not a valid format string for its arguments (e.g. a lone `%` resolved through `String.format`) throws
 * here, and a text left with an unfilled placeholder (`%1$s`) is reported. This is what the Settings screen does with
 * these texts, without Compose.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsTextsTest(
    private val language: String,
) {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private val resources: Resources by lazy { resourcesFor(language) }
    private val defaults = AppSettings()
    private val specs: List<SettingSpec> = SettingsCatalog.sections.flatMap { it.items.specs() }

    private fun resourcesFor(tag: String): Resources {
        val configuration = Configuration(application.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return application.createConfigurationContext(configuration).resources
    }

    /** Resolves every text; returns one line per text that throws, is blank or keeps a format placeholder. */
    private fun problemsOf(texts: List<Pair<String, UiText>>): List<String> =
        texts.mapNotNull { (what, text) ->
            val resolved =
                try {
                    text.resolve(resources)
                } catch (e: IllegalArgumentException) {
                    // java.util.IllegalFormatException: UnknownFormatConversionException, MissingFormatArgumentException, ...
                    return@mapNotNull "$what: ${e.javaClass.simpleName}: ${e.message}"
                } catch (e: Resources.NotFoundException) {
                    return@mapNotNull "$what: ${e.message}"
                }
            when {
                resolved.isBlank() -> "$what: blank"
                PLACEHOLDER.containsMatchIn(resolved) -> "$what: unfilled placeholder in \"$resolved\""
                else -> null
            }
        }

    private fun assertResolves(texts: List<Pair<String, UiText>>) {
        assertTrue("nothing to resolve", texts.isNotEmpty())
        assertEquals("[$language] texts that do not resolve", emptyList<String>(), problemsOf(texts))
    }

    @Test
    fun `the resources are those of the language`() {
        val english = resourcesFor("en").getString(R.string.settings_section_features)
        val own = resources.getString(R.string.settings_section_features)
        if (language == "en") assertEquals(english, own) else assertNotEquals(english, own)
    }

    @Test
    fun `section titles, summaries, sub-headings and table labels resolve`() {
        val texts =
            SettingsCatalog.sections.flatMap { section ->
                listOf(
                    "${section.id} title" to section.title,
                    "${section.id} summary" to section.summary,
                    "${section.id} expand" to UiText.of(R.string.cd_expand_section, section.title),
                    "${section.id} collapse" to UiText.of(R.string.cd_collapse_section, section.title),
                ) + section.items.flatMap { itemTexts(section.id, it) }
            }
        assertResolves(texts)
    }

    private fun itemTexts(
        section: String,
        item: SettingsItem,
    ): List<Pair<String, UiText>> =
        when (item) {
            is SettingsItem.Field, SettingsItem.SourcesLink, SettingsItem.LogLink -> {
                emptyList()
            }

            is SettingsItem.Subheader -> {
                listOf("$section sub-heading" to item.title)
            }

            is SettingsItem.EmissionTable -> {
                listOf("$section emission hint" to item.hint) +
                    item.rows.flatMap { row ->
                        listOf("$section emission state" to row.state) + row.cells.map { "$section emission feature" to it.feature }
                    }
            }

            is SettingsItem.TransitionWeights -> {
                listOf("$section transitions hint" to item.hint) +
                    item.rows.map { "$section transitions from" to UiText.of(R.string.settings_transitions_from, it.from) }
            }
        }

    @Test
    fun `titles, hints, placeholders and option labels of every setting resolve`() {
        val texts =
            specs.flatMap { spec ->
                val base =
                    listOf(
                        "${spec.path} title" to spec.title,
                        "${spec.path} hint" to spec.hint,
                        "${spec.path} time label" to UiText.of(R.string.format_label_value, spec.title, UiText.Verbatim("07:30")),
                    )
                base +
                    when (spec) {
                        is ChoiceSpec -> {
                            spec.options.map { "${spec.path} option ${it.key}" to it.label }
                        }

                        is TextSpec -> {
                            listOf("${spec.path} placeholder" to spec.placeholder)
                        }

                        is OptionalDoubleSpec -> {
                            listOf("${spec.path} placeholder" to UiText.of(R.string.settings_same_as_nights_value, "0.45"))
                        }

                        else -> {
                            emptyList()
                        }
                    }
            }
        assertTrue("every choice of the catalog", specs.filterIsInstance<ChoiceSpec>().size >= 5)
        assertResolves(texts)
    }

    @Test
    fun `units, values with units and accepted-values texts resolve`() {
        val texts =
            specs.flatMap { spec ->
                val unit = unitOf(spec)
                val bounds = boundsOf(spec)
                listOfNotNull(
                    unit?.let { "${spec.path} unit" to it },
                    unit?.let { "${spec.path} value with unit" to UiText.of(R.string.format_value_unit, "42", it) },
                    bounds?.let { "${spec.path} bounds" to it },
                    bounds?.let { "${spec.path} allowed values" to UiText.of(R.string.settings_allowed_values, it) },
                    (spec as? RangeSpec)?.let { "${spec.path} range" to UiText.of(R.string.format_range, "0.2", "0.8") },
                )
            }
        assertTrue("the percent units are covered", texts.any { it.second == UiText.of(R.string.unit_percent) })
        assertResolves(texts)
    }

    @Test
    fun `every validation message of every setting resolves`() {
        val errors = specs.flatMap { spec -> invalidCandidates(spec).flatMap { SettingsChecks.validate(it) }.map { spec to it } }
        val codes = errors.map { it.second.code }.toSet()
        val expected =
            setOf(
                SettingsErrorCode.OUT_OF_RANGE,
                SettingsErrorCode.NOT_ALLOWED,
                SettingsErrorCode.NOT_FINITE,
                SettingsErrorCode.MIN_ABOVE_MAX,
                SettingsErrorCode.BLANK,
                SettingsErrorCode.TOO_LONG,
            )
        assertTrue("codes reached: $codes", codes.containsAll(expected))
        val texts =
            errors.distinct().flatMap { (spec, error) ->
                val label = "${spec.path} -> ${error.path} ${error.code} (${error.constraint})"
                listOf(
                    "$label below the field" to SettingsChecks.describe(FieldProblem.Invalid(error), spec.path),
                    "$label import" to SettingsChecks.describeWithTitle(error),
                )
            } +
                specs.flatMap { spec ->
                    listOf(true, false).map { integer ->
                        "${spec.path} not a number" to SettingsChecks.describe(FieldProblem.NotANumber(integer), spec.path)
                    }
                }
        assertResolves(texts)
    }

    @Test
    fun `every error code resolves, also for paths the screen does not edit`() {
        val samples =
            SettingsErrorCode.entries.flatMap { code ->
                listOf(
                    SettingsError("algo.preprocess.minBpm", code, "250", constraintOf(code)),
                    SettingsError("sources.sourcePackages[1]", code, "not a package", constraintOf(code)),
                    SettingsError("$", code, "", constraintOf(code)),
                    SettingsError("algo.unknown.path", code, "1.0E-4", constraintOf(code)),
                )
            }
        val texts =
            samples.flatMap { error ->
                listOf(
                    "${error.path} ${error.code}" to SettingsChecks.describe(error),
                    "${error.path} ${error.code} import" to SettingsChecks.describeWithTitle(error),
                )
            }
        assertResolves(texts)
    }

    @Test
    fun `the stale-nights prompt resolves for every plural form`() {
        val texts =
            listOf(0, 1, 2, 3, 5, 11, 21, 22, 25, 101).map { count ->
                "stale $count" to UiText.plural(R.plurals.settings_stale_nights, count)
            } + ("recompute" to UiText.of(R.string.settings_stale_recompute))
        assertResolves(texts)
    }

    private fun unitOf(spec: SettingSpec): UiText? =
        when (spec) {
            is IntSpec -> spec.unit
            is DoubleSpec -> spec.unit
            is OptionalDoubleSpec -> spec.unit
            else -> null
        }

    private fun boundsOf(spec: SettingSpec): UiText? =
        when (spec) {
            is IntSpec -> spec.range?.let { UiText.of(R.string.bounds_range, it.first.toString(), it.last.toString()) }
            is DoubleSpec -> spec.bounds
            is OptionalDoubleSpec -> spec.bounds
            else -> null
        }

    /** Settings with an invalid value for [spec] (several kinds where the type allows). */
    private fun invalidCandidates(spec: SettingSpec): List<AppSettings> {
        val s = defaults
        return when (spec) {
            is IntSpec -> {
                val range = spec.range ?: (0..0)
                listOfNotNull(
                    spec.write(s, range.last + 1),
                    spec.write(s, range.first - 1),
                    spec.write(s, spec.read(s) + 1).takeIf { spec.oddOnly },
                )
            }

            is DoubleSpec -> {
                listOf(-1e9, -1.0, 0.0, 1e9, Double.NaN, Double.POSITIVE_INFINITY).map { spec.write(s, it) }
            }

            is OptionalDoubleSpec -> {
                listOf(-1e9, -1.0, 0.0, 1e9, Double.NaN, Double.POSITIVE_INFINITY).map { spec.write(s, it) }
            }

            is RangeSpec -> {
                listOf(spec.write(s, 0.9, 0.1), spec.write(s, -0.5, 1.5))
            }

            is TextSpec -> {
                listOf(spec.write(s, "x".repeat(SettingsLimits.TITLE_TEMPLATE_MAX_LENGTH + 1)), spec.write(s, "   "))
            }

            is TimeSpec -> {
                listOf(spec.write(s, -1), spec.write(s, MINUTES_PER_DAY))
            }

            is ChoiceSpec -> {
                // Only numeric choices can hold a value outside their options (enum names are checked by the parser).
                listOfNotNull(runCatching { spec.write(s, "7") }.getOrNull())
            }

            is SwitchSpec -> {
                emptyList()
            }
        }
    }

    private fun constraintOf(code: SettingsErrorCode): String =
        when (code) {
            SettingsErrorCode.MALFORMED_JSON -> "Unexpected JSON token at offset 12: 100% is not a number"
            SettingsErrorCode.OUT_OF_RANGE -> ">= 0.0"
            SettingsErrorCode.NOT_ODD -> "odd, 1..31"
            SettingsErrorCode.NOT_ALLOWED -> "one of [30, 60, 120]"
            SettingsErrorCode.NOT_FINITE -> "a finite number"
            SettingsErrorCode.MIN_ABOVE_MAX -> "<= algo.preprocess.maxBpm (220.0)"
            SettingsErrorCode.INVALID_PACKAGE_NAME -> "a package name"
            SettingsErrorCode.BLANK -> "not blank"
            SettingsErrorCode.TOO_LONG -> "<= 1000 characters"
        }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** A java.util.Formatter conversion left in a resolved text (`%s`, `%1$d`, `%.2f`); "50 %" is not one. */
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z]""")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("ru"))
    }
}
