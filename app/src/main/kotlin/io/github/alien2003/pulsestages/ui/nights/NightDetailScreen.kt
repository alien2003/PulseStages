package io.github.alien2003.pulsestages.ui.nights

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.ui.common.ActionRow
import io.github.alien2003.pulsestages.ui.common.ConfirmDialog
import io.github.alien2003.pulsestages.ui.common.EmptyState
import io.github.alien2003.pulsestages.ui.common.ErrorState
import io.github.alien2003.pulsestages.ui.common.JobPhase
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
import io.github.alien2003.pulsestages.ui.common.formatTime
import io.github.alien2003.pulsestages.ui.common.isWideLayout
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.common.sourceName
import io.github.alien2003.pulsestages.ui.theme.spacing
import kotlin.math.roundToInt

private const val HASH_PREFIX_LENGTH = 12
private const val CSV_MIME_TYPE = "text/csv"

/**
 * Night detail as its own destination (compact windows): top app bar with a back button and the detail pane. When
 * the window becomes wide (unfolded, rotated), it hands the night to the list ([onWideLayout]), which shows it in its
 * right pane. When the data were replaced and the new data do not have the night (debug builds: demo mode was
 * switched), it returns to the list ([onBack]). [viewModel] is the Nights tab's shared detail ViewModel; the CSV file
 * picker is registered here, outside the pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightDetailRoute(
    clientRecordId: String,
    viewModel: NightDetailViewModel,
    onBack: () -> Unit,
    onWideLayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = isWideLayout()
    NightExportEffect(viewModel, canLaunch = !wide)
    val currentOnWideLayout by rememberUpdatedState(onWideLayout)
    LaunchedEffect(wide) {
        if (wide) currentOnWideLayout()
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gone = state.clientRecordId == clientRecordId && state.goneWithReplacedData
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(gone) {
        if (gone) currentOnBack()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.night_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        NightDetailPane(clientRecordId = clientRecordId, viewModel = viewModel, modifier = Modifier.padding(innerPadding))
    }
}

/**
 * Opens the CSV file picker when [viewModel] has a CSV waiting for its destination ([canLaunch]: this screen shows the
 * detail) and hands the picked document back. Registered unconditionally by the destinations, so that a result is
 * delivered even when the window size changed while the picker was open (the launcher's key is saved with the
 * destination, not with a pane).
 */
@Composable
internal fun NightExportEffect(
    viewModel: NightDetailViewModel,
    canLaunch: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)) { uri ->
            viewModel.onExportDestination(uri?.toString())
        }
    val pending = state.pendingExport
    LaunchedEffect(pending, canLaunch) {
        if (pending != null && canLaunch && viewModel.claimExportPicker()) {
            try {
                exportLauncher.launch(pending.fileName)
            } catch (ignored: ActivityNotFoundException) {
                viewModel.onExportPickerUnavailable()
            }
        }
    }
}

/**
 * Callbacks of [NightDetailContent].
 *
 * @property onSelectTime a tap or drag on a chart at this time.
 * @property onMoveSelection move the selected epoch by this many epochs.
 * @property onClearSelection close the epoch details.
 * @property onRetryInspection read the night again.
 * @property onRecompute "Recompute".
 * @property onCompare "Compare with RULES".
 * @property onExport "Export CSV".
 * @property onDelete "Delete my session" (opens the confirmation).
 */
