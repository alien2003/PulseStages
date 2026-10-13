package io.github.alien2003.pulsestages.data.local.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import io.github.alien2003.pulsestages.algo.model.EstimatorType
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
                val result = repository.update { it.copy(schedule = it.schedule.copy(periodicHours = 6)) }

                val applied = assertInstanceOf(SettingsUpdateResult.Applied::class.java, result)
                assertEquals(6, applied.settings.schedule.periodicHours)
                assertEquals(
                    6,
                    repository.settings
                        .first()
                        .schedule.periodicHours,
                )
            }
            withRepository { repository ->
                assertEquals(
                    6,
                    repository.settings
                        .first()
                        .schedule.periodicHours,
                )
            }
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
                assertInstanceOf(SettingsImportResult.Invalid::class.java, outOfRange)

                assertEquals(
                    LogLevel.WARN,
                    repository.settings
                        .first()
                        .debug.logLevel,
                )
                assertEquals(
                    ScheduleSettings().periodicHours,
                    repository.settings
                        .first()
                        .schedule.periodicHours,
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
            // The handler rewrote the file with valid default JSON.
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            assertTrue(root.containsKey("algo"))
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
