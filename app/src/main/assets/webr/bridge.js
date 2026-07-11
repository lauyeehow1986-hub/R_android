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

// Lazily create a user package library and put it first on .libPaths(), so
// installs land there and library() finds them. Called on demand by the package
// ops only — NEVER at boot — so the run path stays byte-identical to the
// verified baseline. This is an in-process (MEMFS) library: installs work within
// the app session but do not yet persist across restarts. (An earlier attempt to
// back this with an IndexedDB FS.mount destabilised the WebR channel and broke
// all evaluation, so persistence is deferred to a safer snapshot mechanism.)
async function ensureUserLib() {
  if (userLibReady) return;
  await webR.evalRVoid(
    `dir.create(${JSON.stringify(USER_LIB)}, showWarnings = FALSE, recursive = TRUE); ` +
    `.libPaths(c(${JSON.stringify(USER_LIB)}, .libPaths()))`
  );
  userLibReady = true;
}

async function boot() {
  await webR.init();
  await webR.evalRVoid('dir.create("/rmobile", showWarnings = FALSE)');
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
  // Load the harness template and inject the entry file path.
  const harnessBytes = await (await fetch('./harness.R')).arrayBuffer();
  const harness = new TextDecoder().decode(harnessBytes);
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
    await webR.evalRVoid(`remove.packages(${JSON.stringify(pkg)}, lib = ${JSON.stringify(USER_LIB)})`);
    const stillR = await webR.evalR(`${JSON.stringify(pkg)} %in% rownames(installed.packages(lib.loc = ${JSON.stringify(USER_LIB)}))`);
    const still = (await stillR.toArray())[0] === true;
    webR.destroy(stillR);
    AndroidBridge.onResult(id, JSON.stringify({ removed: !still, error: still ? `${pkg} was not removed.` : null }));
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
