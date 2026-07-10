# Code assist — autocomplete + R help — design

**Date:** 2026-07-10
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + `app/`

## Problem

The editor is a plain text field with syntax highlighting and a quick-insert
operator bar. There is no completion of function/object names and no way to read
R help (`?fn`) without leaving the app. Both are table stakes for writing R on a
phone, where typing long identifiers is slow and you can't fall back to a console
`?topic`.

## Goal

1. **Autocomplete** — as you type an identifier, a horizontal suggestion strip
   above the keyboard offers matching R symbols; tapping one inserts it. Symbols
   are session-relevant (base R, your workspace objects, your project's installed
   packages, and packages you've `library()`-d).
2. **R help** — open a function's help page (rendered text) in a bottom sheet,
   reached from a completion chip, from the identifier under the cursor, and from
   a help-search field.

## Decisions (from brainstorming)

- **Completion source: hybrid.** Suggestions are computed **entirely app-side**
  (instant, no per-keystroke network) against a *symbol set* that unions:
  (a) a baked-in list of common base-R function/keyword names, (b) the session's
  `workspaceObjects` (already returned by `/execute`), (c) the active project's
  installed-package names (already available via `/packages`), and (d) a **cached
  symbol index** fetched *occasionally* from a new backend endpoint. The backend
  index enriches; it is never on the keystroke hot path.
- **Completion UI: suggestion strip.** A horizontal, scrollable row of chips just
  above the operator bar — thumb-friendly, consistent with the existing
  quick-insert bar. No floating cursor-anchored popup.
- **Help rendering: formatted text sheet.** The backend renders the topic to
  plain text with `tools::Rd2txt` (the same content as console `?fn`); the app
  shows it in a scrollable monospace bottom sheet. No WebView.
- **Help trigger: contextual + search.** Long-press a completion chip; a `? <token>`
  action in the strip for the identifier under the cursor; and a `?` help-search
  entry point in the editor top bar for topics not yet typed.
- **`/symbols` scope: base + attached packages** (what a live R session sees), not
  every installed package's exports. Smaller list, lighter subprocess.
- **Completion insert: plain identifier**, no auto-`()`. This deliberately avoids
  the smart-backspace / undo conflict that auto-inserted parentheses create.

## Architecture

Two independent capabilities layered onto the existing editor. Autocomplete is a
pure, app-side function over a symbol set. Help is an on-demand backend call
rendered in a sheet. Neither adds load to the `/execute` path.

## Backend (`plumber.R`)

Two new endpoints. Both are **read-only** (no session-state mutation) and **spawn
an isolated `Rscript --vanilla`** with the session's rlib prepended to
`.libPaths()` (exactly as `/execute` and `/install` do). Because they spawn a
subprocess, both are added to `is_protected()` (auth + rate limit apply when
configured), matching the stance for other subprocess-spawning endpoints.

### `GET /symbols?sessionId=`

The hybrid index. Occasional, cached client-side.

- Sanitizes `sessionId` (default `"default"`), reads the session's attached-package
  list from `paths$attached` (the same file `/execute` writes after each run).
- Runs a fixed script that collects exported names for **base + recommended
  packages** plus each **attached** package, via
  `getNamespaceExports(pkg)` wrapped in `try()` (a package that fails to load
  contributes nothing). Non-syntactic names (those not matching
  `^[A-Za-z.][A-Za-z0-9._]*$`) are dropped.
- Returns `{ "symbols": [<name>, ...] }` — sorted, de-duplicated, capped at
  `R_SYMBOLS_MAX` (default 5000). On any failure returns `{ "symbols": [] }`
  (the app still has its baked list + workspace + package names).

Rationale for attached-only: it mirrors what R's own completion sees in a live
session, keeps the payload bounded, and stays cheap. Exports of installed-but-not-
attached packages are reachable in code via `pkg::name` anyway.

### `POST /help`

On-demand help rendering.

- Request `{ "topic": <string>, "sessionId": <string?> }`. `topic` is validated
  against `^[A-Za-z0-9._]+$`; an invalid/missing topic returns HTTP 400 with
  `{ topic, packageName: null, text: "", found: false }`.
- Runs a fixed script with the session rlib on `.libPaths()` that resolves the
  topic across base + the session's attached/installed packages (via
  `utils::help((topic))` / the Rd db) and renders the first match with
  `tools::Rd2txt` into a text file, which is read back.
- Response `{ "topic", "packageName", "text", "found" }`. When no topic matches:
  `found = false`, `text = ""`, `packageName = null`.

Config constants live next to the existing `Sys.getenv` block: `R_SYMBOLS_MAX`
(default 5000). Help uses the existing `R_EXECUTION_TIMEOUT_SECONDS` for its
subprocess timeout.

## App

### Models (`data/model/AssistModels.kt`)

kotlinx.serialization, unboxed-JSON compatible:

