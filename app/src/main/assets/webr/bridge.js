import { WebR } from './dist/webr.mjs';

// baseUrl MUST be absolute: WebR's web worker resolves it relative to its OWN
// location (already inside dist/), so a relative './dist/' doubles to dist/dist/
// and R.bin.js fails to load. Derive the absolute URL from the page.
const baseUrl = new URL('./dist/', document.baseURI).href;
const webR = new WebR({ baseUrl });
let ready = false;
const LOCAL_REPO_URL = new URL('./repo', document.baseURI).href;
const USER_LIB_ROOT = '/rmobile/library';           // per-session libs live at <root>/<session>
const SHARED_LIBRARY_KEY = 'shared';                 // kept in sync with Kotlin ProjectSession.SHARED_LIBRARY_KEY
const SNAP_TARBALL = '/rmobile/lib.tar';             // scratch path for a lib tar
const SNAP_CHUNK = 512 * 1024;
const WS_RDATA = '/rmobile/workspace.RData';         // scratch path for a workspace blob
const WS_MAX_BYTES = 200 * 1024 * 1024;
const MAX_RESIDENT_LIBS = 3;                         // LRU cap on lib dirs kept in the VFS

let currentSession = null;                           // the session whose state is live
let currentLibraryKey = null;                        // which library dir .libPaths currently points at
let baseLibPaths = null;                             // pristine .libPaths() captured at boot
let pendingWorkspaceSnapshot = null;                 // in-flight background save.image
let runGeneration = 0;                               // bumped per run so a timed-out zombie run won't snapshot late
const residentLibs = [];                             // session ids with a restored lib dir (LRU order)
let lastSwapWarning = '';                            // surfaced into the next run's stderr
let lastWorkspaceInfo = '';
let lastSnapshotInfo = '';
let lastRestoreInfo = '';

function bytesToB64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(s);
}
function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

// Stream a buffer of bytes to Kotlin under the given snapshot kind ('library' |
// 'workspace') and sessionId, in base64 chunks. Split from streamOut so a caller
// that already holds the bytes (workspace, which size-checks first) needn't re-read.
function streamBytesOut(kind, sessionId, bytes) {
  AndroidBridge.snapshotBegin(kind, sessionId);
  for (let i = 0; i < bytes.length; i += SNAP_CHUNK) {
    AndroidBridge.snapshotAppend(kind, sessionId, bytesToB64(bytes.subarray(i, i + SNAP_CHUNK)));
  }
  AndroidBridge.snapshotCommit(kind, sessionId);
}

// Read a VFS file and stream its bytes to Kotlin. Returns the byte count.
async function streamOut(kind, sessionId, vfsPath) {
  const bytes = await webR.FS.readFile(vfsPath);
  streamBytesOut(kind, sessionId, bytes);
  return bytes.length;
}

