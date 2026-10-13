package io.github.alien2003.pulsestages.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.ui.common.LoadingState
import io.github.alien2003.pulsestages.ui.common.MessageSnackbarEffect
import io.github.alien2003.pulsestages.ui.common.isWideLayout
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.theme.spacing

/** MIME types offered when importing settings (JSON files are often reported as octet-stream or plain text). */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")
private const val EXPORT_MIME_TYPE = "application/json"

/**
 * Callbacks of [SettingsScreen].
 *
 * @property onEdit an edit of one setting.
 * @property onExport pick a document and export the settings to it.
 * @property onImport pick a document and import settings from it.
 * @property onRequestReset open the reset confirmation.
 * @property onConfirmReset reset every setting.
 * @property onDismissReset close the reset confirmation.
 * @property onResetEmissions reset the HMM emission table to the physiology table.
 * @property onDismissImportErrors close the import error dialog.
 * @property onOpenSources open the Sources screen.
 * @property onOpenLog open the Log screen.
 * @property onMessageShown a Snackbar message was shown.
 * @property onMessageAction the action of a Snackbar message was tapped ("Recompute" the stale nights).
 */
data class SettingsActions(
    val onEdit: (SettingsEdit) -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onRequestReset: () -> Unit = {},
    val onConfirmReset: () -> Unit = {},
    val onDismissReset: () -> Unit = {},
    val onResetEmissions: () -> Unit = {},
    val onDismissImportErrors: () -> Unit = {},
    val onOpenSources: () -> Unit = {},
    val onOpenLog: () -> Unit = {},
    val onMessageShown: (Long) -> Unit = {},
    val onMessageAction: (Long) -> Unit = {},
)

/** Settings tab: every setting, grouped, plus export / import / reset and the links to Sources and Log. */
@Composable
fun SettingsRoute(
    onOpenSources: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME_TYPE)) { uri ->
            uri?.let { viewModel.exportTo(it.toString()) }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importFrom(it.toString()) }
        }
    SettingsScreen(
        state = state,
        actions =
            SettingsActions(
                onEdit = viewModel::onEdit,
                onExport = {
                    try {
                        exportLauncher.launch(SettingsViewModel.EXPORT_FILE_NAME)
                    } catch (ignored: ActivityNotFoundException) {
                        viewModel.onDocumentPickerUnavailable()
                    }
                },
                onImport = {
                    try {
                        importLauncher.launch(IMPORT_MIME_TYPES)
                    } catch (ignored: ActivityNotFoundException) {
                        viewModel.onDocumentPickerUnavailable()
                    }
                },
                onRequestReset = viewModel::requestReset,
                onConfirmReset = viewModel::confirmReset,
                onDismissReset = viewModel::dismissReset,
                onResetEmissions = viewModel::resetEmissions,
                onDismissImportErrors = viewModel::dismissImportErrors,
                onOpenSources = onOpenSources,
                onOpenLog = onOpenLog,
                onMessageShown = viewModel::messageShown,
                onMessageAction = viewModel::onMessageAction,
            ),
    )
}

