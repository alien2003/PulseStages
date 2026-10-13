package io.github.alien2003.pulsestages.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.theme.spacing

/** The "Debug build" card of the Settings tab with [viewModel]'s demo-mode switch (debug builds only). */
@Composable
fun DemoModeSettingsCard(
    modifier: Modifier = Modifier,
    viewModel: DemoModeViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    DemoModeCard(enabled = enabled, onEnabledChange = viewModel::setEnabled, modifier = modifier)
}

/**
 * Stateless "Debug build" card: the demo-mode switch with a short explanation. The whole row toggles it; the
 * Nights, night detail and Compare screens follow at once and show a banner while it is on.
 */
@Composable
fun DemoModeCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(
                text = stringResource(R.string.demo_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)
                        .padding(vertical = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.demo_mode_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.demo_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The whole row is the toggleable element; the Switch only shows the state.
                Switch(checked = enabled, onCheckedChange = null)
            }
        }
    }
}
