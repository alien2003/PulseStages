package io.github.alien2003.pulsestages.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.ui.common.ActionRow
import io.github.alien2003.pulsestages.ui.common.ConfirmDialog
import io.github.alien2003.pulsestages.ui.common.EmptyState
import io.github.alien2003.pulsestages.ui.common.ErrorState
import io.github.alien2003.pulsestages.ui.common.LoadingState
import io.github.alien2003.pulsestages.ui.common.MessageSnackbarEffect
import io.github.alien2003.pulsestages.ui.common.PrimaryAction
import io.github.alien2003.pulsestages.ui.common.SecondaryAction
import io.github.alien2003.pulsestages.ui.common.SyntheticBanner
import io.github.alien2003.pulsestages.ui.common.durationMinutes
import io.github.alien2003.pulsestages.ui.common.formatDate
import io.github.alien2003.pulsestages.ui.common.formatDateTime
import io.github.alien2003.pulsestages.ui.common.formatDecimal
import io.github.alien2003.pulsestages.ui.common.formatDuration
import io.github.alien2003.pulsestages.ui.common.formatPercent
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.common.sourceName
import io.github.alien2003.pulsestages.ui.nights.labelRes
import io.github.alien2003.pulsestages.ui.nights.shortLabelRes
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import io.github.alien2003.pulsestages.ui.theme.spacing
import kotlin.math.roundToLong

private val TABLE_CELL_WIDTH = 72.dp
private const val HEAT_ALPHA = 0.55f
private const val STAGE_TABLE_COLUMNS = 4

/** Compare tab: validation against reference nights from other apps and the personal model. */
@Composable
fun CompareRoute(viewModel: CompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompareScreen(
        state = state,
        actions =
            CompareActions(
                onRefresh = viewModel::refresh,
                onSelectRange = viewModel::selectRange,
                onCalibrate = viewModel::calibrate,
                onReset = viewModel::requestReset,
                onConfirmReset = viewModel::confirmReset,
                onDismissReset = viewModel::dismissReset,
                onMessageShown = viewModel::messageShown,
            ),
    )
}

/**
 * Callbacks of [CompareScreen].
 *
 * @property onRefresh search again.
 * @property onSelectRange search another range.
 * @property onCalibrate "Calibrate".
 * @property onReset "Reset to global model" (opens the confirmation).
 * @property onConfirmReset the reset was confirmed.
 * @property onDismissReset the confirmation was dismissed.
 * @property onMessageShown a Snackbar message was shown.
 */
data class CompareActions(
    val onRefresh: () -> Unit = {},
    val onSelectRange: (CompareRange) -> Unit = {},
    val onCalibrate: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onConfirmReset: () -> Unit = {},
    val onDismissReset: () -> Unit = {},
    val onMessageShown: (Long) -> Unit = {},
)

/** Stateless Compare screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    state: CompareUiState,
    actions: CompareActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.messages, snackbarHostState, actions.onMessageShown)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compare_title)) },
                actions = {
                    IconButton(onClick = actions.onRefresh, enabled = state.busy == null) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.synthetic) SyntheticBanner()
            RangeSelector(state, actions.onSelectRange)
            val contentModifier = Modifier.weight(1f).fillMaxWidth()
            when (state.phase) {
                ComparePhase.SEARCHING -> {
                    LoadingState(contentModifier, label = stringResource(R.string.compare_searching))
                }

                ComparePhase.SCORING -> {
                    LoadingState(
                        contentModifier,
                        label = pluralStringResource(R.plurals.compare_scoring, state.referenceCount, state.referenceCount),
                    )
                }

                ComparePhase.ERROR -> {
                    ErrorState(
                        state.error?.resolve() ?: stringResource(R.string.compare_error_generic),
                        contentModifier,
                        onRetry = actions.onRefresh,
                    )
                }

                ComparePhase.READY -> {
                    if (state.empty) EmptyReferences(state, contentModifier) else CompareContent(state, actions, contentModifier)
                }
            }
        }
    }
    if (state.confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.compare_reset_confirm_title),
            body = stringResource(R.string.compare_reset_confirm_body),
            confirm = stringResource(R.string.compare_action_reset),
            onConfirm = actions.onConfirmReset,
            onDismiss = actions.onDismissReset,
        )
    }
}

@Composable
private fun EmptyReferences(
    state: CompareUiState,
    modifier: Modifier,
) {
    val parts =
        buildList {
            val days = state.range.days
            add(
                if (days == null) {
                    stringResource(R.string.compare_empty_body_all)
                } else {
                    pluralStringResource(R.plurals.compare_empty_body, days, days)
                },
            )
            add(stringResource(R.string.compare_empty_explanation))
            if (state.withoutHeartRate > 0) {
                add(pluralStringResource(R.plurals.compare_skipped_no_hr, state.withoutHeartRate, state.withoutHeartRate))
            }
            if (state.withoutSleepStages > 0) {
                add(pluralStringResource(R.plurals.compare_skipped_no_stages, state.withoutSleepStages, state.withoutSleepStages))
            }
            if (state.historyLimited) add(stringResource(R.string.home_history_limited))
        }
    EmptyState(
        title = stringResource(R.string.compare_empty_title),
        body = parts.joinToString("\n\n"),
        icon = R.drawable.ic_compare,
        modifier = modifier,
    )
}

@Composable
private fun CompareContent(
    state: CompareUiState,
    actions: CompareActions,
    modifier: Modifier,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(spacing.medium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            SummaryCard(state)
            state.metrics?.let { metrics ->
                StagesCard(metrics)
                MatrixCard(metrics)
            }
            ModelCard(state, actions)
            NightsCard(state)
        }
    }
}

/** The searched range: 90 days, a year or the whole history (the longer ones need READ_HEALTH_DATA_HISTORY). */
@Composable
private fun RangeSelector(
    state: CompareUiState,
    onSelect: (CompareRange) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.medium, vertical = spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
            Text(
                text = stringResource(R.string.compare_range_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CompareRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = range == state.range,
                        onClick = { onSelect(range) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = CompareRange.entries.size),
                        enabled = state.canSelect(range) || range == state.range,
                    ) { Text(stringResource(range.labelRes())) }
                }
            }
        }
    }
}

