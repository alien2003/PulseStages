package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.pipeline.NightInputReader
import io.github.alien2003.pulsestages.data.hc.pipeline.NightLedger
import io.github.alien2003.pulsestages.data.hc.pipeline.NightSpan
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import javax.inject.Inject

/**
 * The ledger's nights and their estimation inputs, re-read from Health Connect the way the pipeline read them: the
 * session of the ledger row, its package (with the current `hrSources` policy), the forced-awake gaps between the
 * fragments recorded in `source_sessions`, and the baseline of the nights before it. Read-only: never writes to
 * Health Connect or the ledger.
 */
class LedgerNightInputs
    @Inject
    constructor(
        private val ledger: NightLedger,
        private val inputReader: NightInputReader,
        repository: HealthRepository,
    ) {
        /** Access checks before reading, and the classification of refusals. */
        val guard: HealthAccessGuard = HealthAccessGuard(repository)

        /** The ledger night with [clientRecordId], or null. */
        suspend fun night(clientRecordId: String): EstimatedNightEntity? = ledger.nights.findByClientRecordId(clientRecordId)

        /** Number of nights (not naps: [SessionMode.NIGHT] as recorded) that currently have an output (WRITTEN). */
        suspend fun writtenNightCount(): Int = ledger.nights.countWithStatusAndMode(NightStatus.WRITTEN, SessionMode.NIGHT)

        /** The latest [limit] WRITTEN nights (not naps, [SessionMode.NIGHT] as recorded), oldest first. */
        suspend fun latestWrittenNights(limit: Int): List<EstimatedNightEntity> =
            ledger.nights
                .findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, listOf(NightStatus.WRITTEN))
                .filter { it.mode == SessionMode.NIGHT }
                .takeLast(limit.coerceAtLeast(0))

        /**
         * The estimation input of [night] with [settings] and the [granted] permissions. Throws
         * [io.github.alien2003.pulsestages.data.hc.HealthDataException] on Health Connect errors.
         */
        suspend fun read(
            night: EstimatedNightEntity,
            settings: AppSettings,
            granted: Set<String>,
        ): NightInput = inputReader.read(NightSpan.of(night, ledger.sources.findByUuids(night.sourceUuids)), settings, granted)
    }