// Pull a snapshot (kind, sessionId)'s bytes back from Kotlin into a VFS file.
// Returns the byte count written, or 0 if there is no stored snapshot.
async function streamIn(kind, sessionId, vfsPath) {
  const size = AndroidBridge.snapshotSize(kind, sessionId);
  if (!size) return 0;
  const parts = [];
  let total = 0;
  for (let off = 0; off < size; off += SNAP_CHUNK) {
    const b64 = AndroidBridge.snapshotRead(kind, sessionId, off, SNAP_CHUNK);
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

function libDir(sessionId) { return `${USER_LIB_ROOT}/${sessionId}`; }

// Point .libPaths() at ONLY this session's lib dir + the pristine base. This RESETS
// (not unions) each swap, so a previously-visited project's library can't remain
// searchable — that would defeat per-project package isolation. Called by ensureSession.
async function ensureUserLib(sessionId) {
  const dir = libDir(sessionId);
  const paths = [dir, ...(baseLibPaths || [])].map((p) => JSON.stringify(p)).join(', ');
  await webR.evalRVoid(
    `dir.create(${JSON.stringify(dir)}, showWarnings = FALSE, recursive = TRUE); ` +
    `.libPaths(c(${paths}))`
  );
}

// Track a session's lib as most-recently-used (LRU order in residentLibs).
function touchResident(sessionId) {
  const i = residentLibs.indexOf(sessionId);
  if (i >= 0) residentLibs.splice(i, 1);
  residentLibs.push(sessionId);
}

// Restore a session's lib dir from its Kotlin tarball snapshot into the VFS if this
// process hasn't already. Cross-restart persistence WITHOUT WebR's IDBFS FS.mount
// (which broke the eval channel). Idempotent; tracks LRU residency. Best-effort.
async function ensureLibResident(sessionId) {
  if (residentLibs.includes(sessionId)) { touchResident(sessionId); return; }
  const dir = libDir(sessionId);
  try {
    const total = await streamIn('library', sessionId, SNAP_TARBALL);
    await webR.evalRVoid(`dir.create(${JSON.stringify(dir)}, showWarnings = FALSE, recursive = TRUE)`);
    if (total) {
      // tar="internal" is REQUIRED: the default untar shells out to the external
      // tar via system(), which is unsupported under Emscripten/WebR.
      await webR.evalRVoid(`utils::untar(${JSON.stringify(SNAP_TARBALL)}, exdir = ${JSON.stringify(dir)}, tar = "internal")`);
      try { await webR.evalRVoid(`unlink(${JSON.stringify(SNAP_TARBALL)})`); } catch (e) {}
    }
    lastRestoreInfo = `lib ${sessionId}: restored ${total}B`;
  } catch (e) { lastRestoreInfo = `lib ${sessionId}: restore failed: ` + String(e); }
  touchResident(sessionId); // adds it (absent) as most-recently-used
}

// Bound in-process VFS growth: keep at most MAX_RESIDENT_LIBS session lib dirs
// restored. Evict the least-recently-used (never the current). An evicted dir
// re-restores from its Kotlin tarball on next visit. Best-effort.
async function evictLibsIfOverCap(keepSessionId) {
  while (residentLibs.length > MAX_RESIDENT_LIBS) {
    const victim = residentLibs.find((s) => s !== keepSessionId);
    if (!victim) break;
    const i = residentLibs.indexOf(victim);
    residentLibs.splice(i, 1);
    const dir = libDir(victim);
    try {
      // Unmount any freshly-installed (mounted) package images before unlinking.
      const pkgsR = await webR.evalR(`if (dir.exists(${JSON.stringify(dir)})) list.files(${JSON.stringify(dir)}) else character(0)`);
      const pkgs = await pkgsR.toArray(); webR.destroy(pkgsR);
      for (const p of pkgs) { try { await webR.FS.unmount(`${dir}/${p}`); } catch (e) {} }
      await webR.evalRVoid(`unlink(${JSON.stringify(dir)}, recursive = TRUE, force = TRUE)`);
    } catch (e) { /* leave it resident on failure — memory, not correctness */ }
  }
}

// Tar the current session's lib and hand the bytes to Kotlin under its snapshot.
async function snapshotLibrary(sessionId) {
  try {
    const dir = libDir(sessionId);
    await webR.evalRVoid(
      `local({ owd <- getwd(); on.exit(setwd(owd)); setwd(${JSON.stringify(dir)}); ` +
      `utils::tar(${JSON.stringify(SNAP_TARBALL)}, ".", compression = "none") })`
    );
    const n = await streamOut('library', sessionId, SNAP_TARBALL);
    lastSnapshotInfo = `lib ${sessionId}: tar ${n}B`;
    try { await webR.evalRVoid(`unlink(${JSON.stringify(SNAP_TARBALL)})`); } catch (e) {}
  } catch (e) { lastSnapshotInfo = `lib ${sessionId}: snapshot failed: ` + String(e); }
}

// Persist a session's globalenv, mirroring the library snapshot. Best-effort: any
// failure leaves the in-memory workspace and the run result untouched. Called
// fire-and-forget after each successful run and when swapping a session out.
async function snapshotWorkspace(sessionId) {
  try {
    const nR = await webR.evalR('length(ls(globalenv()))');
    const n = (await nR.toArray())[0];
    webR.destroy(nR);
    if (!n) {
      // An emptied workspace (rm() everything, or a reset) must clear the snapshot,
      // else boot/restore would bring back stale variables.
      AndroidBridge.snapshotDelete('workspace', sessionId);
      lastWorkspaceInfo = `${sessionId}: empty → cleared`;
      return;
    }
    // save.image throws if any object can't be serialized (e.g. an open connection);
    // caught below, which leaves the prior snapshot in place.
    await webR.evalRVoid(`save.image(${JSON.stringify(WS_RDATA)})`);
    const bytes = await webR.FS.readFile(WS_RDATA);
    if (bytes.length > WS_MAX_BYTES) {
      // Too big to persist; keep the prior (smaller) snapshot rather than clobber it.
      lastWorkspaceInfo = `${sessionId}: too large (${bytes.length}B) → not persisted`;
      return;
    }
    streamBytesOut('workspace', sessionId, bytes);
    lastWorkspaceInfo = `${sessionId}: saved ${bytes.length}B`;
    try { await webR.evalRVoid(`unlink(${JSON.stringify(WS_RDATA)})`); } catch (e) {}
  } catch (e) { lastWorkspaceInfo = `${sessionId}: snapshot failed: ` + String(e); }
}

// Restore a session's persisted globalenv. Returns true on a clean restore (or a
// genuinely empty session), false if a restore was attempted but threw — the caller
// records a swap warning in that case. Best-effort: a failure leaves an empty env.
async function restoreWorkspace(sessionId) {
  try {
    const total = await streamIn('workspace', sessionId, WS_RDATA);
    if (!total) { lastWorkspaceInfo = `${sessionId}: no workspace snapshot`; return true; }
    await webR.evalRVoid(`load(${JSON.stringify(WS_RDATA)}, envir = globalenv())`);
    try { await webR.evalRVoid(`unlink(${JSON.stringify(WS_RDATA)})`); } catch (e) {}
    lastWorkspaceInfo = `${sessionId}: restored ${total}B`;
    return true;
  } catch (e) { lastWorkspaceInfo = `${sessionId}: restore failed: ` + String(e); return false; }
}

// Reset loaded/attached packages to the base set captured at boot. R namespaces load
// into the single shared WebR process, so a package library()'d in one project would
// otherwise stay loaded after switching projects. Detach non-base attached packages
// (dependents first, retrying), then unload any remaining non-base namespaces. All
// best-effort — a package that refuses to unload is left loaded rather than erroring.
async function resetPackagesToBase() {
  try {
    await webR.evalRVoid(
      'local({\n' +
      '  keepNs <- getOption("rmobile.base.ns", character(0));\n' +
      '  keepAt <- getOption("rmobile.base.attached", search());\n' +
      '  repeat {\n' +
      '    pkgs <- grep("^package:", setdiff(search(), keepAt), value = TRUE);\n' +
      '    if (!length(pkgs)) break;\n' +
      '    prog <- FALSE;\n' +
      '    for (p in pkgs) { if (tryCatch({ detach(p, character.only = TRUE, unload = TRUE); TRUE }, error = function(e) FALSE)) { prog <- TRUE; break } };\n' +
      '    if (!prog) break;\n' +
      '  };\n' +
      '  for (i in 1:5) { extra <- setdiff(loadedNamespaces(), keepNs); if (!length(extra)) break; for (ns in extra) tryCatch(unloadNamespace(ns), error = function(e) NULL) };\n' +
      '})'
    );
  } catch (e) { /* best-effort — never blocks a swap */ }
}

// Make sessionId the live session, pointing the library at libraryKey (which is the
// session id for an isolated project, or "shared" for an opted-in one). Workspace/data
// swap on the session id; the library dir re-points on the library key. A no-op when
// both already match. Best-effort throughout; a failed restore records lastSwapWarning
// (surfaced into the run's stderr) but never throws.
async function ensureSession(sessionId, libraryKey) {
  const lib = libraryKey || sessionId;
  if (sessionId === currentSession && lib === currentLibraryKey) return;
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  const sessionChanged = sessionId !== currentSession;
  try {
    if (sessionChanged) {
      if (currentSession != null) {
        AndroidBridge.onSwapProgress('SAVING_WORKSPACE');
        await snapshotWorkspace(currentSession);
        try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
        await resetPackagesToBase(); // don't let the outgoing project's library()'d packages leak
      }
      AndroidBridge.onSwapProgress('LOADING_WORKSPACE');
      const ok = await restoreWorkspace(sessionId);
      if (!ok) { lastSwapWarning = "Couldn't load this project's saved workspace; starting empty."; AndroidBridge.onSwapProgress('RESTORE_FAILED'); }
      else { lastSwapWarning = ''; }
    } else if (lib !== currentLibraryKey) {
      // Same project, library pointer changed (an opt-in toggle). Unload packages loaded
      // from the old library so they don't linger against the new one.
      await resetPackagesToBase();
    }
    AndroidBridge.onSwapProgress('RESTORING_LIBRARY');
    await ensureLibResident(lib);
    await ensureUserLib(lib);
    currentSession = sessionId;
    currentLibraryKey = lib;
    await evictLibsIfOverCap(lib);
  } finally {
    AndroidBridge.onSwapProgress('IDLE');
  }
}

const DATA_CHUNK = 512 * 1024;

// Lazily mirror the session's on-device data files into /rmobile/data/<session>
// (persists for the app process), pulling only new/changed files and dropping
// deleted ones. Chunked base64 transfer, like the library snapshot.
// Returns the names of files that couldn't be synced (too large for the in-memory
// VFS), so the caller can tell the user why a bare-name read failed.
async function syncData(sessionId) {
  const skipped = [];
  const dir = `/rmobile/data/${sessionId}`;
  await webR.evalRVoid(`dir.create(${JSON.stringify(dir)}, showWarnings = FALSE, recursive = TRUE)`);
  let wanted;
  try { wanted = JSON.parse(AndroidBridge.dataList(sessionId)); } catch (e) { return skipped; }
  const wantedNames = new Set(wanted.map((f) => f.name));
  const haveR = await webR.evalR(`list.files(${JSON.stringify(dir)})`);
  const have = await haveR.toArray(); webR.destroy(haveR);
  for (const name of have) if (!wantedNames.has(name)) {
    await webR.evalRVoid(`unlink(file.path(${JSON.stringify(dir)}, ${JSON.stringify(name)}))`);
  }
  for (const f of wanted) {
    const path = `${dir}/${f.name}`;
    let size = -1;
    try {
      const sz = await webR.evalR(`if (file.exists(${JSON.stringify(path)})) file.info(${JSON.stringify(path)})$size else -1`);
      size = (await sz.toArray())[0]; webR.destroy(sz);
    } catch (e) {}
    if (size === f.size) continue;
    // Stream chunks into ONE pre-allocated buffer (not an array of chunks + a second
    // joined buffer), so peak memory is ~fileSize, not ~2x. The whole file still has
    // to fit in the in-memory VFS, so a too-large file throws (RangeError on the
    // allocation, or an OOM on write). Catch it: skip that one file so other files
    // still sync and ordinary runs keep working — it just isn't readable on-device
    // (the Remote engine handles large data). Without this, one big file would break
    // every run/preview in the session.
    try {
      const all = new Uint8Array(f.size);
      let o = 0;
      for (let off = 0; off < f.size; off += DATA_CHUNK) {
        const b64 = AndroidBridge.dataChunk(sessionId, f.name, off, DATA_CHUNK);
        if (!b64) break;
        const chunk = b64ToBytes(b64);
        all.set(chunk, o);
        o += chunk.length;
      }
      await webR.FS.writeFile(path, all);
    } catch (e) {
      try { await webR.evalRVoid(`unlink(${JSON.stringify(path)})`); } catch (e2) {}
      skipped.push(f.name);
    }
  }
  return skipped;
}

// Make the session's data files readable by bare name from the run cwd. Called
// AFTER the run's own files are written, and file.symlink fails (silently) if the
// link name already exists, so a same-named run file shadows a data file.
async function linkData(sessionId) {
  const dir = `/rmobile/data/${sessionId}`;
  await webR.evalRVoid(
    `local({ d <- ${JSON.stringify(dir)}; if (dir.exists(d)) for (f in list.files(d, full.names = TRUE)) ` +
    `try(file.symlink(f, file.path("/rmobile/run", basename(f))), silent = TRUE) })`
  );
}

async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  // Capture the pristine library paths BEFORE any session dir is added, so
  // ensureUserLib can reset .libPaths to exactly [session dir, ...base] each swap.
  const bp = await webR.evalR('.libPaths()');
  baseLibPaths = await bp.toArray();
  webR.destroy(bp);
  // Capture the base loaded/attached packages so a project swap can unload anything
  // a project library()'d — otherwise a loaded namespace stays live and leaks across
  // projects. Stored in options() (survives the globalenv clear on each swap).
  await webR.evalRVoid('options(rmobile.base.ns = loadedNamespaces(), rmobile.base.attached = search())');
  // No eager restore: per-session workspace/library are restored lazily by
  // ensureSession on the first session-scoped op (the app always runs in a project).
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));