private fun CompareRange.labelRes(): Int =
    when (this) {
        CompareRange.RECENT -> R.string.compare_range_recent
        CompareRange.YEAR -> R.string.compare_range_year
        CompareRange.ALL -> R.string.compare_range_all
    }

@Composable
private fun foundTitle(state: CompareUiState): String {
    val days = state.range.days
    return if (days == null) {
        pluralStringResource(R.plurals.compare_found_all, state.referenceCount, state.referenceCount)
    } else {
        pluralStringResource(R.plurals.compare_found, state.referenceCount, state.referenceCount, days)
    }
}

@Composable
private fun SummaryCard(state: CompareUiState) {
    CompareCard(title = foundTitle(state)) {
        Text(stringResource(R.string.compare_explanation), style = MaterialTheme.typography.bodyMedium)
        if (state.historyLimited) {
            Text(
                stringResource(R.string.home_history_limited),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val metrics = state.metrics ?: return@CompareCard
        val none = stringResource(R.string.value_not_available)
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large)) {
            Metric(stringResource(R.string.compare_kappa), metrics.kappa?.let { formatDecimal(it, KAPPA_DIGITS) } ?: none)
            Metric(stringResource(R.string.compare_accuracy), metrics.accuracy?.let { formatPercent(it) } ?: none)
            Metric(stringResource(R.string.compare_macro_f1), metrics.macroF1?.let { formatDecimal(it, KAPPA_DIGITS) } ?: none)
        }
        Text(
            pluralStringResource(R.plurals.compare_scored_nights, metrics.scoredNights, metrics.scoredNights),
            style = MaterialTheme.typography.bodySmall,
        )
        metrics.meanMinutesError?.let { error ->
            Text(
                stringResource(
                    R.string.compare_minutes_error,
                    formatDuration(error.deep.roundToLong()),
                    formatDuration(error.rem.roundToLong()),
                    formatDuration(error.awake.roundToLong()),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.withoutHeartRate > 0) {
            Text(
                pluralStringResource(R.plurals.compare_skipped_no_hr, state.withoutHeartRate, state.withoutHeartRate),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StagesCard(metrics: CompareMetrics) {
    CompareCard(title = stringResource(R.string.compare_stages_title)) {
        Text(stringResource(R.string.compare_stages_hint), style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(
                listOf(
                    stringResource(R.string.compare_col_stage),
                    stringResource(R.string.compare_col_recall),
                    stringResource(R.string.compare_col_precision),
                    stringResource(R.string.compare_col_f1),
                ),
                header = true,
            )
            metrics.stages.forEach { stage ->
                HorizontalDivider(modifier = Modifier.width(TABLE_CELL_WIDTH * STAGE_TABLE_COLUMNS))
                TableRow(
                    listOf(
                        stringResource(stage.stage.shortLabelRes()),
                        formatPercent(stage.recall),
                        formatPercent(stage.precision),
                        formatDecimal(stage.f1, KAPPA_DIGITS),
                    ),
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    header: Boolean = false,
) {
    Row {
        cells.forEach { text ->
            Text(
                text = text,
                modifier = Modifier.width(TABLE_CELL_WIDTH).padding(vertical = MaterialTheme.spacing.extraSmall),
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MatrixCard(metrics: CompareMetrics) {
    CompareCard(title = stringResource(R.string.compare_matrix_title)) {
        Text(stringResource(R.string.compare_matrix_hint), style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                MatrixHeader(stringResource(R.string.compare_matrix_corner))
                SleepStage.SCORED.forEach { MatrixHeader(stringResource(it.shortLabelRes())) }
            }
            metrics.matrix.forEach { row ->
                Row {
                    MatrixHeader(stringResource(row.truth.shortLabelRes()))
                    row.counts.forEachIndexed { index, count -> MatrixCell(count, row.shares[index]) }
                }
            }
        }
    }
}

@Composable
private fun MatrixHeader(text: String) {
    Box(modifier = Modifier.width(TABLE_CELL_WIDTH).padding(MaterialTheme.spacing.extraSmall), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MatrixCell(
    count: Long,
    share: Double,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = (share * HEAT_ALPHA).toFloat())
    Column(
        modifier =
            Modifier
                .width(TABLE_CELL_WIDTH)
                .background(if (count == 0L) Color.Transparent else tint)
                .padding(MaterialTheme.spacing.extraSmall)
                .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(formatPercent(share), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ModelCard(
    state: CompareUiState,
    actions: CompareActions,
) {
    CompareCard(title = stringResource(R.string.compare_model_title)) {
        val model = state.model
        Text(stringResource(modelName(model?.kind)), style = MaterialTheme.typography.bodyLarge)
        if (model != null) {
            Text(
                pluralStringResource(
                    R.plurals.compare_model_detail,
                    model.nightsUsed,
                    localDateTime(model.createdAt).formatDateTime(),
                    model.nightsUsed,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            val before = model.kappaBefore
            val after = model.kappaAfter
            if (before != null && after != null) {
                Text(
                    stringResource(R.string.compare_model_kappa, formatDecimal(before, KAPPA_DIGITS), formatDecimal(after, KAPPA_DIGITS)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(stringResource(R.string.compare_model_hint), style = MaterialTheme.typography.bodySmall)
        if (!state.personalizationEnabled) {
            Text(
                stringResource(R.string.compare_personalization_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.busy?.let { busy ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(busy.labelRes()), style = MaterialTheme.typography.bodyMedium)
        }
        ActionRow {
            PrimaryAction(stringResource(R.string.compare_action_calibrate), enabled = state.canCalibrate, onClick = actions.onCalibrate)
            SecondaryAction(
                stringResource(R.string.compare_action_reset),
                enabled = model != null && state.busy == null,
                onClick = actions.onReset,
            )
        }
    }
}

@Composable
private fun NightsCard(state: CompareUiState) {
    CompareCard(title = stringResource(R.string.compare_nights_title)) {
        val none = stringResource(R.string.value_not_available)
        state.nights.forEach { night ->
            val start = localDateTime(night.startMs, night.zoneOffsetSec)
            ListItem(
                overlineContent = { Text(sourceName(night.packageName, night.label)) },
                headlineContent = {
                    Text(
                        stringResource(
                            R.string.compare_night_headline,
                            start.formatDate(),
                            formatDuration(durationMinutes(night.startMs, night.endMs)),
                        ),
                    )
                },
                supportingContent = {
                    Text(
                        if (night.estimator == null) {
                            stringResource(R.string.compare_night_not_estimated)
                        } else {
                            stringResource(
                                R.string.compare_night_scores,
                                night.kappa?.let { formatDecimal(it, KAPPA_DIGITS) } ?: none,
                                night.accuracy?.let { formatPercent(it) } ?: none,
                                stringResource(night.estimator.labelRes()),
                            )
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun CompareCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

private fun modelName(kind: PersonalModelKind?): Int =
    when (kind) {
        PersonalModelKind.CALIBRATED -> R.string.model_personal_calibrated
        PersonalModelKind.EM -> R.string.model_personal_em
        null -> R.string.model_global
    }

private fun CompareBusy.labelRes(): Int =
    when (this) {
        CompareBusy.CALIBRATING -> R.string.compare_calibrating
        CompareBusy.RESETTING -> R.string.compare_resetting
        CompareBusy.RESCORING -> R.string.compare_rescoring
    }

@Preview(showBackground = true)
@Composable
private fun CompareScreenEmptyPreview() {
    PulseStagesTheme(dynamicColor = false) {
        CompareScreen(state = CompareUiState(phase = ComparePhase.READY, historyLimited = true), actions = CompareActions())
    }
}
