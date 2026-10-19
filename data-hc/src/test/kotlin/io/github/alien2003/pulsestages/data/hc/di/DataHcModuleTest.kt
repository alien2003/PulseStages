package io.github.alien2003.pulsestages.data.hc.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.data.hc.BackgroundRuleHealthRepository
import io.github.alien2003.pulsestages.data.hc.HealthConnectRepository
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeNightEstimator
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillEngine
import io.github.alien2003.pulsestages.data.hc.pipeline.DefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.pipeline.MyDataEraser
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import io.github.alien2003.pulsestages.data.hc.pipeline.NightPipeline
import io.github.alien2003.pulsestages.data.hc.pipeline.NightRecomputer
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceSettlement
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Builds the real Hilt graph of :data-hc together with :data-local (Robolectric), with a test [NightEstimator] (the
 * real one is bound by :work / :app). Dagger only validates bindings that are requested, so every entry point of
 * this module is requested here.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class DataHcModuleTest {
    /** The binding :work / :app provide in production. */
    @Module
    @InstallIn(SingletonComponent::class)
    object TestEstimatorModule {
        /** A deterministic estimator. */
        @Provides
        fun provideNightEstimator(): NightEstimator = FakeNightEstimator()
    }

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: HealthRepository

    @Inject
    @field:PlatformHealthRepository
    lateinit var platformRepository: HealthRepository

    @Inject lateinit var pipeline: NightPipeline

    @Inject lateinit var sync: NewNightsSync

    @Inject lateinit var backfill: BackfillEngine

    @Inject lateinit var otherBackfill: BackfillEngine

    @Inject lateinit var recomputer: NightRecomputer

    @Inject lateinit var otherRecomputer: NightRecomputer

    @Inject lateinit var eraser: MyDataEraser

    @Inject lateinit var scanner: SourceScanner

    @Inject lateinit var settlement: SourceSettlement

    @Inject lateinit var guard: HealthAccessGuard

    @Inject lateinit var titles: DefaultTitleProvider

    @Inject
    @field:OwnPackageName
    lateinit var ownPackage: String

    @Inject lateinit var database: PulseStagesDatabase

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        database.close()
        applicationScope.cancel()
    }

    @Test
    fun graphProvidesTheHealthConnectLayer() {
        assertTrue("the app reads behind the background rule", repository is BackgroundRuleHealthRepository)
        assertTrue(platformRepository is HealthConnectRepository)
        assertSame("the backfill state machine is a singleton", backfill, otherBackfill)
        assertSame("one recomputer, so that erasing reaches the running recompute", recomputer, otherRecomputer)
        assertTrue(ownPackage, ownPackage.startsWith("io.github.alien2003.pulsestages.data.hc"))
        assertTrue(titles.defaultTitleTemplate().isNotBlank())
        listOf(pipeline, sync, recomputer, eraser, scanner, settlement, guard).forEach { assertNotNull(it) }
    }
}
