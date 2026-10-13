package io.github.alien2003.pulsestages.ui.settings

import androidx.compose.runtime.Immutable
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.ui.common.UiText

/**
 * One editable setting. [path] is the JSON path of the value inside [AppSettings] (the same path that
 * [io.github.alien2003.pulsestages.data.local.settings.SettingsValidator] reports errors for), so errors can be shown
 * next to the control that edits the value.
 */
@Immutable
sealed interface SettingSpec {
    /** JSON path of the edited value (the first one for specs that edit two values). */
    val path: String

    /** Every JSON path this spec edits. */
    val paths: List<String> get() = listOf(path)

    /** Short name of the setting. */
    val title: UiText

    /** One-sentence explanation shown below the control. */
    val hint: UiText
}

/**
 * A boolean edited with a Switch.
 *
 * @property read current value.
 * @property write settings with the new value.
 */
class SwitchSpec(
    override val path: String,
    override val title: UiText,
    override val hint: UiText,
    val read: (AppSettings) -> Boolean,
    val write: (AppSettings, Boolean) -> AppSettings,
) : SettingSpec

/**
 * One option of a [ChoiceSpec].
 *
 * @property key stable key of the option (enum name or number).
 * @property label text of the option.
 */
@Immutable
data class ChoiceOption(
    val key: String,
    val label: UiText,
)

/** How a [ChoiceSpec] is shown. */
enum class ChoiceStyle {
    /** Segmented buttons (two or three short options). */
    SEGMENTED,

    /** Exposed dropdown menu. */
    DROPDOWN,
}

/**
 * One value out of a fixed set.
 *
 * @property options the options in display order.
 * @property style segmented buttons or a dropdown.
 * @property read key of the current option.
 * @property write settings with the option of the given key.
 */
class ChoiceSpec(
    override val path: String,
    override val title: UiText,
    override val hint: UiText,
    val options: List<ChoiceOption>,
    val style: ChoiceStyle,
    val read: (AppSettings) -> String,
    val write: (AppSettings, String) -> AppSettings,
) : SettingSpec

/**
 * A whole number, edited with a slider (small ranges) or a number field.
 *
 * @property range the inclusive range the validator accepts (also the slider range).
 * @property oddOnly only odd values are valid (window lengths); sliders then step by two.
 * @property slider show a slider instead of a number field.
 * @property unit unit shown with the value, if any.
 * @property read current value.
 * @property write settings with the new value.
 */
class IntSpec(
    override val path: String,
    override val title: UiText,
    override val hint: UiText,
    val range: IntRange?,
    val read: (AppSettings) -> Int,
    val write: (AppSettings, Int) -> AppSettings,
    val oddOnly: Boolean = false,
    val slider: Boolean = false,
    val unit: UiText? = null,
) : SettingSpec

/**
 * A decimal number, edited with a slider (bounded shares and probabilities) or a number field.
 *
 * @property sliderRange slider range, or null for a number field.
 * @property bounds human-readable accepted values for the number field ("≥ 0", "> 0", "0–100"), or null.
 * @property unit unit shown with the value, if any.
 * @property read current value.
 * @property write settings with the new value.
 */
class DoubleSpec(
    override val path: String,
    override val title: UiText,
    override val hint: UiText,
    val read: (AppSettings) -> Double,
    val write: (AppSettings, Double) -> AppSettings,
    val sliderRange: ClosedFloatingPointRange<Double>? = null,
    val bounds: UiText? = null,
    val unit: UiText? = null,
) : SettingSpec

/**
 * A local time of day in minutes after midnight, edited with a time picker.
 *
 * @property read current value.
 * @property write settings with the new value.
 */
class TimeSpec(
    override val path: String,
    override val title: UiText,
    override val hint: UiText,
    val read: (AppSettings) -> Int,
    val write: (AppSettings, Int) -> AppSettings,
) : SettingSpec

/**
 * Optional free text; empty text means "use the default" (null).
 *
 * @property placeholder shown when the value is null.
 * @property read current value.
 * @property write settings with the new value.
 */
