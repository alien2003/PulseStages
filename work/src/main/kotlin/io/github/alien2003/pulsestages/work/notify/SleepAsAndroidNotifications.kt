package io.github.alien2003.pulsestages.work.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.work.R
import io.github.alien2003.pulsestages.work.saa.ChangedNight
import io.github.alien2003.pulsestages.work.saa.NoticeResult
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroid
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidNotices
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The notification "Check a night in Sleep as Android" ([SleepAsAndroidNotices]): a night that PulseStages changed
 * after Sleep as Android was asked to sync, which Sleep as Android may keep in its earlier version (or versions, when
 * nights of the request were merged), with how to repair it: delete every imported record from
 * [ChangedNight.repairStartMs] to [ChangedNight.repairEndMs] there, since Sleep as Android skips a session that
 * overlaps any record it holds, then recompute (under "Never overwrite", [ChangedNight.neverOverwrite], tap "Sync now"
 * in Sleep as Android instead). It has its own channel at default importance ([CHANNEL_ID]; the progress channel is
 * low, and a channel's importance cannot be raised later), created at the first notice that notifications allow, so the
 * user can turn it off on its own. One notification per night: tagged with the night's clientRecordId under
 * [NOTICE_ID], so a newer version replaces it without a new alert while it is shown, the notices of the nights it
 * replaced are cancelled (a [ChangedNight.silent] one alerts no more), and [withdraw] removes it. It is not bridged to
 * a watch by PulseStages (local only; the repair needs the phone). It is private: on a lock screen set to hide
 * sensitive content it shows only its public version, "A night in Sleep as Android may be out of date" (no date or
 * times); otherwise Android shows its full text there too. Tapping it opens the night in the app ([OpenNight]); its
 * action opens Sleep as Android when that can be launched.
 *
 * Dates and times use the default locale, like the app's screens.
 */
@Singleton
class SleepAsAndroidNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SleepAsAndroidNotices {
        override fun show(change: ChangedNight): NoticeResult {
            val manager = NotificationManagerCompat.from(context)
            return when {
                !allowed(manager) -> NoticeResult.NOT_ALLOWED
                !channelOn(manager) -> NoticeResult.CHANNEL_OFF
                else -> post(manager, change)
            }
        }

        /** [allowed] on this device. */
        private fun allowed(manager: NotificationManagerCompat): Boolean =
            allowed(Build.VERSION.SDK_INT, manager.areNotificationsEnabled()) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }

        /**
         * Creates the channel of the notice (idempotent: the user's changes to it are kept; only once notifications are
         * allowed) and tells whether the user left it on.
         */
        private fun channelOn(manager: NotificationManagerCompat): Boolean {
            val channel =
                NotificationChannelCompat
                    .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.work_saa_channel_name))
                    .setDescription(context.getString(R.string.work_saa_channel_description))
                    .build()
            manager.createNotificationChannel(channel)
            return manager.getNotificationChannelCompat(CHANNEL_ID)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        override fun withdraw(clientRecordId: String) {
            NotificationManagerCompat.from(context).cancel(clientRecordId, NOTICE_ID)
        }

        /** Posts the notice of [change] under its night's tag and cancels the notices of the nights it replaced. */
        @SuppressLint("MissingPermission") // Checked first ([allowed]); a revoke in between is caught.
        private fun post(
            manager: NotificationManagerCompat,
            change: ChangedNight,
        ): NoticeResult {
            change.replaced.forEach { manager.cancel(it, NOTICE_ID) }
            return try {
                manager.notify(change.night.clientRecordId, NOTICE_ID, notification(change))
                NoticeResult.SHOWN
            } catch (ignored: SecurityException) {
                NoticeResult.NOT_ALLOWED
            }
        }

        private fun notification(change: ChangedNight): Notification {
            val night = change.night
            val startOffset = night.startZoneOffsetSec
            val endOffset = night.endZoneOffsetSec
            val date = date(night.sessionStartMs, startOffset)
            val start = time(night.sessionStartMs, startOffset)
            val end = time(night.sessionEndMs, endOffset)
            val text =
                if (change.sentSpans.size > 1) {
                    val spans =
                        change.sentSpans.joinToString(", ") {
                            context.getString(R.string.work_saa_span, time(it.startMs, startOffset), time(it.endMs, endOffset))
                        }
                    context.getString(R.string.work_saa_changed_text_several, date, start, end, spans)
                } else {
                    context.getString(
                        R.string.work_saa_changed_text,
                        date,
                        start,
                        end,
                        time(change.sentStartMs, startOffset),
                        time(change.sentEndMs, endOffset),
                    )
                }
            val steps =
                context.getString(
                    // Under "Never overwrite" a recompute rewrites nothing and owes no request: the user syncs instead.
                    if (change.neverOverwrite) R.string.work_saa_changed_steps_never else R.string.work_saa_changed_steps,
                    time(change.repairStartMs, startOffset),
                    time(change.repairEndMs, endOffset),
                )
            val title = context.getString(R.string.work_saa_changed_title)
            val publicVersion =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pulsestages)
                    .setContentTitle(context.getString(R.string.work_saa_changed_public))
                    .build()
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pulsestages)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$steps"))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(change.silent)
                    // Not bridged to a watch: the repair needs the phone.
                    .setLocalOnly(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
            OpenNight.pendingIntent(context, night.clientRecordId)?.let { builder.setContentIntent(it) }
            // The action does not dismiss the notice: the user comes back to it for "Recompute" (or its steps).
            openSleepAsAndroid()?.let { builder.addAction(0, context.getString(R.string.work_saa_open_app), it) }
            return builder.build()
        }

        /** Opens Sleep as Android when it can be launched (the app manifest queries its package), or null. */
        private fun openSleepAsAndroid(): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(SleepAsAndroid.PACKAGE)?.let { intent ->
                PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun date(
            epochMs: Long,
            offsetSec: Int,
        ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local(epochMs, offsetSec))

        private fun time(
            epochMs: Long,
            offsetSec: Int,
        ): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local(epochMs, offsetSec))

        private fun local(
            epochMs: Long,
            offsetSec: Int,
        ) = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSec))

        /** Ids and the permission rule. */
        companion object {
            /** Channel of the notice. */
            const val CHANNEL_ID: String = "pulsestages.saa"

            /** Notification id of the notice (1001–1003 are the progress notifications), tagged with the night. */
            const val NOTICE_ID: Int = 1004

            /**
             * Whether the notice may be posted on Android [sdkInt]: notifications are on for the app ([enabled]) and, on
             * Android 13+, POST_NOTIFICATIONS is [granted] (Android 12 and lower have no runtime permission).
             */
            internal fun allowed(
                sdkInt: Int,
                enabled: Boolean,
                granted: () -> Boolean,
            ): Boolean = enabled && (sdkInt < Build.VERSION_CODES.TIRAMISU || granted())
        }
    }
