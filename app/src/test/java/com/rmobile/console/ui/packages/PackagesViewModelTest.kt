package com.rmobile.console.ui.packages

import com.rmobile.console.data.RExecutionRepository
import com.rmobile.console.data.model.ExecuteRequest
import com.rmobile.console.data.model.ExecuteResponse
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.ResetRequest
import com.rmobile.console.data.model.ResetResponse
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
class PackagesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeApi(
        var packagesResponse: PackagesResponse = PackagesResponse(),
        var installResponse: InstallResponse = InstallResponse(installed = true),
    ) : RExecutionApi {
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse = ExecuteResponse()
        override suspend fun reset(request: ResetRequest): ResetResponse = ResetResponse(ok = true)
        override suspend fun install(request: InstallRequest): InstallResponse = installResponse
        override suspend fun packages(): PackagesResponse = packagesResponse
    }

    private fun viewModel(api: FakeApi) = PackagesViewModel(RExecutionRepository(api))

    @Test
    fun `loads installed packages on init`() = runTest {
        val vm = viewModel(FakeApi(packagesResponse = PackagesResponse(listOf("glue", "praise"))))
        advanceUntilIdle()
        assertEquals(listOf("glue", "praise"), vm.uiState.value.installed)
    }

    @Test
    fun `successful install reports success and refreshes the list`() = runTest {
        val api = FakeApi(
            packagesResponse = PackagesResponse(emptyList()),
            installResponse = InstallResponse(installed = true, stdout = "done"),
        )
        val vm = viewModel(api)
        advanceUntilIdle()
        api.packagesResponse = PackagesResponse(listOf("praise"))
        vm.onPackageNameChanged("praise")
        vm.install()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.installing)
        assertFalse(s.isError)
        assertTrue(s.message!!.contains("praise"))
        assertEquals(listOf("praise"), s.installed)
    }

    @Test
    fun `failed install surfaces the error`() = runTest {
        val vm = viewModel(FakeApi(installResponse = InstallResponse(installed = false, error = "not available")))
        advanceUntilIdle()
        vm.onPackageNameChanged("nope")
        vm.install()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.isError)
        assertTrue(s.message!!.contains("not available"))
    }
}
