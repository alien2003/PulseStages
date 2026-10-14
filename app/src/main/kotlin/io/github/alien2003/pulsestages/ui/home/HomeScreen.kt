package io.github.alien2003.pulsestages.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alien2003.pulsestages.PermissionsRationaleActivity
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.usecase.PermissionStatus
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.platform.HealthConnectIntents
import io.github.alien2003.pulsestages.platform.startFirstAvailable
import io.github.alien2003.pulsestages.ui.common.ActionRow
import io.github.alien2003.pulsestages.ui.common.ConfirmDialog
import io.github.alien2003.pulsestages.ui.common.MessageSnackbarEffect
import io.github.alien2003.pulsestages.ui.common.PrimaryAction
import io.github.alien2003.pulsestages.ui.common.SecondaryAction
import io.github.alien2003.pulsestages.ui.common.isWideLayout
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import io.github.alien2003.pulsestages.ui.theme.spacing

/** Whether the app may post notifications (backfill progress); runtime permission on Android 13+. */
enum class NotificationAccess {
    /** Android 12 and lower: no runtime permission. */
    NOT_NEEDED,

    /** Granted. */
    GRANTED,

    /** Not granted (the backfill still runs, without a visible notification). */
    NOT_GRANTED,
}

/** Home tab: Health Connect and permission status, sources and schedule, counters and the background jobs. */
@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(notificationAccess(context)) }
    val healthLauncher =
        rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            viewModel.onPermissionsResult(granted)
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifications = notificationAccess(context)
            if (!granted) viewModel.onNotificationsDenied()
        }
    // Permissions can change in Health Connect or the system settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        notifications = notificationAccess(context)
    }

    fun request(permissions: Set<String>) {
        viewModel.onPermissionRequestStarted(permissions)
        try {
            healthLauncher.launch(permissions)
        } catch (ignored: ActivityNotFoundException) {
            viewModel.onExternalScreenUnavailable()
        }
    }

    fun open(vararg intents: Intent) {
        if (!context.startFirstAvailable(*intents)) viewModel.onExternalScreenUnavailable()
    }

    HomeScreen(
        state = state,
        notifications = notifications,
        actions =
            HomeActions(
                onGrant = { state.permissions?.let { request(it.requestable) } },
                onGrantHistory = { request(setOf(HealthPermissions.READ_HEALTH_DATA_HISTORY)) },
                onGrantBackground = { request(setOf(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)) },
                onAllowNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenNotificationSettings = { open(notificationSettings(context)) },
                onInstallHealthConnect = {
                    open(HealthConnectIntents.installOrUpdate(context), HealthConnectIntents.installOrUpdateInBrowser())
                },
                onOpenHealthConnect = { open(HealthConnectIntents.managePermissions(context), HealthConnectIntents.settings()) },
                onOpenPrivacy = {
                    open(
                        Intent(context, PermissionsRationaleActivity::class.java)
                            .putExtra(PermissionsRationaleActivity.EXTRA_FROM_APP, true),
                    )
                },
                onRetry = viewModel::refresh,
                onEstimateNow = viewModel::estimateNow,
                onStartBackfill = viewModel::startBackfill,
                onCancelBackfill = viewModel::cancelBackfill,
                onRecomputeStale = viewModel::recomputeStale,
                onRecomputeAll = viewModel::requestRecomputeAll,
                onConfirmRecomputeAll = viewModel::confirmRecomputeAll,
                onDeleteAll = viewModel::requestDeleteAll,
                onConfirmDeleteAll = viewModel::confirmDeleteAll,
                onDismissDialog = viewModel::dismissDialog,
                onMessageShown = viewModel::messageShown,
            ),
    )
}

/**
 * Callbacks of [HomeScreen].
 *
 * @property onGrant request every permission that is still missing.
 * @property onGrantHistory request READ_HEALTH_DATA_HISTORY.
 * @property onGrantBackground request READ_HEALTH_DATA_IN_BACKGROUND.
 * @property onAllowNotifications request POST_NOTIFICATIONS (Android 13+).
 * @property onOpenNotificationSettings open the app's notification settings.
 * @property onInstallHealthConnect open the Play Store page that installs or updates Health Connect.
 * @property onOpenHealthConnect open the app's permissions in Health Connect.
 * @property onOpenPrivacy open the privacy / data-use screen.
 * @property onRetry read the status again.
 * @property onEstimateNow "Estimate new".
 * @property onStartBackfill "Backfill history".
 * @property onCancelBackfill cancel the backfill.
 * @property onRecomputeStale recompute the stale nights.
 * @property onRecomputeAll "Recompute all" (opens the confirmation).
 * @property onConfirmRecomputeAll the recompute was confirmed.
 * @property onDeleteAll "Delete my sessions" (opens the confirmation).
 * @property onConfirmDeleteAll the deletion was confirmed.
 * @property onDismissDialog a confirmation was dismissed.
 * @property onMessageShown a Snackbar message was shown.
 */
