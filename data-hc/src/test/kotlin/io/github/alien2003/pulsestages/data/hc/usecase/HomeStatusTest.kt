package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind
import io.github.alien2003.pulsestages.algo.personal.PersonalProvenance
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcFeature
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind as StoredKind

class HomeStatusTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the home status combines access, sources, the last run and the counters`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.addNight(OHEALTH, nightStart(5), durationMs = 30 * 60_000L)
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OTHER_APP, OHEALTH), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }
            h.sync.run(inBackground = false)

            val status = h.homeStatus.status().first { it.counters.estimated == 2 }

            assertEquals(HcAvailability.AVAILABLE, status.access.availability)
            val permissions = checkNotNull(status.access.permissions)
            assertTrue(permissions.allRequiredGranted)
            assertEquals(SpecialAccess.GRANTED, permissions.history)
            assertEquals(SpecialAccess.GRANTED, permissions.background)
            assertEquals(listOf(OHEALTH, OTHER_APP), status.enabledSources, "priority order")
            assertEquals(SyncOutcome.SUCCESS, status.lastRun.outcome)
            assertFalse(status.lastRun.inProgress)
            assertEquals(3, status.counters.found)
            assertEquals(1, status.counters.skipped, "the nap")
            assertEquals(0, status.counters.stale)
            assertEquals(h.pipeline.currentParamsHash(h.settings.current, h.repository.granted), status.paramsHash)
            assertNull(status.backfill)
            assertNull(status.activeModel)
        }

    @Test
    fun `stale nights are counted with the current params hash, which follows settings and the active model`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            assertEquals(
                0,
                h.homeStatus
                    .status()
                    .first { it.counters.estimated == 1 }
                    .counters.stale,
            )

            h.settings.set { it.copy(write = it.write.copy(awakeAsLight = true)) }
            assertEquals(
                1,
                h.homeStatus
                    .status()
                    .first { it.paramsHash == h.pipeline.currentParamsHash(h.settings.current, h.repository.granted) }
                    .counters.stale,
            )

            h.settings.set { it.copy(write = it.write.copy(awakeAsLight = false)) }
            assertEquals(
                0,
                h.homeStatus
                    .status()
                    .first { it.paramsHash == h.pipeline.currentParamsHash(h.settings.current, h.repository.granted) }
                    .counters.stale,
            )

            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }
            assertEquals(
                1,
                h.homeStatus
                    .status()
                    .first { it.paramsHash == h.pipeline.currentParamsHash(h.settings.current, h.repository.granted) }
                    .counters.stale,
            )

            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = false)) }
            assertEquals(
                0,
                h.homeStatus
                    .status()
                    .first { it.paramsHash == h.pipeline.currentParamsHash(h.settings.current, h.repository.granted) }
                    .counters.stale,
            )

            h.estimator.hashSalt = "personal model"
            val model =
                PersonalModel(biasDeltas = listOf(0.1, 0.0, 0.0, -0.1), provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, 3))
            h.modelStore.saveActive(model, StoredKind.CALIBRATED)

            val withModel = h.homeStatus.status().first { it.activeModel != null && it.counters.stale == 1 }
            assertEquals(StoredKind.CALIBRATED, withModel.activeModel?.kind)
        }

    @Test
    fun `stale nights follow the inputs - hrSources and the granted optional channels`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            assertEquals(
                0,
                h.homeStatus
                    .status()
                    .first { it.counters.estimated == 1 }
                    .counters.stale,
            )

            h.settings.set { it.copy(sources = it.sources.copy(hrSources = HrSourcesMode.ANY)) }
            assertEquals(
                1,
                h.homeStatus
                    .status()
                    .first { it.hrSources == HrSourcesMode.ANY }
                    .counters.stale,
            )
            h.settings.set { it.copy(sources = it.sources.copy(hrSources = HrSourcesMode.SAME_AS_SLEEP)) }

            h.repository.revoke(HealthPermissions.READ_HEART_RATE_VARIABILITY)
            h.homeStatus.refresh()
            val revoked =
                h.homeStatus.status().first {
                    it.hrSources == HrSourcesMode.SAME_AS_SLEEP &&
                        it.access.permissions
                            ?.granted
                            ?.contains(HealthPermissions.READ_HEART_RATE_VARIABILITY) == false
                }
            assertEquals(1, revoked.counters.stale, "the night was estimated with HRV, a re-estimate would not read it")
            assertEquals(h.pipeline.currentParamsHash(h.settings.current, h.repository.granted), revoked.paramsHash)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `access is read again on refresh`() =
        harnessTest(dir) { h ->
            h.repository.features -= HcFeature.READ_HISTORY
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            val seen = mutableListOf<HomeStatus>()
            backgroundScope.launch { h.homeStatus.status().collect { seen += it } }
            runCurrent()
            val before = checkNotNull(seen.last().access.permissions)
            assertEquals(SpecialAccess.NOT_AVAILABLE, before.history)
            assertEquals(SpecialAccess.NOT_GRANTED, before.background)
            assertEquals(setOf(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND), before.requestable)

            h.repository.revoke(HealthPermissions.READ_HEART_RATE)
            runCurrent()
            assertTrue(checkNotNull(seen.last().access.permissions).allRequiredGranted, "not observable: unchanged until a refresh")
            h.homeStatus.refresh()
            runCurrent()
            assertEquals(setOf(HealthPermissions.READ_HEART_RATE), checkNotNull(seen.last().access.permissions).missingRequired)

            h.repository.availability = HcAvailability.PROVIDER_UPDATE_REQUIRED
            h.homeStatus.refresh()
            runCurrent()
            assertEquals(HcAvailability.PROVIDER_UPDATE_REQUIRED, seen.last().access.availability)
            assertNull(seen.last().access.permissions)
        }

    @Test
    fun `the backfill state is part of the status`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(3))
            h.backfill.run(inBackground = false)

            val status = h.homeStatus.status().first { it.backfill != null }

            assertEquals(BackfillStatus.DONE, status.backfill?.status)
            assertEquals(1, status.backfill?.nightsEstimated)
        }
}
