package io.github.alien2003.pulsestages.e2e

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.github.alien2003.pulsestages.e2e.support.E2eWorkManager

/**
 * The instrumentation of the suite (`testInstrumentationRunner`): AndroidJUnitRunner, but the in-memory WorkManager of
 * [E2eWorkManager] is installed first. Android calls `Instrumentation.onCreate` after it created the app's Application
 * object and before it calls the app's `Application.onCreate`, so the app's start-up code already finds that
 * WorkManager and the app's real one is never initialized in a test process.
 */
class E2eRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        E2eWorkManager.install(targetContext)
        super.onCreate(arguments)
    }
}