async function bitmapToPng(image) {
  // captureGraphics yields ImageBitmap; re-encode as PNG data (base64, no prefix).
  const canvas = new OffscreenCanvas(image.width, image.height);
  const ctx = canvas.getContext('2d');
  ctx.drawImage(image, 0, 0);
  const blob = await canvas.convertToBlob({ type: 'image/png' });
  const buf = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let i = 0; i < buf.length; i++) binary += String.fromCharCode(buf[i]);
  return btoa(binary);
}

async function readTables() {
  const tables = [];
  for (let i = 1; ; i++) {
    const name = `/rmobile/run/table${String(i).padStart(3, '0')}.json`;
    let bytes;
    try { bytes = await webR.FS.readFile(name); } catch { break; }
    tables.push(JSON.parse(new TextDecoder().decode(bytes)));
  }
  return tables;
}

// Prepend a clear explanation when a run couldn't load a too-large data file
// (skipped by syncData), so R's cryptic "cannot open the connection" is legible.
function withSkippedNote(stderr, skipped) {
  if (!skipped || !skipped.length) return stderr;
  const s = skipped.length > 1;
  const note = `Note: ${skipped.join(', ')} ${s ? 'are' : 'is'} too large for the on-device (Local) engine and ${s ? 'were' : 'was'} not loaded. Switch to the Remote engine in Settings to read ${s ? 'them' : 'it'} in code.`;
  return stderr ? `${note}\n${stderr}` : note;
}