data class HomeActions(
    val onGrant: () -> Unit = {},
    val onGrantHistory: () -> Unit = {},
    val onGrantBackground: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onInstallHealthConnect: () -> Unit = {},
    val onOpenHealthConnect: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onEstimateNow: () -> Unit = {},
    val onStartBackfill: () -> Unit = {},
    val onCancelBackfill: () -> Unit = {},
    val onRecomputeStale: () -> Unit = {},
    val onRecomputeAll: () -> Unit = {},
    val onConfirmRecomputeAll: () -> Unit = {},
    val onDeleteAll: () -> Unit = {},
    val onConfirmDeleteAll: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onMessageShown: (Long) -> Unit = {},
)

/** Stateless Home screen; wide windows show the status and the jobs in two columns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    notifications: NotificationAccess,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbarEffect(state.messages, snackbarHostState, actions.onMessageShown)
    val wide = isWideLayout()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                        AccessCards(state, notifications, actions)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                        WorkCards(state, actions)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.medium),
                ) {
                    AccessCards(state, notifications, actions)
                    WorkCards(state, actions)
                }
            }
        }
    }
    HomeDialogs(state.dialog, actions)
}

@Composable
private fun AccessCards(
    state: HomeUiState,
    notifications: NotificationAccess,
    actions: HomeActions,
) {
    if (state.failed) StatusFailedCard(actions.onRetry)
    HealthConnectCard(state.availability, actions)
    if (state.availability == HcAvailability.AVAILABLE) PermissionsCard(state, notifications, actions)
}

@Composable
private fun WorkCards(
    state: HomeUiState,
    actions: HomeActions,
) {
    ScheduleCard(state)
    state.counters?.let { CountersCard(it) }
    JobsCard(state, actions)
    AboutCard(onOpenPrivacy = actions.onOpenPrivacy)
}

@Composable
private fun StatusFailedCard(onRetry: () -> Unit) {
    HomeCard(title = stringResource(R.string.home_status_failed_title)) {
        Text(stringResource(R.string.home_status_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun HealthConnectCard(
    availability: HcAvailability?,
    actions: HomeActions,
) {
    HomeCard(title = stringResource(R.string.home_hc_title)) {
        when (availability) {
            null -> {
                StatusLine(headline = stringResource(R.string.hc_status_checking), supporting = null, status = LineStatus.INFO)
            }

            HcAvailability.AVAILABLE -> {
                StatusLine(headline = stringResource(R.string.hc_status_available), supporting = null, status = LineStatus.OK)
            }

            HcAvailability.PROVIDER_UPDATE_REQUIRED -> {
                StatusLine(
                    headline = stringResource(R.string.hc_status_update_required),
                    supporting = stringResource(R.string.home_hc_update_detail),
                    status = LineStatus.PROBLEM,
                )
                ActionRow { PrimaryAction(stringResource(R.string.home_action_install_hc), onClick = actions.onInstallHealthConnect) }
            }

            HcAvailability.NOT_SUPPORTED -> {
                StatusLine(
                    headline = stringResource(R.string.hc_status_unavailable),
                    supporting = stringResource(R.string.home_hc_unavailable_detail),
                    status = LineStatus.PROBLEM,
                )
                ActionRow { SecondaryAction(stringResource(R.string.action_retry), onClick = actions.onRetry) }
            }
        }
    }
}

@Composable
private fun PermissionsCard(
    state: HomeUiState,
    notifications: NotificationAccess,
    actions: HomeActions,
) {
    HomeCard(title = stringResource(R.string.home_permissions_title)) {
        val permissions = state.permissions
        if (permissions == null) {
            StatusLine(
                headline = stringResource(R.string.home_permissions_unknown),
                supporting = stringResource(R.string.home_permissions_unknown_detail),
                status = LineStatus.PROBLEM,
            )
            ActionRow { SecondaryAction(stringResource(R.string.action_retry), onClick = actions.onRetry) }
            return@HomeCard
        }
        PermissionLines(permissions, notifications)
        if (state.permissionDialogBlocked) {
            Text(
                text = stringResource(R.string.home_permissions_blocked_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ActionRow {
            PermissionButtons(state, permissions, notifications, actions)
        }
    }
}

@Composable
private fun PermissionLines(
    permissions: PermissionStatus,
    notifications: NotificationAccess,
) {
    val requiredGranted = HealthPermissions.REQUIRED.size - permissions.missingRequired.size
    StatusLine(
        headline = stringResource(R.string.home_permissions_required, requiredGranted, HealthPermissions.REQUIRED.size),
        supporting = stringResource(R.string.home_permissions_required_detail),
        status = if (permissions.allRequiredGranted) LineStatus.OK else LineStatus.PROBLEM,
    )
    OPTIONAL_LABELS.forEach { (permission, label) ->
        val granted = permission !in permissions.missingOptional
        StatusLine(
            headline = stringResource(label),
            supporting = stringResource(if (granted) R.string.home_permission_granted else R.string.home_permission_optional),
            status = if (granted) LineStatus.OK else LineStatus.INFO,
        )
    }
    SpecialLine(stringResource(R.string.home_history_title), permissions.history, stringResource(R.string.home_history_limited))
    SpecialLine(stringResource(R.string.home_background_title), permissions.background, stringResource(R.string.home_background_missing))
    if (notifications != NotificationAccess.NOT_NEEDED) {
        val granted = notifications == NotificationAccess.GRANTED
        StatusLine(
            headline = stringResource(R.string.home_notifications_title),
            supporting = stringResource(if (granted) R.string.home_permission_granted else R.string.home_notifications_missing),
            status = if (granted) LineStatus.OK else LineStatus.INFO,
        )
    }
}

@Composable
private fun PermissionButtons(
    state: HomeUiState,
    permissions: PermissionStatus,
    notifications: NotificationAccess,
    actions: HomeActions,
) {
    val blocked = state.permissionDialogBlocked
    if (blocked) {
        PrimaryAction(stringResource(R.string.home_action_open_hc_settings), onClick = actions.onOpenHealthConnect)
    } else if (state.canRequestMore) {
        PrimaryAction(stringResource(R.string.home_action_grant), onClick = actions.onGrant)
    }
    if (!blocked && permissions.history == SpecialAccess.NOT_GRANTED) {
        SecondaryAction(stringResource(R.string.home_action_grant_history), onClick = actions.onGrantHistory)
    }
    if (!blocked && permissions.background == SpecialAccess.NOT_GRANTED) {
        SecondaryAction(stringResource(R.string.home_action_grant_background), onClick = actions.onGrantBackground)
    }
    if (notifications == NotificationAccess.NOT_GRANTED) {
        SecondaryAction(stringResource(R.string.home_action_allow_notifications), onClick = actions.onAllowNotifications)
        SecondaryAction(stringResource(R.string.home_action_notification_settings), onClick = actions.onOpenNotificationSettings)
    }
    if (!blocked) SecondaryAction(stringResource(R.string.home_action_open_hc), onClick = actions.onOpenHealthConnect)
}

@Composable
private fun SpecialLine(
    title: String,
    access: SpecialAccess,
    missingText: String,
) {
    val supporting =
        when (access) {
            SpecialAccess.GRANTED -> stringResource(R.string.home_permission_granted)
            SpecialAccess.NOT_GRANTED -> missingText
            SpecialAccess.NOT_AVAILABLE -> stringResource(R.string.home_feature_unavailable, missingText)
        }
    StatusLine(
        headline = title,
        supporting = supporting,
        status = if (access == SpecialAccess.GRANTED) LineStatus.OK else LineStatus.PROBLEM,
    )
}

@Composable
private fun CountersCard(counters: HomeCounters) {
    HomeCard(title = stringResource(R.string.home_counters_title)) {
        Text(stringResource(R.string.home_counters_hint), style = MaterialTheme.typography.bodySmall)
        CounterTiles(counters)
    }
}

@Composable
internal fun HomeCard(
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

@Composable
private fun HomeDialogs(
    dialog: HomeDialog?,
    actions: HomeActions,
) {
    when (dialog) {
        HomeDialog.RECOMPUTE_ALL -> {
            ConfirmDialog(
                title = stringResource(R.string.home_recompute_confirm_title),
                body = stringResource(R.string.home_recompute_confirm_body),
                confirm = stringResource(R.string.home_action_recompute_all),
                onConfirm = actions.onConfirmRecomputeAll,
                onDismiss = actions.onDismissDialog,
            )
        }

        HomeDialog.DELETE_ALL -> {
            ConfirmDialog(
                title = stringResource(R.string.home_delete_confirm_title),
                body = stringResource(R.string.home_delete_confirm_body),
                confirm = stringResource(R.string.action_delete),
                onConfirm = actions.onConfirmDeleteAll,
                onDismiss = actions.onDismissDialog,
            )
        }

        null -> {}
    }
}

private fun notificationAccess(context: Context): NotificationAccess =
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> {
            NotificationAccess.NOT_NEEDED
        }

        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
            NotificationAccess.GRANTED
        }

        else -> {
            NotificationAccess.NOT_GRANTED
        }
    }

private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private val OPTIONAL_LABELS: List<Pair<String, Int>> =
    listOf(
        HealthPermissions.READ_OXYGEN_SATURATION to R.string.home_permission_spo2,
        HealthPermissions.READ_RESPIRATORY_RATE to R.string.home_permission_respiratory_rate,
        HealthPermissions.READ_HEART_RATE_VARIABILITY to R.string.home_permission_hrv,
    )

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    PulseStagesTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    loading = false,
                    availability = HcAvailability.AVAILABLE,
                    permissions =
                        PermissionStatus(
                            granted = HealthPermissions.REQUIRED,
                            missingRequired = emptySet(),
                            missingOptional = HealthPermissions.OPTIONAL,
                            history = SpecialAccess.NOT_GRANTED,
                            background = SpecialAccess.GRANTED,
                        ),
                    sources = listOf(SourceLabel("com.heytap.health.international", null)),
                    counters = HomeCounters(found = 120, estimated = 112, skipped = 8, failed = 0, stale = 3, nights = 104, naps = 6),
                ),
            notifications = NotificationAccess.NOT_GRANTED,
            actions = HomeActions(),
        )
    }
}
