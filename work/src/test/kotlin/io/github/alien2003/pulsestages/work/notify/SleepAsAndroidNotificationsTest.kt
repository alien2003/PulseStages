package io.github.alien2003.pulsestages.work.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.work.R
import io.github.alien2003.pulsestages.work.saa.ChangedNight
import io.github.alien2003.pulsestages.work.saa.NoticeResult
import io.github.alien2003.pulsestages.work.saa.SentSpan
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The notification "Check a night in Sleep as Android" on Robolectric (JUnit 4 via the Vintage engine): posted only with
 * notifications allowed and its channel on, one per night under the night's tag, with the night's times, a lock-screen
 * version without them, the intent that opens the night and the action that opens Sleep as Android. Only SDK 35 is
 * available to Robolectric offline, so Android 12 and lower (no runtime permission) are covered by the permission rule
 * alone ([SleepAsAndroidNotifications.allowed]).
 */
@RunWith(RobolectricTestRunner::class)
class SleepAsAndroidNotificationsTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = application.getSystemService(NotificationManager::class.java)
    private val notifications = SleepAsAndroidNotifications(application)
    private val launcher = ComponentName(application.packageName, "io.github.alien2003.pulsestages.MainActivity")
    private lateinit var locale: Locale

    @Before
    fun setUp() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
        registerLauncher(launcher)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
    }

    private fun registerLauncher(component: ComponentName) {
        val packageManager = shadowOf(application.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun grant() = shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * The night [id] of 19 Oct 2026 at UTC+2 from 02:20 UTC (04:20 local) to [endUtc] UTC, sent when it ended at
     * [sentEndUtc] UTC (the defaults show as 10:20 and 08:03).
     */
    private fun change(
        id: String = "ps:0a",
        endUtc: String = "08:20",
        sentEndUtc: String = "06:03",
        replaced: Set<String> = emptySet(),
        sentSpans: List<SentSpan> = listOf(SentSpan(utc("02:20"), utc(sentEndUtc))),
        silent: Boolean = false,
        neverOverwrite: Boolean = false,
    ) = ChangedNight(
        night =
            EstimatedNightEntity(
                clientRecordId = id,
                sourceUuids = listOf("a"),
                sourcePackage = "com.heytap.health.international",
                sessionStartMs = utc("02:20"),
                sessionEndMs = utc(endUtc),
                startZoneOffsetSec = 2 * 3600,
                endZoneOffsetSec = 2 * 3600,
                hcRecordId = "hc-$id",
                algoVersion = 1,
                paramsHash = "p",
                estimator = EstimatorType.HYBRID,
                status = NightStatus.WRITTEN,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        sentStartMs = utc("02:20"),
        sentEndMs = utc(sentEndUtc),
        replaced = replaced,
        otherSources = replaced.isNotEmpty(),
        sentSpans = sentSpans,
        silent = silent,
        neverOverwrite = neverOverwrite,
    )

    private fun utc(time: String): Long = Instant.parse("2026-10-19T$time:00Z").toEpochMilli()

    private fun posted(tag: String): Notification? = shadowOf(manager).getNotification(tag, SleepAsAndroidNotifications.NOTICE_ID)

    private fun Notification.text(key: String): String? = extras.getCharSequence(key)?.toString()

    @Test
    fun withoutThePermissionNothingIsPostedAndNoChannelCreated() {
        assertEquals(NoticeResult.NOT_ALLOWED, notifications.show(change()))

        assertEquals(0, shadowOf(manager).size())
        assertNull(manager.getNotificationChannel(SleepAsAndroidNotifications.CHANNEL_ID))
    }

    @Test
    fun theNoticeIsPostedUnderTheNightsTagOnItsOwnChannelWithTheTimesOfTheNight() {
        grant()

        assertEquals(NoticeResult.SHOWN, notifications.show(change(endUtc = "08:20")))

        val notification = checkNotNull(posted("ps:0a"))
        assertEquals("ps:0a", manager.activeNotifications.single().tag)
        assertEquals(SleepAsAndroidNotifications.NOTICE_ID, manager.activeNotifications.single().id)
        assertEquals(SleepAsAndroidNotifications.CHANNEL_ID, notification.channelId)
        val channel = manager.getNotificationChannel(SleepAsAndroidNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("Nights in Sleep as Android", channel.name)
        assertEquals("Check a night in Sleep as Android", notification.text(Notification.EXTRA_TITLE))
        val text =
            "19 Oct 2026: the night now lasts 04:20–10:20. When Sleep as Android was last asked to sync, it lasted 04:20–08:03, " +
                "and Sleep as Android never updates a night it imported."
        assertEquals(text, notification.text(Notification.EXTRA_TEXT))
        val bigText = notification.text(Notification.EXTRA_BIG_TEXT).orEmpty()
        assertTrue(
            bigText,
            bigText.startsWith(
                "$text\n\nIf Sleep as Android shows other times for this night, delete there every record with the cloud icon " +
                    "between 04:20 and 10:20, and do not undo the deletion.",
            ),
        )
        assertTrue(bigText, bigText.contains("tap “Recompute” on this night in PulseStages"))
        assertEquals(Notification.CATEGORY_STATUS, notification.category)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue("not bridged to a watch", NotificationCompat.getLocalOnly(notification))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = checkNotNull(notification.publicVersion)
        assertEquals("A night in Sleep as Android may be out of date", publicVersion.text(Notification.EXTRA_TITLE))
        assertNull("no date or times on a locked screen", publicVersion.text(Notification.EXTRA_TEXT))
    }

    @Test
    fun tappingTheNoticeOpensItsNightInTheApp() {
        grant()
        notifications.show(change())

        val pendingIntent = checkNotNull(posted("ps:0a")?.contentIntent)
        val shadow = shadowOf(pendingIntent)
        assertTrue(shadow.isActivity)
        assertTrue(shadow.isImmutable)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, shadow.flags and PendingIntent.FLAG_UPDATE_CURRENT)
        assertEquals(OpenNight.requestCodeOf("ps:0a"), shadow.requestCode)
        val intent = shadow.savedIntent
        assertEquals("ps:0a", intent.getStringExtra(OpenNight.EXTRA_CLIENT_RECORD_ID))
        assertEquals(OpenNight.FLAGS, intent.flags and OpenNight.FLAGS)
        assertEquals(launcher, intent.component)
        // As the launcher's intent (Launcher3: MAIN, LAUNCHER and the activity): a task that this tap starts is found
        // the same by the launcher icon.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(launcher)
        assertTrue(intent.filterEquals(launcherIntent))
        assertNull(intent.data)
        assertNull(intent.`package`)
    }

    @Test
    fun theNoticesOfTwoNightsOpenTheirOwnNight() {
        grant()
        notifications.show(change(id = "ps:0a"))
        notifications.show(change(id = "ps:0b"))

        val first = shadowOf(checkNotNull(posted("ps:0a")?.contentIntent)).savedIntent
        val second = shadowOf(checkNotNull(posted("ps:0b")?.contentIntent)).savedIntent
        assertEquals("ps:0a", first.getStringExtra(OpenNight.EXTRA_CLIENT_RECORD_ID))
        assertEquals("ps:0b", second.getStringExtra(OpenNight.EXTRA_CLIENT_RECORD_ID))
    }

    @Test
    fun nightsOfTheRequestMergedSinceAreListedAndTheRepairCoversThemAll() {
        grant()
        val spans = listOf(SentSpan(utc("02:20"), utc("06:03")), SentSpan(utc("06:40"), utc("08:15")))

        notifications.show(change(endUtc = "08:15", replaced = setOf("ps:0b"), sentSpans = spans))

        val notification = checkNotNull(posted("ps:0a"))
        val text =
            "19 Oct 2026: the night now lasts 04:20–10:15. When Sleep as Android was last asked to sync, it was separate nights " +
                "(04:20–08:03, 08:40–10:15), and Sleep as Android never updates a night it imported."
        assertEquals(text, notification.text(Notification.EXTRA_TEXT))
        assertTrue(
            notification.text(Notification.EXTRA_BIG_TEXT).orEmpty().contains("every record with the cloud icon between 04:20 and 10:15"),
        )
    }

    @Test
    fun underNeverOverwriteTheRepairIsASyncInSleepAsAndroid() {
        grant()

        notifications.show(change(neverOverwrite = true))

        val bigText = checkNotNull(posted("ps:0a")).text(Notification.EXTRA_BIG_TEXT).orEmpty()
        assertTrue(bigText, bigText.contains("delete there every record with the cloud icon between 04:20 and 10:20"))
        assertTrue(bigText, bigText.contains("Then tap “Sync now” in its Settings → Services"))
        assertTrue(bigText, bigText.contains("“Recompute” in PulseStages rewrites nothing"))
        assertFalse(bigText, bigText.contains("tap “Recompute” on this night"))
    }

    @Test
    fun theRepairCoversTheSpanSentWhenTheNightIsNowShorter() {
        grant()

        notifications.show(change(endUtc = "05:29", sentEndUtc = "06:03"))

        val bigText = checkNotNull(posted("ps:0a")).text(Notification.EXTRA_BIG_TEXT).orEmpty()
        assertTrue(bigText, bigText.contains("between 04:20 and 08:03"))
    }

    @Test
    fun aNoticeWithTheTimesOfTheNoticeItReplacesAlertsNoMore() {
        grant()

        notifications.show(change(id = "ps:0c", replaced = setOf("ps:0b"), silent = true))
        notifications.show(change(id = "ps:0d"))

        assertEquals("silenced", NotificationCompat.GROUP_KEY_SILENT, posted("ps:0c")?.group)
        assertNull("alerts as usual", posted("ps:0d")?.group)
        assertNotEquals(0, checkNotNull(posted("ps:0d")).flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun aWithdrawnNoticeIsRemoved() {
        grant()
        notifications.show(change(id = "ps:0a"))
        notifications.show(change(id = "ps:0b"))

        notifications.withdraw("ps:0a")

        assertEquals(listOf("ps:0b"), manager.activeNotifications.map { it.tag })
    }

    @Test
    fun aNewerVersionReplacesTheNoticeOfItsNightAndAMergedNightCancelsTheNightsItReplaced() {
        grant()
        notifications.show(change(endUtc = "07:00"))

        notifications.show(change(endUtc = "08:20"))

        assertEquals(1, shadowOf(manager).size())
        assertTrue(posted("ps:0a")?.text(Notification.EXTRA_TEXT).orEmpty().contains("04:20–10:20"))

        notifications.show(change(id = "ps:0ab", endUtc = "09:00", replaced = setOf("ps:0a", "ps:0b")))

        assertEquals(listOf("ps:0ab"), manager.activeNotifications.map { it.tag })
    }

    @Test
    fun notificationsTurnedOffForTheAppShowNothing() {
        grant()
        shadowOf(manager).setNotificationsEnabled(false)

        assertEquals(NoticeResult.NOT_ALLOWED, notifications.show(change()))

        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun aChannelTheUserTurnedOffShowsNothing() {
        grant()
        manager.createNotificationChannel(
            NotificationChannel(SleepAsAndroidNotifications.CHANNEL_ID, "Nights in Sleep as Android", NotificationManager.IMPORTANCE_NONE),
        )

        assertEquals(NoticeResult.CHANNEL_OFF, notifications.show(change()))

        assertEquals(0, shadowOf(manager).size())
        assertEquals(
            "the user's choice is kept",
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(SleepAsAndroidNotifications.CHANNEL_ID).importance,
        )
    }

    @Test
    fun theActionOpensSleepAsAndroidOnlyWhenItCanBeLaunched() {
        grant()
        notifications.show(change())
        assertTrue(posted("ps:0a")?.actions.isNullOrEmpty())

        val sleepAsAndroid = ComponentName(SleepAsAndroid.PACKAGE, "com.urbandroid.sleep.alarmclock.AlarmClock")
        registerLauncher(sleepAsAndroid)
        notifications.show(change())

        val action = checkNotNull(posted("ps:0a")?.actions?.single())
        assertEquals("Open Sleep as Android", action.title.toString())
        assertEquals(sleepAsAndroid, shadowOf(action.actionIntent).savedIntent.component)
    }

    @Test
    @Config(qualifiers = "ru")
    fun theNoticeIsInRussianInARussianLocale() {
        // As on a phone in Russian: the resources and java.time's formatting both in Russian (restored after the test).
        // The expected texts are read from the Russian resources: the repository's code stays in English.
        val russian = Locale.forLanguageTag("ru")
        Locale.setDefault(russian)
        grant()
        notifications.show(change())

        val notification = checkNotNull(posted("ps:0a"))
        val title = notification.text(Notification.EXTRA_TITLE)
        assertEquals(application.getString(R.string.work_saa_changed_title), title)
        assertNotEquals("Russian, not the default English", "Check a night in Sleep as Android", title)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(russian).format(LocalDate.of(2026, 10, 19))
        val text = application.getString(R.string.work_saa_changed_text, date, "04:20", "10:20", "04:20", "08:03")
        assertEquals(text, notification.text(Notification.EXTRA_TEXT))
        assertEquals(
            "$text\n\n${application.getString(R.string.work_saa_changed_steps, "04:20", "10:20")}",
            notification.text(Notification.EXTRA_BIG_TEXT),
        )
        assertEquals(application.getString(R.string.work_saa_changed_public), notification.publicVersion.text(Notification.EXTRA_TITLE))

        notifications.show(change(neverOverwrite = true))

        assertEquals(
            "$text\n\n${application.getString(R.string.work_saa_changed_steps_never, "04:20", "10:20")}",
            checkNotNull(posted("ps:0a")).text(Notification.EXTRA_BIG_TEXT),
        )
    }

    @Test
    fun beforeAndroid13OnlyTheAppsNotificationSettingCounts() {
        val denied = { false }
        val granted = { true }

        assertTrue(SleepAsAndroidNotifications.allowed(Build.VERSION_CODES.S_V2, enabled = true, granted = denied))
        assertFalse(SleepAsAndroidNotifications.allowed(Build.VERSION_CODES.S_V2, enabled = false, granted = granted))
        assertFalse(SleepAsAndroidNotifications.allowed(Build.VERSION_CODES.TIRAMISU, enabled = true, granted = denied))
        assertFalse(SleepAsAndroidNotifications.allowed(Build.VERSION_CODES.TIRAMISU, enabled = false, granted = granted))
        assertTrue(SleepAsAndroidNotifications.allowed(Build.VERSION_CODES.TIRAMISU, enabled = true, granted = granted))
    }
}
