# On-device package installation (Local/WebR engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Local (WebR) engine install, list, and uninstall R packages on-device — hybrid source (APK-bundled core repo + online r-wasm fallback) with an IndexedDB-backed library that survives app restarts.

**Architecture:** Package operations become engine-routed (added to the `ExecutionEngine` interface), so the existing Packages screen drives whichever engine is active. `RemoteExecutionEngine` delegates to the current backend calls (unchanged); `LocalExecutionEngine` delegates to new `WebRController` → `bridge.js` functions that call `webR.installPackages(pkg, { repos: [bundledRepo, r-wasm] })` and persist the library via an IndexedDB-mounted VFS dir.

**Tech Stack:** Kotlin + Jetpack Compose, kotlinx.serialization, JUnit + coroutines-test (JVM), WebR 0.4.2 (WASM in an offscreen WebView), Node.js (vendoring script).

**Reference:** `docs/superpowers/specs/2026-07-12-on-device-package-install-design.md`.

**Environment note (test command):** JVM unit tests need `JAVA_HOME` set (Android Studio's JBR). All `./gradlew` commands below assume it is set. On this machine (bash tool): `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.

**Verification legend:**
- 🟢 **CI/JVM** — verified by `./gradlew :app:testDebugUnitTest`; a subagent can fully verify.
- 🔵 **Device** — WebR/WebView/asset behavior CI cannot exercise; the subagent implements to spec and the **user verifies on a device/emulator** (as with the whole on-device engine). These tasks are complete when the code is in and self-consistent; the user runs the manual checklist in Task 6.

---

## File Structure

- `app/.../data/execution/ExecutionEngine.kt` — **modify**: 3 new interface methods + `RemoteExecutionEngine` impls.
- `app/.../data/execution/LocalExecutionEngine.kt` — **modify**: 3 new methods delegating to the controller.
- `app/.../data/execution/WebRController.kt` — **modify**: `installPackage`/`uninstallPackage`/`listPackages` bridge wrappers.
- `app/.../ui/packages/PackagesViewModel.kt` — **modify**: route package ops through the current engine; add `engineIsLocal`.
- `app/.../ui/packages/PackagesUiState.kt` — **modify**: add `engineIsLocal`.
- `app/.../ui/packages/PackagesScreen.kt` — **modify**: engine-aware caption; hide "Import legacy" on Local.
- `app/src/main/assets/webr/bridge.js` — **modify**: boot-time persistent library mount + `webrInstall`/`webrUninstall`/`webrListPackages`.
- `app/src/main/assets/webr/scripts/fetch-webr-packages.mjs` + `fetch-webr-packages.sh` — **create**: vendoring script.
- `app/src/main/assets/webr/repo/**` — **create** (generated, committed binary): the bundled mini-repo.
- `.gitattributes` — **modify**: mark `repo/**` binary.
- Tests: `RemoteExecutionEngineTest.kt`, `PackagesViewModelTest.kt` — **modify**.
- Docs: `CLAUDE.md`, `README.md`, `app/src/main/assets/webr/README.md` — **modify**.

---

## Task 1: Extend the engine contract (interface + Remote + Local + WebRController)

🟢 CI/JVM for the RemoteExecutionEngine part; the Local/controller edits just need to compile.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/ExecutionEngine.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/execution/LocalExecutionEngine.kt`
- Modify: `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/RemoteExecutionEngineTest.kt`

- [ ] **Step 1: Write the failing tests** (append to `RemoteExecutionEngineTest.kt`)

The existing `RecordingApi` already implements every `RExecutionApi` method. Extend it to record package calls, and add three tests. Replace the `install`/`uninstall`/`packages` overrides in `RecordingApi` with recording versions and add fields:

```kotlin
    private class RecordingApi : RExecutionApi {
        var lastRequest: ExecuteRequest? = null
        var lastInstall: com.rmobile.console.data.model.InstallRequest? = null
        var lastUninstall: com.rmobile.console.data.model.UninstallRequest? = null
        var lastPackagesSessionId: String? = null
        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            lastRequest = request
            return ExecuteResponse(stdout = "ok", workspaceObjects = listOf("x"))
        }
        override suspend fun reset(request: com.rmobile.console.data.model.ResetRequest) = ResetResponse(ok = true)
        override suspend fun install(request: com.rmobile.console.data.model.InstallRequest): com.rmobile.console.data.model.InstallResponse {
            lastInstall = request
            return com.rmobile.console.data.model.InstallResponse(installed = true, stdout = "installed")
        }
        override suspend fun uninstall(request: com.rmobile.console.data.model.UninstallRequest): com.rmobile.console.data.model.UninstallResponse {
            lastUninstall = request
            return com.rmobile.console.data.model.UninstallResponse(removed = true)
        }
        override suspend fun packages(sessionId: String): com.rmobile.console.data.model.PackagesResponse {
            lastPackagesSessionId = sessionId
            return com.rmobile.console.data.model.PackagesResponse(listOf("glue"))
        }
        override suspend fun importLegacy(request: com.rmobile.console.data.model.ImportLegacyRequest) = com.rmobile.console.data.model.ImportLegacyResponse()
        override suspend fun symbols(sessionId: String) = com.rmobile.console.data.model.SymbolsResponse()
        override suspend fun help(request: com.rmobile.console.data.model.HelpRequest) = com.rmobile.console.data.model.HelpResponse()
        override suspend fun preview(request: com.rmobile.console.data.model.PreviewRequest) = com.rmobile.console.data.model.PreviewResponse()
    }

    @Test
    fun `install forwards package and session to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.install(com.rmobile.console.data.model.InstallRequest("praise", "proj-2"))
        assertEquals("praise", api.lastInstall!!.packageName)
        assertEquals("proj-2", api.lastInstall!!.sessionId)
        assertTrue(result.getOrNull()!!.installed)
    }

    @Test
    fun `uninstall forwards package and session to the repository`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.uninstall(com.rmobile.console.data.model.UninstallRequest("praise", "proj-2"))
        assertEquals("praise", api.lastUninstall!!.packageName)
        assertTrue(result.getOrNull()!!.removed)
    }

    @Test
    fun `listPackages forwards the session and returns the list`() = runTest {
        val api = RecordingApi()
        val engine = RemoteExecutionEngine(RExecutionRepository(api))
        val result = engine.listPackages("proj-2")
        assertEquals("proj-2", api.lastPackagesSessionId)
        assertEquals(listOf("glue"), result.getOrNull()!!.packages)
    }
```