data class NightDetailActions(
    val onSelectTime: (Long) -> Unit = {},
    val onMoveSelection: (Int) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRetryInspection: () -> Unit = {},
    val onRecompute: () -> Unit = {},
    val onCompare: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

/**
 * The detail of the night [clientRecordId] with the Nights tab's shared [viewModel]; used by the detail destination
 * and as the right pane of wide layouts. While the pane is visible (started), the ViewModel re-estimates the night
 * when its ledger row changes. [showSyntheticBanner] is false in the right pane, where the list's banner above both
 * panes already says that the data are synthetic.
 */
@Composable
fun NightDetailPane(
    clientRecordId: String,
    viewModel: NightDetailViewModel,
    modifier: Modifier = Modifier,
    showSyntheticBanner: Boolean = true,
) {
    LaunchedEffect(viewModel, clientRecordId) { viewModel.show(clientRecordId) }
    LifecycleStartEffect(viewModel) {
        viewModel.onPaneStarted()
        onStopOrDispose { viewModel.onPaneStopped() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.messages, snackbarHostState, viewModel::messageShown)
    // Until the ViewModel switched to this night, the previous night's state is not shown.
    val load = if (state.clientRecordId == clientRecordId) state.load else DetailLoad.LOADING
    Box(modifier = modifier.fillMaxSize()) {
        when (load) {
            DetailLoad.LOADING -> {
                LoadingState(Modifier.fillMaxSize())
            }

            DetailLoad.ERROR -> {
                ErrorState(stringResource(R.string.nights_error), Modifier.fillMaxSize())
            }

            DetailLoad.NOT_FOUND -> {
                EmptyState(
                    title = stringResource(R.string.night_not_found_title),
                    body = stringResource(R.string.night_not_found_body),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DetailLoad.LOADED -> {
                NightDetailContent(
                    state = state,
                    showSyntheticBanner = showSyntheticBanner,
                    actions =
                        NightDetailActions(
                            onSelectTime = viewModel::selectAt,
                            onMoveSelection = viewModel::moveSelection,
                            onClearSelection = viewModel::clearSelection,
                            onRetryInspection = viewModel::retryInspection,
                            onRecompute = viewModel::recompute,
                            onCompare = viewModel::compareWithRules,
                            onExport = viewModel::exportCsv,
                            onDelete = viewModel::requestDelete,
                        ),
                )
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.night_delete_confirm_title),
            body = stringResource(R.string.night_delete_confirm_body),
            confirm = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/** Stateless content of the night detail; [showSyntheticBanner] shows the demo banner when the data are synthetic. */
@Composable
fun NightDetailContent(
    state: NightDetailUiState,
    actions: NightDetailActions,
    modifier: Modifier = Modifier,
    showSyntheticBanner: Boolean = true,
) {
    val night = state.night ?: return
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.synthetic && showSyntheticBanner) SyntheticBanner()
        Column(modifier = Modifier.padding(spacing.medium), verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            HeaderCard(night, state.stale)
            ChartsCard(state, night, actions)
            state.selection?.let { EpochCard(it, night.startZoneOffsetSec, actions, writtenAsSleepWake = night.sleepWakeOnly) }
            ActionsCard(state, night, actions)
            ComparisonCard(state.comparison, night)
            QualityCard(night)
            if (night.warnings.isNotEmpty() || night.hasFlags()) WarningsCard(night)
            SourceCard(night)
        }
    }
}

@Composable
private fun HeaderCard(
    night: EstimatedNightEntity,
    stale: Boolean,
) {
    val start = localDateTime(night.sessionStartMs, night.startZoneOffsetSec)
    val end = localDateTime(night.sessionEndMs, night.endZoneOffsetSec)
    DetailCard(title = start.formatDate()) {
        Text(
            text =
                stringResource(
                    R.string.nights_time_range,
                    start.formatTime(),
                    end.formatTime(),
                    formatDuration(durationMinutes(night.sessionStartMs, night.sessionEndMs)),
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        LabeledValue(stringResource(R.string.night_detail_status), stringResource(night.status.labelRes()))
        LabeledValue(stringResource(R.string.night_detail_mode), stringResource(night.mode.labelRes()))
        if (night.mode == SessionMode.NAP && night.status == NightStatus.WRITTEN) {
            // Nap stages are not validated (ALGORITHM.md section 13.13): the hybrid caution names the checks the nap model
            // failed; other estimators get the short caution.
            val caution =
                if (night.estimator == EstimatorType.HYBRID) {
                    R.string.night_detail_nap_not_validated
                } else {
                    R.string.night_detail_nap_not_validated_generic
                }
            Text(text = stringResource(caution), style = MaterialTheme.typography.bodyMedium)
            if (night.sleepWakeOnly) {
                Text(text = stringResource(R.string.night_detail_nap_sleep_wake), style = MaterialTheme.typography.bodySmall)
            }
        }
        // The ledger's statusReason is an English diagnostic (it is in the log); a localized explanation is shown.
        statusReasonRes(night.status, night.statusReason)?.let { reason ->
            LabeledValue(stringResource(R.string.night_detail_reason), stringResource(reason))
        }
        if (stale) {
            Text(
                text = stringResource(R.string.night_detail_stale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ChartsCard(
    state: NightDetailUiState,
    night: EstimatedNightEntity,
    actions: NightDetailActions,
) {
    val ready = state.ready
    val window = ChartWindow(night.sessionStartMs, night.sessionEndMs, night.startZoneOffsetSec)
    val title =
        when {
            night.stages.isEmpty() -> R.string.night_detail_hypnogram_not_written
            night.sleepWakeOnly -> R.string.night_detail_hypnogram_sleep_wake
            else -> R.string.night_detail_hypnogram
        }
    DetailCard(title = stringResource(title)) {
        if (night.stages.isEmpty() && ready == null) {
            Text(stringResource(R.string.night_detail_no_stages), style = MaterialTheme.typography.bodyMedium)
        } else {
            // Nothing written (not estimated before): the re-estimate with the current settings is drawn instead.
            val stages = night.stages.ifEmpty { ready?.estimate?.stages.orEmpty() }
            NightCharts(
                stages = stages,
                window = window,
                heartRate = ready?.let { HeartRateSeries(it.estimate.perEpoch, it.epochMs) },
                selected = state.selection?.let { it.startMs to it.endMs },
                onSelectTime = if (ready != null) actions.onSelectTime else null,
            )
            StageLegend(stageMinutesOf(stages))
            ChartLegend(withHeartRate = ready != null)
            if (ready != null && state.selection == null) {
                // The charts are selected by touch; this button opens the epoch details (with previous / next) for
                // keyboard, switch and screen-reader users.
                ActionRow {
                    SecondaryAction(stringResource(R.string.night_epoch_inspect), onClick = { actions.onMoveSelection(0) })
                }
            }
        }
        InspectionStatus(state, actions)
    }
}

@Composable
private fun InspectionStatus(
    state: NightDetailUiState,
    actions: NightDetailActions,
) {
    when (val inspection = state.inspection) {
        InspectionState.Loading -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.night_inspection_loading), style = MaterialTheme.typography.bodyMedium)
        }

        is InspectionState.Ready -> {
            Text(stringResource(R.string.night_inspection_hint), style = MaterialTheme.typography.bodySmall)
            if (inspection.inspection.writtenIsStale && state.night?.status == NightStatus.WRITTEN) {
                Text(stringResource(R.string.night_inspection_differs), style = MaterialTheme.typography.bodySmall)
            }
        }

        is InspectionState.NotEstimated -> {
            Text(
                text = stringResource(R.string.night_inspection_not_estimated, stringResource(inspection.reason.labelRes())),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is InspectionState.Blocked -> {
            InspectionProblem(stringResource(inspection.reason.labelRes()), actions.onRetryInspection)
        }

        is InspectionState.Failed -> {
            InspectionProblem(
                stringResource(if (inspection.retryable) R.string.error_hc_transient else R.string.night_inspection_failed),
                actions.onRetryInspection,
            )
        }
    }
}

@Composable
private fun InspectionProblem(
    text: String,
    onRetry: () -> Unit,
) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    ActionRow { SecondaryAction(stringResource(R.string.action_retry), onClick = onRetry) }
}

@Composable
private fun ActionsCard(
    state: NightDetailUiState,
    night: EstimatedNightEntity,
    actions: NightDetailActions,
) {
    val ready = state.ready != null
    DetailCard(title = stringResource(R.string.night_detail_actions)) {
        if (state.recompute.active) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val text =
                when {
                    state.recompute.retrying -> R.string.home_job_retrying
                    state.recompute.phase == JobPhase.PENDING -> R.string.home_job_pending
                    else -> R.string.night_recompute_running
                }
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.exporting || state.deleting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.recomputeBlockedByPolicy) {
            Text(stringResource(R.string.night_recompute_blocked_never), style = MaterialTheme.typography.bodyMedium)
        }
        ActionRow {
            PrimaryAction(
                stringResource(R.string.night_action_recompute),
                enabled = !state.recompute.active && night.status in RECOMPUTABLE && !state.recomputeBlockedByPolicy,
                onClick = actions.onRecompute,
            )
            SecondaryAction(
                stringResource(R.string.night_action_compare_rules),
                enabled = ready && state.comparison !is ComparisonState.Running,
                onClick = actions.onCompare,
            )
            SecondaryAction(stringResource(R.string.night_action_export), enabled = ready && !state.exporting, onClick = actions.onExport)
            SecondaryAction(
                stringResource(R.string.night_action_delete),
                enabled = night.status == NightStatus.WRITTEN && !state.deleting,
                destructive = true,
                onClick = actions.onDelete,
            )
        }
    }
}

@Composable
private fun ComparisonCard(
    comparison: ComparisonState,
    night: EstimatedNightEntity,
) {
    if (comparison == ComparisonState.None) return
    DetailCard(title = stringResource(R.string.night_compare_title)) {
        when (comparison) {
            ComparisonState.None -> {}

            ComparisonState.Running -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is ComparisonState.Failed -> {
                Text(comparison.message.resolve(), color = MaterialTheme.colorScheme.error)
            }

            is ComparisonState.Ready -> {
                val window = ChartWindow(night.sessionStartMs, night.sessionEndMs, night.startZoneOffsetSec)
                Text(
                    text =
                        stringResource(
                            R.string.night_compare_agreement,
                            comparison.kappa?.let { formatDecimal(it, 2) } ?: stringResource(R.string.value_not_available),
                            comparison.accuracy?.let { formatPercent(it) } ?: stringResource(R.string.value_not_available),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.night_compare_primary, stringResource(comparison.primaryEstimator.labelRes())),
                    style = MaterialTheme.typography.titleSmall,
                )
                NightCharts(comparison.primary, window, heartRate = null, selected = null, onSelectTime = null)
                Text(stringResource(R.string.night_compare_rules), style = MaterialTheme.typography.titleSmall)
                NightCharts(comparison.rules, window, heartRate = null, selected = null, onSelectTime = null)
                Text(stringResource(R.string.night_compare_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun QualityCard(night: EstimatedNightEntity) {
    DetailCard(title = stringResource(R.string.night_detail_quality)) {
        LabeledValue(stringResource(R.string.night_detail_estimator), stringResource(night.estimator.labelRes()))
        LabeledValue(stringResource(R.string.night_detail_confidence), formatPercent(night.confidence))
        LabeledValue(stringResource(R.string.night_detail_coverage), formatPercent(night.coverage))
        LabeledValue(stringResource(R.string.night_detail_hr_samples), night.hrSamples.toString())
        night.nadirBpm?.let { nadir ->
            LabeledValue(stringResource(R.string.night_detail_nadir), stringResource(R.string.format_bpm, nadir.roundToInt()))
        }
        LabeledValue(stringResource(R.string.night_detail_algo_version), night.algoVersion.toString())
        LabeledValue(stringResource(R.string.night_detail_params_hash), night.paramsHash.take(HASH_PREFIX_LENGTH))
        night.modelId?.let { LabeledValue(stringResource(R.string.night_detail_model), it) }
    }
}

@Composable
private fun WarningsCard(night: EstimatedNightEntity) {
    DetailCard(title = stringResource(R.string.night_detail_warnings)) {
        val flags =
            listOfNotNull(
                R.string.night_flag_elevated_hr.takeIf { night.elevatedHr },
                R.string.night_flag_low_confidence.takeIf { night.lowConfidence },
                R.string.night_flag_calibrated.takeIf { night.calibrated },
                R.string.night_flag_rules_fallback.takeIf { night.rulesFallback },
            )
        val texts = flags + night.warnings.map { it.labelRes() }.filterNot { it in flags }
        texts.distinct().forEach { text ->
            ListItem(
                headlineContent = { Text(stringResource(text)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = stringResource(R.string.cd_warning),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun SourceCard(night: EstimatedNightEntity) {
    DetailCard(title = stringResource(R.string.night_detail_source)) {
        LabeledValue(stringResource(R.string.night_detail_source_package), sourceName(night.sourcePackage, label = null))
        LabeledValue(stringResource(R.string.night_detail_source_sessions), night.sourceUuids.size.toString())
        LabeledValue(stringResource(R.string.night_detail_client_record_id), night.clientRecordId)
        LabeledValue(
            stringResource(R.string.night_detail_updated),
            localDateTime(night.updatedAt).formatDateTime(),
        )
    }
}

/** A card with a heading. */
@Composable
internal fun DetailCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A label and its value, stacked so that long values and large fonts wrap instead of overflowing. */
@Composable
internal fun LabeledValue(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Statuses whose night can be recomputed (its sources are still known). */
private val RECOMPUTABLE = setOf(NightStatus.WRITTEN, NightStatus.NOT_ESTIMATED, NightStatus.FAILED, NightStatus.DELETED)

private fun EstimatedNightEntity.hasFlags(): Boolean = elevatedHr || lowConfidence || calibrated || rulesFallback
