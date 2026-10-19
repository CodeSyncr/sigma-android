# Nimbus Sigma for Android

A native spreadsheet editor for your Android app, backed by [Sigma](https://nimbusgo.space/cloud/docs/sigma) on Nimbus Cloud. Upload a spreadsheet from your server, open it in the app, and people edit it with formulas, formatting, charts, several sheets and Vani AI. Every change saves back as they type, and your server downloads the edited `.xlsx` or CSV whenever it likes.

It is native throughout: the grid is drawn on a canvas, with Android views around it. The formulas are Sigma's own engine, the same code the web editor runs, so a sheet computes the same on a phone as on the web.

The engine is not in this library. Each session downloads it from Sigma for a sheet opened through the API, checks it, and keeps it in memory only, so the editor works only with a Nimbus Cloud account and a Sigma API key. It runs in Android's sandboxed JavaScript engine (`androidx.javascriptengine`), or in a hidden WebView on devices whose WebView does not offer the sandbox.

**Requirements:** Android 8.0 (API 26) or later, a current Android System WebView, Kotlin coroutines. Built with compileSdk 35 and Java 17.

## Install

The library is on [JitPack](https://jitpack.io/#CodeSyncr/sigma-android). In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and in your module:

```kotlin
dependencies {
    implementation("com.github.CodeSyncr:sigma-android:1.0.0")
}
```

## How it fits together

```
your server ──(sgm_ key)──▶ Nimbus Cloud     upload sheets, mint signed links, download edits
     │
     └──(signed link)──▶ your app ──▶ SigmaSession ──▶ SigmaSheetView     edits save straight back
```

**Billing.** Sigma's API counts each time a sheet is opened in an editor (in your app or embedded on the web), not uploads; your plan includes an allowance each month. Test keys (`sgm_test_`) are for building: their opens are capped and their sheets are deleted after a week, and the editor shows "Test mode".

**Keep the `sgm_` key on your server.** A key compiled into an app can be extracted, and it can read and delete every document on your account. The app only ever needs a link, which expires (fifteen minutes by default; one minute to seven days). Once a link opens a sheet, the editing session lasts eight hours.

On your server (any language; here, the REST API):

```bash
# Upload, or create a blank sheet with {"title": "Budget", "blank": "xlsx"}
curl -X POST "https://nimbusgo.space/api/v1/sigma/documents?mode=edit" \
  -H "Authorization: Bearer $SIGMA_KEY" -F file=@budget.xlsx

# A fresh link for the app
curl -X POST "https://nimbusgo.space/api/v1/sigma/documents/42/link?mode=edit" \
  -H "Authorization: Bearer $SIGMA_KEY"

# The edited file, later
curl "https://nimbusgo.space/api/v1/sigma/documents/42/content" \
  -H "Authorization: Bearer $SIGMA_KEY" -o budget.xlsx
```

## Use

```kotlin
class SheetActivity : ComponentActivity() {
    private lateinit var session: SigmaSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Asked for a link when the sheet opens, and again whenever one expires.
        session = SigmaSession(this) { myApi.sheetLink(id = 42) }
        setContentView(SigmaSheetView(this).apply { open(session) })
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(NonCancellable) { runCatching { session.save() }; session.close() }
    }
}
```

Give the activity `android:windowSoftInputMode="adjustResize"` so the keyboard pushes the sheet up; the view also keeps clear of the keyboard when the window does not resize.

Compose:

```kotlin
AndroidView(factory = { SigmaSheetView(it).apply { open(session) } })
```

Keep the session in a `ViewModel` (or handle configuration changes) so a rotation does not open the sheet again; each open counts against your allowance.

### What people get

- **The grid:** smooth scrolling and flinging, sticky headers, frozen rows and columns, tap to select, round handles to stretch a selection, tap again to type, a formula bar, a row of formula keys above the keyboard, column resizing, a long-press menu (cut, copy, paste, fill, insert and delete rows and columns, sort, notes, freeze), sheet tabs, undo and redo. Hardware keyboards get arrows, Tab, Enter and Ctrl+C/X/V/Z/Y/A/D/R/B/I/U/F.
- **Formatting:** bold, italic, underline, strikethrough, text and fill colours, alignment, number formats, decimals, text size, borders.
- **Data:** find and replace, sort (a header row stays put), conditional formatting, notes.
- **Charts:** column, bar, line, area, scatter, pie and doughnut, bound to their cells. Move, resize, restyle, delete.
- **Vani AI**, when your Nimbus Cloud account is on Pro: people ask, and apply the change it proposes as one undo step. Its use counts against your plan.
- **Share:** the sheet as Excel or CSV through the system share sheet.

### The session

| StateFlow | Meaning |
|---|---|
| `phase` | `Loading`, `Ready`, or `Failed(SigmaException)` |
| `document` | id, title, file name, format (`XLSX`/`CSV`), mode (`EDIT`/`VIEW`) and whether a test key opened it |
| `title` | the sheet's name; change it with `rename()` |
| `saveState` | `Saved`, `Unsaved`, `Saving`, or `Failed` |
| `workbook` | the open `SigmaWorkbook` |
| `aiAvailable` | whether Vani AI is offered |

```kotlin
session.save()                                  // save now, without waiting
val file = session.export(SigmaFormat.XLSX)     // SigmaFile: data + filename
```

When a link has expired or been revoked, the session asks your function for a fresh one. If the editing session ends while someone is working, it gets a fresh link and saves again; nothing typed is lost.

### Driving the sheet from code

`SigmaWorkbook` is the open sheet in Sigma's engine. Its calls suspend: the engine runs in its own process.

```kotlin
val book = session.workbook.value!!
book.set(mapOf(SigmaAddress.parse("A1")!! to "Rent", SigmaAddress.parse("B1")!! to "1200", SigmaAddress.parse("B3")!! to "=SUM(B1:B2)"))
book.value(SigmaAddress.parse("B3")!!)          // SigmaValue.Number(1200.0)
book.applyStyle(SigmaStyle(bold = true, fill = "#fff2cc"), SigmaSelection.parse("A1:B1")!!)
book.addChart(SigmaChart.Kind.COLUMN, data = "A1:B2")
book.undo()
```

Pass a `SigmaGridController` to `open(session, controller)` to read the selection or drive the grid (select, format, sort, insert a chart, open a panel) from your own controls.

## Server-side Kotlin

`SigmaClient` wraps the REST API for Ktor, Spring, tools and prototypes. Do not ship it in an app.

```kotlin
val sigma = SigmaClient(apiKey = System.getenv("SIGMA_KEY"))
val doc = sigma.upload(bytes, "budget.xlsx", mode = SigmaLinkMode.EDIT)
val link = sigma.link(doc.id, lifetimeSeconds = 600)
val edited = sigma.content(doc.id)
```

## Example

[`demo`](demo) is a small app: list your sheets, create one or upload a sample, edit it, share it. Open the project in Android Studio and run the `demo` configuration, then tap **Settings** and enter your Nimbus Cloud address and a Sigma key (`sgm_test_…` from Nimbus Cloud › API & CLI Keys). On the emulator, a cloud on your computer is reachable with `adb reverse tcp:3333 tcp:3333` and `http://localhost:3333`.

The demo keeps the key on the device so it runs on its own. A real app keeps it on its server.

## Tests

`./gradlew :nimbussigma:testDebugUnitTest` runs the unit tests. To open real sheets on a device or emulator against a running cloud:

```bash
./gradlew :nimbussigma:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.cloud=http://localhost:3333 \
  -Pandroid.testInstrumentationRunnerArguments.key=sgm_test_…
```

## License

Proprietary. The SDK may be used only with Nimbus Cloud's Sigma API under your Nimbus Cloud account; see [LICENSE](LICENSE).
