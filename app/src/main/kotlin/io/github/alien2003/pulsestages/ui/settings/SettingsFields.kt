package io.github.alien2003.pulsestages.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.common.SingleChoice
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.theme.spacing
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private const val MINUTES_PER_HOUR = 60
private const val SLIDER_DECIMALS_SCALE = 100.0
private const val MAX_TICKED_VALUES = 25

/** Renders the control of [spec] with its title, hint, current value and validation problems. */
@Composable
fun SettingField(
    spec: SettingSpec,
    state: SettingsUiState,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val problems = state.problems[spec.path].orEmpty()
    when (spec) {
        is SwitchSpec -> {
            SwitchSetting(spec, spec.read(state.settings), problems, onEdit, modifier)
        }

        is ChoiceSpec -> {
            ChoiceSetting(spec, spec.read(state.settings), problems, onEdit, modifier)
        }

        is IntSpec -> {
            if (spec.slider) {
                IntSliderSetting(
                    spec,
                    spec.read(state.settings),
                    problems,
                    onEdit,
                    modifier,
                )
            } else {
                NumberSetting(spec, state, onEdit, modifier)
            }
        }

        is DoubleSpec -> {
            if (spec.sliderRange !=
                null
            ) {
                DoubleSliderSetting(spec, spec.read(state.settings), problems, onEdit, modifier)
            } else {
                NumberSetting(spec, state, onEdit, modifier)
            }
        }

        is TimeSpec -> {
            TimeSetting(spec, spec.read(state.settings), problems, onEdit, modifier)
        }

        is TextSpec, is OptionalDoubleSpec -> {
            NumberSetting(spec, state, onEdit, modifier)
        }

        is RangeSpec -> {
            RangeSetting(spec, spec.readMin(state.settings), spec.readMax(state.settings), problems, onEdit, modifier)
        }
    }
}

/**
 * A text field for a number or text setting. The typed text is kept locally (re-initialised only when the settings
 * are replaced as a whole) and every change is sent to the ViewModel, which stores valid values immediately.
 */
@Composable
fun NumberSetting(
    spec: SettingSpec,
    state: SettingsUiState,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String? = null,
) {
    var text by rememberSaveable(spec.path, state.revision) { mutableStateOf(state.textOf(spec)) }
    val problems = state.problems[spec.path].orEmpty()
    val title = spec.title.resolve()
    val keyboard =
        when (spec) {
            is IntSpec -> KeyboardType.Number
            is DoubleSpec, is OptionalDoubleSpec -> KeyboardType.Decimal
            else -> KeyboardType.Text
        }
    val placeholder =
        when (spec) {
            is TextSpec -> spec.placeholder
            is OptionalDoubleSpec -> UiText.of(R.string.settings_same_as_nights_value, formatSettingNumber(spec.inherited(state.settings)))
            else -> null
        }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onEdit(SettingsEdit.Text(spec.path, it))
        },
        modifier = modifier.fillMaxWidth().semantics { if (label != null) contentDescription = title },
        label = { Text(label ?: title) },
        placeholder = placeholder?.let { { Text(it.resolve()) } },
        suffix = unitOf(spec)?.let { { Text(it) } },
        singleLine = spec !is TextSpec,
        isError = problems.isNotEmpty(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        supportingText =
            if (compact && problems.isEmpty()) {
                null
            } else {
                { SupportingText(spec, problems, showHint = !compact) }
            },
    )
}

@Composable
private fun unitOf(spec: SettingSpec): String? =
    when (spec) {
        is IntSpec -> spec.unit?.resolve()
        is DoubleSpec -> spec.unit?.resolve()
        is OptionalDoubleSpec -> spec.unit?.resolve()
        else -> null
    }

@Composable
private fun boundsOf(spec: SettingSpec): String? =
    when (spec) {
        is IntSpec -> spec.range?.let { stringResource(R.string.bounds_range, it.first.toString(), it.last.toString()) }
        is DoubleSpec -> spec.bounds?.resolve()
        is OptionalDoubleSpec -> spec.bounds?.resolve()
        else -> null
    }

/** Problems (in the error colour), the hint and the accepted values of [spec]. */
@Composable
private fun SupportingText(
    spec: SettingSpec,
    problems: List<FieldProblem>,
    showHint: Boolean = true,
) {
    Column {
        ProblemTexts(spec.path, problems)
        if (showHint) {
            Text(spec.hint.resolve())
            boundsOf(spec)?.let { Text(stringResource(R.string.settings_allowed_values, it)) }
        }
    }
}

