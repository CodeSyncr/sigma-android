package space.nimbusgo.sigma.grid

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import space.nimbusgo.sigma.SigmaFunction

/** What a formula being typed needs help with: a function name to finish, or the arguments of the call it is in. */
internal sealed class FormulaHint {
    data object None : FormulaHint()
    data class Suggest(val prefix: String, val matches: List<SigmaFunction>) : FormulaHint()
    data class Arguments(val function: SigmaFunction, val argument: Int) : FormulaHint()

    companion object {
        private val NAME_AT_END = Regex("(?:^|[^A-Za-z0-9_.$!])([A-Za-z][A-Za-z0-9.]*)$")

        /** Reads a formula as typed so far (the cursor at its end). */
        fun of(text: String, functions: List<SigmaFunction>): FormulaHint {
            if (!text.startsWith("=") || functions.isEmpty()) return None
            val body = text.substring(1)
            // A name being typed: offer the functions it starts.
            NAME_AT_END.find(body)?.groupValues?.get(1)?.let { prefix ->
                // Shortest first: the everyday functions (SUM, IF) have the short names.
                val matches = functions.filter { it.name.startsWith(prefix, ignoreCase = true) && !it.name.equals(prefix, ignoreCase = true) }
                    .sortedWith(compareBy({ it.name.length }, { it.name }))
                if (matches.isNotEmpty()) return Suggest(prefix, matches.take(12))
            }
            // Inside a call: which function, and which of its arguments.
            val calls = ArrayDeque<Pair<String, Int>>()
            var inString = false
            var i = 0
            while (i < body.length) {
                val ch = body[i]
                when {
                    ch == '"' -> inString = !inString
                    inString -> Unit
                    ch == '(' -> {
                        val name = Regex("([A-Za-z][A-Za-z0-9.]*)$").find(body.substring(0, i))?.groupValues?.get(1) ?: ""
                        calls.addLast(name to 0)
                    }
                    ch == ')' -> calls.removeLastOrNull()
                    ch == ',' -> calls.removeLastOrNull()?.let { (n, a) -> calls.addLast(n to a + 1) }
                }
                i++
            }
            val (name, arg) = calls.lastOrNull() ?: return None
            val fn = functions.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return None
            return Arguments(fn, arg)
        }

        /** The arguments of a syntax like "SUMIF(range, criterion, [sum_range])". */
        fun arguments(syntax: String): List<String> {
            val inner = syntax.substringAfter('(', "").substringBeforeLast(')', "")
            if (inner.isBlank()) return emptyList()
            // Split on commas outside square brackets: "[number2, …]" is one argument.
            val out = mutableListOf<String>()
            var depth = 0
            val cur = StringBuilder()
            for (ch in inner) {
                when (ch) {
                    '[' -> { depth++; cur.append(ch) }
                    ']' -> { depth--; cur.append(ch) }
                    ',' -> if (depth == 0) { out.add(cur.toString().trim()); cur.clear() } else cur.append(ch)
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            return out
        }
    }
}

/**
 * Above the keyboard while a formula is typed: the functions a half-typed
 * name could be, or the syntax of the function being filled in with the
 * current argument in bold.
 */
internal class FormulaHelpBar(context: Context, private val pick: (String) -> Unit) : FrameLayout(context) {
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chips)
    }
    private val hint = context.label("", 13f, GridColors.ink).apply {
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        maxLines = 2
    }

    init {
        setBackgroundColor(GridColors.surface)
        addView(scroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        addView(hint, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        chips.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        show(FormulaHint.None)
    }

    fun show(h: FormulaHint) {
        when (h) {
            FormulaHint.None -> visibility = GONE
            is FormulaHint.Suggest -> {
                visibility = VISIBLE
                hint.visibility = GONE
                scroller.visibility = VISIBLE
                chips.removeAllViews()
                for (f in h.matches) {
                    chips.addView(context.label(f.name, 14f, GridColors.accent, bold = true).apply {
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                        background = pressable(context, Color.WHITE, 16f)
                        contentDescription = "${f.name}: ${f.description}"
                        isClickable = true
                        setOnClickListener { pick(f.name) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6f) })
                }
                scroller.scrollTo(0, 0)
            }
            is FormulaHint.Arguments -> {
                visibility = VISIBLE
                scroller.visibility = GONE
                hint.visibility = VISIBLE
                hint.text = syntax(h)
            }
        }
    }

    /** "SUMIF(range, **criterion**, [sum_range])  Adds the cells that meet a condition." */
    private fun syntax(h: FormulaHint.Arguments): CharSequence {
        val args = FormulaHint.arguments(h.function.syntax)
        val out = SpannableStringBuilder()
        out.append(h.function.name).append('(')
        // Past the last argument, a repeating one ("[number2, …]") is still the current one.
        val current = if (args.isEmpty()) -1 else minOf(h.argument, args.size - 1)
        args.forEachIndexed { i, a ->
            if (i > 0) out.append(", ")
            val from = out.length
            out.append(a)
            if (i == current) {
                out.setSpan(StyleSpan(Typeface.BOLD), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(GridColors.accent), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        out.append(')')
        out.setSpan(android.text.style.TypefaceSpan("monospace"), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (h.function.description.isNotEmpty()) {
            val from = out.length
            out.append("   ").append(h.function.description)
            out.setSpan(ForegroundColorSpan(GridColors.secondary), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
