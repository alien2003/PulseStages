package io.github.alien2003.pulsestages.ui.settings

import androidx.compose.runtime.Immutable
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.params.AlgoParamsValidator
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.data.local.settings.SettingsErrorCode
import io.github.alien2003.pulsestages.data.local.settings.SettingsValidator
import io.github.alien2003.pulsestages.ui.common.UiText
import java.math.BigDecimal

/** Why an edited value was not stored. */
@Immutable
sealed interface FieldProblem {
    /**
     * The text is not a number.
     *
     * @property integer a whole number was expected.
     */
    data class NotANumber(
        val integer: Boolean,
    ) : FieldProblem

    /**
     * The value violates a validation rule.
     *
     * @property error the rule that failed (it may concern a related value, e.g. a minimum above its maximum).
     */
    data class Invalid(
        val error: SettingsError,
    ) : FieldProblem
}

/**
 * Validation used by the Settings screen and the import: [SettingsValidator] (which guards the store) plus
 * [AlgoParamsValidator] for the few algorithm values the settings validator does not cover, so that nothing is
 * stored that the estimators would reject. Errors are reported with settings paths (`algo.` prefix), each path once.
 */
object SettingsChecks {
    private const val ALGO_PREFIX = "algo."
    private const val ODD_PREFIX = "odd, "
    private const val FINITE_CONSTRAINT = "a finite number"
    private val MIN_ABOVE_MAX = Regex("""<= (\S+) \((.*)\)""")

    /** Every problem of [settings]; empty when it may be stored. */
    fun validate(settings: AppSettings): List<SettingsError> {
        val primary = SettingsValidator.validate(settings).errors
        val reported = primary.map { it.path }.toSet()
        val secondary =
            AlgoParamsValidator
                .problems(settings.algo)
                .map { problem ->
                    val code =
                        when {
                            problem.constraint == FINITE_CONSTRAINT -> SettingsErrorCode.NOT_FINITE
                            problem.constraint.startsWith("<= ") -> SettingsErrorCode.MIN_ABOVE_MAX
                            problem.constraint.startsWith("one of ") -> SettingsErrorCode.NOT_ALLOWED
                            else -> SettingsErrorCode.OUT_OF_RANGE
                        }
                    SettingsError(ALGO_PREFIX + problem.path, code, problem.value, problem.constraint)
                }.filter { it.path !in reported }
        return primary + secondary
    }

    /** Localized description of [problem] shown under the control that edits [editedPath]. */
    fun describe(
        problem: FieldProblem,
        editedPath: String,
    ): UiText =
        when (problem) {
            is FieldProblem.NotANumber -> {
                UiText.of(if (problem.integer) R.string.settings_error_not_integer else R.string.settings_error_not_number)
            }

            is FieldProblem.Invalid -> {
                val base = describe(problem.error)
                val error = problem.error
                if (error.path == editedPath || error.path == "$" || error.code == SettingsErrorCode.MIN_ABOVE_MAX) {
                    base
                } else {
                    UiText.of(R.string.settings_error_other_field, titleOf(error.path), base)
                }
            }
        }

    /** Localized one-line description of [error] including the name of the setting (import error list). */
    fun describeWithTitle(error: SettingsError): UiText =
        if (error.path == "$" || error.code == SettingsErrorCode.MIN_ABOVE_MAX) {
            describe(error)
        } else {
            UiText.of(R.string.settings_error_other_field, titleOf(error.path), describe(error))
        }

    /** Localized description of [error] without the setting's name. */
    fun describe(error: SettingsError): UiText {
        val constraint = error.constraint
        return when (error.code) {
            SettingsErrorCode.MALFORMED_JSON -> {
                UiText.of(R.string.settings_error_malformed, constraint)
            }

            SettingsErrorCode.OUT_OF_RANGE, SettingsErrorCode.NOT_ODD -> {
                if (constraint.startsWith(ODD_PREFIX)) {
                    UiText.of(R.string.settings_error_not_odd, pretty(constraint.removePrefix(ODD_PREFIX)))
                } else {
                    UiText.of(R.string.settings_error_out_of_range, pretty(constraint))
                }
            }

            SettingsErrorCode.NOT_ALLOWED -> {
                UiText.of(R.string.settings_error_not_allowed, constraint.removePrefix("one of "))
            }

            SettingsErrorCode.NOT_FINITE -> {
                UiText.of(R.string.settings_error_not_finite)
            }

            SettingsErrorCode.MIN_ABOVE_MAX -> {
                minAboveMax(error)
            }

            SettingsErrorCode.INVALID_PACKAGE_NAME -> {
                UiText.of(R.string.settings_error_package, error.value)
            }

            SettingsErrorCode.BLANK -> {
                UiText.of(R.string.settings_error_blank)
            }

            SettingsErrorCode.TOO_LONG -> {
                UiText.of(R.string.settings_error_too_long, pretty(constraint))
            }
        }
    }

    /** Title of the setting at [path] (the path itself when it is not editable here). */
    fun titleOf(path: String): UiText {
        val normalized = if (path.startsWith(ALGO_PREFIX) || SettingsCatalog.spec(path) != null) path else ALGO_PREFIX + path
        return (SettingsCatalog.spec(normalized) ?: SettingsCatalog.spec(path))?.title ?: UiText.Verbatim(path)
    }

    private fun minAboveMax(error: SettingsError): UiText {
        val match = MIN_ABOVE_MAX.matchEntire(error.constraint)
        val highPath = match?.groupValues?.get(1) ?: error.constraint
        val highValue = match?.groupValues?.get(2).orEmpty()
        return UiText.of(
            R.string.settings_error_min_above_max,
            titleOf(error.path),
            pretty(error.value),
            titleOf(highPath),
            pretty(highValue),
        )
    }

    /** Language-neutral constraint notation in a readable form: `10..720` → `10–720`, `>= 0.0` → `≥ 0`. */
    fun pretty(constraint: String): String =
        constraint
            .replace(Regex("""-?\d+(\.\d+)?(E-?\d+)?""")) { number -> formatNumber(number.value) }
            .replace("..", "–")
            .replace(">=", "≥")
            .replace("<=", "≤")

    private fun formatNumber(text: String): String = text.toBigDecimalOrNull()?.let(::plain) ?: text

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}

/** The value of a decimal setting as text: no exponent, no trailing zeros (`1.0E-4` → `0.0001`, `20.0` → `20`). */
fun formatSettingNumber(value: Double): String =
    if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else value.toString()

/** Parses a decimal typed by the user (comma or dot as decimal separator); null unless it is a finite number. */
fun parseSettingNumber(text: String): Double? =
    text
        .trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() }
