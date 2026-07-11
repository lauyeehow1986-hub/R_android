package com.rmobile.console.ui.settings

import com.rmobile.console.data.settings.AppSettings
import com.rmobile.console.data.settings.ExecutionEngineChoice
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSettings(
        override var baseUrl: String = "http://10.0.2.2:8000/",
        override var apiKey: String = "",
        override val defaultBaseUrl: String = "http://10.0.2.2:8000/",
        override var executionEngine: ExecutionEngineChoice = ExecutionEngineChoice.LOCAL,
    ) : AppSettings

    @Test
    fun `save normalizes and persists the url`() {
        val settings = FakeSettings()
        val vm = SettingsViewModel(settings) { _, _ -> Result.success(Unit) }

        vm.onBaseUrlChanged("192.168.1.5:8000")
        vm.onApiKeyChanged("  secret  ")
        vm.save()

        assertEquals("http://192.168.1.5:8000/", settings.baseUrl)
        assertEquals("secret", settings.apiKey)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `save rejects an invalid url without persisting`() {
        val settings = FakeSettings()
        val vm = SettingsViewModel(settings) { _, _ -> Result.success(Unit) }

        vm.onBaseUrlChanged("http://")
        vm.save()

        assertNotNull(vm.uiState.value.urlError)
        assertEquals("http://10.0.2.2:8000/", settings.baseUrl)
    }

    @Test
    fun `testConnection reports success`() = runTest {
        val vm = SettingsViewModel(FakeSettings()) { _, _ -> Result.success(Unit) }

        vm.testConnection()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.connectionTest is ConnectionTest.Ok)
    }

    @Test
    fun `testConnection reports the failure message`() = runTest {
        val vm = SettingsViewModel(FakeSettings()) { _, _ -> Result.failure(RuntimeException("Backend responded with HTTP 500.")) }

        vm.testConnection()
        advanceUntilIdle()

        val result = vm.uiState.value.connectionTest
        assertTrue(result is ConnectionTest.Failed)
        assertEquals("Backend responded with HTTP 500.", (result as ConnectionTest.Failed).message)
    }

    @Test
    fun `setEngine persists the choice and updates state`() {
        val store = FakeSettings()
        val vm = SettingsViewModel(store = store, probeHealth = { _, _ -> Result.success(Unit) })
        vm.setEngine(ExecutionEngineChoice.REMOTE)
        assertEquals(ExecutionEngineChoice.REMOTE, store.executionEngine)
        assertEquals(ExecutionEngineChoice.REMOTE, vm.uiState.value.executionEngine)
    }

    @Test
    fun `testConnection on an invalid url sets the url error and skips the probe`() = runTest {
        var probed = false
        val vm = SettingsViewModel(FakeSettings()) { _, _ -> probed = true; Result.success(Unit) }

        vm.onBaseUrlChanged("http://")
        vm.testConnection()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.urlError)
        assertTrue(vm.uiState.value.connectionTest is ConnectionTest.None)
        assertTrue(!probed)
    }
}
