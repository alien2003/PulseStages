package io.github.alien2003.pulsestages.ui.nights

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import io.github.alien2003.pulsestages.ui.common.EmptyState
import io.github.alien2003.pulsestages.ui.common.ErrorState
import io.github.alien2003.pulsestages.ui.common.LoadingState
import io.github.alien2003.pulsestages.ui.common.SingleChoice
import io.github.alien2003.pulsestages.ui.common.SyntheticBanner
import io.github.alien2003.pulsestages.ui.common.durationMinutes
import io.github.alien2003.pulsestages.ui.common.formatDate
import io.github.alien2003.pulsestages.ui.common.formatDuration
import io.github.alien2003.pulsestages.ui.common.formatPercent
import io.github.alien2003.pulsestages.ui.common.formatTime
import io.github.alien2003.pulsestages.ui.common.isWideLayout
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import io.github.alien2003.pulsestages.ui.theme.spacing

private const val LIST_PANE_WEIGHT = 0.42f
private const val DETAIL_PANE_WEIGHT = 0.58f

/**
 * Nights tab. Compact windows show the list and open the detail as its own destination ([onOpenNight]); wide windows
 * (foldable inner screen, landscape) show the list and the selected night side by side. The selection is kept in
 * [viewModel]; when the window becomes compact while a selected night is shown in the right pane, that night is opened
 * as its own destination (the detail destination returns here when the window becomes wide again). The night detail
 * ViewModel ([detailViewModel]) is shared with the detail destination, and the CSV file picker is registered here,
 * outside the panes, so that its result arrives whatever the window size is when the picker returns.
 */
@Composable
fun NightsRoute(
    onOpenNight: (String) -> Unit,
    viewModel: NightsViewModel = hiltViewModel(),
    detailViewModel: NightDetailViewModel = hiltViewModel(key = NIGHT_DETAIL_VIEW_MODEL_KEY),
) {
    val wide = isWideLayout()
    NightExportEffect(detailViewModel, canLaunch = wide)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selectedId.collectAsStateWithLifecycle()
    LaunchedEffect(wide) {
        if (!wide) viewModel.takeNightToOpen()?.let(onOpenNight)
    }
    NightsScreen(
        state = state,
        wide = wide,
        selectedId = selected,
        onSelect = { id ->
            viewModel.select(id, inPane = wide)
            if (!wide) onOpenNight(id)
        },
        onFilter = viewModel::setFilter,
        detailPane = { id, modifier -> NightDetailPane(clientRecordId = id, viewModel = detailViewModel, modifier = modifier) },
    )
}

/**
 * Stateless Nights screen; [onFilter] switches between all sessions, nights and naps; [detailPane] draws the night
 * detail in the right pane of wide layouts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightsScreen(
    state: NightsUiState,
    wide: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFilter: (NightsFilter) -> Unit = {},
    detailPane: @Composable (clientRecordId: String, modifier: Modifier) -> Unit = { _, _ -> },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nights_title)) }) },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding).fillMaxSize()
        when (state) {
            NightsUiState.Loading -> {
                LoadingState(contentModifier)
            }

            NightsUiState.Error -> {
                ErrorState(stringResource(R.string.nights_error), contentModifier)
            }

            is NightsUiState.Loaded -> {
                Column(modifier = contentModifier) {
                    if (state.synthetic) SyntheticBanner()
                    if (state.total > 0) FilterRow(state.filter, onFilter)
                    LoadedNights(state, wide, selectedId, onSelect, detailPane, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** All / Nights / Naps. */
@Composable
private fun FilterRow(
    filter: NightsFilter,
    onFilter: (NightsFilter) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val label = stringResource(R.string.nights_filter_label)
    // Segmented buttons, or radio buttons when a label does not fit (e.g. Russian at the largest font).
    SingleChoice(
        labels = NightsFilter.entries.map { stringResource(it.labelRes()) },
        selected = filter.ordinal,
        onSelect = { onFilter(NightsFilter.entries[it]) },
        modifier =
            Modifier
                .padding(horizontal = spacing.medium, vertical = spacing.small)
                .semantics { contentDescription = label },
    )
}

@StringRes
private fun NightsFilter.labelRes(): Int =
    when (this) {
        NightsFilter.ALL -> R.string.nights_filter_all
        NightsFilter.NIGHTS -> R.string.nights_filter_nights
        NightsFilter.NAPS -> R.string.nights_filter_naps
    }

