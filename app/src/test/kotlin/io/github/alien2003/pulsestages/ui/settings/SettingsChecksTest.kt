package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.data.local.settings.SettingsErrorCode
import io.github.alien2003.pulsestages.ui.common.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsChecksTest {
    @Test
    fun `defaults are valid`() {
        assertEquals(emptyList<SettingsError>(), SettingsChecks.validate(AppSettings()))
    }

    @Test
    fun `algorithm values the settings validator does not cover are checked with AlgoParamsValidator`() {
        val settings = AppSettings().pre { it.copy(hampelMinScaleBpm = -1.0) }.rules { it.copy(pseudoPosterior = 1.5) }
        val errors = SettingsChecks.validate(settings)
        assertEquals(listOf("algo.preprocess.hampelMinScaleBpm", "algo.rules.pseudoPosterior"), errors.map { it.path })
        assertTrue(errors.all { it.code == SettingsErrorCode.OUT_OF_RANGE })
    }

    @Test
    fun `values reported by both validators are reported once`() {
        val errors = SettingsChecks.validate(AppSettings().pre { it.copy(epochSec = 45) })
        assertEquals(listOf("algo.preprocess.epochSec"), errors.map { it.path })
        assertEquals(SettingsErrorCode.NOT_ALLOWED, errors.single().code)
    }

    @Test
    fun `constraints are shown in a readable form`() {
        assertEquals("10–720", SettingsChecks.pretty("10..720"))
        assertEquals("0–1", SettingsChecks.pretty("0.0..1.0"))
        assertEquals("≥ 0", SettingsChecks.pretty(">= 0.0"))
        assertEquals("> 1", SettingsChecks.pretty("> 1.0"))
        assertEquals("≤ 0.6", SettingsChecks.pretty("<= 0.6"))
    }

    @Test
    fun `errors map to localized messages`() {
        val outOfRange = SettingsError("sources.minSessionMin", SettingsErrorCode.OUT_OF_RANGE, "5", "10..720")
        assertEquals(UiText.Res(R.string.settings_error_out_of_range, listOf("10–720")), SettingsChecks.describe(outOfRange))
        val odd = SettingsError("algo.preprocess.hampelWindow", SettingsErrorCode.NOT_ODD, "4", "odd, 3..31")
        assertEquals(UiText.Res(R.string.settings_error_not_odd, listOf("3–31")), SettingsChecks.describe(odd))
        val notAllowed = SettingsError("algo.preprocess.epochSec", SettingsErrorCode.NOT_ALLOWED, "45", "one of 30, 60, 120")
        assertEquals(UiText.Res(R.string.settings_error_not_allowed, listOf("30, 60, 120")), SettingsChecks.describe(notAllowed))
    }

    @Test
    fun `a minimum above its maximum names both settings`() {
        val error =
            SettingsError(
                "algo.preprocess.rulesMinCoverage",
                SettingsErrorCode.MIN_ABOVE_MAX,
                "0.4",
                "<= algo.preprocess.minCoverage (0.3)",
            )
        val text = SettingsChecks.describe(FieldProblem.Invalid(error), editedPath = "algo.preprocess.minCoverage")
        val expected =
            UiText.Res(
                R.string.settings_error_min_above_max,
                listOf(
                    UiText.Res(R.string.set_pre_rules_min_coverage_title),
                    "0.4",
                    UiText.Res(R.string.set_pre_min_coverage_title),
                    "0.3",
                ),
            )
        assertEquals(expected, text)
    }

    @Test
    fun `a problem of another setting is prefixed with its title`() {
        val error = SettingsError("algo.hmm.meanBouts.awakeMin", SettingsErrorCode.OUT_OF_RANGE, "1.5", "> 2.0")
        val text = SettingsChecks.describe(FieldProblem.Invalid(error), editedPath = "algo.preprocess.epochSec")
        assertEquals(R.string.settings_error_other_field, (text as UiText.Res).id)
    }

    @Test
    fun `numbers are formatted without exponent and parsed with comma or dot`() {
        assertEquals("0.0001", formatSettingNumber(1e-4))
        assertEquals("20", formatSettingNumber(20.0))
        assertEquals("0.45", formatSettingNumber(0.45))
        assertEquals(0.5, parseSettingNumber(" 0,5 "))
        assertEquals(12.0, parseSettingNumber("12"))
        assertNull(parseSettingNumber("NaN"))
        assertNull(parseSettingNumber("1e400"))
        assertNull(parseSettingNumber("abc"))
    }
}