```kotlin
@Serializable
data class SymbolsResponse(val symbols: List<String> = emptyList())

@Serializable
data class HelpRequest(val topic: String, val sessionId: String? = null)

@Serializable
data class HelpResponse(
    val topic: String = "",
    val packageName: String? = null,
    val text: String = "",
    val found: Boolean = false,
)
```

### API (`data/network/RExecutionApi.kt`)

```kotlin
@GET("symbols")
suspend fun symbols(@Query("sessionId") sessionId: String): SymbolsResponse

@POST("help")
suspend fun help(@Body request: HelpRequest): HelpResponse
```

### Repository (`data/RExecutionRepository.kt`)

`listSymbols(sessionId: String = DEFAULT_SESSION_ID): Result<List<String>>` and
`help(topic: String, sessionId: String = DEFAULT_SESSION_ID): Result<HelpResponse>`,
following the existing `Result`-returning suspend pattern.

### Completion engine (`ui/editor/completion/`, pure, unit-tested)

No Compose/Android types.

- **`CompletionContext(text: String, cursor: Int)`** — the input abstraction. The
  MVP reads only the identifier token ending at `cursor`, but shaping the input as
  a context (not a bare prefix string) is the seam a future call-stack analyzer
  extends without touching call sites. **Landmine note:** argument-name completion
  and signatures need to know which call the cursor is inside — that is a
  `CallContext` extension of this type, plus a warm R worker (see Future).
- **`CompletionOps.tokenRange(ctx): IntRange?`** — the R identifier token
  (`[A-Za-z.][A-Za-z0-9._]*`) ending at the cursor, or null if none.
- **`CompletionOps.suggest(prefix: String, symbols: List<String>, limit: Int): List<String>`**
  — case-insensitive prefix match, ranked (case-sensitive exact-prefix first, then
  alphabetical), de-duplicated, capped at `limit`. Empty/blank prefix → empty list.
- **`BaseRSymbols`** — the baked list of common base-R function + keyword names
  (a curated constant, ~200–400 entries; not exhaustive — `/symbols` enriches).

### EditorTextOps

Add **`replaceRange(value: TextFieldValue, range: IntRange, replacement: String): TextFieldValue`**
— replaces the token range with the chosen symbol and places the cursor at the end
of the inserted text. Pure, unit-tested, alongside the existing `insertAt`.

### EditorViewModel

- `EditorUiState` gains `completionSymbols: List<String>` and `help: HelpState?`.
- **`HelpState`** = `Loading(topic)` | `Loaded(HelpResponse)` | `NotFound(topic)` |
  `Error(topic, message)`. A single frame for the MVP. **Landmine note:** a future
  cross-topic-links feature wraps this in a back-stack; `/help` already returns
  `topic` + `packageName` as stable keys for that.
- **Symbol-set assembly:** `completionSymbols` = `BaseRSymbols` ∪ `workspaceObjects`
  ∪ active project's installed-package names ∪ the cached `/symbols` index,
  de-duplicated.
- **`refreshSymbols()`** calls `repository.listSymbols(session)` and merges the
  result into the cached index. Triggered on project open, after a **successful**
  run, and on return from the Packages screen (covers install *and* uninstall) —
  so the in-memory cache never goes stale within a session.
- **`showHelp(topic)`** sets `help = Loading`, calls `repository.help(topic, session)`,
  then transitions to `Loaded`/`NotFound`/`Error`. **`dismissHelp()`** clears it.

### EditorScreen UI

- **Suggestion strip:** a `LazyRow` of chips rendered just above the operator bar.
  Suggestions are derived **off the main thread with a debounce**: a `snapshotFlow`
  over the editor `TextFieldValue` → `debounce` → map on `Dispatchers.Default`
  (`CompletionOps.tokenRange` + `suggest` against `uiState.completionSymbols`) →
  collected back into a local state. This keeps typing jank-proof regardless of
  symbol-set size and is exactly the seam a future fuzzy matcher plugs into. The
  strip is visible only when the current token is non-empty and has matches.
  - **Tap a chip** → `EditorTextOps.replaceRange` with the symbol (plain
    identifier), sync back through the existing `code`/`TextFieldValue` path.
  - **Long-press a chip** → `showHelp(symbol)`.
