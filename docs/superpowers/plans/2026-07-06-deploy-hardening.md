# Backend Deploy Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A hardened deployment profile where the execution container has no network egress (blocking SSRF/exfiltration), packages are baked at build time via a manifest, and `/tmp` is size-capped.

**Architecture:** A `packages.txt` manifest baked by the `Dockerfile` at build (which has network). A `docker-compose.hardened.yml` override puts `r-execution` on an `internal` network with no route out and fronts it with a Caddy reverse proxy for ingress. Verified by a docker-compose integration check + a new CI job.

**Tech Stack:** Docker / Docker Compose, Caddy, Plumber (R), GitHub Actions.

---

## Prerequisites

- Docker is available locally (used to build + run the hardened stack). The
  existing `r-backend-test` image is NOT used here.
- **Branch:** `feat/deploy-hardening` (already created; holds the spec).

---

## File Structure

- Create: `backend/packages.txt` (CRAN package manifest, empty by default).
- Modify: `backend/Dockerfile` (bake the manifest).
- Create: `backend/docker-compose.hardened.yml` (override: internal net + proxy + tmpfs cap).
- Create: `backend/Caddyfile` (reverse-proxy config).
- Modify: `.github/workflows/android.yml` (add a `hardened-egress` job).
- Modify: `backend/README.md`, `CLAUDE.md` (docs).

---

## Task 1: Build-time package manifest

**Files:** Create `backend/packages.txt`; Modify `backend/Dockerfile`.

- [ ] **Step 1: Create the manifest**

Create `backend/packages.txt`:
```
# CRAN packages to bake into the image (one per line). Empty by default.
# Add a package here and rebuild for a hardened (no-egress) deployment,
# where runtime `install.packages()` / POST /install cannot reach CRAN.
```

- [ ] **Step 2: Bake the manifest in the Dockerfile**

