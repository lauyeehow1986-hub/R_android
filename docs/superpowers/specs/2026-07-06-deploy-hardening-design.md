# Backend deploy hardening — design

**Date:** 2026-07-06
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + CI

## Problem

`/execute` runs arbitrary untrusted R code. Today the container is non-root,
read-only-root, all-caps-dropped, CPU/memory-capped, and optionally
auth/rate-limited — but **network egress is unrestricted**, so submitted code can
reach cloud-metadata endpoints, internal services, or exfiltrate data (SSRF), and
`/tmp` isn't size-capped. This blocks safely exposing the backend beyond
localhost.

## Goal

A **hardened deployment profile** where the execution container has **no outbound
network access**, packages are **baked at build time** (so no runtime egress is
needed for them), and `/tmp` is size-capped — verifiable in Docker.

## Decisions (from brainstorming)

- **Egress restriction (+ disk caps)** is the scope; per-request VM/gVisor
  isolation is out of scope (can't run on Docker Desktop for Windows) and
  documented as the remaining gap.
- **Build-time package pipeline** (a 2-stage build): packages are declared in a
  manifest and installed during the image build (which has network); the runtime
  container needs no internet. Runtime `/install` stays for the default/dev
  profile only.

## Build-time package pipeline ("stage 1": bake packages, with network)

- `backend/packages.txt` — a manifest of CRAN packages to bake into the image,
  one per line (`#` comments and blank lines allowed). **Empty by default** (so
  the standard image stays lean). Adding a package for a hardened deploy = add a
  line, rebuild the image, redeploy. This is the "trigger a new build for new
  packages" step — the build has network; the runtime (stage 2) does not.
- **`Dockerfile`** (single stage, extending the existing one): after the existing
  `install2.r ... plumber processx base64enc jsonlite remotes`, `COPY packages.txt`
  and add a step that installs the manifest packages when it's non-empty:
  ```dockerfile
  COPY packages.txt ./
  RUN pkgs="$(grep -vE '^\s*#|^\s*$' packages.txt || true)"; \
      if [ -n "$pkgs" ]; then install2.r --error --skipmissing $pkgs; fi
  ```
  The image already carries the `-dev` system libraries (from the package-install
  feature), so baked packages load at runtime without any extra work. A single
  stage keeps this low-risk; a slimmed multi-stage image (no compilers at
  runtime) is a **future optimization**, not part of this pass.
- The default `docker-compose.yml` build is unchanged in behavior — it just also
  bakes whatever is in `packages.txt` (nothing, by default).

## Runtime egress restriction (hardened profile)

- `backend/docker-compose.hardened.yml` — an override applied with
  `docker compose -f docker-compose.yml -f docker-compose.hardened.yml ...`:
  - Defines two networks: `edge` (default bridge, published) and `execnet`
    (`internal: true` — no route to the internet).
  - Puts `r-execution` **only on `execnet`** (removing its published port), so it
    has no outbound path.
  - Adds a `proxy` service (Caddy, `caddy:2-alpine`) on **both** networks that
    reverse-proxies `:8000` to `r-execution:8000`; the published `8000:8000` port
    moves to `proxy`. Config via a one-line `Caddyfile`
    (`:8000 { reverse_proxy r-execution:8000 }`) mounted read-only.
  - Keeps all existing hardening on `r-execution` (read-only root, cap_drop,
    no-new-privileges, mem/cpu limits) and adds a **tmpfs size cap**:
    `tmpfs: /tmp:size=64m` (replacing the uncapped `/tmp`).
- The app connects to the host's published `:8000` (now the proxy) exactly as
  before — ingress unchanged, egress gone.

## Testing

- **Hardened integration check** (docker-compose; local + a new CI job) — the
  Docker networking can't be exercised by the native-R testthat suite:
  1. Build + `docker compose -f docker-compose.yml -f docker-compose.hardened.yml up -d --build`; wait for health.
  2. `GET http://localhost:8000/health` (via the proxy) → `{"status":"ok"}` (ingress works).
  3. `POST /execute` with
     `cat(tryCatch({ readLines(url("http://example.com", open = "rb")); "REACHED" }, error = function(e) "BLOCKED"))`
     → stdout `"BLOCKED"` (egress blocked). Use a short per-request timeout so the
     assertion is fast.
  4. `docker compose ... down -v`.
- **New CI job `hardened-egress`** (GitHub Actions, Docker available): runs steps
  1–4 above and fails if `/health` isn't reachable or the egress probe returns
  `"REACHED"`.
- The existing `backend-tests` (testthat) and `build` (Android) jobs are
  unchanged and still pass.
- The build-time package mechanism is verified by the image building; a manual
  check is documented (add a package to `packages.txt`, rebuild, `library(it)` in
  `/execute`).

## Docs

- `backend/README.md`: a "Hardened deployment" section (the hardened compose
  override, the `packages.txt` build workflow, egress blocking, tmpfs cap); move
  "network egress is not restricted" from the "not handled" list to "handled in
  the hardened profile", and keep the per-request-isolation and disk-quota gaps.
- `CLAUDE.md`: note the multi-stage `Dockerfile`, `packages.txt`, and
  `docker-compose.hardened.yml` in the backend section.

## Out of scope (documented gaps)

- **Per-request VM/gVisor isolation** (Firecracker/gVisor/ephemeral container per
  request) — the deeper isolation for true multi-tenant; not runnable on Docker
  Desktop for Windows. Noted as the next hardening step.
- Real per-volume disk **quotas** (only `/tmp` is capped; the `sessions`/`rlib`
  volumes can still grow).
- The **default/dev profile stays egress-on** so runtime `/install` still works
  there; hardening is opt-in via the override file.

## Risks / notes

- Because the build is single-stage, packages in `packages.txt` load at runtime
  using the `-dev`/runtime system libraries already in the image (e.g. `libxml2`
  for `xml2` is present). A package needing a system lib not already installed
  fails at build with a clear error → add the apt lib to the `Dockerfile` and
  rebuild (same as the existing `/install` limitation).
- Caddy is a light dependency added **only** in the hardened override; the
  default `docker-compose.yml` is unaffected.
- Slimming the runtime image via a true multi-stage build (dropping compilers) is
  a documented future optimization, not part of this pass.