- [ ] **Step 2: Run tests to verify they fail (compile error)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.RemoteExecutionEngineTest"`
Expected: FAIL — compilation error, `engine.install`/`uninstall`/`listPackages` are unresolved.

- [ ] **Step 3: Add the three methods to the `ExecutionEngine` interface and implement `RemoteExecutionEngine`**

In `ExecutionEngine.kt`, add imports and the interface methods + Remote impls:

```kotlin
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
```

Interface (add below `reset`):

```kotlin
    /** Lists the packages installed for [sessionId] (Remote) or globally (Local). */
    suspend fun listPackages(sessionId: String): Result<PackagesResponse>
    /** Installs a package. */
    suspend fun install(request: InstallRequest): Result<InstallResponse>
    /** Uninstalls a package. */
    suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse>
```

`RemoteExecutionEngine` (add):

```kotlin
    override suspend fun listPackages(sessionId: String): Result<PackagesResponse> =
        repository.listPackages(sessionId)

    override suspend fun install(request: InstallRequest): Result<InstallResponse> =
        repository.install(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)

    override suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse> =
        repository.uninstall(request.packageName, request.sessionId ?: RExecutionRepository.DEFAULT_SESSION_ID)
```

- [ ] **Step 4: Implement the new methods in `WebRController` and `LocalExecutionEngine`** (so the module compiles)

In `WebRController.kt`, add below `reset()`:

```kotlin
    /** Installs [pkg] via the bridge (bundled repo first, then r-wasm). Returns InstallResponse JSON. */
    suspend fun installPackage(pkg: String): String =
        callBridge("window.webrInstall", org.json.JSONObject.quote(pkg))

    /** Uninstalls [pkg] via the bridge. Returns UninstallResponse JSON. */
    suspend fun uninstallPackage(pkg: String): String =
        callBridge("window.webrUninstall", org.json.JSONObject.quote(pkg))

    /** Lists installed packages via the bridge. Returns PackagesResponse JSON. */
    suspend fun listPackages(): String = callBridge("window.webrListPackages", null)
```

In `LocalExecutionEngine.kt`, add imports and methods:

```kotlin
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.InstallResponse
import com.rmobile.console.data.model.PackagesResponse
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.model.UninstallResponse
```

```kotlin
    override suspend fun listPackages(sessionId: String): Result<PackagesResponse> = runCatching {
        json.decodeFromString<PackagesResponse>(controller.listPackages())
    }

    override suspend fun install(request: InstallRequest): Result<InstallResponse> = runCatching {
        json.decodeFromString<InstallResponse>(controller.installPackage(request.packageName))
    }

    override suspend fun uninstall(request: UninstallRequest): Result<UninstallResponse> = runCatching {
        json.decodeFromString<UninstallResponse>(controller.uninstallPackage(request.packageName))
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.RemoteExecutionEngineTest"`
Expected: PASS (5 tests). If other test-file `ExecutionEngine` fakes now fail to compile, fix them in Step 6 first.

- [ ] **Step 6: Fix any other test `ExecutionEngine` implementations**

Fakes implementing `ExecutionEngine` live in `EditorViewModelTest.kt`, `SettingsViewModelTest.kt`, and `ExecutionEngineChoiceTest.kt`. For each `ExecutionEngine` fake, add the three overrides returning defaults:

```kotlin
        override suspend fun listPackages(sessionId: String) = Result.success(com.rmobile.console.data.model.PackagesResponse())
        override suspend fun install(request: com.rmobile.console.data.model.InstallRequest) = Result.success(com.rmobile.console.data.model.InstallResponse())
        override suspend fun uninstall(request: com.rmobile.console.data.model.UninstallRequest) = Result.success(com.rmobile.console.data.model.UninstallResponse())
```

Run the full suite: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing tests + 3 new).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/ app/src/test/java/com/rmobile/console/
git commit -m "$(cat <<'EOF'
feat: add package ops (list/install/uninstall) to ExecutionEngine

RemoteExecutionEngine delegates to the existing repository calls; the Local
engine delegates to new WebRController bridge wrappers (JS added next).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Route PackagesViewModel through the current engine

🟢 CI/JVM.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesUiState.kt`
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesViewModel.kt`
- Test: `app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt`

- [ ] **Step 1: Write the failing tests** (in `PackagesViewModelTest.kt`)

First, update the `viewModel(...)` helper so existing tests route through a `RemoteExecutionEngine` wrapping the same `FakeApi`, and add an `engineIsLocal` seam:

```kotlin
    private fun viewModel(
        api: FakeApi,
        projectStore: ProjectStore = InMemoryProjectStore(),
        engineIsLocal: Boolean = false,
    ): PackagesViewModel {
        val repo = RExecutionRepository(api)
        return PackagesViewModel(
            repository = repo,
            projectStore = projectStore,
            engineProvider = { com.rmobile.console.data.execution.RemoteExecutionEngine(repo) },
            engineIsLocalProvider = { engineIsLocal },
        )
    }
```

Add a `FakeEngine` and two new tests:

