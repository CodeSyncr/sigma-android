package space.nimbusgo.sigma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.nimbusgo.sigma.grid.FormulaHint

class FormulaHintTests {
    private val fns = listOf(
        SigmaFunction("SUM", "Math", "SUM(number1, [number2, …])", "Adds numbers and ranges."),
        SigmaFunction("SUMIF", "Math", "SUMIF(range, criterion, [sum_range])", "Adds the cells that meet a condition."),
        SigmaFunction("SUMIFS", "Math", "SUMIFS(sum_range, range1, criterion1, [range2, criterion2, …])", ""),
        SigmaFunction("IF", "Logical", "IF(condition, value_if_true, [value_if_false])", ""),
        SigmaFunction("ROUND", "Math", "ROUND(number, [places])", ""),
    )

    @Test fun suggestsFromAHalfTypedName() {
        val h = FormulaHint.of("=SU", fns)
        assertTrue(h is FormulaHint.Suggest)
        assertEquals(listOf("SUM", "SUMIF", "SUMIFS"), (h as FormulaHint.Suggest).matches.map { it.name })
        assertEquals("SU", h.prefix)
    }

    @Test fun suggestsAfterAnOperatorAndInLowerCase() {
        val h = FormulaHint.of("=A1+ro", fns) as FormulaHint.Suggest
        assertEquals(listOf("ROUND"), h.matches.map { it.name })
    }

    @Test fun nothingOutsideAFormula() {
        assertEquals(FormulaHint.None, FormulaHint.of("SU", fns))
        assertEquals(FormulaHint.None, FormulaHint.of("=", fns))
    }

    @Test fun argumentHintsInsideACall() {
        assertEquals(FormulaHint.Arguments(fns[1], 0), FormulaHint.of("=SUMIF(", fns))
        assertEquals(FormulaHint.Arguments(fns[1], 1), FormulaHint.of("=SUMIF(A1:A9, ", fns))
        assertEquals(FormulaHint.Arguments(fns[1], 2), FormulaHint.of("=sumif(A1:A9,\">5\",", fns))
    }

    @Test fun innermostCallWins() {
        assertEquals(FormulaHint.Arguments(fns[4], 1), FormulaHint.of("=IF(ROUND(B2, ", fns))
        assertEquals(FormulaHint.Arguments(fns[3], 1), FormulaHint.of("=IF(ROUND(B2, 1), ", fns))
    }

    @Test fun commasInTextDoNotCount() {
        assertEquals(FormulaHint.Arguments(fns[3], 0), FormulaHint.of("=IF(\"a, b", fns))
    }

    @Test fun anExactNameShowsItsArgumentsNotItself() {
        // "=SUM" still offers SUMIF and SUMIFS, but not SUM itself.
        val h = FormulaHint.of("=SUM", fns) as FormulaHint.Suggest
        assertEquals(listOf("SUMIF", "SUMIFS"), h.matches.map { it.name })
    }

    @Test fun splitsArguments() {
        assertEquals(listOf("number1", "[number2, …]"), FormulaHint.arguments("SUM(number1, [number2, …])"))
        assertEquals(listOf("range", "criterion", "[sum_range]"), FormulaHint.arguments("SUMIF(range, criterion, [sum_range])"))
        assertEquals(emptyList<String>(), FormulaHint.arguments("PI()"))
    }
}
