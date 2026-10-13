package io.github.alien2003.pulsestages.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Replaces `Dispatchers.Main` (used by `viewModelScope`) with an unconfined test dispatcher for each test, so that
 * ViewModel coroutines run eagerly; tests pass [dispatcher] to `runTest` to share its scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension :
    BeforeEachCallback,
    AfterEachCallback {
    /** The dispatcher installed as Main. */
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()

    override fun beforeEach(context: ExtensionContext) {
        Dispatchers.setMain(dispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
    }
}
