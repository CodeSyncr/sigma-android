package space.nimbusgo.sigma

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.nimbusgo.sigma.engine.JsRuntime
import space.nimbusgo.sigma.engine.WebViewRuntime

/**
 * Opens real sheets against a running cloud and Sigma. Pass
 * `-Pandroid.testInstrumentationRunnerArguments.cloud=http://localhost:3333`
 * and `…key=sgm_test_…` (with `adb reverse` for both ports); skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class SessionEndToEndTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cloud = args.getString("cloud") ?: ""
    private val key = args.getString("key") ?: ""

    private fun client(): SigmaClient {
        assumeTrue("needs cloud and key arguments", cloud.isNotEmpty() && key.isNotEmpty())
        return SigmaClient(key, cloud)
    }

    private suspend fun ready(session: SigmaSession): SigmaWorkbook {
        session.start()
        val phase = withTimeout(60_000) { session.phase.first { it !is SigmaSession.Phase.Loading } }
        if (phase is SigmaSession.Phase.Failed) throw phase.error
        return session.workbook.value!!
    }

    @Test fun opensEditsAndSavesACsv() = runBlocking {
        val sigma = client()
        val doc = sigma.upload("Item,Cost\nRent,1200\nFood,300\n".toByteArray(), "budget.csv", title = "Android budget")
        val session = SigmaSession(context) { sigma.link(doc.id).url }
        try {
            val book = ready(session)
            assertTrue(session.document.value!!.test)
            assertEquals(SigmaFormat.CSV, session.document.value!!.format)
            assertEquals(SigmaValue.Number(1200.0), book.value(SigmaAddress.parse("B2")!!))

            book.set("=SUM(B2:B3)", SigmaAddress.parse("B4")!!)
            assertEquals("1500", book.value(SigmaAddress.parse("B4")!!).shown())
            book.applyStyle(SigmaStyle(bold = true), SigmaSelection.parse("A1:B1")!!)
            assertEquals(true, book.style(SigmaAddress.parse("A1")!!).bold)
            val shown = book.cells(0..3, 0..1)
            assertTrue(shown.any { it.row == 3 && it.column == 1 && it.text == "1500" && it.isNumber })

            // Sort the rows above the total, cheapest first; the total follows its cells.
            book.sort(SigmaSelection.parse("A2:B3")!!, byColumn = 1, ascending = true)
            assertEquals("Food", book.input(SigmaAddress(1, 0)))
            assertEquals("1500", book.value(SigmaAddress.parse("B4")!!).shown())

            session.save()
            assertEquals(SigmaSession.SaveState.Saved, session.saveState.value)
            val csv = sigma.content(doc.id).toString(Charsets.UTF_8)
            assertTrue(csv, csv.contains("1500"))
            assertTrue(sigma.workbook(doc.id).contains("SUM(B2:B3)"))

            val xlsx = session.export(SigmaFormat.XLSX)
            assertEquals("Android budget.xlsx", xlsx.filename)
            assertEquals('P'.code.toByte(), xlsx.data[0])
        } finally {
            session.close()
            sigma.delete(doc.id)
        }
    }

    @Test fun reopensFromSigmasJsonAndAutosaves() = runBlocking {
        val sigma = client()
        val doc = sigma.createBlank("Android blank")
        val first = SigmaSession(context) { sigma.link(doc.id).url }
        try {
            val book = ready(first)
            first.saveDelayMillis = 200
            book.set(mapOf(SigmaAddress(0, 0) to "Score", SigmaAddress(1, 0) to "7", SigmaAddress(2, 0) to "=A2*6"))
            book.addSheet()
            withTimeout(20_000) { first.saveState.first { it == SigmaSession.SaveState.Saved && first.lastSaved.value != null } }
        } finally {
            first.close()
        }
        val again = SigmaSession(context) { sigma.link(doc.id).url }
        try {
            val book = ready(again)
            assertEquals(2, book.sheets.value.size)
            assertEquals("42", book.value(SigmaAddress(2, 0), sheet = 0).shown())
        } finally {
            again.close()
            sigma.delete(doc.id)
        }
    }

    @Test fun functionsSheetsAndPasteValues() = runBlocking {
        val sigma = client()
        val doc = sigma.createBlank("Android tools")
        val session = SigmaSession(context) { sigma.link(doc.id).url }
        try {
            val book = ready(session)
            // The catalogue comes from the engine itself.
            val fns = book.functions()
            assertTrue(fns.size >= 80)
            assertEquals("SUMIF(range, criterion, [sum_range])", fns.first { it.name == "SUMIF" }.syntax)

            book.set(mapOf(SigmaAddress(0, 0) to "2", SigmaAddress(1, 0) to "=A1*21"))
            book.applyStyle(SigmaStyle(bold = true), SigmaSelection.parse("C1")!!)
            val clip = book.copy(SigmaSelection.parse("A2")!!)
            book.pasteValues(clip, SigmaAddress(0, 2))
            assertEquals("42", book.input(SigmaAddress(0, 2)))
            assertEquals(true, book.style(SigmaAddress(0, 2)).bold)
            // Whole numbers stay whole: "1200", never "1200.0".
            book.set(mapOf(SigmaAddress(0, 4) to "Cost", SigmaAddress(1, 4) to "1200", SigmaAddress(2, 4) to "340", SigmaAddress(3, 4) to "=SUM(E2:E3)"))
            val numbers = book.copy(SigmaSelection.parse("E1:E4")!!)
            assertEquals("Cost\n1200\n340\n1540", numbers.text)
            book.pasteValues(numbers, SigmaAddress(0, 6))
            assertEquals(listOf("Cost", "1200", "340", "1540"), (0..3).map { book.input(SigmaAddress(it, 6)) })

            val copy = book.duplicateSheet(0)
            assertEquals(1, copy)
            assertEquals(listOf("Sheet1", "Sheet1 copy"), book.sheets.value.map { it.name })
            assertEquals("42", book.value(SigmaAddress(1, 0), sheet = 1).shown())
            book.moveSheet(1, 0)
            assertEquals(listOf("Sheet1 copy", "Sheet1"), book.sheets.value.map { it.name })
        } finally {
            session.close()
            sigma.delete(doc.id)
        }
    }

    @Test fun refusesABadLink() = runBlocking {
        client()
        val session = SigmaSession(context) { "$cloud/v/sigma/1?sig=forged" }
        session.start()
        val phase = withTimeout(30_000) { session.phase.first { it !is SigmaSession.Phase.Loading } }
        assertTrue(phase.toString(), phase is SigmaSession.Phase.Failed)
        session.close()
    }

    @Test fun viewLinksCannotSave() = runBlocking {
        val sigma = client()
        val doc = sigma.upload("a,b\n1,2\n".toByteArray(), "v.csv", mode = SigmaLinkMode.VIEW)
        val session = SigmaSession(context) { sigma.link(doc.id, SigmaLinkMode.VIEW).url }
        try {
            ready(session)
            assertEquals(SigmaLinkMode.VIEW, session.document.value!!.mode)
            val failed = runCatching { session.save() }.exceptionOrNull()
            assertTrue(failed is SigmaException.Editor)
        } finally {
            session.close()
            sigma.delete(doc.id)
        }
    }

    /** The fallback runtime, used where the sandbox is missing, runs the same engine. */
    @Test fun webViewRuntimeRunsTheEngine() = runBlocking {
        val sigma = client()
        val doc = sigma.upload("x\n2\n".toByteArray(), "w.csv")
        val session = SigmaSession(context) { sigma.link(doc.id).url }
        try {
            ready(session)
            val source = EngineCache.cachedSourceForTests()!!
            val rt: JsRuntime = WebViewRuntime.create(context)
            try {
                rt.evaluate(source + "\n;0")
                assertTrue(rt.evaluate("SigmaCore.version").startsWith("sigma@"))
                val h = rt.evaluate("SigmaCore.create(null)")
                rt.evaluate("SigmaCore.call($h, 'setCell', [0, 0, 0, '=2+3'])")
                assertEquals("5", rt.evaluate("SigmaCore.value($h, 0, 0, 0)"))
                assertTrue(rt.evaluate("SigmaCore.exportFile($h, 'xlsx', 0)").length > 100)
            } finally {
                rt.close()
            }
        } finally {
            session.close()
            sigma.delete(doc.id)
        }
    }
}