/** Stateless Settings screen: one column on compact windows, two columns on wide ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.messages, snackbarHostState, actions.onMessageShown, actions.onMessageAction)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.loading) {
            LoadingState(Modifier.padding(innerPadding).fillMaxSize())
        } else {
            SettingsGrid(state, actions, Modifier.padding(innerPadding))
        }
    }
    if (state.confirmReset) ResetDialog(actions)
    state.importErrors?.let { ImportErrorsDialog(it, actions.onDismissImportErrors) }
}

@Composable
private fun SettingsGrid(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier,
) {
    val spacing = MaterialTheme.spacing
    val columns = if (isWideLayout()) 2 else 1
    var toggled by rememberSaveable { mutableStateOf(listOf<String>()) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        // imePadding keeps the focused number field above the keyboard (edge-to-edge windows are not resized).
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(spacing.medium),
        horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        verticalItemSpacing = spacing.medium,
    ) {
        item(key = "actions", span = StaggeredGridItemSpan.FullLine) {
            ActionsCard(state, actions)
        }
        items(SettingsCatalog.sections, key = { it.id }) { section ->
            val expanded = section.expandedByDefault != (section.id in toggled)
            SectionCard(
                section = section,
                expanded = expanded,
                onToggle = { toggled = if (section.id in toggled) toggled - section.id else toggled + section.id },
                state = state,
                actions = actions,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val spacing = MaterialTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(
                text = stringResource(R.string.settings_actions_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.settings_actions_hint), style = MaterialTheme.typography.bodySmall)
            if (state.loadFailed) {
                Text(
                    text = stringResource(R.string.settings_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                Button(onClick = actions.onExport, enabled = !state.busy) { Text(stringResource(R.string.settings_action_export)) }
                OutlinedButton(onClick = actions.onImport, enabled = !state.busy) { Text(stringResource(R.string.settings_action_import)) }
                OutlinedButton(onClick = actions.onRequestReset, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_action_reset))
                }
                OutlinedButton(onClick = actions.onOpenSources) { Text(stringResource(R.string.sources_title)) }
                OutlinedButton(onClick = actions.onOpenLog) { Text(stringResource(R.string.log_title)) }
            }
        }
    }
}

@Composable
private fun SectionCard(
    section: SettingsSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val spacing = MaterialTheme.spacing
    val title = section.title.resolve()
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(start = spacing.medium, end = spacing.small, top = spacing.small, bottom = spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    text = section.summary.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                    contentDescription =
                        stringResource(if (expanded) R.string.cd_collapse_section else R.string.cd_expand_section, title),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = spacing.medium, end = spacing.medium, bottom = spacing.medium),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                section.items.forEach { item -> SectionItem(item, state, actions) }
            }
        }
    }
}

@Composable
private fun SectionItem(
    item: SettingsItem,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    when (item) {
        is SettingsItem.Field -> {
            SettingField(item.spec, state, actions.onEdit)
        }

        is SettingsItem.Subheader -> {
            Column {
                HorizontalDivider()
                Text(
                    text = item.title.resolve(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.small).semantics { heading() },
                )
            }
        }

        SettingsItem.SourcesLink -> {
            LinkItem(
                title = stringResource(R.string.settings_sources_link_title),
                supporting = sourcesSummary(state.settings.sources.sourcePackages),
                onClick = actions.onOpenSources,
            )
        }

        SettingsItem.LogLink -> {
            LinkItem(
                title = stringResource(R.string.log_title),
                supporting = stringResource(R.string.settings_log_link_hint),
                onClick = actions.onOpenLog,
            )
        }

        is SettingsItem.EmissionTable -> {
            EmissionTableEditor(item, state, actions.onEdit, actions.onResetEmissions)
        }

        is SettingsItem.TransitionWeights -> {
            TransitionWeightsEditor(item, state, actions.onEdit)
        }
    }
}

@Composable
private fun sourcesSummary(packages: List<String>): String =
    if (packages.isEmpty()) {
        stringResource(R.string.settings_sources_none)
    } else {
        val names = packages.map { if (it == OHEALTH_PACKAGE) stringResource(R.string.source_label_ohealth) else it }
        stringResource(R.string.settings_sources_enabled, names.joinToString(stringResource(R.string.list_separator)))
    }

@Composable
private fun LinkItem(
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.cd_open, title))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ResetDialog(actions: SettingsActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissReset,
        title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
        text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
        confirmButton = { TextButton(onClick = actions.onConfirmReset) { Text(stringResource(R.string.settings_action_reset)) } },
        dismissButton = { TextButton(onClick = actions.onDismissReset) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ImportErrorsDialog(
    errors: List<SettingsError>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_errors_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                Text(pluralStringResource(R.plurals.settings_import_errors_count, errors.size, errors.size))
                errors.forEach { error ->
                    Text(SettingsChecks.describeWithTitle(error).resolve(), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
