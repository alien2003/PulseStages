package io.github.alien2003.pulsestages.ui.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.platform.startFirstAvailable
import io.github.alien2003.pulsestages.ui.common.EmptyState
import io.github.alien2003.pulsestages.ui.common.ErrorState
import io.github.alien2003.pulsestages.ui.common.LoadingState
import io.github.alien2003.pulsestages.ui.common.MessageSnackbarEffect
import io.github.alien2003.pulsestages.ui.common.formatDateTime
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.theme.spacing

private const val COLLAPSED_LINES = 4

/** Log screen (opened from Settings). */
@Composable
fun LogRoute(
    onBack: () -> Unit,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.log_share_chooser)
    val subject = stringResource(R.string.log_share_subject)
    state.shareUri?.let { uri ->
        LaunchedEffect(uri) {
            viewModel.shareHandled(context.startFirstAvailable(shareIntent(context, uri.toUri(), subject, chooserTitle)))
        }
    }
    LogScreen(
        state = state,
        onBack = onBack,
        onToggleLevel = viewModel::toggleLevel,
        onShare = viewModel::share,
        onMessageShown = viewModel::messageShown,
    )
}

/** ACTION_SEND chooser for the text file at [uri] with a temporary read grant for the receiving app. */
private fun shareIntent(
    context: Context,
    uri: Uri,
    subject: String,
    title: String,
): Intent {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(send, title).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Stateless Log screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    state: LogUiState,
    onBack: () -> Unit,
    onToggleLevel: (LogLevel) -> Unit,
    onShare: () -> Unit,
    onMessageShown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.messages, snackbarHostState, onMessageShown)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onShare, enabled = !state.sharing) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.log_action_share))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            LevelChips(state.levels, onToggleLevel)
            if (state.sharing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                state.loading -> {
                    LoadingState(Modifier.fillMaxSize())
                }

                state.failed -> {
                    ErrorState(stringResource(R.string.log_error), Modifier.fillMaxSize())
                }

                state.entries.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.log_empty_title),
                        body = stringResource(if (state.levels.isEmpty()) R.string.log_empty_no_levels else R.string.log_empty_body),
                        icon = R.drawable.ic_list,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    LogList(state.entries)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelChips(
    selected: Set<LogLevel>,
    onToggle: (LogLevel) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.medium, vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        LogLevel.entries.forEach { level ->
            val isSelected = level in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(level) },
                label = { Text(stringResource(level.labelRes())) },
                leadingIcon =
                    if (isSelected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = stringResource(R.string.cd_filter_selected),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun LogList(entries: List<LogEntryEntity>) {
    var expanded by rememberSaveable { mutableStateOf(listOf<Long>()) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "count") {
            Text(
                text = pluralStringResource(R.plurals.log_entries_shown, entries.size, entries.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
            )
        }
        items(entries, key = { it.id }) { entry ->
            val isExpanded = entry.id in expanded
            LogRow(
                entry = entry,
                expanded = isExpanded,
                onClick = { expanded = if (isExpanded) expanded - entry.id else expanded + entry.id },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun LogRow(
    entry: LogEntryEntity,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val levelColor =
        when (entry.level) {
            LogLevel.ERROR -> MaterialTheme.colorScheme.error
            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
            LogLevel.INFO, LogLevel.DEBUG -> Color.Unspecified
        }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        overlineContent = {
            Text(
                text =
                    stringResource(
                        R.string.log_entry_header,
                        localDateTime(entry.timestampMs).formatDateTime(),
                        stringResource(entry.level.labelRes()),
                        entry.tag,
                    ),
                color = levelColor,
            )
        },
        headlineContent = {
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = entry.nightKey?.let { key -> { Text(stringResource(R.string.log_entry_night, key)) } },
    )
}

/** Localized name of a log level. */
fun LogLevel.labelRes(): Int =
    when (this) {
        LogLevel.DEBUG -> R.string.log_level_debug
        LogLevel.INFO -> R.string.log_level_info
        LogLevel.WARN -> R.string.log_level_warn
        LogLevel.ERROR -> R.string.log_level_error
    }
