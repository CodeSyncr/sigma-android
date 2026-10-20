# Changelog

## 1.0.2

- **Vani AI looks as it does in Website Studio.** The launcher is Studio's pill: Nimbo, the mascot, waving, with "Ask Vani" (Nimbo alone on a phone). It floats over the grid in place of the format bar pill; drag it anywhere and it snaps to the nearer side, and the app remembers where. While Vani works Nimbo thinks and a ring pulses, even with the panel closed. The panel is Studio's too: Nimbo and "Vani" in the header, his greeting and ways to start, ink and white bubbles, Nimbo waiting for the answer, the composer, and New chat. Nimbo is drawn natively, so nothing extra is downloaded.
- **Formula help:** typing a function name offers the functions it could be; inside a call, the syntax shows with the current argument highlighted. **Insert function** (the ƒx key and button) lists every function by category, searchable. The list comes from Sigma's engine, so it is always what Sigma computes. `SigmaWorkbook.functions()` returns it.
- **Pinch to zoom**, 50% to 200% (Ctrl + / − / 0 on a keyboard).
- **Fill by dragging:** Fill › Drag to fill turns the corner handle into a fill handle; series continue and formulas shift.
- **Row heights:** drag the edge between row numbers.
- **Freeze presets:** 1 row, 2 rows, 1 column, or up to the selected cell.
- **Clear formatting**, **AutoSum**, and **Paste values only** (`SigmaWorkbook.pasteValues`).
- A **⋮ More** button pinned at the end of the format bar: Freeze, Rows, Columns, Fill, Sort, Paste values only and Note, always one tap away.
- **Sheet tabs:** duplicate, and move left or right (`duplicateSheet`, `moveSheet`).
- A cleaner look: Material icons in the format bar, formula bar and tabs; panels open as bottom sheets; a shorter cell menu.
- Fixed: selection handles showed over a frozen pane once their cell had scrolled under it.
- Needs a Sigma server with the engine of October 2026 or later (for the function list, duplicate, move and paste values).

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
