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

## Running the tests

Integration tests (testthat + httr2) start a Plumber instance and exercise every
endpoint over HTTP. With R installed and the packages `plumber, processx,
base64enc, jsonlite, testthat, httr2, withr`:

    Rscript backend/run-tests.R

Tests live in `backend/tests/` (`helper-server.R` starts/stops the server;
`test-*.R` are the cases). CI runs them on every push/PR.

## Endpoints

- `POST /execute` — body `{"code": "<R source>"}`, returns
  `{"stdout", "stderr", "plots": ["<base64 png>", ...], "tables", "error", "timedOut"}`.
  Any data frame, tibble, data.table, matrix, or 2-D `table` **printed at the
  script's top level** (`df`, `head(df)`, `summary(cars)`, or `print(df)`) is also
  returned in `tables` as `{"columns","columnTypes","rows","totalRows"}` (rows
  capped at `R_TABLE_MAX_ROWS`, default 200; `totalRows` is the true count).
  Data frames printed *inside* a function or a `source()`d helper are not captured.
  When `R_API_KEY` is set, requires an `X-API-Key: <key>` header (else `401`);
  returns `429` if the per-IP rate limit is exceeded.
  Optional `sessionId` (defaults to `default`); the response adds
  `workspaceObjects` (names in the session's global env after the run, or
  omitted when the run errored/timed out). Alternatively send a multi-file
  project: `files` (a list of `{"name","content"}`) plus `entryFile` (one of the
  names) — all files are written to the run dir and the entry is `source()`d, so
  `source("helpers.R")` works. File names must match `^[A-Za-z0-9][A-Za-z0-9._-]*$`;
  `code` and `files` are mutually exclusive (`files` wins if present).
- `POST /reset` — body `{"sessionId": "default"}`, clears that session's saved
  workspace and attached-package list. Optional `"purgePackages": true` also
  deletes that session's installed-package library (`rlib`) — used when a
  project is deleted. Returns `{"ok": true}`. Same auth / rate-limit rules as
  `/execute`.
- `POST /install` — body `{"package":"<name>"}`, installs a CRAN package into
  the active session's library. Optional `sessionId` (default `default`).
  Returns `{"stdout","stderr","error","timedOut",
  "installed","systemRequirements"}`. Same auth / rate-limit rules as
  `/execute`; its own timeout (`R_INSTALL_TIMEOUT_SECONDS`, default 300s).
- `POST /uninstall` — body `{"package":"<name>"}`, removes a package from the
  active session's library. Optional `sessionId` (default `default`). Returns
  `{"removed","error"}` (`removed:true` only when the package was present and
  is now gone; `removed:false` with no error for a package that wasn't
  installed there). Same auth / rate-limit rules as `/execute`. Only that
  session's library is touched — base and pre-baked image packages are
  unaffected.
- `GET /packages` — lists user-installed packages in a session's library
  (`{"packages":[...]}`). Takes an optional `?sessionId=` query param (default
  `default`) — it's per-session, not a global shared list. Read-only, no auth.
- `POST /import-legacy` — body `{"sessionId":"default"}`, copies packages from
  the legacy shared library (`R_PKG_LIB`, now read-only) into that session's
  library, skipping any already present. Returns `{"imported":<int>,"packages":[...]}`.
  Same auth / rate-limit rules as `/execute`.
- `GET /symbols` — returns completion symbol names for a session: the exported
  names of R's base and default auto-attached packages (`base`, `methods`,
  `datasets`, `utils`, `grDevices`, `graphics`, `stats`) plus every package the
  session has `library()`-d (read from that session's recorded
  attached-package list). Runs in an isolated `Rscript --vanilla` subprocess
  with the session's library on `.libPaths()`. Takes an optional `?sessionId=`
  query param (default `default`). Returns `{"symbols": ["abbreviate",
  "abline", "abs", ...]}` — sorted, de-duplicated, and capped at
  `R_SYMBOLS_MAX` (default 5000). Read-only. Same auth / rate-limit rules as
  `/execute`.
- `POST /help` — renders an R help topic to plain text with `tools::Rd2txt`.
  Body `{"topic":"mean","sessionId":"proj-123"}` (`sessionId` optional,
  default `default`); `topic` must match `^[A-Za-z0-9._]+$` (else `400`).
  Resolves across base R and the session's installed/attached packages.
  Returns `{"topic","packageName","text","found"}`, e.g.
  `{"topic":"mean","packageName":"base","text":"mean {base}\n...","found":true}`;
  `found` is `false` with an empty `text` when no topic matches. Read-only.
  Same auth / rate-limit rules as `/execute`.
