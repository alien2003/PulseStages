package io.github.alien2003.pulsestages.data.local.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val corruptions = mutableListOf<CorruptionException>()

    private val file: File get() = File(dir, AppSettingsDataStoreFactory.FILE_NAME)

    /** Runs [block] with a repository whose DataStore lives in its own child job, cancelled afterwards. */
    private suspend fun TestScope.withRepository(block: suspend (SettingsRepository) -> Unit) {
        val storeJob = Job(coroutineContext[Job])
        val store: DataStore<AppSettings> =
            AppSettingsDataStoreFactory.create(
                scope = CoroutineScope(coroutineContext + storeJob),
                onCorruption = { corruptions += it },
                produceFile = { file },
            )
        try {
            block(DataStoreSettingsRepository(store))
        } finally {
            storeJob.cancelAndJoin()
        }
    }

    @Test
    fun `emits defaults when nothing is stored`() =
        runTest {
            withRepository { repository -> assertEquals(AppSettings(), repository.settings.first()) }
            assertTrue(corruptions.isEmpty())
        }

    @Test
    fun `valid update is stored and survives a new DataStore instance`() =
        runTest {
            withRepository { repository ->
                val result = repository.update { it.copy(schedule = it.schedule.copy(periodicMinutes = 360)) }

                val applied = assertInstanceOf(SettingsUpdateResult.Applied::class.java, result)
                assertEquals(360, applied.settings.schedule.periodicMinutes)
                assertEquals(
                    360,
                    repository.settings
                        .first()
                        .schedule.periodicMinutes,
                )
            }
            withRepository { repository ->
                assertEquals(
                    360,
                    repository.settings
                        .first()
                        .schedule.periodicMinutes,
                )
            }
        }

    @Test
    fun `a file of an older version with periodicHours reads as minutes and the next update stores periodicMinutes`() =
        runTest {
            file.writeText("""{"schedule":{"periodicHours":3},"sources":{"minSessionMin":60}}""")

            withRepository { repository ->
                val settings = repository.settings.first()
                assertEquals(180, settings.schedule.periodicMinutes)
                assertEquals(60, settings.sources.minSessionMin)
                // Reading does not rewrite the file.
                assertTrue("periodicHours" in file.readText())

                val result = repository.update { it.copy(sources = it.sources.copy(mergeGapMin = 10)) }
                assertInstanceOf(SettingsUpdateResult.Applied::class.java, result)
            }

            val stored = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(
                Json.parseToJsonElement("""{"sources":{"minSessionMin":60,"mergeGapMin":10},"schedule":{"periodicMinutes":180}}"""),
                stored,
            )
            assertTrue(corruptions.isEmpty())
            withRepository { repository ->
                assertEquals(
                    180,
                    repository.settings
                        .first()
                        .schedule.periodicMinutes,
                )
            }
        }

    @Test
    fun `the file stores only changed values, so defaults that a new app version changes reach the user`() =
        runTest {
            withRepository { repository ->
                repository.update { it.copy(sources = it.sources.copy(minSessionMin = 60)) }
            }

            val stored = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(Json.parseToJsonElement("""{"sources":{"minSessionMin":60}}"""), stored)
            // A file written by an older version with other trained defaults holds no algorithm values: whatever
            // TrainedDefaults says in this build applies. Simulated with an older file that has only user choices.
            file.writeText("""{"sources":{"minSessionMin":60},"algo":{"hybrid":{"maxBoutMin":90}}}""")
            withRepository { repository ->
                val settings = repository.settings.first()
                assertEquals(60, settings.sources.minSessionMin)
                assertEquals(90, settings.algo.hybrid.maxBoutMin, "the user's own change is kept")
                assertEquals(AlgoParams().copy(hybrid = AlgoParams().hybrid.copy(maxBoutMin = 90)), settings.algo)
                assertEquals(AlgoParams().estimator, settings.algo.estimator)
                assertEquals(AlgoParams().hybrid.durationModel, settings.algo.hybrid.durationModel)
            }
            val export = Json.parseToJsonElement(SettingsJson.encode(AppSettings())).jsonObject
            assertTrue("estimator" in export.getValue("algo").jsonObject, "an export still lists every value")
        }

    @Test
    fun `invalid update is rejected and nothing is stored`() =
        runTest {
            withRepository { repository ->
                repository.update { it.copy(sources = it.sources.copy(minSessionMin = 120)) }

                val result = repository.update { it.copy(sources = it.sources.copy(minSessionMin = 5, mergeGapMin = 999)) }

                val rejected = assertInstanceOf(SettingsUpdateResult.Rejected::class.java, result)
                assertEquals(listOf("sources.minSessionMin", "sources.mergeGapMin"), rejected.errors.map { it.path })
                val stored = repository.settings.first().sources
                assertEquals(120, stored.minSessionMin)
                assertEquals(SourceSettings().mergeGapMin, stored.mergeGapMin)
            }
        }

    @Test
    fun `update stores the repaired settings`() =
        runTest {
            withRepository { repository ->
                repository.update { it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH_PACKAGE, "com.example.watch"))) }

                assertEquals(
                    listOf(OHEALTH_PACKAGE, "com.example.watch"),
                    repository.settings
                        .first()
                        .sources.sourcePriority,
                )
            }
        }

    @Test
    fun `reset restores every default`() =
        runTest {
            withRepository { repository ->
                repository.update {
                    it.copy(
                        algo = it.algo.copy(estimator = EstimatorType.RULES),
                        debug = DebugSettings(logLevel = LogLevel.ERROR),
                        write = it.write.copy(awakeAsLight = true),
                    )
                }
                assertEquals(
                    EstimatorType.RULES,
                    repository.settings
                        .first()
                        .algo.estimator,
                )

                repository.reset()

                assertEquals(AppSettings(), repository.settings.first())
            }
        }

    @Test
    fun `settings flow emits every change`() =
        runTest {
            withRepository { repository ->
                val seen = mutableListOf<AppSettings>()
                val collector = launch(UnconfinedTestDispatcher(testScheduler)) { repository.settings.take(2).toList(seen) }

                repository.update { it.copy(schedule = it.schedule.copy(dailyTimeMinutes = 600)) }
                collector.join()

                assertEquals(listOf(DEFAULT_DAILY_TIME_MINUTES, 600), seen.map { it.schedule.dailyTimeMinutes })
            }
        }

    @Test
    fun `importAndApply stores valid JSON and leaves settings untouched for invalid JSON`() =
        runTest {
            withRepository { repository ->
                val ok = repository.importAndApply("""{"debug": {"logLevel": "WARN"}, "unknown": 1}""")
                assertInstanceOf(SettingsImportResult.Success::class.java, ok)
                assertEquals(
                    LogLevel.WARN,
                    repository.settings
                        .first()
                        .debug.logLevel,
                )

                val bad = repository.importAndApply("""{"debug": {"logLevel": "TRACE"}}""")
                assertInstanceOf(SettingsImportResult.Invalid::class.java, bad)
                val outOfRange = repository.importAndApply("""{"schedule": {"periodicHours": 0}}""")
                assertEquals(
                    listOf("schedule.periodicMinutes"),
                    assertInstanceOf(SettingsImportResult.Invalid::class.java, outOfRange).errors.map { it.path },
                )
                val notAllowed = repository.importAndApply("""{"schedule": {"periodicMinutes": 45}}""")
                assertInstanceOf(SettingsImportResult.Invalid::class.java, notAllowed)

                assertEquals(
                    LogLevel.WARN,
                    repository.settings
                        .first()
                        .debug.logLevel,
                )
                assertEquals(
                    ScheduleSettings().periodicMinutes,
                    repository.settings
                        .first()
                        .schedule.periodicMinutes,
                )
            }
        }

    @Test
    fun `exportJson output imports back to the same settings`() =
        runTest {
            withRepository { repository ->
                repository.update { it.copy(sources = it.sources.copy(backfillDays = 400)) }
                val current = repository.settings.first()

                val result = repository.importJson(repository.exportJson(current))

                assertEquals(SettingsImportResult.Success(current), result)
            }
        }

    @Test
    fun `corrupted file is replaced by defaults and reported`() =
        runTest {
            file.writeText("{\"sources\": {\"minSessionMin\": ") // truncated write

            withRepository { repository -> assertEquals(AppSettings(), repository.settings.first()) }

            assertEquals(1, corruptions.size)
            // The handler rewrote the file with valid JSON of the defaults (which store nothing).
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            assertTrue(root.isEmpty(), root.toString())
            withRepository { repository -> assertEquals(AppSettings(), repository.settings.first()) }
            assertEquals(1, corruptions.size, "a repaired file is not corrupt any more")
        }

    @Test
    fun `file with a wrong value type is treated as corrupted`() =
        runTest {
            file.writeText("""{"schedule": {"periodicHours": "hourly"}}""")

            withRepository { repository -> assertEquals(AppSettings(), repository.settings.first()) }

            assertEquals(1, corruptions.size)
        }
}