In `backend/Dockerfile`, immediately AFTER the existing
`RUN install2.r --error --skipmissing plumber processx base64enc jsonlite remotes`
line (still running as root, before the `useradd` line), add:
```dockerfile
COPY packages.txt /tmp/packages.txt
RUN pkgs="$(grep -vE '^[[:space:]]*#|^[[:space:]]*$' /tmp/packages.txt || true)"; \
    if [ -n "$pkgs" ]; then install2.r --error --skipmissing $pkgs; fi; \
    rm -f /tmp/packages.txt
```
(`grep ... || true` avoids grep's exit-1-on-no-match failing the build; with the default empty manifest, `$pkgs` is empty and no install runs.)

- [ ] **Step 3: Verify the image still builds**

```bash
cd /c/Users/lauye/Downloads/R_android/backend
docker compose build r-execution 2>&1 | tail -5
```
Expected: build succeeds (the manifest is empty, so the new step is a no-op).
(Manual mechanism check, not required here: adding e.g. `praise` to `packages.txt`
and rebuilding bakes it — `docker run ... Rscript -e 'library(praise)'` loads.)

- [ ] **Step 4: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/packages.txt backend/Dockerfile
git commit -m "backend: bake CRAN packages from packages.txt at build time"
```

---

## Task 2: Hardened compose profile (no egress) + Caddy proxy

**Files:** Create `backend/docker-compose.hardened.yml`, `backend/Caddyfile`.

- [ ] **Step 1: Create the Caddyfile**

Create `backend/Caddyfile`:
```
:8000 {
	reverse_proxy r-execution:8000
}
```

- [ ] **Step 2: Create the hardened override**

Create `backend/docker-compose.hardened.yml`:
```yaml
# Hardened profile — apply as an override on top of docker-compose.yml:
#   docker compose -f docker-compose.yml -f docker-compose.hardened.yml up -d --build
#
# The execution container gets NO network egress: r-execution runs only on an
# `internal` network (no route to the internet), and a Caddy reverse proxy on a
# separate edge network provides ingress. Submitted R code therefore cannot
# reach cloud-metadata / internal services / the internet.
services:
  r-execution:
    # Remove the published port from the base file — ingress comes via the proxy.
    ports: !override []
    # Only on the internal (no-egress) network.
    networks:
      - execnet
    # Cap the scratch filesystem so a run can't fill it.
    tmpfs: !override
      - /tmp:size=64m

  proxy:
    image: caddy:2-alpine
    depends_on:
      - r-execution
    ports:
      - "8000:8000"
    networks:
      - edge
      - execnet
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro

networks:
  edge:
  execnet:
    internal: true
```

- [ ] **Step 3: Validate the merged config**

```bash
cd /c/Users/lauye/Downloads/R_android/backend
docker compose -f docker-compose.yml -f docker-compose.hardened.yml config >/dev/null && echo "CONFIG_OK"
```
Expected: `CONFIG_OK`. If `!override` is rejected (very old compose), replace
`ports: !override []` with `ports: !reset null` and `tmpfs: !override` with
`tmpfs: !reset` + re-add the entry; re-run until `CONFIG_OK`.

- [ ] **Step 4: Integration test — ingress works, egress is blocked**

```bash
cd /c/Users/lauye/Downloads/R_android/backend
docker compose -f docker-compose.yml -f docker-compose.hardened.yml up -d --build
for i in $(seq 1 30); do curl -sf http://localhost:8000/health >/dev/null 2>&1 && break; sleep 2; done
echo "health: $(curl -s http://localhost:8000/health)"
PROBE='{"code":"options(timeout=5); cat(tryCatch({ readLines(url(\"http://example.com\")); \"REACHED\" }, error = function(e) \"BLOCKED\"))"}'
echo "egress: $(curl -s -X POST http://localhost:8000/execute -H 'Content-Type: application/json' -d "$PROBE")"
docker compose -f docker-compose.yml -f docker-compose.hardened.yml down -v
```
Expected: `health:` shows `{"status":"ok"}` (ingress via the proxy works), and
`egress:` shows `"stdout":"BLOCKED"` (the executed code could not reach the
internet). If egress shows `"REACHED"`, the topology is wrong — confirm
`r-execution` is only on `execnet` and has no published port; fix and re-run.

- [ ] **Step 5: Commit**
```bash
cd /c/Users/lauye/Downloads/R_android
git add backend/docker-compose.hardened.yml backend/Caddyfile
git commit -m "backend: hardened compose profile blocks execution egress via internal net + proxy"
```

---

## Task 3: `hardened-egress` CI job

**Files:** Modify `.github/workflows/android.yml`.

- [ ] **Step 1: Add the job**

Read `.github/workflows/android.yml` and add this job as a sibling of the
existing `build` / `backend-tests` jobs (same 2-space indentation, at the end of
the `jobs:` map):
```yaml
  hardened-egress:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Bring up the hardened stack
        working-directory: backend
        run: docker compose -f docker-compose.yml -f docker-compose.hardened.yml up -d --build

      - name: Wait for health (via the proxy)
        run: |
          for i in $(seq 1 60); do
            curl -sf http://localhost:8000/health >/dev/null 2>&1 && exit 0
            sleep 2
          done
          echo "backend never became healthy"; exit 1

      - name: Ingress works
        run: curl -s http://localhost:8000/health | grep -q '"status":"ok"'

      - name: Egress is blocked
        run: |
          out=$(curl -s -X POST http://localhost:8000/execute \
            -H 'Content-Type: application/json' \
            -d '{"code":"options(timeout=5); cat(tryCatch({ readLines(url(\"http://example.com\")); \"REACHED\" }, error = function(e) \"BLOCKED\"))"}')
          echo "response: $out"
          echo "$out" | grep -q '"stdout":"BLOCKED"'

      - name: Tear down
        if: always()
        working-directory: backend
        run: docker compose -f docker-compose.yml -f docker-compose.hardened.yml down -v
```

- [ ] **Step 2: Validate the workflow YAML**

```bash
cd /c/Users/lauye/Downloads/R_android
python -c "import yaml; d=yaml.safe_load(open('.github/workflows/android.yml')); print('jobs:', list(d['jobs'].keys()))"
```
Expected: prints the job list including `hardened-egress`. (The job runs on
GitHub, where Docker is available; it can't be executed locally beyond the
Task 2 check.)

- [ ] **Step 3: Commit**
```bash
git add .github/workflows/android.yml
git commit -m "ci: hardened-egress job verifies the execution container has no egress"
```

---

## Task 4: Docs

**Files:** Modify `backend/README.md`, `CLAUDE.md`.

- [ ] **Step 1: backend/README.md — hardened deployment section**

Add a section (after the "Packages" section):
```markdown
## Hardened deployment

For exposing the backend beyond localhost, use the hardened profile, which gives
the execution container **no network egress**:

```bash
docker compose -f docker-compose.yml -f docker-compose.hardened.yml up -d --build
```

`r-execution` runs only on an `internal` Docker network (no route to the
internet) behind a Caddy reverse proxy, so submitted R code cannot reach
cloud-metadata endpoints, internal services, or exfiltrate data. `/tmp` is
size-capped (64m).

Because there's no egress, runtime package installation (`POST /install`) does
not work in this profile — **bake packages at build time** instead: add them to
`packages.txt` (one per line) and rebuild the image. (Runtime `/install` remains
available in the default/dev profile.)
```

Then update the security section: move the "Network egress is not restricted"
bullet from the "not handled" list into the "in place" list, reworded to note it
is handled by the hardened profile; leave the per-request-isolation and
disk-quota bullets in "not handled" (the tmpfs cap is a partial mitigation —
mention it).

- [ ] **Step 2: CLAUDE.md**

In the backend architecture section, add a sentence noting: `packages.txt` baked
by the `Dockerfile` at build time, and `docker-compose.hardened.yml` (internal
network + Caddy proxy) as the no-egress deployment profile; per-request VM
isolation remains the outstanding gap.

- [ ] **Step 3: Commit**
```bash
git add backend/README.md CLAUDE.md
git commit -m "docs: document the hardened deployment profile"
```

---

## Done

A hardened, egress-free deployment profile with build-time package baking and a
tmpfs cap, verified locally and in a `hardened-egress` CI job. Open a PR from
`feat/deploy-hardening` (base `claude/r-app-android-version-ztmyd1`). Per-request
VM/gVisor isolation remains the documented next step.
