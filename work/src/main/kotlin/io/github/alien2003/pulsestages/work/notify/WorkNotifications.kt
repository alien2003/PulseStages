package io.github.alien2003.pulsestages.work.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.work.R
import io.github.alien2003.pulsestages.work.worker.JobProgress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The notification channel and the foreground notifications of the workers. The works the user asks for (backfill,
 * recompute, "estimate now") run as a foreground service of type `dataSync` (declared in this module's manifest
 * together with FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC and POST_NOTIFICATIONS), so that Health Connect keeps
 * treating their reads as foreground reads when the user leaves the app; on Android 11 and lower WorkManager also runs
 * expedited works as a foreground service with these notifications. Without the POST_NOTIFICATIONS grant (Android 13+)
 * the work still runs, the notification is just not shown in the drawer.
 */
@Singleton
class WorkNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Creates the low-importance channel of the background work (idempotent). */
        fun ensureChannel() {
            val channel =
                NotificationChannelCompat
                    .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.work_channel_name))
                    .setDescription(context.getString(R.string.work_channel_description))
                    .setShowBadge(false)
                    .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Foreground info of "estimate new nights" ("estimate now", and expedited work on Android 11 and lower). */
        fun estimateInfo(): ForegroundInfo =
            foregroundInfo(ESTIMATE_ID, notification(context.getString(R.string.work_estimate_title), text = null, progress = null))

        /** Foreground info of the backfill with its [progress] (windows of 7 days), or a starting notification. */
        fun backfillInfo(progress: JobProgress?): ForegroundInfo {
            val text =
                progress?.takeIf { it.total > 0 }?.let {
                    context.resources.getQuantityString(
                        R.plurals.work_backfill_progress,
                        it.nightsEstimated,
                        it.done,
                        it.total,
                        it.nightsEstimated,
                    )
                }
            return foregroundInfo(BACKFILL_ID, notification(context.getString(R.string.work_backfill_title), text, progress))
        }

        /** Foreground info of a recompute with its [progress] (nights), or a starting notification. */
        fun recomputeInfo(progress: JobProgress?): ForegroundInfo {
            val text =
                progress?.takeIf { it.total > 0 }?.let {
                    context.resources.getQuantityString(R.plurals.work_recompute_progress, it.total, it.done, it.total)
                }
            return foregroundInfo(RECOMPUTE_ID, notification(context.getString(R.string.work_recompute_title), text, progress))
        }

        private fun notification(
            title: String,
            text: String?,
            progress: JobProgress?,
        ): Notification {
            ensureChannel()
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pulsestages)
                    .setContentTitle(title)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            text?.let { builder.setContentText(it) }
            if (progress != null && progress.total > 0) {
                builder.setProgress(progress.total, progress.done.coerceIn(0, progress.total), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            openAppIntent()?.let { builder.setContentIntent(it) }
            return builder.build()
        }

        private fun openAppIntent(): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun foregroundInfo(
            id: Int,
            notification: Notification,
        ): ForegroundInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(id, notification)
            }

        /** Ids. */
        companion object {
            /** Channel of every work notification. */
            const val CHANNEL_ID: String = "pulsestages.work"

            /** Notification id of "estimate new nights". */
            const val ESTIMATE_ID: Int = 1001

            /** Notification id of the backfill. */
            const val BACKFILL_ID: Int = 1002

            /** Notification id of a recompute. */
            const val RECOMPUTE_ID: Int = 1003
        }
    }