async function resetRunDir() {
  await webR.evalRVoid('unlink("/rmobile/run", recursive = TRUE); dir.create("/rmobile/run"); setwd("/rmobile/run")');
}

async function runOnce(req, libraryKey) {
  // Tag this run. A run that "timed out" (its Promise.race branch lost) is never
  // cancelled and may still finish later; the tag lets it detect it is no longer the
  // current run and skip snapshotting.
  const myGen = ++runGeneration;
  // Never let a still-running background workspace snapshot overlap the next run
  // (both touch globalenv on the single WebR worker). Wait for it first.
  if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
  // Make this project's session live (swaps workspace + library if it changed).
  const sessionId = req.sessionId || 'default';
  await ensureSession(sessionId, libraryKey || sessionId);
  await resetRunDir();
  let skippedData = [];
  if (req.sessionId) { try { skippedData = await syncData(req.sessionId); } catch (e) {} }
  const files = req.files && req.files.length ? req.files
    : [{ name: 'script.R', content: req.code || '' }];
  const entry = req.entryFile || files[0].name;
  for (const f of files) {
    await webR.FS.writeFile(`/rmobile/run/${f.name}`, new TextEncoder().encode(f.content));
  }
  if (req.sessionId) { try { await linkData(req.sessionId); } catch (e) {} }
  // Load the harness template and inject the entry file path. Normalise line
  // endings: a CRLF checkout (Windows autocrlf) would otherwise leave a stray \r
  // after `local({`, which WebR's R parser rejects as an "unexpected invalid
  // token", breaking every run. Strip it here so the harness parses regardless.
  const harnessBytes = await (await fetch('./harness.R')).arrayBuffer();
  const harness = new TextDecoder().decode(harnessBytes).replace(/\r\n?/g, '\n');
  await webR.objs.globalEnv.bind('.RMOBILE_ENTRY', entry);

  const shelter = await new webR.Shelter();
  try {
    let cap;
    try {
      cap = await shelter.captureR(harness, {
        withAutoprint: false,
        captureStreams: true,
        captureGraphics: { width: 800, height: 600 },
        env: await webR.objs.globalEnv,
      });
    } catch (e) {
      // harness.R runs user code without tryCatch, so a top-level R error (e.g.
      // read.csv on a missing/too-large file) propagates and captureR throws.
      // Surface it as stderr WITH the too-large-file note, instead of letting it
      // escape to webrRun as a bare exception that hides the note.
      let msg = withSkippedNote(String((e && e.message) || e), skippedData);
      if (lastSwapWarning) { msg = `${lastSwapWarning}\n${msg}`; lastSwapWarning = ''; }
      return { stdout: '', stderr: msg, plots: [], tables: [], workspaceObjects: null, error: null, timedOut: false };
    }
    const stdout = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
    let stderr = withSkippedNote(cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n'), skippedData);
    if (lastSwapWarning) { stderr = stderr ? `${lastSwapWarning}\n${stderr}` : lastSwapWarning; lastSwapWarning = ''; }
    const plots = [];
    for (const img of cap.images || []) plots.push(await bitmapToPng(img));
    const tables = await readTables();
    const wsR = await webR.evalR('ls(globalenv())');
    const workspaceObjects = await wsR.toArray();
    webR.destroy(wsR);
    // Persist the (possibly mutated) workspace without blocking the result — but only
    // if this is still the current run (a timed-out zombie must not schedule a save).
    // The guard at the top of the next runOnce awaits this before proceeding. The
    // identity check stops a late finally from nulling a newer run's pending promise.
    if (myGen === runGeneration) {
      const snap = snapshotWorkspace(sessionId);
      pendingWorkspaceSnapshot = snap;
      snap.finally(() => { if (pendingWorkspaceSnapshot === snap) pendingWorkspaceSnapshot = null; });
    }
    return { stdout, stderr, plots, tables, workspaceObjects, error: null, timedOut: false };
  } finally { shelter.purge(); }
}

window.webrRun = async (id, requestJson, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ error: 'WebR not ready', timedOut: false })); return; }
  const req = JSON.parse(requestJson);
  const timeoutMs = (req.timeoutSeconds || 20) * 1000;
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(async () => { try { await webR.interrupt(); } catch {} resolve({ stdout: '', stderr: '', plots: [], tables: [], workspaceObjects: null, error: `Execution timed out after ${req.timeoutSeconds || 20}s.`, timedOut: true }); }, timeoutMs);
  });
  try {
    const result = await Promise.race([runOnce(req, libraryKey), timeout]);
    clearTimeout(timer);
    AndroidBridge.onResult(id, JSON.stringify(result));
  } catch (e) {
    clearTimeout(timer);
    AndroidBridge.onResult(id, JSON.stringify({ stdout: '', stderr: String(e), plots: [], tables: [], workspaceObjects: null, error: String(e), timedOut: false }));
  }
};