```kotlin
    private class FakeEngine : com.rmobile.console.data.execution.ExecutionEngine {
        var installedArg: String? = null
        override suspend fun execute(request: ExecuteRequest) = Result.success(ExecuteResponse())
        override suspend fun reset(sessionId: String) = Result.success(Unit)
        override suspend fun listPackages(sessionId: String) = Result.success(PackagesResponse(listOf("local-pkg")))
        override suspend fun install(request: InstallRequest): Result<InstallResponse> {
            installedArg = request.packageName
            return Result.success(InstallResponse(installed = true))
        }
        override suspend fun uninstall(request: UninstallRequest) = Result.success(UninstallResponse(removed = true))
    }

    @Test
    fun `routes install through the provided engine, not the repository`() = runTest {
        val api = FakeApi()
        val fakeEngine = FakeEngine()
        val vm = PackagesViewModel(
            repository = RExecutionRepository(api),
            projectStore = InMemoryProjectStore(),
            engineProvider = { fakeEngine },
            engineIsLocalProvider = { true },
        )
        advanceUntilIdle()
        assertEquals(listOf("local-pkg"), vm.uiState.value.installed) // list came from the engine
        vm.onPackageNameChanged("dplyr")
        vm.install()
        advanceUntilIdle()
        assertEquals("dplyr", fakeEngine.installedArg)          // engine received the install
        assertNull(api.lastInstallSessionId)                     // repository was NOT used
    }

    @Test
    fun `engineIsLocal is reflected in state`() = runTest {
        val vm = viewModel(FakeApi(), engineIsLocal = true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.engineIsLocal)
    }
```

Add `import org.junit.Assert.assertNull` at the top.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest"`
Expected: FAIL — `PackagesViewModel` has no `engineProvider`/`engineIsLocalProvider` params; `uiState.engineIsLocal` unresolved.

- [ ] **Step 3: Add `engineIsLocal` to `PackagesUiState`**

```kotlin
data class PackagesUiState(
    val installed: List<String> = emptyList(),
    val packageName: String = "",
    val installing: Boolean = false,
    val message: String? = null,
    val log: String = "",
    val isError: Boolean = false,
    val projectName: String = "",
    val engineIsLocal: Boolean = false,
)
```

- [ ] **Step 4: Route `PackagesViewModel` through the engine**

Add imports:

```kotlin
import com.rmobile.console.data.execution.ExecutionEngine
import com.rmobile.console.data.model.InstallRequest
import com.rmobile.console.data.model.UninstallRequest
import com.rmobile.console.data.settings.ExecutionEngineChoice
```

Change the constructor and body. New constructor:

```kotlin
class PackagesViewModel(
    private val repository: RExecutionRepository = RExecutionRepository(NetworkModule.rExecutionApi),
    private val projectStore: ProjectStore = ServiceLocator.settingsStore,
    private val engineProvider: () -> ExecutionEngine = { ServiceLocator.currentExecutionEngine() },
    private val engineIsLocalProvider: () -> Boolean =
        { ServiceLocator.settingsStore.executionEngine == ExecutionEngineChoice.LOCAL },
) : ViewModel() {
```

In `init`, set the flag after computing `session`:

```kotlin
        _uiState.value = _uiState.value.copy(
            projectName = active?.name ?: "",
            engineIsLocal = engineIsLocalProvider(),
        )
        refresh()
```

Replace the three repository calls with engine calls:

- `refresh()`: `engineProvider().listPackages(session).onSuccess { ... }`
- `install()`: `engineProvider().install(InstallRequest(pkg, session)).onSuccess { ... }.onFailure { ... }` (keep the exact success/error branching; `response` is still an `InstallResponse`).
- `uninstall(packageName)`: `engineProvider().uninstall(UninstallRequest(packageName, session)).onSuccess { ... }.onFailure { ... }`.

Leave `importLegacy()` calling `repository.importLegacy(session)` unchanged (Remote-only).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.rmobile.console.ui.packages.PackagesViewModelTest"`
Expected: PASS (all prior tests via the Remote-engine helper + 2 new).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/packages/ app/src/test/java/com/rmobile/console/ui/packages/PackagesViewModelTest.kt
git commit -m "$(cat <<'EOF'
feat: route Packages screen through the active execution engine

Install/uninstall/list now target ServiceLocator.currentExecutionEngine(), so
the Packages screen manages the Local (on-device) library when Local is active.
importLegacy stays Remote-only. Adds engineIsLocal to the UI state.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: bridge.js — persistent library + install/uninstall/list

🔵 Device (WebR/WebView). Implement to spec; the user verifies in Task 6.

**Files:**
- Modify: `app/src/main/assets/webr/bridge.js`

- [ ] **Step 1: Mount a persistent library dir at boot**

Replace the `boot()` function so it mounts an IndexedDB-backed dir and puts it first on `.libPaths()` **before** signalling ready. Keep the existing `/rmobile` dir creation:

