package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.NightBaselineDao
import io.github.alien2003.pulsestages.data.local.db.SourceSessionDao
import javax.inject.Inject

/**
 * The parts of the Room ledger the pipeline keeps in sync with Health Connect. Changes go through [NightPipeline]
 * (under [LedgerLock]).
 *
 * @property nights `estimated_nights` and `night_sources`.
 * @property sources `source_sessions`.
 * @property baselines `night_baselines`.
 */
class NightLedger
    @Inject
    constructor(
        val nights: EstimatedNightDao,
        val sources: SourceSessionDao,
        val baselines: NightBaselineDao,
    )
