package io.github.alien2003.pulsestages.ui.about

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.clear
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.io.IOException

class AboutViewModelTest {
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val bundle: HybridModel = HybridModel.parse(File("src/main/assets/models/hybrid_model.json").readText())

    @Test
    fun `shows the app version, the algorithm version and the bundled model`() =
        runTest(main.dispatcher) {
            val viewModel = AboutViewModel({ "1.2.3" }, HybridModelProvider { bundle }, RecordingLogger())

            assertEquals(AboutInfo("1.2.3", ALGO_VERSION, bundle.modelId, modelChecked = true), viewModel.about.value)
            clear(viewModel)
        }

    @Test
    fun `a missing or unreadable bundle is shown as such, never as a crash`() =
        runTest(main.dispatcher) {
            val none = AboutViewModel({ "" }, HybridModelProvider { null }, RecordingLogger())
            assertEquals(AboutInfo("", ALGO_VERSION, null, modelChecked = true), none.about.value)

            val logger = RecordingLogger()
            val failing = AboutViewModel({ "1.0" }, HybridModelProvider { throw IOException("broken asset") }, logger)
            assertEquals(AboutInfo("1.0", ALGO_VERSION, null, modelChecked = true), failing.about.value)
            assertTrue(logger.lines.any { it.message.contains("versions card") })
            listOf(none, failing).forEach(::clear)
        }
}