window.webrReset = async (id, sessionId, purge, libraryKey) => {
  const lib = libraryKey || sessionId;
  try {
    // Wait out any in-flight background snapshot first: otherwise its already-read,
    // pre-reset bytes get streamed to disk AFTER our snapshotDelete, resurrecting
    // exactly what the reset is clearing.
    if (pendingWorkspaceSnapshot) { try { await pendingWorkspaceSnapshot; } catch (e) {} }
    // Clear the live globalenv (and unload its packages) only if this is the live session.
    if (sessionId === currentSession) {
      try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); } catch (e) {}
      await resetPackagesToBase();
    }
    AndroidBridge.snapshotDelete('workspace', sessionId); // must not resurrect vars on next launch
    // Never purge a shared library on a single project's reset/delete — other projects use it.
    if (purge && lib !== SHARED_LIBRARY_KEY) {
      AndroidBridge.snapshotDelete('library', lib);
      const libd = libDir(lib);
      try { await webR.evalRVoid(`if (dir.exists(${JSON.stringify(libd)})) unlink(${JSON.stringify(libd)}, recursive = TRUE, force = TRUE)`); } catch (e) {}
      const i = residentLibs.indexOf(lib);
      if (i >= 0) residentLibs.splice(i, 1);
      if (lib === currentLibraryKey) currentLibraryKey = null;
    }
    // Drop the live-session pointer on any purge (project delete), incl. a shared-library
    // project whose library we intentionally kept — so the next op re-inits cleanly.
    if (purge && sessionId === currentSession) currentSession = null;
    AndroidBridge.onResult(id, JSON.stringify({ ok: true }));
  } catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};

