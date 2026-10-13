package io.github.alien2003.pulsestages.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Provider

/**
 * [ScreenSources] of release builds: the ledger and Health Connect, never replaced. Each source is created when a
 * screen first reads it.
 */
class RealScreenSources
    @Inject
    constructor(
        ledger: Provider<LedgerNightsSource>,
        healthConnect: Provider<HealthConnectReferenceSource>,
    ) : ScreenSources {
        override val nights: Flow<NightsSource> = flow { emit(ledger.get()) }

        override val references: Flow<ReferenceSource> = flow { emit(healthConnect.get()) }
    }
