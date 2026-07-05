package com.rmobile.console.ui.editor

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.history.HistoryEntry
import com.rmobile.console.data.history.HistoryStore
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
import com.rmobile.console.data.network.RExecutionApi
import com.rmobile.console.data.scripts.SavedScript
import com.rmobile.console.data.scripts.SavedScriptStore
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
        var resetResponse: ResetResponse = ResetResponse(ok = true),
        var resetError: Throwable? = null,
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            error?.let { throw it }
            return response
        }
        override suspend fun reset(request: ResetRequest): ResetResponse {
            resetError?.let { throw it }
            return resetResponse
        }
    }

    private class InMemoryHistoryStore(initial: List<HistoryEntry> = emptyList()) : HistoryStore {
        var stored: List<HistoryEntry> = initial
        override fun load() = stored
        override fun persist(entries: List<HistoryEntry>) { stored = entries }
    }

    private class InMemoryScriptStore(initial: List<SavedScript> = emptyList()) : SavedScriptStore {
        var stored: List<SavedScript> = initial
        override fun loadScripts() = stored
        override fun persistScripts(scripts: List<SavedScript>) { stored = scripts }
    }

    private fun viewModel(
        api: FakeApi = FakeApi(),
        store: InMemoryHistoryStore = InMemoryHistoryStore(),
        scriptStore: InMemoryScriptStore = InMemoryScriptStore(),
        now: () -> Long = { 1000L },
    ) = EditorViewModel(RExecutionRepository(api), store, scriptStore, now)

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
    fun `timed-out response sets the timedOut flag`() = runTest {
        val vm = viewModel(
            api = FakeApi(ExecuteResponse(error = "Execution timed out after 20s.", timedOut = true)),
        )

        vm.onCodeChanged("Sys.sleep(60)")
        vm.runCode()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.timedOut)
        assertEquals("Execution timed out after 20s.", state.errorMessage)
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

    @Test
    fun `initial state loads persisted scripts`() {
        val scriptStore = InMemoryScriptStore(listOf(SavedScript(1, "demo", "1+1", 1)))
        val vm = viewModel(scriptStore = scriptStore)

        assertEquals("demo", vm.uiState.value.savedScripts.single().name)
    }

    @Test
    fun `save current script persists a named entry`() {
        val scriptStore = InMemoryScriptStore()
        val vm = viewModel(scriptStore = scriptStore, now = { 77L })

        vm.onCodeChanged("mean(1:10)")
        vm.saveCurrentScript("  My script  ")

        val saved = vm.uiState.value.savedScripts.single()
        assertEquals(SavedScript(id = 77L, name = "My script", code = "mean(1:10)", updatedAt = 77L), saved)
        assertEquals(listOf(saved), scriptStore.stored)
    }

    @Test
    fun `save is ignored when name or code is blank`() {
        val scriptStore = InMemoryScriptStore()
        val vm = viewModel(scriptStore = scriptStore)

        vm.onCodeChanged("x")
        vm.saveCurrentScript("   ")
        vm.onCodeChanged("   ")
        vm.saveCurrentScript("name")

        assertTrue(vm.uiState.value.savedScripts.isEmpty())
        assertTrue(scriptStore.stored.isEmpty())
    }

    @Test
    fun `load script places its code in the editor`() {
        val vm = viewModel()
        vm.loadScript(SavedScript(1, "demo", "plot(cars)", 1))

        assertEquals("plot(cars)", vm.uiState.value.code)
    }

    @Test
    fun `delete script removes it from state and store`() {
        val scriptStore = InMemoryScriptStore(listOf(SavedScript(1, "a", "1", 1)))
        val vm = viewModel(scriptStore = scriptStore)

        vm.deleteScript(1)

        assertTrue(vm.uiState.value.savedScripts.isEmpty())
        assertTrue(scriptStore.stored.isEmpty())
    }

    @Test
    fun `successful run stores workspace objects`() = runTest {
        val vm = viewModel(api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x", "df"))))

        vm.onCodeChanged("x <- 1")
        vm.runCode()
        advanceUntilIdle()

        assertEquals(listOf("x", "df"), vm.uiState.value.workspaceObjects)
    }

    @Test
    fun `errored run leaves workspace objects unchanged`() = runTest {
        val api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x")))
        val vm = viewModel(api = api)

        vm.onCodeChanged("x <- 1")
        vm.runCode()
        advanceUntilIdle()
        // Next run errors on the backend: workspaceObjects null in the response.
        api.response = ExecuteResponse(error = "boom", workspaceObjects = null)
        vm.onCodeChanged("stop('boom')")
        vm.runCode()
        advanceUntilIdle()

        assertEquals(listOf("x"), vm.uiState.value.workspaceObjects)
    }

    @Test
    fun `reset session clears workspace objects`() = runTest {
        val vm = viewModel(api = FakeApi(ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x"))))

        vm.onCodeChanged("x <- 1")
        vm.runCode()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.workspaceObjects.isNotEmpty())

        vm.resetSession()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.workspaceObjects.isEmpty())
    }
}