```js
const LOCAL_REPO_URL = new URL('./repo', document.baseURI).href;

async function mountLibrary() {
  // Persist installed packages across app restarts via an IndexedDB-backed VFS
  // dir that is first on .libPaths(). Verified on-device; if IDBFS is not
  // available in this WebR build, see the snapshot fallback in the plan.
  await webR.evalRVoid('dir.create("/rmobile/library", showWarnings = FALSE, recursive = TRUE)');
  await webR.FS.mount('IDBFS', {}, '/rmobile/library');
  await webR.FS.syncfs(true); // load previously-persisted packages
  await webR.evalRVoid('.libPaths(c("/rmobile/library", .libPaths()))');
}

async function persistLibrary() {
  try { await webR.FS.syncfs(false); } catch (e) { /* best-effort flush */ }
}

async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  await mountLibrary();
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));
```

> **IDBFS-unavailable fallback (only if Step-1 device check fails in Task 6):** replace `webR.FS.mount('IDBFS', …)` + `syncfs` with a manual snapshot: on boot, ask Kotlin (a new `AndroidBridge.readLibraryArchive()` returning base64, added to `WebRController`) for a previously saved tarball, `FS.writeFile` it and `untar` into `/rmobile/library`; in `persistLibrary()`, `tar` the dir, `FS.readFile` it, and hand the base64 to a new `AndroidBridge.writeLibraryArchive(b64)` that writes under `filesDir`. Choose IDBFS if the boot check passes; only build the fallback if it does not.

- [ ] **Step 2: Add the package bridge functions**

Append these `window.*` handlers (alongside `webrRun`/`webrReset`), each guarded by `ready` and returning JSON matching the Kotlin models:

```js
window.webrInstall = async (id, pkg) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ installed: false, error: 'WebR not ready', stdout: '', stderr: '', timedOut: false })); return; }
  const shelter = await new webR.Shelter();
  try {
    const cap = await shelter.captureR(
      `webr::install(${JSON.stringify(pkg)}, repos = c(${JSON.stringify(LOCAL_REPO_URL)}, "https://repo.r-wasm.org"))`,
      { withAutoprint: false, captureStreams: true }
    );
    const stdout = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
    const stderr = cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n');
    // Confirm the package is now loadable to decide installed=true.
    const okR = await webR.evalR(`requireNamespace(${JSON.stringify(pkg)}, quietly = TRUE)`);
    const installed = (await okR.toBoolean?.()) ?? (await okR.toArray())[0] === true;
    webR.destroy(okR);
    if (installed) await persistLibrary();
    AndroidBridge.onResult(id, JSON.stringify({
      installed, stdout, stderr,
      error: installed ? null : `Could not install ${pkg} (not in the bundled repo and not reachable online?).`,
      timedOut: false, systemRequirements: null,
    }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ installed: false, stdout: '', stderr: String(e), error: String(e), timedOut: false, systemRequirements: null }));
  } finally { shelter.purge(); }
};

window.webrUninstall = async (id, pkg) => {
  try {
    await webR.evalRVoid(`remove.packages(${JSON.stringify(pkg)}, lib = "/rmobile/library")`);
    await persistLibrary();
    const stillR = await webR.evalR(`${JSON.stringify(pkg)} %in% rownames(installed.packages())`);
    const still = (await stillR.toArray())[0] === true;
    webR.destroy(stillR);
    AndroidBridge.onResult(id, JSON.stringify({ removed: !still, error: still ? `${pkg} was not removed.` : null }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ removed: false, error: String(e) }));
  }
};

window.webrListPackages = async (id) => {
  try {
    const r = await webR.evalR('rownames(installed.packages())');
    const packages = await r.toArray();
    webR.destroy(r);
    AndroidBridge.onResult(id, JSON.stringify({ packages }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ packages: [] }));
  }
};
```

> **Device-verify notes (Task 6):** exact WebR API names to confirm on-device — `webR.FS.mount`/`syncfs` signature and IDBFS availability; `webr::install(..., repos=)` accepting a vector and installing into `.libPaths()[1]`; `RObject.toArray()`/`toBoolean()` shapes. Adjust minimally if a name differs; the bridge/Kotlin contract (the JSON shapes) must not change.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/webr/bridge.js
git commit -m "$(cat <<'EOF'
feat: WebR bridge — persistent library + install/uninstall/list

