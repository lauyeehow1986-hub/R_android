# R execution backend

A minimal [Plumber](https://www.rplumber.io/) API that runs an R script in a
subprocess and returns its stdout, stderr, and any plots as base64 PNGs. This
is what the Android app in `/app` talks to — see the root `CLAUDE.md` for how
the two fit together.

## Running locally

```bash
docker compose up --build
```

The API listens on `http://localhost:8000`. The Android emulator reaches your
host machine at `10.0.2.2`, which is why `app/build.gradle.kts` defaults
`R_EXECUTION_BASE_URL` to `http://10.0.2.2:8000/`.

```bash
curl -X POST http://localhost:8000/execute \
  -H "Content-Type: application/json" \
  -d '{"code": "summary(cars)\nplot(cars)"}'
```

## Endpoints

- `POST /execute` — body `{"code": "<R source>"}`, returns
  `{"stdout", "stderr", "plots": ["<base64 png>", ...], "error", "timedOut"}`.
  When `R_API_KEY` is set, requires an `X-API-Key: <key>` header (else `401`);
  returns `429` if the per-IP rate limit is exceeded.
  Optional `sessionId` (defaults to `default`); the response adds
  `workspaceObjects` (names in the session's global env after the run, or
  omitted when the run errored/timed out).
- `POST /reset` — body `{"sessionId": "default"}`, clears that session's saved
  workspace and attached-package list. Returns `{"ok": true}`. Same auth /
  rate-limit rules as `/execute`.
- `GET /health` — liveness check (never requires auth).

## Durable sessions

Each session keeps `workspace.RData` (global-env objects) and `attached.txt`
(user-attached packages) under `R_SESSION_DIR` (default `/data/sessions`, a
named volume in `docker-compose.yml`). The wrapper restores them before each
run and saves them after a **successful** run, so a failed or timed-out run
never overwrites good state. Only data/objects and attached packages persist —
connections, external pointers, and `options()` do not.

## Security — read this before deploying anywhere reachable from the internet

`/execute` runs arbitrary, untrusted R code. That is the entire point of the
app, and it is also a remote-code-execution primitive by construction. What's
in place today:

- Each request runs in its own `Rscript --vanilla` **subprocess** (not in the
  Plumber process itself), with a hard wall-clock **timeout**
  (`R_EXECUTION_TIMEOUT_SECONDS`, default 20s) and its own temp working
  directory that's deleted afterward.
- The container runs as an unprivileged user, with a read-only root
  filesystem, all Linux capabilities dropped, and `no-new-privileges`
  (`docker-compose.yml`).
- CPU and memory are capped at the container level (`cpus`, `mem_limit`).
- **Optional shared-secret auth**: set `R_API_KEY` and `/execute` requires a
  matching `X-API-Key` header (401 otherwise). Unset = disabled, for local dev.
- **Optional per-IP rate limiting**: set `R_RATE_LIMIT_PER_MINUTE` to a
  positive number to throttle `/execute` per client IP (429 when exceeded).
  `0`/unset disables it. This is an in-process fixed-window counter — good
  enough for a single instance, not a substitute for a real gateway/WAF across
  a fleet.

What is **not** handled, and needs to be before this is exposed beyond your
own dev machine:

- **Network egress is not restricted.** Submitted R code can currently make
  outbound HTTP requests, hit internal/cloud-metadata endpoints, etc. Put
  this behind a network policy (firewall egress rules, or run it in a
  container/VM with no route to anything sensitive) before trusting it with
  real users.
- **No per-request process isolation beyond a subprocess.** For real
  multi-tenant use, run each execution in its own ephemeral container,
  gVisor sandbox, or microVM (Firecracker) rather than trusting Linux
  capability-dropping alone.
- **Auth and rate limiting exist but are off by default.** Set `R_API_KEY`
  and `R_RATE_LIMIT_PER_MINUTE` (above) before this is reachable from anything
  but a trusted client on a private network. The rate limiter also trusts
  `REMOTE_ADDR`; behind a proxy you'd want it to read `X-Forwarded-For`.
- **Disk quota isn't enforced** beyond the OS temp cleanup — a script that
  fills the tmpfs before its timeout fires could still cause problems.
- **Session state is unbounded and attacker-writable.** Persisted workspaces
  can grow without limit and hold arbitrary user data; there is no per-session
  quota or eviction yet. The `sessionId` is sanitized to `[A-Za-z0-9_-]` — keep
  that guard if you add real multi-session support.

Treat this as a working MVP for local development, not a hardened multi-user
service.