// Package management (device-verified). webr::install resolves against the
// bundled local repo first, then falls back to the online r-wasm CRAN mirror,
// installing into the active library (libDir(lib), where lib is the project's
// library key — its own session, or "shared" when it opted into a shared library).
window.webrInstall = async (id, pkg, sessionId, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ installed: false, error: 'WebR not ready', stdout: '', stderr: '', timedOut: false, systemRequirements: null })); return; }
  const lib = libraryKey || sessionId;
  await ensureSession(sessionId, lib);
  const shelter = await new webR.Shelter();
  try {
    const cap = await shelter.captureR(
      `webr::install(${JSON.stringify(pkg)}, repos = c(${JSON.stringify(LOCAL_REPO_URL)}, "https://repo.r-wasm.org"))`,
      { withAutoprint: false, captureStreams: true }
    );
    const stdout = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
    const stderr = cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n');
    const okR = await webR.evalR(`requireNamespace(${JSON.stringify(pkg)}, quietly = TRUE)`);
    const installed = (await okR.toArray())[0] === true;
    webR.destroy(okR);
    if (installed) await snapshotLibrary(lib); // persist across restarts
    // On failure, surface the real R stderr (last few lines) instead of a guess —
    // it names the actual cause (e.g. a missing dependency or an unreachable repo).
    const detail = (stderr || stdout || '').split('\n').filter((l) => l.trim()).slice(-6).join('\n');
    AndroidBridge.onResult(id, JSON.stringify({
      installed, stdout, stderr,
      error: installed ? null : (`Could not install ${pkg}.` + (detail ? `\n${detail}` : ' No details were captured.')),
      timedOut: false, systemRequirements: null,
    }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ installed: false, stdout: '', stderr: String(e), error: String(e), timedOut: false, systemRequirements: null }));
  } finally { shelter.purge(); }
};

