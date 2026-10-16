# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-10-16

### Added
- JSON folding in the detail screen: an arrow left of every multi-line object or array folds it
  to one line (`{ … }  4 keys`), and a header button collapses or expands them all. Key search
  opens the folds around a hit.
- Multipart form uploads now record their parts: one `name: value` line per field, file parts
  described by name, type and size. File contents are still never read.
- The request Body header names the body kind: `form-data`, `x-www-form-urlencoded`,
  `raw (JSON)` and so on.

### Changed
- The copy button now copies `[CODE] [METHOD] url`, the request headers, `[Body] <kind>` with the
  request body (form fields decoded one per line), then the response. When pretty JSON is too
  large for the clipboard it falls back to the raw JSON.

### Fixed
- A JSON body cut at `maxContentLength` was shown as one unreadable raw block even in Pretty
  mode. It is now pretty-printed up to the last complete value, and can be folded.

## [0.1.1] - 2026-09-27

### Added
- Copy button in the detail bar: copies `[METHOD] url`, the request headers and the
  pretty-printed response body as one block.
- Key search for JSON bodies: the magnifier next to Copy opens a find bar under the nav bar that
  highlights matching key names, with a hit count and previous/next.
- Scroll-to-top button, shown once the tabs have scrolled out of view.

### Changed
- The detail bar's Share button is now that Copy button; Share as Text File is still in the
  More menu.

### Fixed
- An Activity passed as `context` is no longer kept alive for the client's lifetime: Ktor holds
  the plugin config as long as the client exists, so the config now stores only the application
  context (`library` and `library-no-op`).

## [0.1.0] - first tag

Targets Ktor 3.6 / Kotlin 2.4.20 / AGP 9.4.

### Added
- `NetworkInspector` Ktor 3 client plugin (any engine) built on `createClientPlugin`.
- No Context needed at install time: the application context is captured through androidx.startup,
  so a top-level `val client` works.
- Ktor 3 saved bodies are read from their own copy and stay re-readable; streaming bodies are teed.
- Bounded body capture: `maxContentLength` cap with truncation marker; the app always gets the
  full, unchanged body. Streaming, multipart and binary bodies are recorded as metadata only.
- `skipHosts`, `skipPathPrefixes`, `skipWhen`, evaluated before any body is read.
- Opt-in redaction: `redactHeaders`, `redactQueryParams` (also applied to form bodies) and
  `redactBodyFields` (JSON fields at any depth), applied at capture time. Nothing is redacted by
  default, like Chucker.
- In-memory ring buffer (`maxTransactions`, default 100) exposed as a `StateFlow`.
- Compose inspector in an Apple-style design (monochrome UI, iOS status colors, Xcode JSON
  colors, light and dark): list with URL search and 2xx/3xx/4xx/5xx/Failed filters; detail with
  Overview / Request / Response tabs; exact-value pretty JSON with syntax colors and a raw toggle;
  two-pane layout on large screens; Copy button next to the Pretty switch.
- Copy as cURL, share as cURL, copy body, share as text file (single or all).
- Notification with the latest requests and the Ktorch logo, skipped silently without
  `POST_NOTIFICATIONS`.
- Optional `logger` sink (e.g. Timber).
- `library-no-op` with an identical public API, checked by `./gradlew check`.
- `library-room`: opt-in disk persistence (`persistToDisk = true`) in `noBackupFilesDir`, excluded
  from backups; history is restored at app start, and requests cut off by a process death show as
  "Interrupted".
- Clipboard copies are flagged sensitive (Android 13+).
