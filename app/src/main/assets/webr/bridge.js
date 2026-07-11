import { WebR } from './dist/webr.mjs';

const webR = new WebR({ baseUrl: './dist/' });
let ready = false;

async function boot() {
  await webR.init();
  ready = true;
  AndroidBridge.onReady();
}
boot().catch((e) => AndroidBridge.onError(String(e)));

// Called from Kotlin via evaluateJavascript.
window.webrEval = async (id, code) => {
  try {
    if (!ready) { AndroidBridge.onResult(id, JSON.stringify({ error: 'WebR not ready' })); return; }
    const shelter = await new webR.Shelter();
    try {
      const cap = await shelter.captureR(code, { withAutoprint: true, captureStreams: true });
      const text = cap.output.filter((o) => o.type === 'stdout').map((o) => o.data).join('\n');
      AndroidBridge.onResult(id, JSON.stringify({ text }));
    } finally { shelter.purge(); }
  } catch (e) {
    AndroidBridge.onResult(id, JSON.stringify({ error: String(e) }));
  }
};
