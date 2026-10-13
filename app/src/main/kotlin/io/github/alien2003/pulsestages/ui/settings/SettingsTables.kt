package io.github.alien2003.pulsestages.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.theme.spacing

private const val FEATURE_LABEL_WEIGHT = 0.8f

/**
 * Editor of the 4 × 3 HMM emission table: one block per state, one line per feature with the mean μ and the standard
 * deviation σ, and the "Reset to physiology table" button. Problems of a cell are shown below its line.
 */
@Composable
fun EmissionTableEditor(
    table: SettingsItem.EmissionTable,
    state: SettingsUiState,
    onEdit: (SettingsEdit) -> Unit,
    onResetEmissions: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(text = table.hint.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        table.rows.forEach { row ->
            Text(
                text = row.state.resolve(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = spacing.small).semantics { heading() },
            )
            row.cells.forEach { cell ->
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        Text(
                            text = cell.feature.resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(FEATURE_LABEL_WEIGHT),
                        )
                        NumberSetting(
                            spec = cell.mean,
                            state = state,
                            onEdit = onEdit,
                            modifier = Modifier.weight(1f),
                            compact = true,
                            label = stringResource(R.string.label_mean),
                        )
                        NumberSetting(
                            spec = cell.sd,
                            state = state,
                            onEdit = onEdit,
                            modifier = Modifier.weight(1f),
                            compact = true,
                            label = stringResource(R.string.label_sd),
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onResetEmissions) { Text(stringResource(R.string.settings_action_reset_emissions)) }
    }
}

/**
 * Editor of the HMM transition weights: one block per source state with a field per target state (fields wrap on
 * narrow screens and with large fonts).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransitionWeightsEditor(
    weights: SettingsItem.TransitionWeights,
    state: SettingsUiState,
    onEdit: (SettingsEdit) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(text = weights.hint.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        weights.rows.forEach { row ->
            Text(
                text = stringResource(R.string.settings_transitions_from, row.from.resolve()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                row.weights.forEach { spec ->
                    NumberSetting(
                        spec = spec,
                        state = state,
                        onEdit = onEdit,
                        modifier = Modifier.widthIn(min = 112.dp).weight(1f),
                        compact = true,
                        label = spec.title.resolve(),
                    )
                }
            }
        }
    }
}