class TextSpec(
    override val path: String,
    override val title: UiText,
    override val hint: UiText,
    val placeholder: UiText,
    val read: (AppSettings) -> String?,
    val write: (AppSettings, String?) -> AppSettings,
) : SettingSpec

/**
 * A range of shares (0..1) edited with a range slider.
 *
 * @property maxPath JSON path of the upper bound ([path] is the lower bound).
 * @property readMin lower bound.
 * @property readMax upper bound.
 * @property write settings with both bounds.
 */
class RangeSpec(
    override val path: String,
    val maxPath: String,
    override val title: UiText,
    override val hint: UiText,
    val readMin: (AppSettings) -> Double,
    val readMax: (AppSettings) -> Double,
    val write: (AppSettings, Double, Double) -> AppSettings,
) : SettingSpec {
    override val paths: List<String> get() = listOf(path, maxPath)
}

/**
 * One cell row of the HMM emission table editor: the mean and the standard deviation of one feature of one state.
 *
 * @property feature feature name (hrNorm, hrVar, hrSlope).
 * @property mean editor of μ.
 * @property sd editor of σ.
 */
@Immutable
data class EmissionCell(
    val feature: UiText,
    val mean: DoubleSpec,
    val sd: DoubleSpec,
)

/**
 * One state (row) of the emission table.
 *
 * @property state the state's name.
 * @property cells one entry per feature.
 */
@Immutable
data class EmissionRow(
    val state: UiText,
    val cells: List<EmissionCell>,
)

/**
 * The transition weights leaving one state.
 *
 * @property from the state's name.
 * @property weights one editor per target state.
 */
@Immutable
data class TransitionRow(
    val from: UiText,
    val weights: List<DoubleSpec>,
)

/** One block of a settings section. */
@Immutable
sealed interface SettingsItem {
    /**
     * A single setting.
     *
     * @property spec the setting.
     */
    data class Field(
        val spec: SettingSpec,
    ) : SettingsItem

    /**
     * A small heading inside a section.
     *
     * @property title the heading.
     */
    data class Subheader(
        val title: UiText,
    ) : SettingsItem

    /**
     * Summary of the enabled sources with a link to the Sources screen (sourcePackages, sourcePriority and
     * manualPackages are edited there).
     */
    data object SourcesLink : SettingsItem

    /** Link to the internal log. */
    data object LogLink : SettingsItem

    /**
     * The 4 × 3 HMM emission table (μ and σ per state and feature) with "Reset to physiology table".
     *
     * @property hint explanation of the table.
     * @property rows one row per state.
     */
    data class EmissionTable(
        val hint: UiText,
        val rows: List<EmissionRow>,
    ) : SettingsItem

    /**
     * The HMM transition weights (non-self transition mass shares).
     *
     * @property hint explanation of the weights.
     * @property rows one row per source state.
     */
    data class TransitionWeights(
        val hint: UiText,
        val rows: List<TransitionRow>,
    ) : SettingsItem
}

/**
 * A titled group of settings.
 *
 * @property id stable id (saved expanded state).
 * @property title section title.
 * @property summary one line below the title.
 * @property items the content.
 * @property expandedByDefault whether the section starts expanded (the everyday settings) or collapsed (algorithm
 *   internals).
 */
@Immutable
data class SettingsSection(
    val id: String,
    val title: UiText,
    val summary: UiText,
    val items: List<SettingsItem>,
    val expandedByDefault: Boolean,
)

/** Every setting spec of [items], including the cells of tables. */
fun List<SettingsItem>.specs(): List<SettingSpec> =
    flatMap { item ->
        when (item) {
            is SettingsItem.Field -> listOf(item.spec)
            is SettingsItem.EmissionTable -> item.rows.flatMap { row -> row.cells.flatMap { listOf(it.mean, it.sd) } }
            is SettingsItem.TransitionWeights -> item.rows.flatMap { it.weights }
            is SettingsItem.Subheader, SettingsItem.SourcesLink, SettingsItem.LogLink -> emptyList()
        }
    }