Boot mounts an IndexedDB-backed /rmobile/library (first on .libPaths) so
installs survive restarts; webrInstall uses repos=[bundled, r-wasm]. Device-verified.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Bundled mini-repo + vendoring script

🔵 Device for the runtime behavior; the script itself runs on this machine (Node + internet).

**Files:**
- Create: `app/src/main/assets/webr/scripts/fetch-webr-packages.mjs`
- Create: `app/src/main/assets/webr/scripts/fetch-webr-packages.sh`
- Create (generated): `app/src/main/assets/webr/repo/bin/emscripten/contrib/<Rver>/{PACKAGES, *.tgz}`
- Modify: `.gitattributes`

- [ ] **Step 1: Write the vendoring Node script** (`fetch-webr-packages.mjs`)

Resolves the recursive dependency closure of the seed set from r-wasm's `PACKAGES` index and downloads each binary into the local repo layout, then writes a local `PACKAGES` containing exactly the vendored set.

```js
#!/usr/bin/env node
// Vendors WebR WASM binaries for the seed set + their full recursive dependency
// closure into app/src/main/assets/webr/repo, mirroring r-wasm's layout so WebR
// treats it as a local repo. Re-run to update. Requires Node 18+ (global fetch).
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { dirname, join, fileURLToPath } from 'node:path';
import { fileURLToPath as f2p } from 'node:url';

const R_VER = process.env.R_VER || '4.4';                 // WebR 0.4.2 → R 4.4.x (verify on-device)
const REPO = 'https://repo.r-wasm.org';
const CONTRIB = `bin/emscripten/contrib/${R_VER}`;
const SEED = [
  // tidyverse core
  'dplyr','tidyr','ggplot2','readr','stringr','tibble','purrr','forcats','lubridate',
  // easystats core
  'parameters','performance','effectsize','insight','datawizard',
  // tiny utilities
  'jsonlite','cli',
];
const here = dirname(f2p(import.meta.url));
const DEST = join(here, '..', 'repo', CONTRIB);

// Parse a Debian-control PACKAGES file into { name: {version, deps:[...]} }.
function parsePackages(text) {
  const out = {};
  for (const block of text.split(/\n\n+/)) {
    if (!block.trim()) continue;
    const fields = {};
    let key = null;
    for (const line of block.split('\n')) {
      const m = line.match(/^([A-Za-z]+):\s?(.*)$/);
      if (m) { key = m[1]; fields[key] = m[2]; }
      else if (key) fields[key] += ' ' + line.trim();
    }
    if (!fields.Package) continue;
    const deps = ['Depends','Imports','LinkingTo']
      .flatMap((k) => (fields[k] || '').split(','))
      .map((d) => d.replace(/\(.*?\)/g, '').trim())
      .filter(Boolean);
    out[fields.Package] = { version: fields.Version, deps };
  }
  return out;
}

const BASE = new Set(['R','base','methods','utils','stats','graphics','grDevices',
  'datasets','tools','grid','splines','stats4','tcltk','compiler','parallel']);

async function main() {
  console.log(`Fetching ${REPO}/${CONTRIB}/PACKAGES ...`);
  const index = parsePackages(await (await fetch(`${REPO}/${CONTRIB}/PACKAGES`)).text());

  // Resolve recursive closure.
  const wanted = new Set();
  const stack = [...SEED];
  while (stack.length) {
    const p = stack.pop();
    if (wanted.has(p) || BASE.has(p)) continue;
    if (!index[p]) { console.warn(`WARN: ${p} not in r-wasm index — skipping`); continue; }
    wanted.add(p);
    for (const d of index[p].deps) if (!wanted.has(d) && !BASE.has(d)) stack.push(d);
  }
  const names = [...wanted].sort();
  console.log(`Resolved ${names.length} packages.`);

  await rm(DEST, { recursive: true, force: true });
  await mkdir(DEST, { recursive: true });

  const localBlocks = [];
  for (const name of names) {
    const ver = index[name].version;
    const file = `${name}_${ver}.tgz`;
    const url = `${REPO}/${CONTRIB}/${file}`;
    process.stdout.write(`  ${file} ... `);
    const res = await fetch(url);
    if (!res.ok) { console.log(`FAILED ${res.status}`); throw new Error(`download ${url}`); }
    await writeFile(join(DEST, file), Buffer.from(await res.arrayBuffer()));
    console.log('ok');
    localBlocks.push(`Package: ${name}\nVersion: ${ver}\n`);
  }
  await writeFile(join(DEST, 'PACKAGES'), localBlocks.join('\n') + '\n');
  console.log(`Wrote ${DEST}/PACKAGES with ${names.length} entries.`);
}
main().catch((e) => { console.error(e); process.exit(1); });
```

