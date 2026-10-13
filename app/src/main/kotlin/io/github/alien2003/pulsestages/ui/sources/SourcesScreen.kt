package io.github.alien2003.pulsestages.ui.sources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import io.github.alien2003.pulsestages.ui.common.ErrorBanner
import io.github.alien2003.pulsestages.ui.common.LoadingState
import io.github.alien2003.pulsestages.ui.common.MessageSnackbarEffect
import io.github.alien2003.pulsestages.ui.common.formatDate
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.theme.spacing

/** Sources screen (opened from Settings). */
@Composable
fun SourcesRoute(
    onBack: () -> Unit,
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SourcesScreen(
        state = state,
        actions =
            SourcesActions(
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onToggle = viewModel::setEnabled,
                onMoveUp = viewModel::moveUp,
                onMoveDown = viewModel::moveDown,
                onRemove = viewModel::removeManual,
                onManualInput = viewModel::onManualInput,
                onAddManual = viewModel::addManual,
                onHrSourcesAny = viewModel::setHrSourcesAny,
                onMessageShown = viewModel::messageShown,
            ),
    )
}

/**
 * Callbacks of [SourcesScreen].
 *
 * @property onBack navigate up.
 * @property onRefresh scan again.
 * @property onToggle enable or disable a package.
 * @property onMoveUp raise a package's priority.
 * @property onMoveDown lower a package's priority.
 * @property onRemove remove a manually added package.
 * @property onManualInput the "add package" text changed.
 * @property onAddManual add the typed package.
 * @property onHrSourcesAny heart rate and steps from any app (true) or the sleep app (false).
 * @property onMessageShown a Snackbar message was shown.
 */
data class SourcesActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onMoveUp: (String) -> Unit = {},
    val onMoveDown: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onManualInput: (String) -> Unit = {},
    val onAddManual: () -> Unit = {},
    val onHrSourcesAny: (Boolean) -> Unit = {},
    val onMessageShown: (Long) -> Unit = {},
)

/** Stateless Sources screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    state: SourcesUiState,
    actions: SourcesActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.messages, snackbarHostState, actions.onMessageShown)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding).fillMaxSize()
        // A failed scan does not hide the configured sources: the error is shown above them (with Retry).
        if (state.loading && state.items.isEmpty()) LoadingState(contentModifier) else SourcesList(state, actions, contentModifier)
    }
}

private fun SourcesError.messageRes(): Int =
    when (this) {
        SourcesError.UNAVAILABLE -> R.string.sources_error_unavailable
        SourcesError.PERMISSION -> R.string.sources_error_permission
        SourcesError.FAILED -> R.string.sources_error_failed
    }

@Composable
private fun SourcesList(
    state: SourcesUiState,
    actions: SourcesActions,
    modifier: Modifier,
) {
    val spacing = MaterialTheme.spacing
    val movable = state.movable
    LazyColumn(modifier = modifier.imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        item(key = "header") {
            Column(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(spacing.medium),
                verticalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                state.error?.let { error ->
                    ErrorBanner(stringResource(error.messageRes()), onRetry = actions.onRefresh.takeUnless { state.loading })
                }
                Text(
                    text =
                        if (!state.scanned) {
                            stringResource(R.string.sources_not_scanned)
                        } else if (state.fullHistory) {
                            stringResource(R.string.sources_scanned_full)
                        } else {
                            pluralStringResource(R.plurals.sources_scanned_days, state.scannedDays.toInt(), state.scannedDays.toInt())
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(R.string.sources_priority_hint), style = MaterialTheme.typography.bodySmall)
                HrSourcesCard(state.hrSources, actions.onHrSourcesAny)
                if (movable.isEmpty()) {
                    Text(text = stringResource(R.string.sources_empty), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        itemsIndexed(state.items, key = { _, item -> item.packageName }) { index, item ->
            Column(modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
                SourceRow(
                    item = item,
                    canMoveUp = !item.isOwn && index > 0,
                    canMoveDown = !item.isOwn && index < movable.lastIndex,
                    actions = actions,
                )
                HorizontalDivider()
            }
        }
        item(key = "manual") {
            ManualAdd(state, actions, Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(spacing.medium))
        }
    }
}

@Composable
private fun HrSourcesCard(
    mode: HrSourcesMode,
    onAny: (Boolean) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val any = mode == HrSourcesMode.ANY
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = any, role = Role.Switch, onValueChange = onAny)
                    .padding(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.sources_hr_any_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(if (any) R.string.sources_hr_any_on else R.string.sources_hr_any_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = any, onCheckedChange = null)
        }
    }
}

@Composable
private fun SourceRow(
    item: SourceItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    actions: SourcesActions,
) {
    val spacing = MaterialTheme.spacing
    val name = displayName(item)
    Column(modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.small)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
            AppIcon(item, name)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge)
                if (name != item.packageName) {
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = statsText(item), style = MaterialTheme.typography.bodySmall)
            }
            if (!item.isOwn) {
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { actions.onToggle(item.packageName, it) },
                    modifier = Modifier.semantics { this.contentDescription = name },
                )
            }
        }
        if (!item.isOwn) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (item.removable) {
                    IconButton(onClick = { actions.onRemove(item.packageName) }) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.cd_remove_source, name))
                    }
                }
                IconButton(onClick = { actions.onMoveUp(item.packageName) }, enabled = canMoveUp) {
                    Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.cd_move_up, name))
                }
                IconButton(onClick = { actions.onMoveDown(item.packageName) }, enabled = canMoveDown) {
                    Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.cd_move_down, name))
                }
            }
        }
    }
}

@Composable
private fun AppIcon(
    item: SourceItem,
    name: String,
) {
    val description = stringResource(R.string.cd_app_icon, name)
    val icon = item.icon
    if (icon != null) {
        Image(bitmap = icon, contentDescription = description, modifier = Modifier.size(40.dp))
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_apps),
            contentDescription = description,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun displayName(item: SourceItem): String =
    when {
        item.isOwn -> stringResource(R.string.sources_own_label)
        item.label != null -> item.label
        item.packageName == OHEALTH_PACKAGE -> stringResource(R.string.source_label_ohealth)
        else -> item.packageName
    }

@Composable
private fun statsText(item: SourceItem): String {
    val last = item.lastSessionEndMs
    val count = item.sessionCount
    return when {
        item.isOwn -> stringResource(R.string.sources_own_hint)
        count == null -> stringResource(R.string.sources_sessions_unknown)
        last == null -> stringResource(R.string.sources_no_sessions)
        else -> pluralStringResource(R.plurals.sources_sessions, count, count, localDateTime(last).formatDate())
    }
}

@Composable
private fun ManualAdd(
    state: SourcesUiState,
    actions: SourcesActions,
    modifier: Modifier,
) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        Text(
            text = stringResource(R.string.sources_manual_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedTextField(
            value = state.manualInput,
            onValueChange = actions.onManualInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.sources_manual_label)) },
            placeholder = { Text(stringResource(R.string.sources_manual_placeholder)) },
            singleLine = true,
            isError = state.manualError != null,
            supportingText = { Text(state.manualError?.resolve() ?: stringResource(R.string.sources_manual_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onAddManual() }),
        )
        Button(onClick = actions.onAddManual) { Text(stringResource(R.string.sources_manual_add)) }
    }
}
