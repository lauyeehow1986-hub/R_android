# Durable R session — design

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + `app/` (both halves)

## Problem

Today every `/execute` call runs the submitted code as a throwaway
`Rscript --vanilla` subprocess in a fresh temp dir, so **no state carries
between runs**. There is no incremental / REPL-style workflow: a variable or
data frame built in one Run is gone by the next.

## Goal

A **durable workspace** that survives both app restarts and server restarts.
After running `x <- 5`, a later run can use `x`; after `library(dplyr)`, a
later run still has `dplyr` attached.

### What persists
- Global-environment objects (via `save.image` / `load`).
- The set of user-attached packages (recorded, then replayed with `library()`).

### Explicitly out of scope (not persisted)
- Open connections, external pointers, and other non-serializable objects.
- `options()` and other session settings.
- Multiple concurrent isolated workspaces (see "Future", below).
- A per-run "ephemeral / don't persist" toggle (reset covers the clean-slate need).
- Disk-usage caps / session eviction.

## Chosen approach: server-side sidecar state (Approach A)

Keep each run an isolated `Rscript --vanilla` subprocess with its wall-clock
timeout. Persist state to a per-session directory on a writable volume, and
restore/save it in the wrapper the backend already builds around user code.

Two alternatives were considered and rejected:
- **Long-lived R worker per session** (Rserve / `callr`): true console
  fidelity but breaks the subprocess sandbox, retains memory indefinitely,
  complicates timeouts, and loses in-memory state on a crash. Revisit only if
  per-run image I/O proves too slow.
- **Client-side code replay** (resend all prior code each run): trivial
  backend but re-executes every prior line, so side effects repeat
  (re-downloads, duplicate prints/plots) and non-idempotent code breaks.

## Backend design

### Storage layout
A writable data root, `R_SESSION_DIR` (default `/data/sessions`), one dir per
session:

```
/data/sessions/<sessionId>/
  workspace.RData   # save.image() of globalenv
  attached.txt      # newline-delimited user-attached packages, in order
```

- `sessionId` defaults to `"default"`. It is sanitized to `[A-Za-z0-9_-]+`
  (empty/invalid → `"default"`) as a path-traversal guard for the future
  multi-session case.
- Root filesystem stays `read_only: true`; the session dir is a **named Docker
  volume**. `tmpfs /tmp` remains for per-run scratch (plots, script file).
- The Dockerfile pre-creates `/data/sessions` owned by the non-root `rexec`
  user, so a freshly-created named volume inherits non-root ownership.

### Execution flow (wrapper bookends)
The existing wrapper opens a `png()` device before user code and closes it
after. New shape (paths are inlined as string literals in the generated
script, so no helper variables leak into `globalenv`):

```r
# --- prelude: restore state ---
tryCatch(
  if (file.exists("<ws>")) load("<ws>", envir = globalenv()),
  error = function(e) {                       # corrupt image → set aside, start clean
    try(file.rename("<ws>", "<ws>.bad"), silent = TRUE)
  }
)
if (file.exists("<attached>"))
  invisible(lapply(readLines("<attached>"), function(p)
    if (nzchar(p))
      suppressWarnings(suppressMessages(try(library(p, character.only = TRUE), silent = TRUE)))))

# --- plot device + user code (unchanged) ---
grDevices::png("<plot pattern>", width = 800, height = 600)
<user code>
invisible(grDevices::dev.off())

# --- epilogue: persist (only reached if user code did not error/time out) ---
.saved <- try(save.image("<ws>"), silent = TRUE)   # created after save → not in the image
if (inherits(.saved, "try-error"))
  message("Note: some objects could not be saved; workspace state was not updated.")
writeLines(setdiff(.packages(), <default attached set>), "<attached>")
writeLines(ls(globalenv()), "<run tmp>/objects.txt")
```

`<default attached set>` is the standard startup set
(`base, methods, datasets, utils, grDevices, graphics, stats`), so only
user-added packages are recorded.

### Guarantees this buys
- **Failed/timed-out runs never overwrite state.** The save code is past the
  point an error/timeout aborts the script, so a bad run cannot corrupt the
  workspace.
