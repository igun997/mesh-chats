package com.meshchats.app.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a [TestDispatcher] for the duration of a test, so
 * `viewModelScope` coroutines run on a controllable scheduler instead of the
 * Android main looper (which is absent under plain JVM unit tests).
 *
 * Defaults to an [UnconfinedTestDispatcher] so launched work runs eagerly and
 * `StateFlow.collect` in a ViewModel `init` block observes its first value
 * without needing an explicit scheduler pump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
