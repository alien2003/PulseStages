package io.github.alien2003.pulsestages.source

import io.github.alien2003.pulsestages.data.hc.usecase.CalibrationOutcome
import io.github.alien2003.pulsestages.data.hc.usecase.HomeStatusAggregator
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalizationService
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceComparison
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNight
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceReport
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceSearch
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * What the Compare tab reads and does: reference nights (sessions of other apps with real stages), the validation
 * metrics of our estimator on them, and the personal model (calibrate / reset). The production implementation is
 * [HealthConnectReferenceSource]; debug builds can switch to synthetic demo references at runtime (see [ScreenSources]
 * and docs/SCREENSHOTS.md).
 */
interface ReferenceSource {
    /** True when the reference nights are synthetic demo data (debug builds only); the screen says so. */
    val synthetic: Boolean

    /** READ_HEALTH_DATA_HISTORY state ([SpecialAccess.NOT_AVAILABLE] when Health Connect cannot tell). */
    suspend fun historyAccess(): SpecialAccess

    /** Reference nights whose session starts in `[fromMs, toMs)`. */
    suspend fun findReferenceNights(
        fromMs: Long,
        toMs: Long,
    ): UseCaseResult<ReferenceSearch>

    /** Estimates [nights] with the current settings (and the active personal model) and scores them. */
    suspend fun compare(nights: List<ReferenceNight>): ReferenceReport

    /** The active personal model, or null when the global models are used. */
    fun activeModel(): Flow<PersonalModelEntity?>

    /** Whether personalization (`personal.enabled`) is switched on. */
    fun personalizationEnabled(): Flow<Boolean>

    /** Calibrates a personal model on [nights] and activates it. */
    suspend fun calibrate(nights: List<ReferenceNight>): CalibrationOutcome

    /** Back to the global models. */
    suspend fun resetToGlobal()
}

/** [ReferenceSource] over Health Connect: [ReferenceComparison] and [PersonalizationService] of data-hc. */
class HealthConnectReferenceSource
    @Inject
    constructor(
        private val comparison: ReferenceComparison,
        private val personalization: PersonalizationService,
        private val homeStatus: HomeStatusAggregator,
        private val settingsRepository: SettingsRepository,
    ) : ReferenceSource {
        override val synthetic: Boolean = false

        override suspend fun historyAccess(): SpecialAccess = homeStatus.readAccess().permissions?.history ?: SpecialAccess.NOT_AVAILABLE

        override suspend fun findReferenceNights(
            fromMs: Long,
            toMs: Long,
        ): UseCaseResult<ReferenceSearch> = comparison.findReferenceNights(fromMs, toMs)

        override suspend fun compare(nights: List<ReferenceNight>): ReferenceReport = comparison.compareWithCurrentSettings(nights)

        override fun activeModel(): Flow<PersonalModelEntity?> = personalization.activeModelRow()

        override fun personalizationEnabled(): Flow<Boolean> =
            settingsRepository.settings.map { it.algo.personal.enabled }.distinctUntilChanged()

        override suspend fun calibrate(nights: List<ReferenceNight>): CalibrationOutcome = personalization.calibrate(nights)

        override suspend fun resetToGlobal() {
            personalization.resetToGlobal()
        }
    }