window.webrUninstall = async (id, pkg, sessionId, libraryKey) => {
  try {
    const libKey = libraryKey || sessionId;
    await ensureSession(sessionId, libKey);
    const lib = libDir(libKey);
    // A freshly installed package is a MOUNTED FS image, which neither
    // remove.packages nor unlink can delete — it must be FS.unmount'd first.
    // (After an app restart the package is instead plain restored files, so the
    // unmount is a harmless no-op and the unlink below clears the files.)
    try { await webR.FS.unmount(`${lib}/${pkg}`); } catch (e) { /* not a mount */ }
    const goneR = await webR.evalR(
      `local({\n` +
      `  p <- ${JSON.stringify(pkg)}; lib <- ${JSON.stringify(lib)}; dir <- file.path(lib, p);\n` +
      `  if (dir.exists(dir)) {\n` +
      `    ff <- tryCatch(list.files(dir, recursive = TRUE, all.files = TRUE, full.names = TRUE, include.dirs = TRUE), error = function(e) character(0));\n` +
      `    try(Sys.chmod(c(dir, ff), mode = '0777', use_umask = FALSE), silent = TRUE);\n` +
      `    unlink(dir, recursive = TRUE, force = TRUE)\n` +
      `  }\n` +
      `  !(p %in% rownames(installed.packages(lib.loc = lib, noCache = TRUE)))\n` +
      `})`
    );
    const removed = (await goneR.toArray())[0] === true;
    webR.destroy(goneR);
    if (removed) {
      // Best-effort: unload from the live session so a package already loaded
      // (e.g. via pkg::fn) stops working now, not just on relaunch.
      try {
        await webR.evalRVoid(
          `local({ p <- ${JSON.stringify(pkg)}; ` +
          `try({ if (paste0("package:", p) %in% search()) detach(paste0("package:", p), character.only = TRUE, unload = TRUE) }, silent = TRUE); ` +
          `try({ if (p %in% loadedNamespaces()) unloadNamespace(p) }, silent = TRUE) })`
        );
      } catch (e) { /* unload is best-effort */ }
      await snapshotLibrary(libKey); // persist the removal across restarts
    }
    AndroidBridge.onResult(id, JSON.stringify({ removed, error: removed ? null : `${pkg} was not removed.` }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ removed: false, error: String(e) }));
  }
};

// List only the session's user-installed packages, not WebR's built-ins.
window.webrListPackages = async (id, sessionId, libraryKey) => {
  try {
    const lib = libraryKey || sessionId;
    await ensureSession(sessionId, lib);
    const r = await webR.evalR(`rownames(installed.packages(lib.loc = ${JSON.stringify(libDir(lib))}))`);
    const packages = await r.toArray();
    webR.destroy(r);
    AndroidBridge.onResult(id, JSON.stringify({ packages }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ packages: [] }));
  }
};

