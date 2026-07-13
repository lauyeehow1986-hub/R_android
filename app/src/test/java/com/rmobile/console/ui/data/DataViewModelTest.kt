package com.rmobile.console.ui.data

import com.rmobile.console.data.datafiles.DataUpload
import com.rmobile.console.data.datafiles.SessionDataStore
import com.rmobile.console.data.model.DataFile
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectOps
import com.rmobile.console.data.project.ProjectStore
import com.rmobile.console.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class FakeStore(var files: List<DataFile> = listOf(DataFile("a.csv", 10))) : SessionDataStore {
        var lastListSession: String? = null
        var lastSaveSession: String? = null
        var deleted: String? = null
        override suspend fun list(sessionId: String): Result<List<DataFile>> { lastListSession = sessionId; return Result.success(files) }
        override suspend fun save(sessionId: String, upload: DataUpload): Result<Unit> { lastSaveSession = sessionId; return Result.success(Unit) }
        override suspend fun delete(sessionId: String, name: String): Result<Boolean> { deleted = name; return Result.success(true) }
    }

    private class InMemoryProjectStore(initial: List<Project> = emptyList(), var lastId: Long? = null) : ProjectStore {
        var stored = initial
        override fun loadProjects() = stored
        override fun persistProjects(projects: List<Project>) { stored = projects }
        override fun loadLastOpenProjectId() = lastId
        override fun persistLastOpenProjectId(id: Long?) { lastId = id }
    }

    private fun upload(name: String, n: Int = 3) = DataUpload(name, n.toLong()) { ByteArrayInputStream(ByteArray(n)) }

    private fun vm(store: FakeStore, projectStore: ProjectStore = InMemoryProjectStore(), local: Boolean = true) =
        DataViewModel(dataStoreProvider = { store }, projectStore = projectStore, engineIsLocalProvider = { local })

    @Test fun `loads files on init`() = runTest {
        val vm = vm(FakeStore(listOf(DataFile("a.csv", 1), DataFile("b.csv", 2))))
        advanceUntilIdle()
        assertEquals(listOf("a.csv", "b.csv"), vm.uiState.value.files.map { it.name })
    }
    @Test fun `upload routes to the store and refreshes`() = runTest {
        val store = FakeStore(emptyList())
        val vm = vm(store); advanceUntilIdle()
        store.files = listOf(DataFile("new.csv", 3))
        vm.upload(upload("new.csv")); advanceUntilIdle()
        assertEquals("proj_or_default_nonnull", store.lastSaveSession?.let { "proj_or_default_nonnull" })
        assertEquals(listOf("new.csv"), vm.uiState.value.files.map { it.name })
    }
    @Test fun `delete routes to the store`() = runTest {
        val store = FakeStore(listOf(DataFile("a.csv", 1)))
        val vm = vm(store); advanceUntilIdle()
        store.files = emptyList()
        vm.delete("a.csv"); advanceUntilIdle()
        assertEquals("a.csv", store.deleted)
        assertTrue(vm.uiState.value.files.isEmpty())
    }
    @Test fun `uses the active project session`() = runTest {
        val project = ProjectOps.newProject(id = 7, name = "P", now = 0)
        val store = FakeStore()
        vm(store, InMemoryProjectStore(listOf(project), 7)); advanceUntilIdle()
        assertEquals("proj-7", store.lastListSession)
    }
    @Test fun `large-file warning flag`() = runTest {
        val vm = vm(FakeStore()); advanceUntilIdle()
        assertFalse(vm.warnLargeFile(100L * 1024 * 1024))
        assertTrue(vm.warnLargeFile(300L * 1024 * 1024))
    }
    @Test fun `no large-file warning on remote`() = runTest {
        val vm = vm(FakeStore(), local = false); advanceUntilIdle()
        assertFalse(vm.warnLargeFile(300L * 1024 * 1024))
    }
}