- [ ] **Step 2: Write the shell wrapper** (`fetch-webr-packages.sh`)

```bash
#!/usr/bin/env bash
# Vendors the bundled WebR package repo (seed set + dependency closure) into
# app/src/main/assets/webr/repo. Requires Node 18+ and internet. Commit repo/.
set -euo pipefail
node "$(cd "$(dirname "$0")" && pwd)/fetch-webr-packages.mjs"
```

- [ ] **Step 3: Run the script to vendor the repo**

Run: `node app/src/main/assets/webr/scripts/fetch-webr-packages.mjs`
Expected: prints the resolved package count and downloads each `.tgz`, then writes `PACKAGES`. Verify:

Run: `ls app/src/main/assets/webr/repo/bin/emscripten/contrib/4.4/ | head` and `test -f app/src/main/assets/webr/repo/bin/emscripten/contrib/4.4/PACKAGES && echo OK`
Expected: many `*.tgz` files and `OK`. (If the R version dir differs, set `R_VER` to match WebR 0.4.2 and re-run — confirmed on-device in Task 6.)

- [ ] **Step 4: Mark the repo binaries as binary in git**

Append to `.gitattributes`:

```gitattributes
app/src/main/assets/webr/repo/** -text
```

- [ ] **Step 5: Verify no bare `.gz` under repo/ (AAPT gunzip guard)**

