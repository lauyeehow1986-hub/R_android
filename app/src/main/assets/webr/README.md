# Vendored WebR runtime

This directory bundles [WebR](https://docs.r-wasm.org/webr/) — real GNU R
compiled to WebAssembly — so the Android app can run R **fully offline**, with
no backend round-trip, inside a hidden WebView.

## What's here

- `scripts/fetch-webr.sh` — vendors the runtime into `dist/`.
- `index.html` + `bridge.js` — the page loaded in the offscreen WebView; boots
  WebR and exposes `window.webrEval(id, code)` to Kotlin.
- `dist/` — the WebR runtime itself (committed, so the app is
  offline-from-install). Populated by the fetch script.

## Version

**WebR 0.4.2.**

## How `dist/` is produced

Run, from the repo root, on a machine with `npm` + internet:

```bash
bash app/src/main/assets/webr/scripts/fetch-webr.sh
```

The script runs `npm pack webr@0.4.2` and copies the package's `dist/`
directory into `app/src/main/assets/webr/dist/`. After running, verify these
exist and then commit `dist/`:

- `dist/webr.mjs` — the WebR ES module entry point.
- `dist/R.bin.wasm` — the R interpreter compiled to WebAssembly.
- `dist/R.bin.data` — the R filesystem image (base packages, etc.).
- the worker / service-worker JS files.

The `dist/` directory is committed to the repo so the app ships the runtime and
works from first install without any download.

## License

WebR is distributed under the **GPL** (GNU General Public License) — the same
license as GNU R itself. The vendored contents of `dist/` are therefore
GPL-licensed. See the WebR project (https://github.com/r-wasm/webr) for the full
license text and details.
