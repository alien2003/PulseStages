package io.github.alien2003.pulsestages.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.alien2003.pulsestages.ui.theme.spacing

/** Content padding of a segmented button on each side (Material 3's text-button padding). */
private val SEGMENT_PADDING: Dp = 12.dp

/** Space between the check icon of a selected segment and its label. */
private val SEGMENT_ICON_SPACING: Dp = 8.dp

/** The outline of a segment (both sides). */
private val SEGMENT_OUTLINE: Dp = 2.dp

/**
 * Width a segmented button needs besides its label: the content padding on both sides, the check icon and its spacing
 * shown when the segment is selected (Material 3 measures the label with the whole inner width and places it after
 * the icon), and the outline.
 */
private val SEGMENT_CHROME: Dp = SEGMENT_PADDING * 2 + SegmentedButtonDefaults.IconSize + SEGMENT_ICON_SPACING + SEGMENT_OUTLINE

/** Minimum height of a row of the vertical fallback (a touch target). */
private val ROW_MIN_HEIGHT: Dp = 48.dp

/**
 * One choice out of [labels] (the option at [selected] is chosen; [onSelect] gets an index): segmented buttons when
 * every label fits on one line in its share of the width with the selected segment's check icon, otherwise a vertical
 * list of radio buttons, so that a long translation or a large font never splits a label inside a segment. The
 * label's text style is the one Material 3 uses for segmented buttons (`labelLarge`), measured with the current font
 * scale.
 */
@Composable
fun SingleChoice(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val available = with(density) { (maxWidth / labels.size.coerceAtLeast(1) - SEGMENT_CHROME).toPx() }
        val fits =
            remember(labels, available, style, density) {
                labels.all { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= available }
            }
        if (fits) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                        enabled = enabled(index),
                    ) { Text(label, maxLines = 1) }
                }
            }
        } else {
            RadioList(labels, selected, onSelect, enabled)
        }
    }
}

@Composable
private fun RadioList(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: (Int) -> Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        labels.forEachIndexed { index, label ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROW_MIN_HEIGHT)
                        .selectable(
                            selected = index == selected,
                            enabled = enabled(index),
                            role = Role.RadioButton,
                            onClick = { onSelect(index) },
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                // The row is the selectable element; the radio button only shows the state.
                RadioButton(selected = index == selected, onClick = null, enabled = enabled(index))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