- `GET /health` — liveness check (never requires auth).

### Data files (`/upload`, `/data`, `/delete-data`)

Each session has a persistent `data/` dir (`SESSION_DIR/<id>/data`) for uploaded
files. Executed code reads them by bare filename (they're symlinked into the run
dir): `read.csv("sales.csv")`, `readRDS("model.rds")`.

- `POST /upload?sessionId=<id>` — `multipart/form-data` with one part named `file`.
  Filename is reduced to a safe basename (disallowed chars → `_`, reserved names
  rejected). Size cap `R_UPLOAD_MAX_BYTES` (default 1 GiB). Returns `{name, size}`.
- `GET /data?sessionId=<id>` → `{files: [{name, size}]}`.
- `POST /delete-data` — `{name, sessionId}` → `{removed}`.

Uploaded data is cleared by `POST /reset` and when a project is deleted.

**Memory:** plumber buffers the whole upload in memory to parse the multipart
body, so the container `mem_limit` (docker-compose) must exceed `R_UPLOAD_MAX_BYTES`.
They move together — lower the cap and you can lower the limit.

**Symlink caveat:** a data file is symlinked (not copied) into the run dir, so code
that *writes* to that filename writes through to the stored copy. Fine for read-only
data; re-upload to replace.

## Durable sessions

Each session keeps `workspace.RData` (global-env objects) and `attached.txt`
(user-attached packages) under `R_SESSION_DIR` (default `/data/sessions`, a
named volume in `docker-compose.yml`). The wrapper restores them before each
run and saves them after a **successful** run, so a failed or timed-out run
never overwrites good state. Only data/objects and attached packages persist —
connections, external pointers, and `options()` do not. Each session also has
its own installed-package library (`rlib`, see "Packages" below); `POST /reset`
with `"purgePackages": true` deletes that library along with the workspace.

## Packages

`POST /install` (body `{"package":"<name>"}`, optional `sessionId`, default
`default`) installs a CRAN package into that **session's own library**, at
`R_SESSION_DIR/<sessionId>/rlib` — packages are isolated per session/project,
not shared globally. Installs use a longer timeout
(`R_INSTALL_TIMEOUT_SECONDS`, default 300s) from `R_CRAN_REPO`. A session's
library is prepended to `.libPaths()` for its runs, so `library()` (and inline
`install.packages()`) work. `GET /packages?sessionId=` lists a session's
installed packages, and `POST /uninstall` (body `{"package":"<name>"}`,
optional `sessionId`) removes one from that session's library (base and
pre-baked image packages are untouched).

The pre-multi-session shared library (`R_PKG_LIB`, default `/data/rlib`) is
still mounted, but now **read-only** — it's a legacy source, not a live
install target. `POST /import-legacy` (body `{"sessionId":"default"}`) copies
packages from it into a session's own library on demand, skipping any already
present there.

Common system libraries are baked into the image, so most popular packages
install (as binaries, no compilation) into any session's library; a package
needing an un-baked lib fails and `/install` returns the apt command to add it
(rebuild the image) — there is no runtime apt, so the container stays non-root
+ read-only-root. Base and pre-baked image packages are available read-only to
every session regardless of which session's library they were built for.

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
`packages.txt` (one per line) and rebuild the image. (Runtime `/install` stays
available in the default/dev profile.)

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

- **Network egress**: unrestricted in the default/dev profile, but **blocked in
  the hardened profile** (see "Hardened deployment" above — `internal` network +
  reverse proxy). Use the hardened profile before trusting this with real users;
  the `/tmp` size cap there also limits scratch-disk abuse (the `sessions`/`rlib`
  volumes are still unbounded).
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
- **`/install` runs arbitrary package code** (same RCE surface as `/execute`)
  and writes to that session's own library — a trojaned package is now
  confined to the session that installed it, rather than persisting for every
  session as it did with the old shared library. Per-session libraries are
  still unbounded/unevicted (the disk-quota caveat above applies per session
  now, not just globally). `/import-legacy` copies packages from the old
  shared library (`R_PKG_LIB`, now read-only) into a session on request — a
  compromised legacy package would propagate to any session that imports it,
  same as before for that path. It is auth/rate-limited and the package name
  is restricted to `^[A-Za-z0-9._]+$`.
- **Session state is unbounded and attacker-writable.** Persisted workspaces
  (and now per-session package libraries) can grow without limit and hold
  arbitrary user data; there is no per-session quota or eviction yet. The
  `sessionId` is sanitized to `[A-Za-z0-9_-]` — keep that guard if you extend
  multi-session support further.

Treat this as a working MVP for local development, not a hardened multi-user
service.
