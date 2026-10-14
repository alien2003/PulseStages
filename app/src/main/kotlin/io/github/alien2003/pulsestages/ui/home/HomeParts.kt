package io.github.alien2003.pulsestages.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.usecase.LastRun
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.ui.common.ActionRow
import io.github.alien2003.pulsestages.ui.common.JobPhase
import io.github.alien2003.pulsestages.ui.common.JobUi
import io.github.alien2003.pulsestages.ui.common.PrimaryAction
import io.github.alien2003.pulsestages.ui.common.SecondaryAction
import io.github.alien2003.pulsestages.ui.common.formatDateTime
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.common.sourceName
import io.github.alien2003.pulsestages.ui.theme.spacing
import io.github.alien2003.pulsestages.work.worker.JobProgress

/** Sources, heart-rate origin, last and next run, and the model in use. */
@Composable
internal fun ScheduleCard(state: HomeUiState) {
    HomeCard(title = stringResource(R.string.home_schedule_title)) {
        val separator = stringResource(R.string.list_separator)
        LabeledLine(
            label = stringResource(R.string.home_sources_label),
            value =
                if (state.sources.isEmpty()) {
                    stringResource(R.string.settings_sources_none)
                } else {
                    state.sources.map { sourceName(it.packageName, it.label) }.joinToString(separator)
                },
            warning = state.sources.isEmpty(),
        )
        LabeledLine(
            label = stringResource(R.string.home_hr_sources_label),
            value =
                stringResource(
                    if (state.hrSources == HrSourcesMode.ANY) R.string.sources_hr_any_on else R.string.sources_hr_any_off,
                ),
        )
        LabeledLine(label = stringResource(R.string.home_last_run_label), value = lastRunText(state.lastRun))
        LabeledLine(
            label = stringResource(R.string.home_next_run_label),
            value = state.nextRunMs?.let { localDateTime(it).formatDateTime() } ?: stringResource(R.string.home_next_run_none),
        )
        LabeledLine(label = stringResource(R.string.home_model_label), value = stringResource(modelLabel(state.activeModel)))
    }
}

@Composable
private fun LabeledLine(
    label: String,
    value: String,
    warning: Boolean = false,
) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun lastRunText(lastRun: LastRun?): String {
    val finished = lastRun?.finishedAtMs
    val started = lastRun?.startedAtMs
    return when {
        lastRun == null -> {
            stringResource(R.string.home_last_run_never)
        }

        lastRun.inProgress && started != null -> {
            stringResource(R.string.home_last_run_running, localDateTime(started).formatDateTime())
        }

        finished != null -> {
            val outcome = lastRun.outcome?.let { stringResource(it.labelRes()) }
            if (outcome == null) {
                localDateTime(finished).formatDateTime()
            } else {
                stringResource(R.string.home_last_run_value, localDateTime(finished).formatDateTime(), outcome)
            }
        }

        else -> {
            stringResource(R.string.home_last_run_never)
        }
    }
}

private fun modelLabel(kind: PersonalModelKind?): Int =
    when (kind) {
        PersonalModelKind.CALIBRATED -> R.string.model_personal_calibrated
        PersonalModelKind.EM -> R.string.model_personal_em
        null -> R.string.model_global
    }

/** Localized outcome of a new-nights sync. */
internal fun SyncOutcome.labelRes(): Int =
    when (this) {
        SyncOutcome.SUCCESS -> R.string.sync_outcome_success
        SyncOutcome.PARTIAL_FAILURE -> R.string.sync_outcome_partial
        SyncOutcome.QUOTA_EXCEEDED -> R.string.sync_outcome_quota
        SyncOutcome.PERMISSION_DENIED -> R.string.sync_outcome_permission
        SyncOutcome.HEALTH_CONNECT_UNAVAILABLE -> R.string.sync_outcome_unavailable
        SyncOutcome.BACKGROUND_READ_BLOCKED -> R.string.sync_outcome_background
        SyncOutcome.FAILED -> R.string.sync_outcome_failed
        SyncOutcome.CANCELLED -> R.string.sync_outcome_cancelled
    }

/**
 * Found / estimated / skipped / stale (and failed, when any) source sessions, then the written nights and naps apart, as
 * number tiles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CounterTiles(counters: HomeCounters) {
    val spacing = MaterialTheme.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.large),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        CounterTile(counters.found, stringResource(R.string.home_counter_found))
        CounterTile(counters.estimated, stringResource(R.string.home_counter_estimated))
        CounterTile(counters.skipped, stringResource(R.string.home_counter_skipped))
        CounterTile(counters.stale, stringResource(R.string.home_counter_stale))
        if (counters.failed > 0) CounterTile(counters.failed, stringResource(R.string.home_counter_failed))
        CounterTile(counters.nights, stringResource(R.string.home_counter_nights))
        CounterTile(counters.naps, stringResource(R.string.home_counter_naps))
    }
}

@Composable
private fun CounterTile(
    value: Int,
    label: String,
) {
    Column(modifier = Modifier.widthIn(min = 64.dp).semantics(mergeDescendants = true) {}) {
        Text(text = value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The jobs: estimate new, backfill, recompute and delete. */