- **Help for the token under the cursor:** when the caret is on an identifier, the
  strip shows a leading `? <token>` action; tapping → `showHelp(token)`. (Avoids
  fighting Compose's text-selection toolbar.)
- **Help search:** a `?` icon in the editor top bar opens a small search dialog
  (a single `OutlinedTextField` + "Open"); submitting → `showHelp(query)`.
- **Help sheet:** a `ModalBottomSheet` bound to `uiState.help`:
  - `Loading` → a centered spinner with the topic.
  - `Loaded` → title `"<topic> {<packageName>}"` (package omitted if null), a
    scrollable monospace `SelectionContainer` body of `text`, a close button.
  - `NotFound` → `"No help found for '<topic>'."`
  - `Error` → the error message with a retry affordance.
  Mirrors the existing history/saved-scripts bottom sheets.

Symbol index and help results are held in memory (VM only), not persisted across
app restarts. The baked base list + workspace objects + package names give offline
value without persistence. **Landmine note:** persisting the index later needs a
version/checksum keyed to the session's installed-package set, or the cache can
point at functions a package update has removed or changed.

## Response contract (kept in sync by hand)

New in `data/model/AssistModels.kt`, matching `plumber.R` field-for-field, and
documented in `CLAUDE.md`'s response-contract section:

- `GET /symbols?sessionId=` → `SymbolsResponse { symbols: [String] }`.
- `POST /help` → request `HelpRequest { topic, sessionId? }`; response
  `HelpResponse { topic, packageName?, text, found }`.

Both must be emitted as **unboxed** JSON (per `run.R`'s serializer) so length-1
`symbols` and the scalar `found`/`text`/`topic`/`packageName` fields deserialize.
`/symbols` and `/help` join `/execute`, `/reset`, `/install`, `/uninstall`,
`/import-legacy` in `is_protected()`.

## Testing

### Backend (`backend/tests/test-assist.R`, testthat + httr2)

- `/symbols` for a fresh session includes base names (`"mean" %in% symbols`,
  `"data.frame" %in% symbols`).
- After a run that does `library(<installed pkg>)` in a session, that package's
  exports appear in `/symbols` for that session; a different session's `/symbols`
  does not include them (session scoping). Gate the install on network
  availability with `skip_if_not`.
- `/symbols` payload is de-duplicated and within the cap.
- `/help` for `mean` → `found == true`, `packageName == "base"`, `text` contains
  `"Usage"`.
- `/help` for a nonexistent topic (`"zzznotarealfn"`) → `found == false`,
  `text == ""`.
- `/help` for an invalid topic (`"a b"`) → HTTP 400, `found == false`.
- Helper additions in `helper-server.R`: `get_symbols(server, session_id, key)`,
  `post_help(server, topic, session_id, key)`.

### App (JVM unit tests)

- **`CompletionOpsTest`**: `tokenRange` finds the token at end-of-token, mid-word,
  after a `.`, at a boundary (space/paren/newline → null), and at string start;
  `suggest` ranks exact-prefix before alphabetical, is case-insensitive, caps at
  `limit`, de-dups, and returns empty for a blank prefix.
- **`EditorTextOpsTest`** (extend): `replaceRange` swaps the token and positions
  the cursor after the inserted text.
- **`RExecutionRepositoryTest`** (extend): `listSymbols`/`help` pass `sessionId`
  through; a fake API asserts the received values.
- **`EditorViewModelTest`** (extend): symbol-set assembly includes workspace
  objects + package names + baked base + cached index; `refreshSymbols` merges the
  fake API's symbols into `completionSymbols`; `showHelp` drives
  `Loading → Loaded`/`NotFound`/`Error`; `dismissHelp` clears it.

### Docs

- `backend/README.md`: `/symbols` and `/help` (params, response, protected,
  subprocess + timeout).
- `CLAUDE.md`: the two endpoints, the new models, the completion/help app
  components, and the response-contract additions.
- Root `README.md`: a "code assist (autocomplete + R help)" feature line.

## Out of scope (YAGNI) — and the landmines for when they return

Marked seams above keep these cheap to add later; none are built now.

- **Argument-name completion / live signature hints.** Need call-stack awareness
  (which call the cursor is inside) against currently-loaded packages — i.e. a
  `CallContext` extension of `CompletionContext` **and** a warm per-session R
  worker (a cold `Rscript` per keystroke can't do this). See Future.
- **Fuzzy / typo-tolerant matching.** Must stay on the background `Flow` the MVP
  already establishes; do not move symbol filtering onto the UI thread.
- **Auto-inserting `()` / argument snippets.** Introduces a smart-backspace / undo
  conflict (does backspace delete the `)` or the snippet?). Deferring auto-`()`
  is why the MVP inserts a plain identifier.
- **Cross-topic links / navigation in help.** Makes `/help` recursive; needs a UI
  back-stack. `/help` already returns `topic` + `packageName` as stable keys.
- **Persisting the symbol index across restarts.** Needs a version/checksum keyed
  to the session's package set to avoid stale/misleading suggestions after a
  package update.

## Future roadmap (named, not scheduled)

The IDE-tier features above converge on one enabling investment: a **warm,
persistent per-session R worker** replacing the cold `Rscript --vanilla`-per-call
model for interactive queries. That worker is the prerequisite for backend-accurate
per-request completion, argument hints, and live signatures — and it sharpens the
existing multi-tenant-isolation gap, so it warrants its own brainstorm/spec rather
than being folded into this MVP. Suggested sequencing:

> **This feature (hybrid completion + text help)** → **warm per-session R worker**
> → **signature / argument hints ("LSP-lite")** on top of the worker.
