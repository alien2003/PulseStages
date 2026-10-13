package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.data.hc.pipeline.LedgerLock
import io.github.alien2003.pulsestages.data.local.db.PersonalModelDao
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject

/**
 * Source of the hybrid model bundle the estimator runs with (in the app: the bundled asset
 * `models/hybrid_model.json`). Needed by calibration, whose classifier bias deltas are fitted for that bundle.
 */
fun interface HybridModelProvider {
    /** The parsed and validated bundle, or null when there is none or it is invalid (HYBRID then falls back to HMM). */
    suspend fun hybridModel(): HybridModel?
}

/**
 * The stored personal models (`personal_models`) as core-algo [PersonalModel]s. At most one is active; switching
 * the active model holds [LedgerLock], so a pipeline run (which holds the lock for its whole duration) computes its
 * params hash and estimates all its nights with one and the same model.
 */
class PersonalModelStore
    @Inject
    constructor(
        private val dao: PersonalModelDao,
        private val clock: Clock,
        private val logger: AppLogger,
    ) {
        /** The active row (kind, kappas, creation time), or null when the global models are used. */
        fun activeRow(): Flow<PersonalModelEntity?> = dao.observeActive()

        /** The active personal model, or null (none, or its JSON is unreadable). */
        fun activeModel(): Flow<PersonalModel?> = dao.observeActive().map { row -> row?.let(::parse) }

        /** The active row right now, or null. */
        suspend fun active(): PersonalModelEntity? = dao.getActive()

        /** Stores [model] of [kind] as the only active model; returns its row id. */
        suspend fun saveActive(
            model: PersonalModel,
            kind: PersonalModelKind,
        ): Long =
            LedgerLock.withLock {
                dao.insert(
                    PersonalModelEntity(
                        createdAt = clock.millis(),
                        kind = kind,
                        json = model.toJson(),
                        fingerprint = model.fingerprint(),
                        active = true,
                        nightsUsed = model.provenance.nightsUsed,
                        kappaBefore = model.provenance.kappaBefore,
                        kappaAfter = model.provenance.kappaAfter,
                    ),
                )
            }

        /** Deactivates every model: back to the global models. */
        suspend fun deactivateAll() {
            LedgerLock.withLock { dao.deactivateAll() }
        }

        /** The model stored in [row], or null (logged) when its JSON cannot be read. */
        fun parse(row: PersonalModelEntity): PersonalModel? =
            try {
                PersonalModel.fromJson(row.json)
            } catch (e: IllegalArgumentException) {
                logger.w(TAG, "personal model ${row.id} is unreadable; using the global models", throwable = e)
                null
            }

        private companion object {
            const val TAG = "PersonalModels"
        }
    }
