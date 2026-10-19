# Changelog

## 1.0.1

- Builds against Kotlin 1.9 and later. 1.0.0 was compiled for Kotlin 2.2, which apps on an older Kotlin (React Native apps, often) could not read.

## 1.0.0

The first release: Sigma's native spreadsheet editor for Android, in step with NimbusSigma 2.1.0 for iOS.

- `SigmaSession` opens a sheet from a signed link your server mints and saves every change back (the file in its own format, and Sigma's workbook), getting a fresh link when one expires or the editing session ends.
- `SigmaSheetView`: the grid drawn natively with frozen panes, selection handles, in-cell and formula-bar editing, formula keys above the keyboard, column resizing, a long-press menu and sheet tabs; a format bar (styles, colours, alignment, number formats, borders, text size); find and replace; sort that keeps a header row; conditional formatting; notes; charts; Vani AI; sharing as Excel or CSV. Hardware keyboards get arrows, Tab, Enter and Ctrl+C/X/V/Z/Y/A/D/R/B/I/U/F.
- `SigmaWorkbook`: the open sheet in Sigma's engine, for reading and changing cells, styles, charts and sheets from code.
- `SigmaGridController` drives the grid from your own controls.
- `SigmaClient` for server-side Kotlin: upload, blank sheets, links, content, workbook JSON, delete, usage.
- The engine is not in the library: a session downloads it from Sigma for a sheet opened through the API, verifies its SHA-256 and keeps it in memory only. It runs in Android's sandboxed JavaScript engine, or a hidden WebView where that is missing.
- A sheet opened with a test-mode key shows a "Test mode" banner; an open refused for an account over its allowance fails with `SigmaException.QuotaExceeded`.
