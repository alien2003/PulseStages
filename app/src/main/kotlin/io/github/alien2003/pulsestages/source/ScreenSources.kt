package io.github.alien2003.pulsestages.source

import kotlinx.coroutines.flow.Flow

/**
 * The data of the Nights list, the night detail and the Compare tab. Each flow emits the source to use now and emits
 * again when it is replaced; the ViewModels follow it and start over with the new data, without a restart.
 *
 * Release builds always use the ledger and Health Connect ([LedgerNightsSource], [HealthConnectReferenceSource]).
 * Debug builds can replace them at runtime with synthetic demo data (the demo-mode switch in Settings, see
 * docs/SCREENSHOTS.md); release builds contain none of that code.
 */
interface ScreenSources {
    /** The source of the Nights list and the night detail. */
    val nights: Flow<NightsSource>

    /** The source of the Compare tab. */
    val references: Flow<ReferenceSource>
}
