package space.nimbusgo.sigma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTests {
    @Test fun readsA1() {
        assertEquals(SigmaAddress(0, 0), SigmaAddress.parse("A1"))
        assertEquals(SigmaAddress(11, 1), SigmaAddress.parse("\$B\$12"))
        assertEquals(SigmaAddress(0, 26), SigmaAddress.parse("aa1"))
        assertNull(SigmaAddress.parse("1A"))
        assertNull(SigmaAddress.parse("A"))
    }

    @Test fun namesColumns() {
        assertEquals("A", SigmaAddress.columnName(0))
        assertEquals("Z", SigmaAddress.columnName(25))
        assertEquals("AA", SigmaAddress.columnName(26))
        assertEquals("XFD", SigmaAddress.columnName(16383))
        assertEquals("C7", SigmaAddress(6, 2).toString())
    }

    @Test fun selections() {
        val s = SigmaSelection.parse("C5:A1")!!
        assertEquals(0, s.top); assertEquals(4, s.bottom); assertEquals(0, s.left); assertEquals(2, s.right)
        assertEquals("A1:C5", s.toString())
        assertTrue(s.contains(SigmaAddress(2, 1)))
    }
}

class SessionTests {
    @Test fun readsTheEditorRedirect() {
        val a = SigmaSession.accessFrom("https://sigma.example.com/embed/42?t=v1.42.7.edit.1900000000.abc")!!
        assertEquals("https://sigma.example.com", a.base)
        assertEquals(42, a.id)
        assertEquals("v1.42.7.edit.1900000000.abc", a.token)
    }

    @Test fun keepsAPathPrefixAndPort() {
        val a = SigmaSession.accessFrom("http://10.0.2.2:3344/sheets/embed/7?x=1&t=tok%2Bx")!!
        assertEquals("http://10.0.2.2:3344/sheets", a.base)
        assertEquals("tok+x", a.token)
    }

    @Test fun refusesOtherPlaces() {
        assertNull(SigmaSession.accessFrom("https://sigma.example.com/embed/42"))
        assertNull(SigmaSession.accessFrom("https://sigma.example.com/editor/42?t=x"))
    }

    @Test fun hashes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SigmaSession.sha256Hex(ByteArray(0)))
    }
}

class ErrorTests {
    @Test fun mapsStatuses() {
        assertTrue(SigmaException.from(401, "x") is SigmaException.Unauthorized)
        assertTrue(SigmaException.from(402, "x") is SigmaException.QuotaExceeded)
        assertTrue(SigmaException.from(403, "x") is SigmaException.LinkInvalid)
        assertTrue(SigmaException.from(404, "x") is SigmaException.NotFound)
        assertTrue(SigmaException.from(410, "x") is SigmaException.LinkExpired)
        assertTrue(SigmaException.from(0, "x") is SigmaException.Network)
        assertEquals("Nimbus Cloud answered 500.", SigmaException.from(500, "").message)
    }

    @Test fun formatsValues() {
        assertEquals("1200", SigmaValue.of(1200.0).toString())
        assertEquals("1.5", SigmaValue.of(1.5).toString())
        assertEquals("TRUE", SigmaValue.of(true).toString())
        assertEquals(SigmaValue.Empty, SigmaValue.of(""))
    }

    @Test fun styleRoundTrips() {
        val s = SigmaStyle(bold = true, fill = "#fff2cc", align = SigmaStyle.Align.CENTER, numberFormat = SigmaStyle.NumberFormat.PERCENT)
        assertEquals(s, SigmaStyle.from(s.toJson()))
        val json = s.toJson()
        assertEquals(setOf("b", "al", "bg", "nf"), json.keys().asSequence().toSet())
        assertEquals("pct", json.getString("nf"))
    }
}
