#!/usr/bin/env node
// Vendors WebR WASM binaries for the seed set + their full recursive dependency
// closure into app/src/main/assets/webr/repo, mirroring r-wasm's layout so WebR
// treats it as a local repo. Re-run to update. Requires Node 18+ (global fetch).
import { mkdir, writeFile, rm } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const R_VER = process.env.R_VER || '4.4';                 // verified against repo.r-wasm.org
const REPO = 'https://repo.r-wasm.org';
const CONTRIB = `bin/emscripten/contrib/${R_VER}`;
const SEED = [
  'dplyr','tidyr','ggplot2','readr','stringr','tibble','purrr','forcats','lubridate',
  'parameters','performance','effectsize','insight','datawizard',
  'jsonlite','cli',
];
const here = dirname(fileURLToPath(import.meta.url));
const DEST = join(here, '..', 'repo', CONTRIB);

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
    // Keep the full upstream control block verbatim: WebR reads the local
    // PACKAGES to resolve dependencies, so the vendored index MUST carry
    // Depends/Imports/LinkingTo (and MD5sum) — not just Package/Version — or a
    // seed package installs without its deps and fails to load.
    out[fields.Package] = { version: fields.Version, deps, raw: block.trim() };
  }
  return out;
}

const BASE = new Set(['R','base','methods','utils','stats','graphics','grDevices',
  'datasets','tools','grid','splines','stats4','tcltk','compiler','parallel']);

async function main() {
  console.log(`Fetching ${REPO}/${CONTRIB}/PACKAGES ...`);
  const res0 = await fetch(`${REPO}/${CONTRIB}/PACKAGES`);
  if (!res0.ok) throw new Error(`PACKAGES fetch failed: ${res0.status} — is R_VER=${R_VER} correct?`);
  const index = parsePackages(await res0.text());

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

  let totalBytes = 0;
  const localBlocks = [];
  for (const name of names) {
    const ver = index[name].version;
    const file = `${name}_${ver}.tgz`;
    const url = `${REPO}/${CONTRIB}/${file}`;
    process.stdout.write(`  ${file} ... `);
    const res = await fetch(url);
    if (!res.ok) { console.log(`FAILED ${res.status}`); throw new Error(`download ${url}`); }
    const buf = Buffer.from(await res.arrayBuffer());
    totalBytes += buf.length;
    await writeFile(join(DEST, file), buf);
    console.log(`ok (${(buf.length/1024).toFixed(0)} KB)`);
    localBlocks.push(index[name].raw); // full upstream block (deps + MD5sum)
  }
  await writeFile(join(DEST, 'PACKAGES'), localBlocks.join('\n\n') + '\n');
  console.log(`Wrote ${names.length} packages, ${(totalBytes/1048576).toFixed(1)} MB total, into ${DEST}`);
}
main().catch((e) => { console.error(e); process.exit(1); });
