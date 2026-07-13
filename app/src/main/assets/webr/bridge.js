import { WebR } from './dist/webr.mjs';

// baseUrl MUST be absolute: WebR's web worker resolves it relative to its OWN
// location (already inside dist/), so a relative './dist/' doubles to dist/dist/
// and R.bin.js fails to load. Derive the absolute URL from the page.
const baseUrl = new URL('./dist/', document.baseURI).href;
const webR = new WebR({ baseUrl });
let ready = false;
let userLibReady = false;
const LOCAL_REPO_URL = new URL('./repo', document.baseURI).href;
const USER_LIB = '/rmobile/library';

const SNAP_TARBALL = '/rmobile/lib.tar';
const SNAP_CHUNK = 512 * 1024; // base64 transfer chunk size (bytes of raw data)
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

// Lazily create a user package library and put it first on .libPaths(), so
// installs land there and library() finds them. Called on demand by the package
// ops (and by restore at boot) — never in the plain run path.
async function ensureUserLib() {
  if (userLibReady) return;
  await webR.evalRVoid(
    `dir.create(${JSON.stringify(USER_LIB)}, showWarnings = FALSE, recursive = TRUE); ` +
    `.libPaths(c(${JSON.stringify(USER_LIB)}, .libPaths()))`
  );
  userLibReady = true;
}

// Cross-restart persistence WITHOUT WebR's IDBFS FS.mount (which broke the eval
// channel): tar the user library, hand the gzip bytes to Kotlin in base64 chunks
// to store under filesDir, and untar them back at boot. All best-effort — a
// failure never blocks install or boot.
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

async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
  await restoreLibrary(); // plain R evals (untar + .libPaths) — no FS.mount
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

async function resetRunDir() {
  await webR.evalRVoid('unlink("/rmobile/run", recursive = TRUE); dir.create("/rmobile/run"); setwd("/rmobile/run")');
}

async function runOnce(req) {
  await resetRunDir();
  const files = req.files && req.files.length ? req.files
    : [{ name: 'script.R', content: req.code || '' }];
  const entry = req.entryFile || files[0].name;
  for (const f of files) {
    await webR.FS.writeFile(`/rmobile/run/${f.name}`, new TextEncoder().encode(f.content));
  }
  // Load the harness template and inject the entry file path. Normalise line
  // endings: a CRLF checkout (Windows autocrlf) would otherwise leave a stray \r
  // after `local({`, which WebR's R parser rejects as an "unexpected invalid
  // token", breaking every run. Strip it here so the harness parses regardless.
  const harnessBytes = await (await fetch('./harness.R')).arrayBuffer();
  const harness = new TextDecoder().decode(harnessBytes).replace(/\r\n?/g, '\n');
  await webR.objs.globalEnv.bind('.RMOBILE_ENTRY', entry);

  const shelter = await new webR.Shelter();
  try {
    const cap = await shelter.captureR(harness, {
      withAutoprint: false,
      captureStreams: true,
      captureGraphics: { width: 800, height: 600 },
      env: await webR.objs.globalEnv,
    });
    const stdout = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
    const stderr = cap.output.filter((o) => o.type === 'stderr').map((o) => o.data).join('\n');
    const plots = [];
    for (const img of cap.images || []) plots.push(await bitmapToPng(img));
    const tables = await readTables();
    const wsR = await webR.evalR('ls(globalenv())');
    const workspaceObjects = await wsR.toArray();
    webR.destroy(wsR);
    return { stdout, stderr, plots, tables, workspaceObjects, error: null, timedOut: false };
  } finally { shelter.purge(); }
}

window.webrRun = async (id, requestJson) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ error: 'WebR not ready', timedOut: false })); return; }
  const req = JSON.parse(requestJson);
  const timeoutMs = (req.timeoutSeconds || 20) * 1000;
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(async () => { try { await webR.interrupt(); } catch {} resolve({ stdout: '', stderr: '', plots: [], tables: [], workspaceObjects: null, error: `Execution timed out after ${req.timeoutSeconds || 20}s.`, timedOut: true }); }, timeoutMs);
  });
  try {
    const result = await Promise.race([runOnce(req), timeout]);
    clearTimeout(timer);
    AndroidBridge.onResult(id, JSON.stringify(result));
  } catch (e) {
    clearTimeout(timer);
    AndroidBridge.onResult(id, JSON.stringify({ stdout: '', stderr: String(e), plots: [], tables: [], workspaceObjects: null, error: String(e), timedOut: false }));
  }
};

window.webrReset = async (id) => {
  try { await webR.evalRVoid('rm(list = ls(globalenv()), envir = globalenv())'); AndroidBridge.onResult(id, JSON.stringify({ ok: true })); }
  catch (e) { AndroidBridge.onResult(id, JSON.stringify({ ok: false, error: String(e) })); }
};

// Package management (device-verified). webr::install resolves against the
// bundled local repo first, then falls back to the online r-wasm CRAN mirror,
// installing into the lazily-created in-process user library (USER_LIB).
window.webrInstall = async (id, pkg) => {
  if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ installed: false, error: 'WebR not ready', stdout: '', stderr: '', timedOut: false, systemRequirements: null })); return; }
  await ensureUserLib();
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
    if (installed) await snapshotLibrary(); // persist across restarts
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

window.webrUninstall = async (id, pkg) => {
  try {
    await ensureUserLib();
    // A freshly installed package is a MOUNTED FS image, which neither
    // remove.packages nor unlink can delete — it must be FS.unmount'd first.
    // (After an app restart the package is instead plain restored files, so the
    // unmount is a harmless no-op and the unlink below clears the files.)
    try { await webR.FS.unmount(`${USER_LIB}/${pkg}`); } catch (e) { /* not a mount */ }
    const goneR = await webR.evalR(
      `local({\n` +
      `  p <- ${JSON.stringify(pkg)}; lib <- ${JSON.stringify(USER_LIB)}; dir <- file.path(lib, p);\n` +
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
      await snapshotLibrary(); // persist the removal across restarts
    }
    AndroidBridge.onResult(id, JSON.stringify({ removed, error: removed ? null : `${pkg} was not removed.` }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ removed: false, error: String(e) }));
  }
};

// List only user-installed packages (the USER_LIB), not WebR's built-ins.
window.webrListPackages = async (id) => {
  try {
    await ensureUserLib();
    const r = await webR.evalR(`rownames(installed.packages(lib.loc = ${JSON.stringify(USER_LIB)}))`);
    const packages = await r.toArray();
    webR.destroy(r);
    AndroidBridge.onResult(id, JSON.stringify({ packages }));
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ packages: [] }));
  }
};