@Composable
private fun LoadedNights(
    state: NightsUiState.Loaded,
    wide: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    detailPane: @Composable (clientRecordId: String, modifier: Modifier) -> Unit,
    modifier: Modifier,
) {
    val nights = state.nights
    if (nights.isEmpty() && state.total > 0) {
        val body = if (state.filter == NightsFilter.NAPS) R.string.nights_filter_empty_naps else R.string.nights_filter_empty_nights
        EmptyState(
            title = stringResource(state.filter.labelRes()),
            body = stringResource(body),
            icon = R.drawable.ic_nights,
            modifier = modifier,
        )
    } else if (nights.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.nights_empty_title),
            body = stringResource(R.string.nights_empty_body),
            icon = R.drawable.ic_nights,
            modifier = modifier,
        )
    } else if (wide) {
        val shown = selectedId?.takeIf { id -> nights.any { it.clientRecordId == id } }
        val detailId = shown ?: nights.first().clientRecordId
        Row(modifier = modifier) {
            NightList(nights, detailId, onSelect, Modifier.weight(LIST_PANE_WEIGHT).fillMaxHeight())
            VerticalDivider()
            Box(modifier = Modifier.weight(DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                detailPane(detailId, Modifier.fillMaxSize())
            }
        }
    } else {
        NightList(nights, null, onSelect, modifier)
    }
}

@Composable
private fun NightList(
    nights: List<NightListItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(nights, key = { it.summary.id }) { item ->
            NightRow(item, selected = item.clientRecordId == selectedId, onClick = { onSelect(item.clientRecordId) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun NightRow(
    item: NightListItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val night = item.summary
    val start = localDateTime(night.sessionStartMs, night.startZoneOffsetSec)
    val end = localDateTime(night.sessionEndMs, night.endZoneOffsetSec)
    val summary = stageMinutes(night.deepMin, night.remMin, night.lightMin, night.awakeMin, night.unknownMin)
    val total = summary.sumOf { it.minutes }
    val spacing = MaterialTheme.spacing
    ListItem(
        modifier =
            Modifier
                .semantics { this.selected = selected }
                .clickable(role = Role.Button, onClick = onClick),
        colors =
            if (selected) {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                ListItemDefaults.colors()
            },
        overlineContent = {
            Text(
                text = stringResource(night.status.labelRes()),
                color = if (item.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(start.formatDate()) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
                Text(
                    stringResource(
                        R.string.nights_time_range,
                        start.formatTime(),
                        end.formatTime(),
                        formatDuration(durationMinutes(night.sessionStartMs, night.sessionEndMs)),
                    ),
                )
                if (item.estimated && total > 0.0) {
                    Text(summary.filter { it.minutes > 0.0 }.shareText(total))
                    StageShareBar(summary)
                    Text(
                        stringResource(
                            R.string.nights_quality,
                            formatPercent(night.confidence),
                            formatPercent(night.coverage),
                        ),
                    )
                }
                NightMarkers(item)
            }
        },
    )
}

@Composable
private fun List<StageMinutes>.shareText(total: Double): String {
    val parts = map { stringResource(R.string.nights_stage_share, stringResource(it.stage.labelRes()), formatPercent(it.minutes / total)) }
    return parts.joinToString(stringResource(R.string.list_separator))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NightMarkers(item: NightListItem) {
    val night = item.summary
    val markers =
        listOfNotNull(
            Marker(R.drawable.ic_nap, R.string.nights_marker_nap, MarkerTone.NEUTRAL).takeIf { item.nap },
            Marker(R.drawable.ic_refresh, R.string.nights_marker_stale, MarkerTone.NEUTRAL).takeIf { item.stale },
            Marker(R.drawable.ic_heart, R.string.nights_marker_elevated_hr, MarkerTone.ALERT).takeIf { night.elevatedHr },
            Marker(R.drawable.ic_warning, R.string.nights_marker_low_confidence, MarkerTone.NEUTRAL).takeIf { night.lowConfidence },
        )
    if (markers.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        markers.forEach { MarkerLabel(it) }
    }
}

private enum class MarkerTone { NEUTRAL, ALERT }

private data class Marker(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    val tone: MarkerTone,
)

/** An icon and a short label; read as one item by screen readers. */
@Composable
private fun MarkerLabel(marker: Marker) {
    val text = stringResource(marker.label)
    val color = if (marker.tone == MarkerTone.ALERT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        Icon(painter = painterResource(marker.icon), contentDescription = text, tint = color, modifier = Modifier.size(16.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Preview(showBackground = true)
@Composable
private fun NightsScreenPreview() {
    val night =
        NightSummary(
            id = 1,
            clientRecordId = "ps:preview",
            sourcePackage = "com.heytap.health.international",
            sessionStartMs = 1_760_310_000_000,
            sessionEndMs = 1_760_337_000_000,
            startZoneOffsetSec = 10_800,
            endZoneOffsetSec = 10_800,
            status = NightStatus.WRITTEN,
            statusReason = null,
            estimator = EstimatorType.HYBRID,
            algoVersion = 1,
            paramsHash = "abc",
            coverage = 0.93,
            confidence = 0.61,
            awakeMin = 30.0,
            lightMin = 230.0,
            deepMin = 80.0,
            remMin = 110.0,
            unknownMin = 0.0,
            elevatedHr = true,
            lowConfidence = false,
            calibrated = false,
            rulesFallback = false,
            updatedAt = 0,
        )
    PulseStagesTheme(dynamicColor = false) {
        NightsScreen(
            state = NightsUiState.Loaded(listOf(NightListItem(night, stale = true))),
            wide = false,
            selectedId = null,
            onSelect = {},
        )
    }
}