- **Corrupt or missing state degrades gracefully.** `load` is wrapped; a
  corrupt image is renamed to `workspace.RData.bad` and the run continues with
  an empty workspace. Unavailable recorded packages are skipped.
- **No internal-variable pollution.** Paths are inlined literals and the
  package-replay loop variable is scoped inside an anonymous function, so
  `save.image` captures only real user objects.
- **`save.image` failure is non-fatal.** Non-serializable objects → the save is
  skipped, the run still succeeds, and a one-line note is added to stderr.
- **Concurrency.** Plumber handles requests single-threaded by default, so
  same-session runs are already serialized; no explicit lock is needed for the
  single-session MVP.

### API contract
- `POST /execute`
  - Request gains `sessionId: String?` (null/blank → `"default"`).
  - Response gains `workspaceObjects: List<String>?` — the object names from
    `objects.txt` on a successful run (possibly `[]` for an empty workspace),
    or **`null`** when the run errored/timed out (the epilogue never ran, so
    the workspace is unchanged and the app should leave its summary as-is
    rather than showing "0 objects"). All existing fields (`stdout`, `stderr`,
    `plots`, `error`, `timedOut`) unchanged.
- `POST /reset` (new)
  - Body `{"sessionId": "default"}`; deletes that session's `workspace.RData`
    and `attached.txt`. Returns `{"ok": true}`. Reset of a nonexistent session
    is a no-op success.
  - Guarded by the existing `auth` + `ratelimit` filters via `is_protected()`
    (it mutates state), alongside `/execute`.
- `GET /health` unchanged (open, stateless).

## App design

Single session in the MVP, so there is no session picker; the app always uses
`sessionId = "default"`.

- **Workspace summary**: a chip/row under the output showing
  `Workspace: df, model (2 objects)` from `response.workspaceObjects`; hidden
  when empty. Makes durability tangible.
- **Reset session**: an action (overflow menu / button) → destructive confirm
  dialog → `RExecutionRepository.reset("default")` → on success clears the
  workspace summary and shows a snackbar/toast.
- **State/flow**:
  - `ExecuteModels`: `ExecuteRequest` gains `sessionId`; `ExecuteResponse`
    gains `workspaceObjects`. A `ResetRequest`/`ResetResponse` (or a shared
    session-id request) is added. These must stay field-for-field in sync with
    `plumber.R` (per the existing response-contract note in `CLAUDE.md`).
  - `RExecutionApi` gains `@POST("reset")`; `RExecutionRepository` gains a
    `reset(sessionId)` returning `Result`.
  - `EditorUiState` gains `workspaceObjects: List<String>`; `EditorViewModel`
    updates it only when the response's `workspaceObjects` is non-null (leaving
    it unchanged on an errored/timed-out run), and clears it on reset.
- No new required Settings.

## Ops

- `docker-compose.yml`: add named volume `r_sessions:/data/sessions`, keep
  `read_only: true` and `tmpfs: /tmp`, add `R_SESSION_DIR=/data/sessions`.
- `Dockerfile`: `mkdir -p /data/sessions && chown -R rexec:rexec /data`.

## Testing

- **Backend** (needs the full plumber image; validated via Docker / CI, not the
  Claude sandbox): `x <- 5` then a second run returns `x`; `library(...)` in
  run 1 is attached in run 2; `/reset` then a run shows an empty workspace; a
  run that errors does not clobber prior state.
- **App** (JVM unit tests, extending existing fakes): `EditorViewModel` sets
  `workspaceObjects` after a run and clears it on reset; `reset()`
  success/failure paths; `ExecuteResponse` deserializes the new field with
  older backends omitting it.

## Security notes (extend `backend/README.md`)

- Durable state means arbitrary user code now writes persistent, attacker-
  controllable data to the session volume, and can fill it (no quota — future
  work). Single-user MVP accepts this.
- The future multi-session case makes `sessionId` a trust boundary; the
  `[A-Za-z0-9_-]+` sanitization is the guard and must be preserved.

## Future (not this spec)

- Multiple isolated workspaces keyed by session/user id, with per-session
  storage, eviction, and identity-mapped auth.
- Per-run ephemeral mode.
- Workspace size caps / eviction.
