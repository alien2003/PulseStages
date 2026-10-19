package io.github.alien2003.pulsestages.work.saa

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The sync request to Sleep as Android on Robolectric: the bare intent of its Intent API, sent as one broadcast only
 * when Sleep as Android's receiver of the action is installed, which [BroadcastSyncRequestSender.isInstalled] looks up
 * without sending anything.
 */
@RunWith(RobolectricTestRunner::class)
class BroadcastSyncRequestSenderTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    private fun installReceiver(packageName: String = SleepAsAndroid.PACKAGE) {
        val packageManager = shadowOf(application.packageManager)
        val receiver = ComponentName(packageName, "$packageName.service.sync.RequestSyncReceiver")
        packageManager.addReceiverIfNotPresent(receiver)
        packageManager.addIntentFilterForReceiver(receiver, IntentFilter(SleepAsAndroid.ACTION_REQUEST_SYNC))
    }

    @Test
    fun theRequestIsTheBareActionRestrictedToSleepAsAndroid() {
        val intent = SleepAsAndroid.requestSyncIntent()

        assertEquals("com.urbandroid.sleep.REQUEST_SYNC", intent.action)
        assertEquals("com.urbandroid.sleep", intent.`package`)
        assertNull("no extras", intent.extras)
        assertNull("no data", intent.data)
        assertNull("no type", intent.type)
        assertNull("no categories", intent.categories)
        assertNull("no component: Sleep as Android's receiver class is not part of its API", intent.component)
        assertEquals("no flags: a stopped Sleep as Android is not woken", 0, intent.flags)
    }

    @Test
    fun withoutSleepAsAndroidNothingIsSent() {
        assertFalse(BroadcastSyncRequestSender(application).isInstalled())
        assertFalse(BroadcastSyncRequestSender(application).send())

        assertTrue(shadowOf(application).broadcastIntents.isEmpty())
    }

    @Test
    fun anotherAppWithAReceiverOfTheActionDoesNotCount() {
        installReceiver("org.example.other")

        assertFalse(BroadcastSyncRequestSender(application).isInstalled())
        assertFalse(BroadcastSyncRequestSender(application).send())

        assertTrue(shadowOf(application).broadcastIntents.isEmpty())
    }

    @Test
    fun withItsReceiverInstalledTheLookupSaysSoAndSendsNothing() {
        installReceiver()

        assertTrue(BroadcastSyncRequestSender(application).isInstalled())

        assertTrue("a lookup only", shadowOf(application).broadcastIntents.isEmpty())
    }

    @Test
    fun withItsReceiverInstalledExactlyOneBareBroadcastIsSent() {
        installReceiver()

        assertTrue(BroadcastSyncRequestSender(application).send())

        val sent = shadowOf(application).broadcastIntents.single()
        assertEquals(SleepAsAndroid.ACTION_REQUEST_SYNC, sent.action)
        assertEquals(SleepAsAndroid.PACKAGE, sent.`package`)
        assertNull(sent.extras)
        assertNull(sent.component)
    }
}