Run: `find app/src/main/assets/webr/repo -name '*.gz' -not -name '*.tgz' | head`
Expected: no output. (`.tgz` is safe — AAPT only auto-gunzips a literal `.gz` extension.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/assets/webr/scripts/fetch-webr-packages.mjs app/src/main/assets/webr/scripts/fetch-webr-packages.sh app/src/main/assets/webr/repo .gitattributes
git commit -m "$(cat <<'EOF'
feat: bundle a WebR mini-repo (tidyverse + easystats core) for offline install

fetch-webr-packages.mjs resolves the seed set's full dependency closure from
r-wasm and vendors the binaries; the bridge searches this repo before the network.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Packages screen — engine-aware caption + hide legacy import on Local

🔵 Device/manual (Compose). No JVM assertion; verified visually in Task 6.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt`

- [ ] **Step 1: Make the caption engine-aware**

Replace the fixed caption `Text("Installed packages apply to the Remote engine.", …)` with:

```kotlin
            Text(
                if (uiState.engineIsLocal)
                    "On-device (Local) package library. Core tidyverse/easystats packages install offline; others download on demand."
                else
                    "Installed packages apply to the Remote engine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
```

- [ ] **Step 2: Hide "Import from legacy library" when Local is active**

Wrap the existing `TextButton(onClick = viewModel::importLegacy)` block:

```kotlin
            if (!uiState.engineIsLocal) {
                TextButton(onClick = viewModel::importLegacy) {
                    Text("Import packages from legacy library")
                }
            }
```

- [ ] **Step 3: Adjust the uninstall dialog copy (optional polish)**

In the `AlertDialog` text, "Removes $target from the shared library." reads fine for both engines; leave as-is.

- [ ] **Step 4: Build to confirm it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/ui/packages/PackagesScreen.kt
git commit -m "$(cat <<'EOF'
feat: Packages screen caption reflects engine; hide legacy import on Local

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Docs + full verification

🟢 CI/JVM for the suite; 🔵 Device for the manual checklist.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `app/src/main/assets/webr/README.md`

- [ ] **Step 1: Run the full JVM suite + Kotlin compile**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests).

- [ ] **Step 2: Update `CLAUDE.md`**

- In the WebR/Local-engine sections: the Local engine now supports package install (hybrid source, IndexedDB-persistent library at `/rmobile/library`). Document `bridge.js` `webrInstall`/`webrUninstall`/`webrListPackages`, the bundled repo (`assets/webr/repo/`, layout, `fetch-webr-packages.mjs`, seed = tidyverse + easystats core), and that `ExecutionEngine` now carries `listPackages`/`install`/`uninstall` (routed by `PackagesViewModel`).
- Update the "v1 boundaries" paragraph: remove "no package install"; keep "no data-import" and "no cross-restart *workspace* persistence" (note: package library now *does* persist across restarts, workspace still does not); keep "engine choice app-wide".
- Update the "Still not built" list: drop on-device package installation.

- [ ] **Step 3: Update `README.md`**

- Features → On-device execution: note the Local engine can install packages (core tidyverse/easystats offline; the rest online) into a library that persists across restarts.
- "Not built yet" list: remove "on-device package installation"; keep local-engine data import, cross-restart *workspace* persistence, per-project engine choice.
- Add a one-line pointer under re-vendoring: "`fetch-webr-packages.sh` re-vendors the bundled offline package repo."

- [ ] **Step 4: Update `app/src/main/assets/webr/README.md`**

Add a "Bundled package repo" section: the `repo/` layout, the seed set, that `fetch-webr-packages.sh` resolves the dependency closure and vendors binaries, and that these are `.tgz` (AAPT-safe, unlike the VFS `.gz`).

- [ ] **Step 5: Device verification checklist (USER runs on emulator/device)**

Build & install: `./gradlew :app:installDebug`. With **Local** engine selected in Settings:
1. Packages screen shows the "On-device (Local)" caption and **no** "Import legacy" button.
2. **Offline install:** enable airplane mode; install `dplyr` → succeeds (served from the bundled repo); `library(dplyr); dplyr::glimpse(mtcars)` runs.
3. **Online install:** disable airplane mode; install a non-bundled package (e.g. `praise`) → succeeds; `praise::praise()` runs.
4. **Persistence:** fully kill and relaunch the app; open Packages → `dplyr`/`praise` still listed; `library(dplyr)` works without reinstalling.
5. **Uninstall:** remove `praise` → disappears from the list; `library(praise)` now errors.
6. Switch to **Remote** engine → Packages screen shows the Remote caption and the legacy-import button returns; Remote install still works (backend running).
7. **If step 4 fails** (packages vanish on relaunch), IDBFS is unavailable → apply the snapshot fallback in Task 3 Step 1 and re-verify.

- [ ] **Step 6: Commit docs**

```bash
git add CLAUDE.md README.md app/src/main/assets/webr/README.md
git commit -m "$(cat <<'EOF'
docs: on-device package install (hybrid source, persistent library)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Finish the branch**

Use superpowers:finishing-a-development-branch (push + PR into `claude/r-app-android-version-ztmyd1`).

---

## Self-Review Notes

- **Spec coverage:** engine abstraction (T1), hybrid source via repos vector + bundled repo (T3,T4), persistence (T3), UI (T2,T5), docs (T6), device verification (T6). All spec sections mapped.
- **Type consistency:** `InstallRequest(packageName, sessionId)`, `UninstallRequest(packageName, sessionId)`, `PackagesResponse(packages)`, `InstallResponse(installed, stdout, stderr, error, timedOut, systemRequirements)`, `UninstallResponse(removed, error)` — used identically in interface, engines, ViewModel, and bridge JSON.
- **Device-gated honesty:** T3/T4/T5 runtime behavior is device-verified (WebR/WebView/AAPT); the JS names and IDBFS availability carry explicit verify-and-adjust notes with a concrete fallback. JVM-provable logic (engine routing, ViewModel) is TDD'd in T1–T2.
