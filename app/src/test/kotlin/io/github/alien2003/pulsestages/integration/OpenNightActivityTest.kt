package io.github.alien2003.pulsestages.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.MainActivity
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.hc.BackgroundRuleHealthRepository
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.di.DataHcBindingsModule
import io.github.alien2003.pulsestages.data.hc.di.PlatformHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.DefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.ResourceDefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.di.ApplicationScope
import io.github.alien2003.pulsestages.ui.common.formatDate
import io.github.alien2003.pulsestages.work.notify.OpenNight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tapped notification of a night ([OpenNight]) in the real MainActivity, its navigation and its screens, over the
 * app's Hilt graph with the in-memory Health Connect (as in [EndToEndWiringTest]) on Robolectric (JUnit 4 via the Vintage
 * engine): it opens that night's detail at launch and while the app is open, again after the user left it, and not
 * again when the activity is recreated, relaunched from Recents or started from the launcher.
 */
@HiltAndroidTest
@UninstallModules(DataHcBindingsModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class OpenNightActivityTest {
    /** DataHcBindingsModule with the fake as the platform repository. */
    @Module
    @InstallIn(SingletonComponent::class)
    object FakePlatformModule {
        /** One fake Health Connect for the whole graph. */
        @Provides
        @Singleton
        fun provideFake(
            @ApplicationContext context: Context,
        ): FakeHealthRepository = FakeHealthRepository(ownPackage = context.packageName, now = System::currentTimeMillis)

        /** The fake in place of HealthConnectRepository. */
        @Provides
        @PlatformHealthRepository
        fun providePlatform(fake: FakeHealthRepository): HealthRepository = fake

        /** As in production: the background rule in front of the platform repository. */
        @Provides
        fun provideRepository(implementation: BackgroundRuleHealthRepository): HealthRepository = implementation

        /** As in production. */
        @Provides
        fun provideTitles(implementation: ResourceDefaultTitleProvider): DefaultTitleProvider = implementation
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var nightDao: EstimatedNightDao

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var database: PulseStagesDatabase

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @field:ApplicationContext
    lateinit var context: Context

    private var controller: ActivityController<MainActivity>? = null

    private val firstStart = System.currentTimeMillis() - 3 * DAY_MS
    private val secondStart = firstStart + DAY_MS

    @Before
    fun setUp() {
        hiltRule.inject()
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.INFO)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        runBlocking {
            nightDao.upsert(night(FIRST, firstStart))
            nightDao.upsert(night(SECOND, secondStart))
        }
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        database.close()
        applicationScope.cancel()
    }

    private fun night(
        clientRecordId: String,
        startMs: Long,
    ) = EstimatedNightEntity(
        clientRecordId = clientRecordId,
        sourceUuids = listOf("uuid-$clientRecordId"),
        sourcePackage = OHEALTH,
        sessionStartMs = startMs,
        sessionEndMs = startMs + 8 * HOUR_MS,
        startZoneOffsetSec = 0,
        endZoneOffsetSec = 0,
        hcRecordId = "hc-$clientRecordId",
        algoVersion = 1,
        paramsHash = "p",
        estimator = EstimatorType.HYBRID,
        status = NightStatus.WRITTEN,
        createdAt = 0L,
        updatedAt = 0L,
    )

    /** The intent of a notification of the night [clientRecordId] ([OpenNight.pendingIntent]), with [extraFlags]. */
    private fun openNight(
        clientRecordId: String,
        extraFlags: Int = 0,
    ): Intent =
        Intent
            .makeMainActivity(ComponentName(context, MainActivity::class.java))
            .putExtra(OpenNight.EXTRA_CLIENT_RECORD_ID, clientRecordId)
            .addFlags(OpenNight.FLAGS or extraFlags)

    private fun launcher(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun launch(intent: Intent): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().also {
            controller = it
            idle()
        }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** Runs the main looper and Compose until [ready] holds (the ViewModels load on background dispatchers). */
    private fun waitUntil(ready: () -> Boolean) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            ready()
        }
        compose.waitForIdle()
    }

    private fun dateOf(startMs: Long): String = Instant.ofEpochMilli(startMs).atOffset(ZoneOffset.UTC).formatDate()

    private fun shows(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** A night's detail is shown: it has a Back button, which neither the tabs nor the list of nights have. */
    private fun detailShown(): Boolean =
        compose.onAllNodesWithContentDescription(context.getString(R.string.action_back)).fetchSemanticsNodes().isNotEmpty()

    /** Waits for the detail of the night that starts at [startMs]. */
    private fun awaitNight(startMs: Long) = waitUntil { detailShown() && shows(dateOf(startMs)) }

    private fun back() {
        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        idle()
    }

    @Test
    fun aNotificationOpensItsNightAtLaunchAndWhileTheAppIsOpen() {
        val activity = launch(openNight(FIRST))
        awaitNight(firstStart)

        activity.newIntent(openNight(SECOND))
        awaitNight(secondStart)

        activity.newIntent(launcher())
        idle()
        assertTrue("the launcher leaves the night open", detailShown() && shows(dateOf(secondStart)))
    }

    @Test
    fun theSameNightOpensAgainAfterTheUserLeftIt() {
        val activity = launch(openNight(FIRST))
        awaitNight(firstStart)
        back()
        assertFalse(detailShown())

        activity.newIntent(openNight(FIRST))

        awaitNight(firstStart)
    }

    @Test
    fun aRecreatedActivityDoesNotOpenTheNightAgain() {
        val activity = launch(openNight(FIRST))
        awaitNight(firstStart)
        back()

        activity.recreate()
        idle()

        assertFalse("the list stays", detailShown())
    }

    @Test
    fun aRelaunchFromRecentsOpensNoNight() {
        launch(openNight(FIRST, extraFlags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))

        assertFalse(detailShown())
    }

    /** Clients and limits. */
    companion object {
        private const val FIRST = "ps:0first"
        private const val SECOND = "ps:0second"
        private const val TIMEOUT_MS = 10_000L
    }
}
