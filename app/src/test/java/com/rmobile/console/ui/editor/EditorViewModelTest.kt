package com.rmobile.console.ui.editor

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeApi(
        var response: ExecuteResponse = ExecuteResponse(stdout = "ok"),
        var error: Throwable? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            error?.let { throw it }
            return response
        }
    }

    private class InMemoryHistoryStore(initial: List<HistoryEntry> = emptyList()) : HistoryStore {
        var stored: List<HistoryEntry> = initial
        override fun load() = stored
        override fun persist(entries: List<HistoryEntry>) { stored = entries }
    }

    private fun viewModel(
        api: FakeApi = FakeApi(),
        store: InMemoryHistoryStore = InMemoryHistoryStore(),
        now: () -> Long = { 1000L },
    ) = EditorViewModel(RExecutionRepository(api), store, now)

    @Test
    fun `initial state loads persisted history`() {
        val store = InMemoryHistoryStore(listOf(HistoryEntry("saved", 1)))
        val vm = viewModel(store = store)

        assertEquals(listOf(HistoryEntry("saved", 1)), vm.uiState.value.history)
    }

    @Test
    fun `successful run populates output and records history`() = runTest {
        val store = InMemoryHistoryStore()
        val vm = viewModel(api = FakeApi(ExecuteResponse(stdout = "hello")), store = store, now = { 42L })

        vm.onCodeChanged("print('hi')")
        vm.runCode()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRunning)
        assertEquals("hello", state.stdout)
        assertEquals(listOf(HistoryEntry("print('hi')", 42L)), state.history)
        assertEquals(state.history, store.stored)
    }

    @Test
    fun `failed run surfaces an error message`() = runTest {
        val vm = viewModel(api = FakeApi(error = RuntimeException("boom")))

        vm.onCodeChanged("x")
        vm.runCode()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isRunning)
        assertEquals("boom", state.errorMessage)
    }

    @Test
    fun `blank code does not run`() = runTest {
        val store = InMemoryHistoryStore()
        val vm = viewModel(store = store)

        vm.onCodeChanged("   ")
        vm.runCode()
        advanceUntilIdle()

        assertTrue(store.stored.isEmpty())
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `restore from history loads code into the editor`() {
        val vm = viewModel()
        vm.restoreFromHistory(HistoryEntry("summary(cars)", 5))

        assertEquals("summary(cars)", vm.uiState.value.code)
    }

    @Test
    fun `clear history empties state and store`() = runTest {
        val store = InMemoryHistoryStore()
        val vm = viewModel(store = store)

        vm.onCodeChanged("x")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.history.isNotEmpty())

        vm.clearHistory()
        assertTrue(vm.uiState.value.history.isEmpty())
        assertTrue(store.stored.isEmpty())
    }
}