@Composable
internal fun JobsCard(
    state: HomeUiState,
    actions: HomeActions,
) {
    HomeCard(title = stringResource(R.string.home_jobs_title)) {
        if (!state.ready) {
            Text(
                text = stringResource(R.string.home_jobs_not_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        EstimateSection(state, actions)
        HorizontalDivider()
        BackfillSection(state, actions)
        HorizontalDivider()
        RecomputeSection(state, actions)
        HorizontalDivider()
        DeleteSection(state, actions)
    }
}

@Composable
private fun EstimateSection(
    state: HomeUiState,
    actions: HomeActions,
) {
    SectionText(stringResource(R.string.home_estimate_title), stringResource(R.string.home_estimate_body))
    if (state.estimateNow.active) {
        JobProgressLine(state.estimateNow, stringResource(R.string.home_estimate_running))
    } else {
        ActionRow { PrimaryAction(stringResource(R.string.home_action_estimate), enabled = state.ready, onClick = actions.onEstimateNow) }
    }
}

@Composable
private fun BackfillSection(
    state: HomeUiState,
    actions: HomeActions,
) {
    val historyLimited = state.permissions?.history != SpecialAccess.GRANTED
    SectionText(
        stringResource(R.string.home_backfill_title),
        stringResource(if (historyLimited) R.string.home_backfill_body_limited else R.string.home_backfill_body),
    )
    val progress = state.backfillProgress
    if (state.backfill.active) {
        val text = progress?.let { backfillProgressText(it) } ?: stringResource(R.string.home_backfill_running)
        JobProgressLine(state.backfill, text)
        ActionRow { SecondaryAction(stringResource(R.string.action_cancel), onClick = actions.onCancelBackfill) }
    } else {
        state.backfillState?.let { BackfillLastResult(it) }
        val label = if (state.backfillResumable) R.string.home_action_backfill_resume else R.string.home_action_backfill
        ActionRow { PrimaryAction(stringResource(label), enabled = state.ready, onClick = actions.onStartBackfill) }
    }
}

@Composable
private fun backfillProgressText(progress: JobProgress): String =
    stringResource(R.string.home_backfill_progress, progress.done, progress.total, progress.nightsFound, progress.nightsEstimated)

@Composable
private fun BackfillLastResult(backfill: BackfillStateEntity) {
    val text =
        when (backfill.status) {
            BackfillStatus.IDLE -> {
                null
            }

            BackfillStatus.DONE -> {
                stringResource(
                    R.string.home_backfill_done,
                    localDateTime(backfill.updatedAt).formatDateTime(),
                    pluralStringResource(R.plurals.result_estimated, backfill.nightsEstimated, backfill.nightsEstimated),
                )
            }

            BackfillStatus.CANCELLED -> {
                stringResource(R.string.home_backfill_cancelled, backfill.windowsDone, backfill.windowsTotal)
            }

            BackfillStatus.FAILED -> {
                stringResource(R.string.home_backfill_failed, backfill.windowsDone, backfill.windowsTotal)
            }

            BackfillStatus.RUNNING, BackfillStatus.WAITING_RETRY -> {
                stringResource(R.string.home_backfill_interrupted, backfill.windowsDone, backfill.windowsTotal)
            }
        }
    text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (backfill.historyLimited && backfill.status == BackfillStatus.DONE) {
        Text(stringResource(R.string.home_backfill_was_limited), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecomputeSection(
    state: HomeUiState,
    actions: HomeActions,
) {
    val stale = state.counters?.stale ?: 0
    SectionText(
        stringResource(R.string.home_recompute_title),
        if (stale > 0) {
            pluralStringResource(R.plurals.home_recompute_stale_body, stale, stale)
        } else {
            stringResource(R.string.home_recompute_body)
        },
    )
    if (state.overwritePolicy == OverwritePolicy.NEVER) {
        Text(
            text = stringResource(R.string.home_recompute_never),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    if (state.recompute.active) {
        val progress = state.recompute.progress
        val text =
            if (progress != null && progress.total > 0) {
                stringResource(R.string.home_recompute_progress, progress.done, progress.total)
            } else {
                stringResource(R.string.home_recompute_running)
            }
        JobProgressLine(state.recompute, text)
    } else {
        ActionRow {
            if (stale > 0) {
                PrimaryAction(
                    stringResource(R.string.home_action_recompute_stale),
                    enabled = state.ready,
                    onClick = actions.onRecomputeStale,
                )
            }
            SecondaryAction(stringResource(R.string.home_action_recompute_all), enabled = state.ready, onClick = actions.onRecomputeAll)
        }
    }
}

@Composable
private fun DeleteSection(
    state: HomeUiState,
    actions: HomeActions,
) {
    SectionText(stringResource(R.string.home_delete_title), stringResource(R.string.home_delete_body))
    if (state.erasing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.home_delete_running), style = MaterialTheme.typography.bodyMedium)
    } else {
        ActionRow {
            SecondaryAction(
                stringResource(R.string.home_action_delete_all),
                enabled = state.availability == HcAvailability.AVAILABLE,
                destructive = true,
                onClick = actions.onDeleteAll,
            )
        }
    }
}

@Composable
private fun SectionText(
    title: String,
    body: String,
) {
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A progress bar (determinate when the progress is known) and what the job is doing. */
@Composable
private fun JobProgressLine(
    job: JobUi,
    runningText: String,
) {
    val progress = job.progress
    if (job.phase == JobPhase.RUNNING && progress != null && progress.total > 0) {
        LinearProgressIndicator(
            progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val text =
        when {
            job.retrying -> stringResource(R.string.home_job_retrying)
            job.phase == JobPhase.PENDING -> stringResource(R.string.home_job_pending)
            else -> runningText
        }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/** What PulseStages does, and the link to the privacy screen. */
@Composable
internal fun AboutCard(onOpenPrivacy: () -> Unit) {
    HomeCard(title = stringResource(R.string.home_about_title)) {
        Text(text = stringResource(R.string.home_body), style = MaterialTheme.typography.bodyMedium)
        ActionRow { SecondaryAction(stringResource(R.string.home_action_privacy), onClick = onOpenPrivacy) }
    }
}
