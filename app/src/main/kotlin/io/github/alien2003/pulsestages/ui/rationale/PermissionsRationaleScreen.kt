package io.github.alien2003.pulsestages.ui.rationale

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import io.github.alien2003.pulsestages.ui.theme.spacing

/**
 * Privacy policy and permission rationale: what PulseStages reads and writes, that nothing leaves the device and how
 * to delete its data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsRationaleScreen(
    onClose: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rationale_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
    ) { innerPadding ->
        val spacing = MaterialTheme.spacing
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Text(text = stringResource(R.string.rationale_intro), style = MaterialTheme.typography.bodyLarge)
                RationaleSection(R.string.rationale_read_title, READ_ITEMS)
                RationaleSection(R.string.rationale_write_title, WRITE_ITEMS)
                RationaleSection(R.string.rationale_device_title, DEVICE_ITEMS)
                RationaleSection(R.string.rationale_delete_title, DELETE_ITEMS)
                Actions(onOpenHealthConnect = onOpenHealthConnect, onOpenApp = onOpenApp)
            }
        }
    }
}

@Composable
private fun RationaleSection(
    @StringRes title: Int,
    items: List<Int>,
) {
    val spacing = MaterialTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                    Text(text = stringResource(R.string.rationale_bullet), style = MaterialTheme.typography.bodyMedium)
                    Text(text = stringResource(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    onOpenHealthConnect: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Button(onClick = onOpenApp) { Text(stringResource(R.string.rationale_action_open_app)) }
        OutlinedButton(onClick = onOpenHealthConnect) { Text(stringResource(R.string.home_action_open_hc)) }
    }
}

private val READ_ITEMS =
    listOf(
        R.string.rationale_read_sleep,
        R.string.rationale_read_heart_rate,
        R.string.rationale_read_steps,
        R.string.rationale_read_optional,
        R.string.rationale_read_history,
        R.string.rationale_read_background,
    )

private val WRITE_ITEMS = listOf(R.string.rationale_write_sessions, R.string.rationale_write_never_others)

private val DEVICE_ITEMS =
    listOf(R.string.rationale_device_no_internet, R.string.rationale_device_local, R.string.rationale_device_saa_request)

private val DELETE_ITEMS =
    listOf(R.string.rationale_delete_in_app, R.string.rationale_delete_health_connect, R.string.rationale_delete_uninstall)

@Preview(showBackground = true)
@Composable
private fun PermissionsRationaleScreenPreview() {
    PulseStagesTheme(dynamicColor = false) {
        PermissionsRationaleScreen(onClose = {}, onOpenHealthConnect = {}, onOpenApp = {})
    }
}
