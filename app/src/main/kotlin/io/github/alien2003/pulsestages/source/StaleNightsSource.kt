package io.github.alien2003.pulsestages.source

import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.hc.usecase.HomeStatusAggregator
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The params hash of the current settings and the WRITTEN nights that are stale with it.
 *
 * @property paramsHash the current params hash (it follows the settings, the granted optional channels and the
 *   active personal model).
 * @property count WRITTEN nights estimated with another algorithm version or params hash.
 */
data class StaleNights(
    val paramsHash: String,
    val count: Int,
)

/** What the Settings screen needs to offer recomputing the nights that a settings change made stale. */
interface StaleNightsSource {
    /** The current params hash and the number of stale nights; emits on every change. */
    fun staleNights(): Flow<StaleNights>

    /** Recomputes the stale nights in the background (ignored while a recompute is pending or running). */
    fun recomputeStale()
}

/** [StaleNightsSource] over the Home status aggregator (ledger counters) and the WorkManager scheduler. */
class LedgerStaleNightsSource
    @Inject
    constructor(
        private val homeStatus: HomeStatusAggregator,
        private val scheduler: WorkScheduler,
    ) : StaleNightsSource {
        override fun staleNights(): Flow<StaleNights> =
            homeStatus.status().map { StaleNights(it.paramsHash, it.counters.stale) }.distinctUntilChanged()

        override fun recomputeStale() {
            scheduler.recomputeAll(RecomputeScope.STALE)
        }
    }
