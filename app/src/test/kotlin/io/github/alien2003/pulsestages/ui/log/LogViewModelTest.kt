package io.github.alien2003.pulsestages.ui.log

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.fake.TestClock
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.testutil.FakeLogDao
import io.github.alien2003.pulsestages.testutil.FakeShareableFileWriter
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.ui.common.UiText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.ZoneOffset

class LogViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val dao = FakeLogDao()
    private val files = FakeShareableFileWriter()
    private val clock = TestClock(1_760_000_000_000L)

    private fun viewModel() = LogViewModel(dao, files, RecordingLogger(), clock, ZoneOffset.UTC)

    private fun LogViewModel.messages(): List<String> = state.value.entries.map { it.message }

    @BeforeEach
    fun entries() {
        dao.add(LogLevel.DEBUG, "d1")
        dao.add(LogLevel.INFO, "i1")
        dao.add(LogLevel.WARN, "w1")
        dao.add(LogLevel.ERROR, "e1")
        dao.add(LogLevel.INFO, "i2")
    }

    @Test
    fun `every level is shown by default, newest first`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            assertFalse(vm.state.value.loading)
            assertEquals(LogLevel.entries.toSet(), vm.state.value.levels)
            assertEquals(listOf("i2", "e1", "w1", "i1", "d1"), vm.messages())
            assertTrue(dao.observedLevelFilters.isEmpty())
        }

    @Test
    fun `filter chips hide and show levels`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.toggleLevel(LogLevel.DEBUG)
            vm.toggleLevel(LogLevel.INFO)
            assertEquals(setOf(LogLevel.WARN, LogLevel.ERROR), vm.state.value.levels)
            assertEquals(listOf("e1", "w1"), vm.messages())
            assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), dao.observedLevelFilters.last())
            vm.toggleLevel(LogLevel.INFO)
            assertEquals(listOf("i2", "e1", "w1", "i1"), vm.messages())
        }

    @Test
    fun `new entries appear while the screen is open`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.toggleLevel(LogLevel.DEBUG)
            dao.add(LogLevel.ERROR, "e2")
            dao.add(LogLevel.DEBUG, "d2")
            assertEquals("e2", vm.messages().first())
            assertFalse("d2" in vm.messages())
        }

    @Test
    fun `no selected level shows nothing`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            LogLevel.entries.forEach(vm::toggleLevel)
            assertTrue(
                vm.state.value.levels
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.entries
                    .isEmpty(),
            )
        }

    @Test
    fun `share writes the selected levels oldest first and hands the uri to the share sheet`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.toggleLevel(LogLevel.DEBUG)
            vm.share()
            val text = checkNotNull(files.files[LogViewModel.SHARE_FILE_NAME])
            val lines = text.lines()
            assertTrue(lines.contains("levels: INFO,WARN,ERROR"), text)
            assertTrue(lines.contains("entries: 4"), text)
            val messages = lines.filter { it.contains(" [Test] ") }.map { it.substringAfterLast(' ') }
            assertEquals(listOf("i1", "w1", "e1", "i2"), messages)
            assertEquals("content://test.fileprovider/shared/${LogViewModel.SHARE_FILE_NAME}", vm.state.value.shareUri)
            vm.shareHandled(started = true)
            assertNull(vm.state.value.shareUri)
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun `a share sheet that cannot be opened is reported`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            vm.share()
            vm.shareHandled(started = false)
            assertNull(vm.state.value.shareUri)
            assertEquals(
                UiText.Res(R.string.error_no_app_for_action),
                vm.state.value.messages
                    .single()
                    .text,
            )
        }

    @Test
    fun `nothing to share and write failures are reported`() =
        runTest(main.dispatcher) {
            val vm = viewModel()
            LogLevel.entries.forEach(vm::toggleLevel)
            vm.share()
            assertEquals(
                UiText.Res(R.string.log_share_empty),
                vm.state.value.messages
                    .last()
                    .text,
            )
            assertTrue(files.files.isEmpty())
            vm.toggleLevel(LogLevel.ERROR)
            files.fail = true
            vm.share()
            assertEquals(
                UiText.Res(R.string.log_share_failed),
                vm.state.value.messages
                    .last()
                    .text,
            )
            assertFalse(vm.state.value.sharing)
            assertNull(vm.state.value.shareUri)
        }

    @Test
    fun `log lines carry time, level, tag, night and indented continuation lines`() {
        val entry = LogEntryEntity(7, 1_760_000_000_123L, LogLevel.WARN, "Sync", "first\nsecond", nightKey = "ps:abc")
        assertEquals("2025-10-09T08:53:20.123Z WARN [Sync] [ps:abc] first\n    second", LogText.line(entry, ZoneOffset.UTC))
    }
}
