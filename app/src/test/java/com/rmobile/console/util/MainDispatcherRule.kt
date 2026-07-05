package com.rmobile.console.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestDispatcher
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps Dispatchers.Main for a test dispatcher so `viewModelScope` work runs
 * under test control. Unconfined so coroutines launched from the ViewModel run
 * eagerly to their first real suspension — with the in-memory fakes here that
 * means state settles synchronously, without cross-scheduler coordination.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
