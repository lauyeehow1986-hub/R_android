# Local (WebR) Workspace Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Local (WebR) engine's workspace variables survive an app restart, reusing the package library's proven `save.image → stream to filesDir → load at boot` mechanism.

**Architecture:** The one live WebR `globalenv()` is the entire Local session (app-wide, shared across projects). We snapshot it to `filesDir/webr-workspace.RData` after each successful run (best-effort, non-blocking) and `load()` it back at boot. The chunked base64 transport that the library snapshot already uses is extracted into a pure, JVM-testable `SnapshotStore` and parameterized by a `kind` string so the library and workspace share one code path.

**Tech Stack:** Kotlin (Android, `@JavascriptInterface` bridge), JavaScript (WebR `bridge.js`), R (`save.image`/`load`), JUnit4 (`TemporaryFolder`), Gradle.

---

## Context every task needs

- The Local engine lives in `app/src/main/assets/webr/bridge.js` (JS running in an offscreen WebView) and `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt` (Kotlin host). They talk over a synchronous `@JavascriptInterface` bridge named `AndroidBridge`, which marshals **strings only** (hence base64 for binary).
- The **library** snapshot already works exactly this way: `snapshotLibrary()`/`restoreLibrary()` in `bridge.js` tar the user library, then call `AndroidBridge.snapshotBegin()/snapshotAppend(b64)/snapshotCommit()` (write) and `snapshotSize()/snapshotRead(offset,length)` (read). Those Kotlin methods are currently hardcoded to a single file (`webr-library.tar.gz`) and inlined in `WebRController.Bridge`.
- **Everything here is best-effort:** a snapshot or restore failure must never break a run, a reset, or boot. Keep the existing `try/catch … {}` swallowing at the bridge boundary.
- **CRLF gotcha:** `bridge.js` is JS (not `webr/*.R`), edited normally. Do not touch `harness.R`.
- **Builds need the Android SDK** (present locally, absent in the remote sandbox). Unit tests are pure JVM and run without a device. The `bridge.js` save/restore path is **device-verified**, like the existing library snapshot (no JVM test for it).
- Windows shell: use `.\gradlew.bat` (PowerShell) for Gradle. `JAVA_HOME` is the Android Studio JBR.
- Commit message trailer (required): `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## Task 1: `SnapshotStore` — pure, testable chunk store

Extract the file-chunk read/append/commit/delete logic (currently inlined and hardcoded in `WebRController.Bridge`) into a standalone class that deals in **raw bytes + a `File`**. Base64 stays at the bridge boundary (Task 2), so this class is plain `java.io` and JVM-unit-testable. This task adds the class and its tests; nothing uses it yet (that's Task 2).

**Files:**
- Create: `app/src/main/java/com/rmobile/console/data/execution/SnapshotStore.kt`
- Test: `app/src/test/java/com/rmobile/console/data/execution/SnapshotStoreTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rmobile/console/data/execution/SnapshotStoreTest.kt`:

```kotlin
package com.rmobile.console.data.execution

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String = "snap.bin") = SnapshotStore(File(tmp.root, name))

    @Test fun `size is zero when nothing committed`() {
        assertEquals(0, store().size())
    }

    @Test fun `begin append commit writes the full payload`() {
        val s = store()
        s.begin()
        s.append(byteArrayOf(1, 2, 3))
        s.append(byteArrayOf(4, 5))
        s.commit()
        assertEquals(5, s.size())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), s.read(0, 5))
    }

    @Test fun `read past end returns empty`() {
        val s = store()
        s.begin(); s.append(byteArrayOf(1, 2, 3)); s.commit()
        assertArrayEquals(ByteArray(0), s.read(3, 10))
        assertArrayEquals(ByteArray(0), s.read(99, 10))
    }

    @Test fun `read clamps length to available bytes`() {
        val s = store()
        s.begin(); s.append(byteArrayOf(1, 2, 3, 4, 5)); s.commit()
        assertArrayEquals(byteArrayOf(3, 4, 5), s.read(2, 100))
    }

    @Test fun `in-progress append does not change committed file until commit`() {
        val s = store()
        s.begin(); s.append("old".toByteArray()); s.commit()
        assertEquals(3, s.size())
        // A fresh begin/append cycle must not clobber the committed file mid-write.
        s.begin()
        s.append("newer-payload".toByteArray())
        assertEquals(3, s.size()) // still the old committed size
        s.commit()
        assertEquals("newer-payload".length, s.size())
    }

    @Test fun `delete removes committed file and any dangling tmp`() {
        val s = store()
        s.begin(); s.append(byteArrayOf(1)); s.commit()
        assertTrue(s.size() > 0)
        s.begin(); s.append(byteArrayOf(2, 3)) // leave a dangling tmp, no commit
        s.delete()
        assertEquals(0, s.size())
        assertFalse(File(tmp.root, "snap.bin").exists())
        assertFalse(File(tmp.root, "snap.bin.tmp").exists())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.SnapshotStoreTest"`
Expected: FAIL — compilation error, `Unresolved reference: SnapshotStore`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/rmobile/console/data/execution/SnapshotStore.kt`:

```kotlin
package com.rmobile.console.data.execution

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A byte-oriented, single-file chunk store for streaming a snapshot between Kotlin
 * and the WebR VFS across the string-only JS bridge. Writes go to a sibling `.tmp`
 * and are atomically promoted on [commit], so a partial/failed write never clobbers
 * the last good snapshot. Pure `java.io` (no Android types) so it is JVM-unit-testable;
 * base64 encoding stays at the bridge boundary in WebRController.
 */
class SnapshotStore(private val file: File) {
    private val tmp = File(file.parentFile, file.name + ".tmp")

    /** Committed size in bytes (0 if nothing committed). Int to match the JS bridge contract. */
    fun size(): Int = if (file.exists()) file.length().toInt() else 0

    /** Bytes [offset, offset+length), clamped to end; empty if offset is at/after end. */
    fun read(offset: Int, length: Int): ByteArray {
        val total = size()
        if (offset >= total) return ByteArray(0)
        val end = minOf(offset + length, total)
        val buf = ByteArray(end - offset)
        RandomAccessFile(file, "r").use { it.seek(offset.toLong()); it.readFully(buf) }
        return buf
    }

    /** Start a fresh write, discarding any prior in-progress tmp. */
    fun begin() {
        tmp.delete()
        tmp.parentFile?.mkdirs()
        tmp.createNewFile()
    }

    fun append(bytes: ByteArray) {
        FileOutputStream(tmp, true).use { it.write(bytes) }
    }

    /** Atomically promote the tmp to the committed file. */
    fun commit() {
        if (tmp.exists()) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Remove the committed file and any dangling tmp. */
    fun delete() {
        file.delete()
        tmp.delete()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.rmobile.console.data.execution.SnapshotStoreTest"`
Expected: PASS — 6 tests pass, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/SnapshotStore.kt app/src/test/java/com/rmobile/console/data/execution/SnapshotStoreTest.kt
git commit -m "$(cat <<'EOF'
feat: SnapshotStore — pure, testable chunk store for WebR snapshots

Extracts the file-chunk read/append/commit/delete transport (currently
inlined in WebRController.Bridge) into a JVM-unit-tested class. Nothing
uses it yet; WebRController adopts it next.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Route the library snapshot through `SnapshotStore`, keyed by `kind`

Pure refactor, no behavior change: make `WebRController.Bridge` delegate to two `SnapshotStore`s (`library`, `workspace`) selected by a new `kind` argument, add `snapshotDelete`, and update `bridge.js` to (a) pass `'library'` on every existing snapshot call and (b) share the streaming loops via `streamOut`/`streamIn` helpers. **Both sides change together** so the running app stays consistent after this one commit.

**Files:**
- Modify: `app/src/main/java/com/rmobile/console/data/execution/WebRController.kt` (the `snapshotFile`/`snapshotTmp` getters at lines 77–82 and the `Bridge` snapshot methods at lines 98–122)
- Modify: `app/src/main/assets/webr/bridge.js` (`snapshotLibrary` lines 48–62, `restoreLibrary` lines 64–93)

- [ ] **Step 1: Replace the hardcoded snapshot file getters with two stores**

In `WebRController.kt`, replace this block (currently lines 77–82):

```kotlin
    // Persist the on-device package library across app restarts as a gzip tarball
    // under filesDir. Transferred in base64 chunks (the bridge marshals strings),
    // so a large library doesn't hit a single-call size limit. This deliberately
    // avoids WebR's IDBFS FS.mount, which destabilised the eval channel.
    private val snapshotFile get() = java.io.File(appContext.filesDir, "webr-library.tar.gz")
    private val snapshotTmp get() = java.io.File(appContext.filesDir, "webr-library.tar.gz.tmp")
```

with:

```kotlin
    // Persisted snapshots under filesDir, streamed to/from the WebR VFS in base64
    // chunks (the bridge marshals strings), so a large payload doesn't hit a
    // single-call size limit. Two kinds: the package library tarball and the
    // workspace (save.image) blob. This deliberately avoids WebR's IDBFS FS.mount,
    // which destabilised the eval channel.
    private val libraryStore = SnapshotStore(java.io.File(appContext.filesDir, "webr-library.tar.gz"))
    private val workspaceStore = SnapshotStore(java.io.File(appContext.filesDir, "webr-workspace.RData"))
    private fun snapshotStore(kind: String) = if (kind == "workspace") workspaceStore else libraryStore
```

- [ ] **Step 2: Replace the inlined `Bridge` snapshot methods with `kind`-keyed delegators**

In `WebRController.kt`, replace this block (currently lines 98–122):

```kotlin
        @JavascriptInterface fun snapshotSize(): Int =
            if (snapshotFile.exists()) snapshotFile.length().toInt() else 0

        @JavascriptInterface fun snapshotRead(offset: Int, length: Int): String = try {
            val f = snapshotFile
            val total = if (f.exists()) f.length().toInt() else 0
            if (offset >= total) "" else {
                val buf = ByteArray(minOf(offset + length, total) - offset)
                java.io.RandomAccessFile(f, "r").use { it.seek(offset.toLong()); it.readFully(buf) }
                android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) { "" }

        @JavascriptInterface fun snapshotBegin() {
            try { snapshotTmp.delete(); snapshotTmp.parentFile?.mkdirs(); snapshotTmp.createNewFile() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotAppend(b64: String) {
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                java.io.FileOutputStream(snapshotTmp, true).use { it.write(bytes) }
            } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotCommit() {
            try { if (snapshotTmp.exists()) { snapshotFile.delete(); snapshotTmp.renameTo(snapshotFile) } } catch (e: Exception) {}
        }
```

with (all methods now take `kind`; base64 is applied here, at the boundary):

```kotlin
        @JavascriptInterface fun snapshotSize(kind: String): Int =
            try { snapshotStore(kind).size() } catch (e: Exception) { 0 }

        @JavascriptInterface fun snapshotRead(kind: String, offset: Int, length: Int): String = try {
            val buf = snapshotStore(kind).read(offset, length)
            if (buf.isEmpty()) "" else android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }

        @JavascriptInterface fun snapshotBegin(kind: String) {
            try { snapshotStore(kind).begin() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotAppend(kind: String, b64: String) {
            try { snapshotStore(kind).append(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)) } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotCommit(kind: String) {
            try { snapshotStore(kind).commit() } catch (e: Exception) {}
        }
        @JavascriptInterface fun snapshotDelete(kind: String) {
            try { snapshotStore(kind).delete() } catch (e: Exception) {}
        }
```

- [ ] **Step 3: Add shared `streamOut`/`streamIn` helpers in `bridge.js`**

In `bridge.js`, immediately after the `b64ToBytes` function (currently ends at line 30, before the `ensureUserLib` comment at line 32), insert:

```javascript
// Stream a buffer of bytes to Kotlin under the given snapshot kind ('library' |
// 'workspace'), in base64 chunks. Split from streamOut so a caller that already
// holds the bytes (workspace, which size-checks first) needn't re-read the file.
function streamBytesOut(kind, bytes) {
  AndroidBridge.snapshotBegin(kind);
  for (let i = 0; i < bytes.length; i += SNAP_CHUNK) {
    AndroidBridge.snapshotAppend(kind, bytesToB64(bytes.subarray(i, i + SNAP_CHUNK)));
  }
  AndroidBridge.snapshotCommit(kind);
}

// Read a VFS file and stream its bytes to Kotlin. Returns the byte count.
async function streamOut(kind, vfsPath) {
  const bytes = await webR.FS.readFile(vfsPath);
  streamBytesOut(kind, bytes);
  return bytes.length;
}

// Pull a snapshot kind's bytes back from Kotlin into a VFS file.
// Returns the byte count written, or 0 if there is no stored snapshot.
async function streamIn(kind, vfsPath) {
  const size = AndroidBridge.snapshotSize(kind);
  if (!size) return 0;
  const parts = [];
  let total = 0;
  for (let off = 0; off < size; off += SNAP_CHUNK) {
    const b64 = AndroidBridge.snapshotRead(kind, off, SNAP_CHUNK);
    if (!b64) break;
    const part = b64ToBytes(b64);
    parts.push(part);
    total += part.length;
  }
  const all = new Uint8Array(total);
  let o = 0;
  for (const p of parts) { all.set(p, o); o += p.length; }
  await webR.FS.writeFile(vfsPath, all);
  return total;
}
```

- [ ] **Step 4: Rewrite `snapshotLibrary`/`restoreLibrary` to use the helpers**

In `bridge.js`, replace the whole `snapshotLibrary` function (currently lines 48–62):

```javascript
async function snapshotLibrary() {
  try {
    await webR.evalRVoid(
      `local({ owd <- getwd(); on.exit(setwd(owd)); setwd(${JSON.stringify(USER_LIB)}); ` +
      `utils::tar(${JSON.stringify(SNAP_TARBALL)}, ".", compression = "none") })`
    );
    const bytes = await webR.FS.readFile(SNAP_TARBALL);
    AndroidBridge.snapshotBegin();
    for (let i = 0; i < bytes.length; i += SNAP_CHUNK) {
      AndroidBridge.snapshotAppend(bytesToB64(bytes.subarray(i, i + SNAP_CHUNK)));
    }
    AndroidBridge.snapshotCommit();
    lastSnapshotInfo = `tar ${bytes.length}B → stored ${AndroidBridge.snapshotSize()}B`;
  } catch (e) { lastSnapshotInfo = 'snapshot failed: ' + String(e); }
}
```

with:

```javascript
async function snapshotLibrary() {
  try {
    await webR.evalRVoid(
      `local({ owd <- getwd(); on.exit(setwd(owd)); setwd(${JSON.stringify(USER_LIB)}); ` +
      `utils::tar(${JSON.stringify(SNAP_TARBALL)}, ".", compression = "none") })`
    );
    const n = await streamOut('library', SNAP_TARBALL);
    lastSnapshotInfo = `tar ${n}B → stored ${AndroidBridge.snapshotSize('library')}B`;
  } catch (e) { lastSnapshotInfo = 'snapshot failed: ' + String(e); }
}
```

Then replace the whole `restoreLibrary` function (currently lines 64–93):

```javascript
async function restoreLibrary() {
  try {
    const size = AndroidBridge.snapshotSize();
    if (!size) { lastRestoreInfo = 'no snapshot'; return; }
    const parts = [];
    let total = 0;
    for (let off = 0; off < size; off += SNAP_CHUNK) {
      const b64 = AndroidBridge.snapshotRead(off, SNAP_CHUNK);
      if (!b64) break;
      const part = b64ToBytes(b64);
      parts.push(part);
      total += part.length;
    }
    const all = new Uint8Array(total);
    let o = 0;
    for (const p of parts) { all.set(p, o); o += p.length; }
    await webR.FS.writeFile(SNAP_TARBALL, all);
    await webR.evalRVoid(
      `dir.create(${JSON.stringify(USER_LIB)}, showWarnings = FALSE, recursive = TRUE); ` +
      // tar="internal" is REQUIRED: the default untar shells out to the external
      // tar via system(), which is unsupported under Emscripten/WebR.
      `utils::untar(${JSON.stringify(SNAP_TARBALL)}, exdir = ${JSON.stringify(USER_LIB)}, tar = "internal"); ` +
      `.libPaths(c(${JSON.stringify(USER_LIB)}, .libPaths()))`
    );
    const nR = await webR.evalR(`length(list.files(${JSON.stringify(USER_LIB)}))`);
    lastRestoreInfo = `restored ${total}B → ${(await nR.toArray())[0]} entries in lib`;
    webR.destroy(nR);
    userLibReady = true;
  } catch (e) { lastRestoreInfo = 'restore failed: ' + String(e); }
}
```

with:

```javascript
async function restoreLibrary() {
  try {
    const total = await streamIn('library', SNAP_TARBALL);
    if (!total) { lastRestoreInfo = 'no snapshot'; return; }
    await webR.evalRVoid(
      `dir.create(${JSON.stringify(USER_LIB)}, showWarnings = FALSE, recursive = TRUE); ` +
      // tar="internal" is REQUIRED: the default untar shells out to the external
      // tar via system(), which is unsupported under Emscripten/WebR.
      `utils::untar(${JSON.stringify(SNAP_TARBALL)}, exdir = ${JSON.stringify(USER_LIB)}, tar = "internal"); ` +
      `.libPaths(c(${JSON.stringify(USER_LIB)}, .libPaths()))`
    );
    const nR = await webR.evalR(`length(list.files(${JSON.stringify(USER_LIB)}))`);
    lastRestoreInfo = `restored ${total}B → ${(await nR.toArray())[0]} entries in lib`;
    webR.destroy(nR);
    userLibReady = true;
  } catch (e) { lastRestoreInfo = 'restore failed: ' + String(e); }
}
```

- [ ] **Step 5: Build to verify both sides compile and the refactor is consistent**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (This proves the Kotlin signatures compile. The `bridge.js` library path is exercised on-device at boot/install; verified in Task 4's device check.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rmobile/console/data/execution/WebRController.kt app/src/main/assets/webr/bridge.js
git commit -m "$(cat <<'EOF'
refactor: key WebR snapshots by kind via SnapshotStore

WebRController.Bridge now delegates to two SnapshotStores (library,
workspace) selected by a `kind` arg, and adds snapshotDelete. bridge.js
passes 'library' and shares streamOut/streamIn/streamBytesOut helpers.
No behavior change — sets up workspace persistence next.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Persist and restore the workspace in `bridge.js`

Add `snapshotWorkspace()` (fire-and-forget after each successful run, size-guarded), `restoreWorkspace()` (at boot), a `pendingWorkspaceSnapshot` guard so a fast next run can't race the background save, and clear-on-reset. This is the behavioral change.

**Files:**
- Modify: `app/src/main/assets/webr/bridge.js` (constants near lines 13–16; `boot` lines 159–166; `resetRunDir`/`runOnce` lines 200–251; `webrReset` lines 271–274)

- [ ] **Step 1: Add workspace constants**

In `bridge.js`, after the existing snapshot constants (currently lines 13–16):

```javascript
const SNAP_TARBALL = '/rmobile/lib.tar';
const SNAP_CHUNK = 512 * 1024; // base64 transfer chunk size (bytes of raw data)
let lastSnapshotInfo = '';
let lastRestoreInfo = '';
```

insert:

```javascript
const WS_RDATA = '/rmobile/workspace.RData';
const WS_MAX_BYTES = 200 * 1024 * 1024; // don't persist a workspace blob larger than this
let pendingWorkspaceSnapshot = null;    // in-flight background save.image, awaited before the next run
let lastWorkspaceInfo = '';
```

- [ ] **Step 2: Add `snapshotWorkspace` and `restoreWorkspace`**

In `bridge.js`, immediately after the `restoreLibrary` function (which ends just before the `const DATA_CHUNK = ...` line, currently line 95), insert:

```javascript
// Persist the shared Local globalenv across restarts, mirroring the library
// snapshot. Best-effort: any failure leaves the in-memory workspace and the run
// result untouched. Called fire-and-forget after each successful run.
async function snapshotWorkspace() {
  try {
    const nR = await webR.evalR('length(ls(globalenv()))');
    const n = (await nR.toArray())[0];
    webR.destroy(nR);
    if (!n) {
      // An emptied workspace (rm() everything, or a reset) must clear the snapshot,
      // else boot would restore stale variables.
      AndroidBridge.snapshotDelete('workspace');
      lastWorkspaceInfo = 'empty → cleared';
      return;
    }
    // save.image throws if any object can't be serialized (e.g. an open connection);
    // caught below, which leaves the prior snapshot in place.
    await webR.evalRVoid(`save.image(${JSON.stringify(WS_RDATA)})`);
    const bytes = await webR.FS.readFile(WS_RDATA);
    if (bytes.length > WS_MAX_BYTES) {
      // Too big to persist; keep the prior (smaller) snapshot rather than clobber it.
      lastWorkspaceInfo = `too large (${bytes.length}B) → not persisted`;
      return;
    }
    streamBytesOut('workspace', bytes);
    lastWorkspaceInfo = `saved ${bytes.length}B`;
  } catch (e) { lastWorkspaceInfo = 'snapshot failed: ' + String(e); }
}

// Restore the persisted globalenv at boot. Best-effort — a failure boots to an
// empty workspace (the pre-persistence behavior).
async function restoreWorkspace() {
  try {
    const total = await streamIn('workspace', WS_RDATA);
    if (!total) { lastWorkspaceInfo = 'no workspace snapshot'; return; }
    await webR.evalRVoid(`load(${JSON.stringify(WS_RDATA)}, envir = globalenv())`);
    lastWorkspaceInfo = `restored ${total}B`;
  } catch (e) { lastWorkspaceInfo = 'restore failed: ' + String(e); }
}
```

- [ ] **Step 3: Restore the workspace at boot**

In `bridge.js`, replace the `boot` function (currently lines 159–166):

```javascript
async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  await restoreLibrary(); // plain R evals (untar + .libPaths) — no FS.mount
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));
```

with:

```javascript
async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  await restoreLibrary(); // plain R evals (untar + .libPaths) — no FS.mount
  await restoreWorkspace(); // load() the persisted globalenv — plain R eval, no FS.mount
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));
```

- [ ] **Step 4: Guard the run against an in-flight snapshot, and snapshot after success**

In `bridge.js`, in `runOnce`, replace the first two lines (currently lines 204–205):

```javascript
async function runOnce(req) {
  await resetRunDir();
```

with:

```javascript
async function runOnce(req) {
  // Never let a still-running background workspace snapshot overlap the next run
  // (both touch globalenv on the single WebR worker). Wait for it first.
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  await resetRunDir();
```

Then, in the same `runOnce`, replace the success-path return (currently lines 246–249):

```javascript
    const wsR = await webR.evalR('ls(globalenv())');
    const workspaceObjects = await wsR.toArray();
    webR.destroy(wsR);
    return { stdout, stderr, plots, tables, workspaceObjects, error: null, timedOut: false };
```

with:

```javascript
    const wsR = await webR.evalR('ls(globalenv())');
    const workspaceObjects = await wsR.toArray();
    webR.destroy(wsR);
    // Persist the (possibly mutated) workspace without blocking the result. The
    // guard at the top of runOnce awaits this before the next run starts.
    pendingWorkspaceSnapshot = snapshotWorkspace().finally(() => { pendingWorkspaceSnapshot = null; });
    return { stdout, stderr, plots, tables, workspaceObjects, error: null, timedOut: false };
```

(The early-return error path — `captureR` throwing — is left untouched, so a failed run does not snapshot.)

- [ ] **Step 5: Clear the snapshot on reset**

In `bridge.js`, replace the `webrReset` handler (currently lines 271–274):

```javascript
window.webrReset = async (id) => {
  try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); AndroidBridge.onResult(id, JSON.stringify({ ok: true })); }
  catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};
```

with:

```javascript
window.webrReset = async (id) => {
  try {
    await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())');
    AndroidBridge.snapshotDelete('workspace'); // a reset must not resurrect vars on next launch
    AndroidBridge.onResult(id, JSON.stringify({ ok: true }));
  } catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};
```

- [ ] **Step 6: Build to verify the app still assembles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (no Kotlin changed this task; this confirms nothing else broke and the asset is packaged).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/assets/webr/bridge.js
git commit -m "$(cat <<'EOF'
feat: persist the Local (WebR) workspace across app restarts

save.image the shared globalenv after each successful run (fire-and-forget,
size-guarded at 200 MB, best-effort), load() it back at boot, and clear it
on reset/empty. A pendingWorkspaceSnapshot guard serializes the background
save against the next run.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Docs + full verification

Update the two docs that assert the local workspace does *not* persist, run the full unit-test suite and lint, and device-verify the round-trip.

**Files:**
- Modify: `CLAUDE.md` (the "v1 boundaries" note, around lines 453–454, and the "Still **not** built" list further down)
- Modify: `app/src/main/assets/webr/README.md` (the library snapshot bullet, around lines 74–81)

- [ ] **Step 1: Update `CLAUDE.md` — the v1 boundaries note**

In `CLAUDE.md`, find this exact text (around lines 453–457):

```
`RTable`). **v1 boundaries (deliberate, not built yet)** — the local **workspace**
(variables) is still in-process (resets on app restart), and **engine choice is app-wide,
not per-project**. The Data/Packages screens adapt their caption to the active engine
(the Data screen also warns above 200 MB on Local, since its FS is in-memory). These are
documented fast-follows.
```

and replace it with (the workspace-persistence boundary is now built, so only the
per-project engine-choice boundary remains, plus a sentence describing the new behavior):

```
`RTable`). The Local **workspace** (the shared WebR `globalenv()`) now **persists across
app restarts** via a `save.image`→`filesDir`→`load()`-at-boot snapshot — the same
best-effort snapshot/restore path as the package library, streamed through the shared
`SnapshotStore` (keyed by `kind`: `library` | `workspace`). It's snapshotted after each
successful run (fire-and-forget, guarded so it can't race the next run, skipped above a
200 MB blob) and cleared on reset. **v1 boundary that remains (deliberate, not built
yet)** — **engine choice is app-wide, not per-project**. The Data/Packages screens adapt
their caption to the active engine (the Data screen also warns above 200 MB on Local,
since its FS is in-memory).
```

- [ ] **Step 2: Update `CLAUDE.md` — the "Still not built" list**

In `CLAUDE.md`, find this text:

```
Still **not** built — don't assume these exist: cross-restart persistence of the
local *workspace* (variables), per-project engine choice, and (backend)
network-egress restriction in the dev profile or per-request VM isolation.
```

and change it to (drop the workspace item, which is now built):

```
Still **not** built — don't assume these exist: per-project engine choice, and
(backend) network-egress restriction in the dev profile or per-request VM isolation.
```

- [ ] **Step 3: Update the WebR README**

In `app/src/main/assets/webr/README.md`, find the library-persistence bullet (around lines 74–81, ending "…destabilised the eval channel.") and append a new paragraph right after that bullet:

```
- The Local **workspace** (the shared WebR `globalenv()`) persists the same way:
  `save.image` to `/rmobile/workspace.RData`, streamed to `filesDir` in base64 chunks
  and `load()`ed back into `globalenv()` at boot. It's snapshotted after each successful
  run (fire-and-forget, guarded so it can't race the next run) and skipped above a 200 MB
  blob; a `webrReset` or an emptied workspace clears it. Kotlin's `SnapshotStore` (keyed by
  `kind`: `library` | `workspace`) is the shared transport for both.
```

- [ ] **Step 4: Run the full unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (including `SnapshotStoreTest`).

- [ ] **Step 5: Run Lint**

Run: `.\gradlew.bat :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, no new errors.

- [ ] **Step 6: Device verification (manual, on a connected device/emulator)**

Run: `.\gradlew.bat :app:installDebug`
Then, with the Local engine selected in Settings:
  1. Run `x <- 42; y <- data.frame(a = 1:3)`. Confirm `x`, `y` appear in the workspace strip.
  2. Fully kill and relaunch the app.
  3. Run `ls()` (or `print(x)`). Expected: `x` and `y` are still present — the workspace restored.
  4. Tap reset, kill/relaunch, run `ls()`. Expected: empty — the snapshot was cleared.
Expected: all four hold. (This path has no JVM test; it is WebR/WebView-bound, like the library snapshot.)

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md app/src/main/assets/webr/README.md
git commit -m "$(cat <<'EOF'
docs: Local workspace now persists across restarts

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Notes for the executor

- **Line numbers** in "Files" headers are from the current checkout and drift as you edit; match on the quoted code, not the number.
- Keep each task's commit runtime-consistent: Task 2 changes the bridge signatures on **both** sides in one commit for exactly this reason — don't split it.
- If `file.symlink`/`save.image`/`load` behave unexpectedly on-device, that's a WebR-build issue to diagnose on the device, not in JVM tests. The existing library snapshot is the reference implementation that already works on this build.
- Do **not** run `/code-review ultra` (billed, user-triggered only).