/** The validation problems of the value at [path], in the error colour. */
@Composable
fun ProblemTexts(
    path: String,
    problems: List<FieldProblem>,
) {
    problems.forEach { problem ->
        Text(
            text = SettingsChecks.describe(problem, path).resolve(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Title, hint and problems above or beside a control. */
@Composable
private fun SettingHeader(
    spec: SettingSpec,
    problems: List<FieldProblem>,
    value: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = spec.title.resolve(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(text = value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(text = spec.hint.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProblemTexts(spec.path, problems)
    }
}

@Composable
private fun SwitchSetting(
    spec: SwitchSpec,
    checked: Boolean,
    problems: List<FieldProblem>,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch) { onEdit(SettingsEdit.Switch(spec.path, it)) }
                .padding(vertical = MaterialTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Column(modifier = Modifier.weight(1f)) { SettingHeader(spec, problems) }
        // The whole row is the toggleable element; the Switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSetting(
    spec: ChoiceSpec,
    selected: String,
    problems: List<FieldProblem>,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        when (spec.style) {
            ChoiceStyle.SEGMENTED -> {
                SettingHeader(spec, problems)
                // Segmented buttons, or radio buttons when a label does not fit (a translation, a large font).
                SingleChoice(
                    labels = spec.options.map { it.label.resolve() },
                    selected = spec.options.indexOfFirst { it.key == selected },
                    onSelect = { index -> onEdit(SettingsEdit.Choice(spec.path, spec.options[index].key)) },
                )
            }

            ChoiceStyle.DROPDOWN -> {
                Dropdown(spec, selected, onEdit)
                Text(
                    text = spec.hint.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProblemTexts(spec.path, problems)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    spec: ChoiceSpec,
    selected: String,
    onEdit: (SettingsEdit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current =
        spec.options
            .firstOrNull { it.key == selected }
            ?.label
            ?.resolve() ?: selected
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(spec.title.resolve()) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            spec.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label.resolve()) },
                    onClick = {
                        expanded = false
                        onEdit(SettingsEdit.Choice(spec.path, option.key))
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun IntSliderSetting(
    spec: IntSpec,
    stored: Int,
    problems: List<FieldProblem>,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier,
) {
    val range = spec.range ?: (stored..stored)
    val step = if (spec.oddOnly) 2 else 1
    val first = if (spec.oddOnly && range.first % 2 == 0) range.first + 1 else range.first
    val count = (range.last - first) / step + 1
    var value by remember(spec.path, stored) { mutableFloatStateOf(stored.toFloat()) }
    val snapped = snapInt(value, first, range.last, step)
    val label = valueWithUnit(snapped.toString(), spec.unit?.resolve())
    Column(modifier = modifier.fillMaxWidth()) {
        SettingHeader(spec, problems, value = label)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onEdit(SettingsEdit.IntValue(spec.path, snapInt(value, first, range.last, step))) },
            valueRange = first.toFloat()..range.last.toFloat(),
            steps = if (count <= MAX_TICKED_VALUES) (count - 2).coerceAtLeast(0) else 0,
            modifier = Modifier.semantics { stateDescription = label },
        )
    }
}

private fun snapInt(
    value: Float,
    first: Int,
    last: Int,
    step: Int,
): Int = (first + ((value - first) / step).roundToInt() * step).coerceIn(first, last)

@Composable
private fun DoubleSliderSetting(
    spec: DoubleSpec,
    stored: Double,
    problems: List<FieldProblem>,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier,
) {
    val range = spec.sliderRange ?: (stored..stored)
    var value by remember(spec.path, stored) { mutableFloatStateOf(stored.toFloat()) }
    val label = valueWithUnit(formatSettingNumber(roundShare(value)), spec.unit?.resolve())
    Column(modifier = modifier.fillMaxWidth()) {
        SettingHeader(spec, problems, value = label)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onEdit(SettingsEdit.DoubleValue(spec.path, roundShare(value))) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.semantics { stateDescription = label },
        )
    }
}

@Composable
private fun RangeSetting(
    spec: RangeSpec,
    storedMin: Double,
    storedMax: Double,
    problems: List<FieldProblem>,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier,
) {
    var value by remember(spec.path, storedMin, storedMax) { mutableStateOf(storedMin.toFloat()..storedMax.toFloat()) }
    val label =
        stringResource(
            R.string.format_range,
            formatSettingNumber(roundShare(value.start)),
            formatSettingNumber(roundShare(value.endInclusive)),
        )
    Column(modifier = modifier.fillMaxWidth()) {
        SettingHeader(spec, problems, value = label)
        RangeSlider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                onEdit(SettingsEdit.Range(spec.path, roundShare(value.start), roundShare(value.endInclusive)))
            },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { stateDescription = label },
        )
    }
}

private fun roundShare(value: Float): Double = (value * SLIDER_DECIMALS_SCALE).roundToInt() / SLIDER_DECIMALS_SCALE

@Composable
private fun valueWithUnit(
    value: String,
    unit: String?,
): String = if (unit == null) value else stringResource(R.string.format_value_unit, value, unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSetting(
    spec: TimeSpec,
    minutes: Int,
    problems: List<FieldProblem>,
    onEdit: (SettingsEdit) -> Unit,
    modifier: Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val time = LocalTime.of(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
    val formatted = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val description = stringResource(R.string.format_label_value, spec.title.resolve(), formatted)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        SettingHeader(spec, problems)
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = description },
        ) { Text(formatted) }
    }
    if (open) {
        val pickerState =
            rememberTimePickerState(
                initialHour = time.hour,
                initialMinute = time.minute,
                is24Hour = DateFormat.is24HourFormat(LocalContext.current),
            )
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(spec.title.resolve()) },
            text = { TimeInput(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onEdit(SettingsEdit.Time(spec.path, pickerState.hour * MINUTES_PER_HOUR + pickerState.minute))
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