// Read-only preview: render an on-device data file or a workspace object as one
// RTable (capped at 200 rows), without mutating the session. Mirrors the backend
// /preview endpoint's file/object handling for the Local engine.
window.webrPreview = async (id, requestJson, libraryKey) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ table: null, error: 'WebR not ready', truncated: false })); return; }
  const req = JSON.parse(requestJson);
  // Object preview reads globalenv(); make the requested project's session live first.
  const sessionId = req.sessionId || 'default';
  await ensureSession(sessionId, libraryKey || sessionId);
  const shelter = await new webR.Shelter();
  try {
    let rExpr;
    let prefixMore = false;   // true when we deliberately read only a file prefix
    let tmpSrc = null;
    if (req.source === 'file') {
      const ext = (req.name.split('.').pop() || '').toLowerCase();
      let size = 0;
      try { const m = JSON.parse(AndroidBridge.dataList(req.sessionId)).find((f) => f.name === req.name); if (m) size = m.size; } catch (e) {}
      if ((ext === 'csv' || ext === 'tsv') && size > 0) {
        // A preview is a peek: pull only a prefix (plenty for the first ~200 rows)
        // instead of syncing the whole file into the in-memory VFS. This lets an
        // arbitrarily large CSV/TSV preview on-device — matching the Remote engine —
        // without the OOM/skip that full sync hits. read.csv(nrows=201) stops early;
        // a record cut at the prefix boundary lands past row 200 (never displayed).
        const PREFIX_BYTES = 8 * 1024 * 1024;
        const want = Math.min(size, PREFIX_BYTES);
        const buf = new Uint8Array(want);
        let o = 0;
        for (let off = 0; off < want; off += DATA_CHUNK) {
          const b64 = AndroidBridge.dataChunk(req.sessionId, req.name, off, Math.min(DATA_CHUNK, want - off));
          if (!b64) break;
          const chunk = b64ToBytes(b64);
          const n = Math.min(chunk.length, want - o);
          buf.set(chunk.subarray(0, n), o); o += n;
        }
        tmpSrc = '/tmp/rmobile_preview_src';
        await webR.FS.writeFile(tmpSrc, buf.subarray(0, o));
        prefixMore = size > want;   // we cut the file, so there are definitely more rows
        const reader = ext === 'csv' ? 'read.csv' : 'read.delim';
        rExpr = `${reader}(${JSON.stringify(tmpSrc)}, check.names = FALSE, nrows = 201L)`;
      } else {
        // rds (and anything else): needs the whole file; sync it (skipped if too big).
        if (req.sessionId) await syncData(req.sessionId);
        const path = `/rmobile/data/${req.sessionId}/${req.name}`;
        const existsR = await webR.evalR(`file.exists(${JSON.stringify(path)})`);
        const exists = (await existsR.toArray())[0] === true; webR.destroy(existsR);
        if (!exists) {
          AndroidBridge.onResult(id, JSON.stringify({ table: null, error: 'This file is too large to load on the on-device (Local) engine, which keeps data in memory. Switch to the Remote engine in Settings to preview large data.', truncated: false }));
          return;
        }
        rExpr = `local({ p <- ${JSON.stringify(path)}; ext <- tolower(tools::file_ext(p)); ` +
          `if (ext %in% c('rds')) readRDS(p) ` +
          `else stop(sprintf('Preview of .%s files needs the Remote engine.', ext)) })`;
      }
    } else {
      rExpr = `get(${JSON.stringify(req.name)}, envir = globalenv())`;
    }
    await webR.evalRVoid(`.pv <- try(${rExpr}, silent = TRUE)`);
    const okR = await webR.evalR(`!inherits(.pv, "try-error")`);
    const ok = (await okR.toArray())[0] === true; webR.destroy(okR);
    if (!ok) {
      const eR = await webR.evalR(`as.character(attr(.pv, "condition")$message)`);
      const msg = (await eR.toArray())[0] || 'Could not read for preview.'; webR.destroy(eR);
      await webR.evalRVoid('if (exists(".pv")) rm(.pv)');
      AndroidBridge.onResult(id, JSON.stringify({ table: null, error: String(msg), truncated: false }));
      return;
    }
    // Emit the RTable JSON by writing it to a file and reading the bytes back —
    // the same proven path as readTables() for /execute. jsonlite::toJSON returns
    // a class-"json" character; extracting it via RObject.toArray() proved
    // unreliable on-device (columns survived but rows came back empty), whereas
    // FS.readFile + TextDecoder + JSON.parse round-trips the full string intact.
    await webR.evalRVoid(
      `local({ df <- as.data.frame(.pv); n <- nrow(df); more <- n > 200; sub <- utils::head(df, 200); ` +
      `cols <- names(sub); types <- vapply(sub, function(c) class(c)[1], character(1)); ` +
      `cells <- lapply(sub, function(c) as.character(format(c, trim = TRUE))); ` +
      `rows <- lapply(seq_len(nrow(sub)), function(i) as.character(vapply(cells, function(c) c[i], character(1)))); ` +
      `js <- jsonlite::toJSON(list(columns = as.character(cols), columnTypes = as.character(types), rows = rows, ` +
      `totalRows = jsonlite::unbox(as.integer(nrow(sub))), more = jsonlite::unbox(more)), auto_unbox = FALSE); ` +
      `writeLines(js, "/tmp/rmobile_preview.json") })`
    );
    const bytes = await webR.FS.readFile('/tmp/rmobile_preview.json');
    const parsed = JSON.parse(new TextDecoder().decode(bytes));
    const truncated = prefixMore || parsed.more === true;
    delete parsed.more;
    await webR.evalRVoid('unlink("/tmp/rmobile_preview.json"); rm(.pv)');
    if (tmpSrc) { try { await webR.evalRVoid(`unlink(${JSON.stringify(tmpSrc)})`); } catch (e) {} }
    AndroidBridge.onResult(id, JSON.stringify({ table: parsed, error: null, truncated }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ table: null, error: String(e), truncated: false }));
  } finally { shelter.purge(); }
};
