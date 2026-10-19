package space.nimbusgo.sigma.grid

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.SigmaAIMessage
import space.nimbusgo.sigma.SigmaException
import space.nimbusgo.sigma.SigmaGridController
import space.nimbusgo.sigma.SigmaSession
import space.nimbusgo.sigma.applyPatch
import space.nimbusgo.sigma.ask

/**
 * Vani AI in the sheet: ask, read the answer, apply the change it proposes
 * (as one undo step). Its use counts against the document owner's plan.
 */
internal class VaniPanel(
    context: Context,
    private val session: SigmaSession,
    private val controller: SigmaGridController,
    private val scope: CoroutineScope,
) : Panel(context, "Vani AI", tall = true) {
    private val messages = context.column()
    private val prompt = context.field("Ask Vani AI about this sheet").apply {
        isSingleLine = false
        maxLines = 4
        imeOptions = EditorInfo.IME_ACTION_SEND
        setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    }
    private val scroller: ScrollView
    private var asking = false
    private var failure: String? = null

    private val suggestions = listOf(
        "Add a total row and format it",
        "Chart this data",
        "Highlight values above average",
        "Build a monthly budget for a family of four",
    )

    init {
        action("Clear", color = GridColors.secondary) { controller.setAIMessages(emptyList()); render() }
        action("Done", bold = true) { dismiss() }
        body.addView(messages, matchWidth())
        scroller = body.parent as ScrollView
        val send = context.primaryButton("Send") { send() }
        val input = context.row(prompt.apply { layoutParams = weighted() }, send.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8f) }
        })
        input.setPadding(dp(12f), dp(8f), dp(12f), dp(12f))
        (scroller.parent as LinearLayout).addView(input, matchWidth())
        prompt.setOnEditorActionListener { _, _, _ -> send(); true }
        render()
    }

    private fun render() {
        messages.removeAllViews()
        val list = controller.aiMessages.value
        if (list.isEmpty()) {
            messages.addView(context.label("Vani AI builds the sheet, not a description of one: tables, formulas, formats and charts bound to your cells.", 15f, GridColors.secondary).apply {
                setPadding(0, dp(6f), 0, dp(10f))
            })
            for (s in suggestions) {
                messages.addView(context.label(s, 14f).apply {
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    background = pressable(context, GridColors.surface, 18f)
                    isClickable = true
                    setOnClickListener { prompt.setText(s); send() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
            }
        }
        list.forEachIndexed { i, m ->
            if (m.role == SigmaAIMessage.Role.USER) {
                messages.addView(context.row(context.label(m.text, 15f).apply {
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    background = rounded(GridColors.headerSelected, dp(14f).toFloat())
                }, gravity = Gravity.END), matchWidth().apply { setMargins(dp(40f), dp(6f), 0, dp(6f)) })
            } else {
                val box = context.column(
                    context.label("✦ Vani AI", 12f, GridColors.secondary, bold = true),
                    context.label("", 15f).apply { text = markdown(m.text); setTextIsSelectable(true); setPadding(0, dp(4f), 0, dp(6f)) },
                )
                if (m.patch != null) {
                    if (m.applied != null) box.addView(context.label("✓ ${m.applied}", 14f, GridColors.accent, bold = true))
                    else box.addView(context.primaryButton("Apply" + (m.patchSummary?.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")) { apply(i) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                messages.addView(box, matchWidth().apply { setMargins(0, dp(6f), 0, dp(6f)) })
            }
        }
        if (asking) messages.addView(context.row(ProgressBar(context).apply { layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)) },
            context.label("  Working on it…", 14f, GridColors.secondary)))
        failure?.let { messages.addView(context.label(it, 13f, GridColors.error).apply { setPadding(0, dp(8f), 0, 0) }) }
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun send() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty() || asking) return
        val history = controller.aiMessages.value
        controller.setAIMessages(history + SigmaAIMessage(SigmaAIMessage.Role.USER, text))
        prompt.setText("")
        failure = null
        asking = true
        render()
        scope.launch {
            try {
                val answer = session.ask(text, history, controller.selection.value)
                controller.setAIMessages(controller.aiMessages.value + answer)
            } catch (e: Exception) {
                failure = (e as? SigmaException)?.message ?: e.message ?: "Vani AI did not answer."
            }
            asking = false
            render()
        }
    }

    private fun apply(index: Int) {
        val m = controller.aiMessages.value.getOrNull(index) ?: return
        val patch = m.patch ?: return
        if (m.applied != null) return
        val book = session.workbook.value ?: return
        scope.launch {
            try {
                val result = book.applyPatch(patch)
                controller.setAIMessages(controller.aiMessages.value.toMutableList().also { it[index] = m.copy(applied = result.summary.ifEmpty { "Applied" }) })
            } catch (e: Exception) {
                failure = e.message
            }
            render()
        }
    }

    /** **bold** and the rest as written. */
    private fun markdown(s: String): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < s.length) {
            val start = s.indexOf("**", i)
            if (start < 0) { out.append(s.substring(i)); break }
            val end = s.indexOf("**", start + 2)
            if (end < 0) { out.append(s.substring(i)); break }
            out.append(s.substring(i, start))
            val from = out.length
            out.append(s.substring(start + 2, end))
            out.setSpan(StyleSpan(Typeface.BOLD), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = end + 2
        }
        return out
    }
}
