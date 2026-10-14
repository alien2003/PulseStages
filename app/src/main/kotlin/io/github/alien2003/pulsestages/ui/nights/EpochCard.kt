package io.github.alien2003.pulsestages.ui.nights

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.ui.common.formatDecimal
import io.github.alien2003.pulsestages.ui.common.formatPercent
import io.github.alien2003.pulsestages.ui.common.formatTime
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.theme.spacing
import io.github.alien2003.pulsestages.ui.theme.stageColor

private const val HR_DIGITS = 1
private const val FEATURE_DIGITS = 3

/**
 * Details of the selected epoch: its stage (re-estimate and as written), the four posterior probabilities, the heart
 * rate and the per-epoch features; buttons move to the previous / next epoch (also usable without touching the charts).
 * For a nap written as asleep / awake only ([writtenAsSleepWake]) the written value is "Asleep" or "Awake", which is
 * what Health Connect holds, not the estimated stage the ledger keeps.
 */
@Composable
internal fun EpochCard(
    selection: EpochSelection,
    zoneOffsetSec: Int,
    actions: NightDetailActions,
    writtenAsSleepWake: Boolean = false,
) {
    val start = localDateTime(selection.startMs, zoneOffsetSec).formatTime()
    val end = localDateTime(selection.endMs, zoneOffsetSec).formatTime()
    DetailCard(title = stringResource(R.string.night_epoch_title, start, end)) {
        Text(
            text =
                stringResource(
                    R.string.night_epoch_stage,
                    stringResource(selection.estimatedStage.labelRes()),
                    selection.writtenStage?.let { stringResource(it.writtenLabelRes(writtenAsSleepWake)) }
                        ?: stringResource(R.string.value_not_available),
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.night_epoch_posteriors), style = MaterialTheme.typography.titleSmall)
        SleepStage.SCORED.forEach { stage -> PosteriorRow(stage, selection.posterior[stage]) }
        Text(text = heartRateText(selection), style = MaterialTheme.typography.bodyMedium)
        if (selection.features.isNotEmpty()) {
            Text(stringResource(R.string.night_epoch_features), style = MaterialTheme.typography.titleSmall)
            FeatureList(selection.features)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onMoveSelection(-1) }) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.cd_previous_epoch))
            }
            IconButton(onClick = { actions.onMoveSelection(1) }) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.cd_next_epoch))
            }
            TextButton(onClick = actions.onClearSelection) { Text(stringResource(R.string.action_close)) }
        }
    }
}

/**
 * The label of a written stage: the stage itself, or for a nap written as asleep / awake "Awake" or "Asleep" (every
 * other stage, data gaps included, was written as Health Connect's generic sleeping stage).
 */
@StringRes
private fun SleepStage.writtenLabelRes(sleepWakeOnly: Boolean): Int =
    if (!sleepWakeOnly || this == SleepStage.AWAKE) labelRes() else R.string.stage_asleep

@Composable
private fun PosteriorRow(
    stage: SleepStage,
    probability: Double,
) {
    val label = stringResource(stage.labelRes())
    val percent = formatPercent(probability)
    val description = stringResource(R.string.format_label_value, label, percent)
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 72.dp))
        LinearProgressIndicator(
            progress = { probability.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.stageColor(stage),
        )
        Text(text = percent, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 40.dp))
    }
}

@Composable
private fun heartRateText(selection: EpochSelection): String {
    val raw = selection.hrRaw
    val smooth = selection.hrSmooth
    return when {
        !selection.hasData || (raw == null && smooth == null) -> {
            stringResource(R.string.night_epoch_no_hr)
        }

        else -> {
            val none = stringResource(R.string.value_not_available)
            stringResource(
                R.string.night_epoch_hr,
                raw?.let { formatDecimal(it, HR_DIGITS) } ?: none,
                smooth?.let { formatDecimal(it, HR_DIGITS) } ?: none,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureList(features: List<FeatureValue>) {
    val none = stringResource(R.string.value_not_available)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        features.forEach { feature ->
            Column {
                Text(feature.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(feature.value?.let { formatDecimal(it, FEATURE_DIGITS) } ?: none, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
