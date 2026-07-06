# Package uninstall — design

**Date:** 2026-07-06
**Status:** Approved (design), pending implementation plan
**Feature area:** `backend/` + `app/`

## Problem

Users can install CRAN packages (`POST /install`, Packages screen) but can't
remove them — the shared library only grows.

## Goal

Uninstall a package from the shared library, from the Packages screen. Mirrors
the install feature.

## Backend — `POST /uninstall`

- Body `{ "package": "<name>" }`, validated with the same regex as install
  (`^[A-Za-z0-9._]+$`); invalid/missing → 400.
- Runs `remove.packages("<pkg>", lib = PKG_LIB)` wrapped in `tryCatch` **in the
  Plumber process** (fast; no subprocess needed) — removing only from the shared
  library `PKG_LIB`, never base/system libraries.
- Response `{ removed, error }` (unboxed JSON), where `removed` = `<pkg>` is no
  longer in `installed.packages(lib.loc = PKG_LIB)` afterward. Uninstalling a
  package that isn't installed there returns `removed:false` with no error
  (harmless no-op — `remove.packages` warns rather than errors when absent, and
  `removed` reflects the real post-state). `error` is set only if the removal
  itself throws.
- No dependency cascade — only the named package is removed (matching R's
  `remove.packages`); a dependent that breaks on the next `library()` is the
  user's responsibility.
- Guarded by the existing `auth` + `ratelimit` filters: `is_protected()` gains
  `/uninstall`.

## App

- **Models** (`PackageModels.kt`): `UninstallRequest(@SerialName("package") packageName)`
  and `UninstallResponse(removed: Boolean = false, error: String? = null)`.
- **API/repository**: `@POST("uninstall")` on `RExecutionApi`;
  `RExecutionRepository.uninstall(packageName): Result<UninstallResponse>`.
- **`PackagesViewModel.uninstall(name)`**: calls the repository; on a `removed`
  success, `refresh()`es the installed list and sets a "Removed <name>." message;
  on failure (or `removed:false`), sets an error message. Reuses the existing
  `installing`/`message`/`isError` state fields (or an equivalent) — no new
  long-running-state juggling beyond what install already has.
- **`PackagesScreen`**: each installed-package row gets a **delete `IconButton`**
  → a confirm dialog ("Uninstall `<pkg>`?") → `viewModel.uninstall(name)`.

## Testing

- **Backend** (`test-install.R` or a new `test-uninstall.R`, testthat + httr2):
  - install `praise` (or a package already present in the test lib), then
    `POST /uninstall` → `removed:true` and `praise` no longer in `GET /packages`.
  - `/uninstall` of a not-installed package → 200, `removed:false`.
  - `/uninstall` with an invalid name → 400.
  - `/uninstall` is auth-protected (401 without the key when `R_API_KEY` is set).
- **App** (`PackagesViewModelTest`): `uninstall` success refreshes the list and
  clears any error; failure (`removed:false` or thrown) surfaces the error and
  leaves the list unchanged. Fake API extended with `uninstall`.
- **Docs**: `backend/README.md` (the `/uninstall` endpoint) and `CLAUDE.md`
  (response contract: `UninstallRequest`/`UninstallResponse`, and `is_protected`
  now covers `/uninstall`).

## Out of scope

- Bulk uninstall (remove several at once).
- Dependency cascade / reverse-dependency checks.
- Uninstalling base or pre-baked (image) packages — only the writable
  `R_PKG_LIB` is touched, so those are unaffected by design.
