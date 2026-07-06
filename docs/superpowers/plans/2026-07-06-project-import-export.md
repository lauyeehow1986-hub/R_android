# Project Import/Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export a project to a `.zip` of its `.R` files (via the share-sheet) and import a `.zip` back into the library (via the file picker).

**Architecture:** A pure, unit-tested `ProjectArchive` does the zip export/import (`java.util.zip`); `EditorViewModel.importProject` adds+opens an imported project; `ProjectsScreen` wires a per-project export (share-sheet, reusing the existing `FileProvider`) and an import (SAF `OpenDocument`).

**Tech Stack:** Kotlin, `java.util.zip`, kotlinx.serialization, Jetpack Compose, JUnit4.

---

## Prerequisites

App tests need the bundled JDK + truststore (plain `./gradlew` fails with `PKIX path building failed`):
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export JKS="C:/Users/lauye/AppData/Local/Temp/claude/C--Users-lauye-Downloads-R-android/6471244e-45c9-4b58-a6cc-c90db51dfa4b/scratchpad/win-roots.jks"
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=$JKS -Djavax.net.ssl.trustStorePassword=changeit"
```
Append to every gradle command: `-Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`. Verify `$JKS` exists (else BLOCKED).

**Branch:** `feat/project-import-export` (already created; holds the spec).

---

## File Structure

- Create: `data/project/ProjectArchive.kt` (pure zip export/import), `ui/projects/ProjectSharing.kt` (`shareProjectZip`).
- Modify: `ui/editor/EditorViewModel.kt` (`importProject`), `ui/projects/ProjectsScreen.kt` (export/import UI).
- Test: `data/project/ProjectArchiveTest.kt`, and add cases to `ui/editor/EditorViewModelTest.kt`.
- Docs: `README.md`, `CLAUDE.md`.

---

## Task 1: `ProjectArchive` (pure zip export/import)

**Files:** Create `app/src/main/java/com/rmobile/console/data/project/ProjectArchive.kt`, `app/src/test/java/com/rmobile/console/data/project/ProjectArchiveTest.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/rmobile/console/data/project/ProjectArchiveTest.kt`:
```kotlin
package com.rmobile.console.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectArchiveTest {

    private fun project() = Project(
        id = 1,
        name = "Demo",
        files = listOf(
            ProjectFile("main.R", "source('helpers.R'); cat(f())"),
            ProjectFile("helpers.R", "f <- function() 1"),
        ),
        activeFileName = "helpers.R",
        entryFileName = "main.R",
        updatedAt = 1,
    )

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (n, c) ->
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `export then import round-trips files, entry, active, and name`() {
        val bytes = ProjectArchive.export(project())
        val restored = ProjectArchive.import(bytes, id = 9, now = 99, fallbackName = "ignored")!!
        assertEquals("Demo", restored.name)
        assertEquals(project().files.toSet(), restored.files.toSet())
        assertEquals("main.R", restored.entryFileName)
        assertEquals("helpers.R", restored.activeFileName)
        assertEquals(9L, restored.id)
        assertEquals(99L, restored.updatedAt)
    }

    @Test
    fun `manifest-less zip uses the fallback name and a main entry`() {
        val bytes = zipOf("main.R" to "cat(1)", "util.R" to "x <- 1")
        val p = ProjectArchive.import(bytes, id = 1, now = 1, fallbackName = "Fallback")!!
        assertEquals("Fallback", p.name)
        assertEquals("main.R", p.entryFileName)
        assertEquals("main.R", p.activeFileName)
        assertEquals(setOf("main.R", "util.R"), p.files.map { it.name }.toSet())
    }

    @Test
    fun `an invalid file name is rejected`() {
        assertNull(ProjectArchive.import(zipOf("../evil.R" to "x"), 1, 1, "x"))
    }

    @Test
    fun `garbage bytes return null`() {
        assertNull(ProjectArchive.import(byteArrayOf(1, 2, 3, 4), 1, 1, "x"))
    }

    @Test
    fun `a zip with no files returns null`() {
        assertNull(ProjectArchive.import(zipOf(), 1, 1, "x"))
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.project.ProjectArchiveTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (`ProjectArchive` doesn't exist).

- [ ] **Step 3: Create `ProjectArchive`**

Create `app/src/main/java/com/rmobile/console/data/project/ProjectArchive.kt`:
```kotlin
package com.rmobile.console.data.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Serializes a [Project] to / from a `.zip` of its `.R` files plus a
 * `.rmobile-project.json` manifest. Pure (no Android) so it's unit-testable.
 */
object ProjectArchive {

    private const val MANIFEST = ".rmobile-project.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Manifest(val name: String, val entryFileName: String, val activeFileName: String)

    fun export(project: Project): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            project.files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            val manifest = Manifest(project.name, project.entryFileName, project.activeFileName)
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Rebuilds a [Project] from [bytes]. Non-manifest entries become files (names
     * validated with [ProjectOps.isValidFileName], which also blocks path names);
     * the manifest supplies name/entry/active when present, else a fallback is
     * used. Returns null when the bytes aren't a readable zip, contain no files,
     * or contain an invalid file name. [id]/[now] are assigned by the caller.
     */
    fun import(bytes: ByteArray, id: Long, now: Long, fallbackName: String): Project? {
        val files = mutableListOf<ProjectFile>()
        var manifest: Manifest? = null
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zip.readBytes().toString(Charsets.UTF_8) // reads to end of this entry
                        if (entry.name == MANIFEST) {
                            manifest = runCatching { json.decodeFromString<Manifest>(content) }.getOrNull()
                        } else {
                            if (!ProjectOps.isValidFileName(entry.name)) return null
                            files.add(ProjectFile(entry.name, content))
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (files.isEmpty()) return null

        val names = files.map { it.name }.toSet()
        val defaultEntry = if ("main.R" in names) "main.R" else files.first().name
        val name = manifest?.name?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { "Imported project" }
        val entryFile = manifest?.entryFileName?.takeIf { it in names } ?: defaultEntry
        val activeFile = manifest?.activeFileName?.takeIf { it in names } ?: entryFile

        return Project(
            id = id,
            name = name,
            files = files,
            activeFileName = activeFile,
            entryFileName = entryFile,
            updatedAt = now,
        )
    }
}
```

- [ ] **Step 4: Run — verify PASS**

Run the Step 2 command. Expected: all 5 `ProjectArchiveTest` cases pass.

- [ ] **Step 5: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add app/src/main/java/com/rmobile/console/data/project/ProjectArchive.kt \
        app/src/test/java/com/rmobile/console/data/project/ProjectArchiveTest.kt
git commit -m "app: ProjectArchive — zip export/import of a project"
```

---

## Task 2: `EditorViewModel.importProject`

**Files:** Modify `ui/editor/EditorViewModel.kt`, `app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt`.

- [ ] **Step 1: Write the failing tests**

In `EditorViewModelTest.kt`, add the import `import com.rmobile.console.data.project.ProjectArchive` and these tests inside the class:
```kotlin
    @Test
    fun `import adds and opens a project`() {
        val vm = viewModel()
        val bytes = ProjectArchive.export(
            Project(5, "Imported", listOf(ProjectFile("main.R", "cat(7)")), "main.R", "main.R", 5),
        )
        vm.importProject(bytes, "fallback")
        assertEquals("Imported", vm.uiState.value.project.name)
        assertEquals("cat(7)", vm.uiState.value.code)
        assertTrue(vm.uiState.value.projects.any { it.name == "Imported" })
    }

    @Test
    fun `import of garbage sets an error and leaves the library unchanged`() {
        val vm = viewModel()
        val before = vm.uiState.value.projects.size
        vm.importProject(byteArrayOf(9, 9, 9), "x")
        assertEquals(before, vm.uiState.value.projects.size)
        assertTrue(vm.uiState.value.errorMessage!!.contains("import", ignoreCase = true))
    }
```
Also add the imports `import com.rmobile.console.data.project.Project` and `import com.rmobile.console.data.project.ProjectFile` if not already present in the test file (Task from multi-file added `Project`/`ProjectOps`/`ProjectStore`; `ProjectFile` may be new — add it).

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.editor.EditorViewModelTest" -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: FAIL to compile (`importProject` doesn't exist).

- [ ] **Step 3: Add `importProject`**

In `EditorViewModel.kt`, add the import `import com.rmobile.console.data.project.ProjectArchive` and this method (e.g. after `deleteProject`):
```kotlin
    /** Imports a project from a `.zip`'s bytes, adding it to the library and opening it. */
    fun importProject(bytes: ByteArray, fallbackName: String) {
        val imported = ProjectArchive.import(bytes, now(), now(), fallbackName)
        if (imported == null) {
            _uiState.update { it.copy(errorMessage = "Couldn't import — not a valid project zip.") }
            return
        }
        val updated = ProjectOps.upsert(_uiState.value.projects, imported)
        projectStore.persistProjects(updated)
        projectStore.persistLastOpenProjectId(imported.id)
        _uiState.update {
            it.copy(
                projects = updated,
                project = imported,
                code = ProjectOps.activeContent(imported),
                errorMessage = null,
            )
        }
    }
```

- [ ] **Step 4: Run — verify PASS**

Run: `./gradlew :app:testDebugUnitTest -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/editor/EditorViewModel.kt \
        app/src/test/java/com/rmobile/console/ui/editor/EditorViewModelTest.kt
git commit -m "app: EditorViewModel.importProject adds + opens an imported project"
```

---

## Task 3: Export/import UI in ProjectsScreen

**Files:** Create `ui/projects/ProjectSharing.kt`; Modify `ui/projects/ProjectsScreen.kt`. No unit test (Android intents/SAF); verify by compile.

- [ ] **Step 1: Create the share helper**

Create `app/src/main/java/com/rmobile/console/ui/projects/ProjectSharing.kt`:
```kotlin
package com.rmobile.console.ui.projects

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rmobile.console.data.project.Project
import com.rmobile.console.data.project.ProjectArchive
import java.io.File

/**
 * Writes [project] as a `.zip` into the app cache and launches a share sheet for
 * it via the app's [FileProvider]. Best-effort (toast on failure).
 */
fun shareProjectZip(context: Context, project: Project) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        val file = File(dir, "$safe.zip")
        file.writeBytes(ProjectArchive.export(project))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share project"))
    }.onFailure {
        Toast.makeText(context, "Couldn't share the project.", Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 2: Wire export + import into ProjectsScreen**

**Read `ProjectsScreen.kt` first.** It has `ProjectsScreen(viewModel: EditorViewModel, onOpenEditor)`, a `TopAppBar` with a `navigationIcon` (back) and an `actions` block containing a "New project" `IconButton` (`Icons.Default.Add`), and a `LazyColumn` whose per-project `Row` has a rename `IconButton` (`Icons.Default.Edit`) and a delete `IconButton` (`Icons.Default.Delete`).

(a) Add these imports:
```kotlin
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
```

(b) Near the top of `ProjectsScreen` (after `val uiState by ...`), add the context, the import launcher, and an import-menu state:
```kotlin
    val context = LocalContext.current
    var importMenu by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument) { uri: Uri? ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) {
                val fallback = importDisplayName(context, uri)?.removeSuffix(".zip") ?: "Imported project"
                viewModel.importProject(bytes, fallback)
                onOpenEditor()
            }
        }
    }
```

(c) In the `TopAppBar` `actions` block, add an overflow menu with an "Import project…" item (next to the existing "New project" `IconButton`):
```kotlin
                    IconButton(onClick = { importMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Import project…") },
                            onClick = {
                                importMenu = false
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                        )
                    }
```

(d) In the per-project `Row`, add an export `IconButton` before the rename one:
```kotlin
                    IconButton(onClick = { shareProjectZip(context, project) }) {
                        Icon(Icons.Default.Share, contentDescription = "Export project")
                    }
```

- [ ] **Step 3: Add the display-name helper**

At the end of `ProjectsScreen.kt` (top-level private function), add:
```kotlin
private fun importDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && c.getColumnIndex(OpenableColumns.DISPLAY_NAME) >= 0) {
            c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME))
        } else {
            null
        }
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL. Fix any missing/duplicate import and re-run.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/rmobile/console/ui/projects/ProjectSharing.kt \
        app/src/main/java/com/rmobile/console/ui/projects/ProjectsScreen.kt
git commit -m "app: export (share-sheet) + import (file picker) projects in the library"
```

---

## Task 4: Full verification + docs

**Files:** Modify `README.md`, `CLAUDE.md`.

- [ ] **Step 1: Full app check**

Run: `./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug -Djavax.net.ssl.trustStore="$JKS" -Djavax.net.ssl.trustStorePassword=changeit --console=plain`
Expected: BUILD SUCCESSFUL; 0 test failures; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 2: Docs**

- `README.md` "Features": under the multi-file-projects bullet, add "— export/import a project as a `.zip` of its `.R` files (share-sheet / file picker)."
- `CLAUDE.md`: in the `data/project/` description, note `ProjectArchive` (pure zip export/import) and that `ProjectsScreen` exports via the share-sheet and imports via SAF (`EditorViewModel.importProject`).

- [ ] **Step 3: Commit**
```bash
git add README.md CLAUDE.md
git commit -m "docs: document project import/export"
```

---

## Done

Projects can be exported to a `.zip` (share-sheet) and imported back (file
picker), with a pure, tested `ProjectArchive`. Open a PR from
`feat/project-import-export` (base `claude/r-app-android-version-ztmyd1`).
