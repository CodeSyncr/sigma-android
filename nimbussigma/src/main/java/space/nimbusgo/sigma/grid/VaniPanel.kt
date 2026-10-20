package space.nimbusgo.sigma.grid

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.nimbusgo.sigma.R
import space.nimbusgo.sigma.SigmaAIMessage
import space.nimbusgo.sigma.SigmaException
import space.nimbusgo.sigma.SigmaGridController
import space.nimbusgo.sigma.SigmaSession
import space.nimbusgo.sigma.applyPatch
import space.nimbusgo.sigma.ask

/**
 * Vani AI in the sheet, drawn as Website Studio's Vani panel: Nimbo and
 * "Vani" in the header, his greeting when the chat is empty, cream and ink
 * bubbles, Nimbo waiting while an answer comes, and the composer. Ask, read
 * the answer, apply the change it proposes (as one undo step). Its use counts
 * against the document owner's plan.
 */
internal class VaniPanel(
    context: Context,
    private val session: SigmaSession,
    private val controller: SigmaGridController,
    private val scope: CoroutineScope,
) : Dialog(context) {
    private val messages = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = ScrollView(context)
    private val headNimbo = NimboView(context, NimboView.Mood.HELLO, 42f)
    private val fresh = headButton(R.drawable.nimbussigma_ic_add, "New chat") {
        controller.setAIMessages(emptyList()); controller.setAIFailure(null)
    }
    private val prompt = EditText(context)
    private val send = ImageView(context)
    private val box = LinearLayout(context)
    private var watching: Job? = null
    private val ticker = Handler(Looper.getMainLooper())
    private var invite = 0

    init {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(View(context).apply { background = rounded(Color.argb(40, 38, 30, 22), dp(2f).toFloat()) },
            LinearLayout.LayoutParams(dp(36f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8f) })
        root.addView(header(), matchWidth())
        root.addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        messages.setPadding(dp(16f), dp(14f), dp(16f), dp(10f))
        scroller.addView(messages)
        scroller.isFillViewport = true
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(composer(), matchWidth().apply { setMargins(dp(12f), dp(4f), dp(12f), dp(12f)) })
        val r = dp(18f).toFloat()
        root.background = GradientDrawable().apply { setColor(PAPER); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        // The window fills the screen so the keyboard can shrink the sheet and keep the composer in view;
        // the clear band above the sheet closes it, as a tap outside would.
        setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply { setOnClickListener { dismiss() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.2f).toInt()))
            addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window?.setGravity(Gravity.BOTTOM)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window?.attributes = window?.attributes?.apply { windowAnimations = android.R.style.Animation_InputMethod }
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setCanceledOnTouchOutside(true)
        render()
    }

    override fun onStart() {
        super.onStart()
        // The answer may arrive, or fail, with the panel closed and opened again.
        watching = scope.launch {
            combine(controller.aiMessages, controller.aiWorking, controller.aiFailure) { _, _, _ -> }.collect { render() }
        }
    }

    override fun onStop() {
        watching?.cancel()
        ticker.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun dp(v: Float): Int = context.dp(v)

    // Header (Studio's .vai-head)

    private fun header(): View {
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(context.label("Vani", 16f, NimboArt.INK, bold = true))
            addView(context.label("Editing " + (session.document.value?.title ?: "this sheet"), 12f, MUTED).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(12f), dp(12f))
            addView(headNimbo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = -dp(4f); bottomMargin = -dp(6f); rightMargin = dp(10f)
            })
            addView(words, weighted())
            addView(fresh)
            addView(headButton(R.drawable.nimbussigma_ic_close, "Close") { dismiss() })
        }
    }

    private fun headButton(icon: Int, label: String, action: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(SUB)
        contentDescription = label
        tooltipText = label
        val p = dp(8.5f)
        setPadding(p, p, p, p)
        background = pressable(context, Color.TRANSPARENT, 17f)
        isClickable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { leftMargin = dp(4f) }
    }

    // Composer (Studio's .vai-inputbox)

    private fun composer(): View {
        prompt.apply {
            hint = "Ask about this sheet, or describe a change"
            setHintTextColor(MUTE)
            setTextColor(NimboArt.INK)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = null
            minLines = 1
            maxLines = 5
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setOnEditorActionListener { _, _, _ -> send(); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { sendState() }
            })
            setOnFocusChangeListener { _, _ -> boxState() }
        }
        send.apply {
            setImageResource(R.drawable.nimbussigma_ic_arrow_upward)
            setColorFilter(NimboArt.CREAM)
            val p = dp(10f)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(NimboArt.INK) }
            elevation = dp(3f).toFloat()
            contentDescription = "Send"
            isClickable = true
            setOnClickListener { send() }
        }
        box.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(10f), dp(10f), dp(8f))
            addView(prompt, matchWidth())
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(send, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            }, matchWidth().apply { topMargin = dp(8f) })
            elevation = dp(4f).toFloat()
        }
        boxState()
        sendState()
        return box
    }

    private fun boxState() {
        box.background = rounded(Color.WHITE, dp(18f).toFloat(), if (prompt.hasFocus()) Color.argb(102, 240, 78, 40) else LINE2, dp(1f))
    }

    private fun sendState() {
        val on = prompt.text.isNotBlank() && !controller.aiWorking.value
        send.isEnabled = on
        send.alpha = if (on) 1f else 0.45f
    }

    // The conversation

    private fun render() {
        val list = controller.aiMessages.value
        val working = controller.aiWorking.value
        headNimbo.mood = if (working) NimboView.Mood.THINKING else NimboView.Mood.HELLO
        fresh.isEnabled = list.isNotEmpty() && !working
        fresh.alpha = if (fresh.isEnabled) 1f else 0.4f
        sendState()
        messages.removeAllViews()
        ticker.removeCallbacksAndMessages(null)
        if (list.isEmpty() && !working) greeting()
        list.forEachIndexed { i, m ->
            if (m.role == SigmaAIMessage.Role.USER) {
                messages.addView(bubble(m.text, Kind.USER), side(right = true))
            } else {
                messages.addView(bubble(m.text, Kind.BOT), side(right = false))
                if (m.patch != null) messages.addView(applyRow(i, m), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
            }
        }
        if (working) {
            // Studio's DOTS: Nimbo waits for the answer to arrive.
            messages.addView(NimboView(context, NimboView.Mood.LOADING, 64f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
            messages.addView(context.label("Thinking", 11.5f, MUTE).apply { setPadding(dp(4f), dp(2f), 0, 0) })
        }
        controller.aiFailure.value?.let { messages.addView(bubble(it, Kind.FAILED), side(right = false)) }
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** The empty chat: Nimbo waves, "Hi, I'm Vani.", a line that changes every few seconds, and ways to start. */
    private fun greeting() {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10f), dp(28f), dp(10f), dp(18f))
        }
        wrap.addView(NimboView(context, NimboView.Mood.HELLO, 120f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14f) })
        val hi = SpannableStringBuilder("Hi, I'm Vani.").apply { setSpan(ForegroundColorSpan(EMBER_TEXT), 8, 13, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        wrap.addView(context.label("", 25.6f, NimboArt.INK, bold = true).apply { text = hi; letterSpacing = -0.03f; gravity = Gravity.CENTER })
        val line = context.label(INVITES[invite], 16f, EMBER_TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minLines = 2
            maxWidth = dp(270f)
        }
        wrap.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        for (s in SUGGESTIONS) {
            // Studio's .vai-chip.
            wrap.addView(context.label(s, 13f, NimboArt.INK).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                background = pressable(context, ORANGE_LIGHT, 999f)
                isClickable = true
                setOnClickListener { prompt.setText(s); send() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        messages.addView(wrap, matchWidth())
        fun next() {
            ticker.postDelayed({
                line.animate().alpha(0f).translationY(-dp(7f).toFloat()).setDuration(350).withEndAction {
                    invite = (invite + 1) % INVITES.size
                    line.text = INVITES[invite]
                    line.translationY = 0f
                    line.animate().alpha(1f).setDuration(350).start()
                }.start()
                next()
            }, 3800)
        }
        next()
    }

    private enum class Kind { USER, BOT, FAILED }

    /** Studio's .vai-msg: 14 sp, rounded 16 with the corner nearest the speaker tucked in. */
    private fun bubble(text: String, kind: Kind): TextView = context.label("", 14f).apply {
        this.text = rich(text)
        setLineSpacing(dp(3.5f).toFloat(), 1f)
        setTextIsSelectable(true)
        // Selectable text takes focus, and Android's focus highlight would grey the whole bubble.
        defaultFocusHighlightEnabled = false
        setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
        maxWidth = (context.resources.displayMetrics.widthPixels * 0.84f).toInt() - dp(32f)
        val r = dp(16f).toFloat(); val t = dp(5f).toFloat()
        val (bg, line, fg) = when (kind) {
            Kind.USER -> Triple(NimboArt.INK, Color.TRANSPARENT, NimboArt.CREAM)
            Kind.BOT -> Triple(Color.WHITE, HAIRLINE, BOT_TEXT)
            Kind.FAILED -> Triple(FAIL_FILL, FAIL_LINE, FAIL_TEXT)
        }
        setTextColor(fg)
        background = GradientDrawable().apply {
            setColor(bg)
            if (line != Color.TRANSPARENT) setStroke(dp(1f), line)
            // top-left, top-right, bottom-right, bottom-left
            cornerRadii = if (kind == Kind.USER) floatArrayOf(r, r, r, r, t, t, r, r) else floatArrayOf(r, r, r, r, r, r, t, t)
        }
        if (kind == Kind.BOT) elevation = dp(1f).toFloat()
    }

    private fun side(right: Boolean) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = if (right) Gravity.END else Gravity.START
        topMargin = dp(10f)
    }

    private fun applyRow(index: Int, m: SigmaAIMessage): View {
        val done = m.applied
        if (done != null) {
            return context.label("", 12.5f, SUB).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                text = SpannableStringBuilder("✓  Applied $done").apply { setSpan(ForegroundColorSpan(DONE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                setPadding(dp(4f), 0, 0, 0)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            background = pressable(context, NimboArt.INK, 999f)
            isClickable = true
            contentDescription = "Apply"
            setOnClickListener { apply(index) }
            addView(ImageView(context).apply { setImageResource(R.drawable.nimbussigma_ic_auto_awesome); setColorFilter(NimboArt.CREAM) }, LinearLayout.LayoutParams(dp(15f), dp(15f)).apply { rightMargin = dp(8f) })
            addView(context.label("Apply" + (m.patchSummary?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), 13f, NimboArt.CREAM, bold = true))
        }
    }

    // Asking

    private fun send() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty() || controller.aiWorking.value) return
        val history = controller.aiMessages.value
        controller.setAIFailure(null)
        controller.setAIWorking(true)
        controller.setAIMessages(history + SigmaAIMessage(SigmaAIMessage.Role.USER, text))
        prompt.setText("")
        scope.launch {
            try {
                val answer = session.ask(text, history, controller.selection.value)
                controller.setAIMessages(controller.aiMessages.value + answer)
            } catch (e: Exception) {
                controller.setAIFailure((e as? SigmaException)?.message ?: e.message ?: "Vani AI did not answer.")
            } finally {
                controller.setAIWorking(false)
            }
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
                controller.setAIFailure(e.message)
            }
        }
    }

    /** **bold** inline; "- " lines as bullets. */
    private fun rich(s: String): CharSequence {
        val text = s.lines().joinToString("\n") { l ->
            val t = l.trimStart()
            if (t.startsWith("- ") || t.startsWith("* ")) "•  " + t.drop(2) else l
        }
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("**", i)
            if (start < 0) { out.append(text.substring(i)); break }
            val end = text.indexOf("**", start + 2)
            if (end < 0) { out.append(text.substring(i)); break }
            out.append(text.substring(i, start))
            val from = out.length
            out.append(text.substring(start + 2, end))
            out.setSpan(StyleSpan(Typeface.BOLD), from, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = end + 2
        }
        return out
    }

    companion object {
        // Studio's colours for Vani (vani-ai.css with studio-sky.css over it).
        val PAPER = Color.rgb(0xfb, 0xf7, 0xf1)
        val HAIRLINE = Color.rgb(0xed, 0xe6, 0xdf)
        val LINE = Color.argb(20, 38, 30, 22)
        val LINE2 = Color.argb(41, 38, 30, 22)
        val BOT_TEXT = Color.rgb(0x46, 0x3f, 0x37)
        val MUTED = Color.rgb(0x9b, 0x91, 0x86)
        val MUTE = Color.rgb(0x9c, 0x98, 0x93)
        val SUB = Color.rgb(0x6b, 0x69, 0x66)
        val EMBER_TEXT = Color.rgb(0xf0, 0x5e, 0x2a)
        val ORANGE_LIGHT = Color.rgb(0xff, 0xf1, 0xea)
        val DONE = Color.rgb(0x1f, 0x7a, 0x55)
        val FAIL_TEXT = Color.rgb(0xa4, 0x33, 0x1a)
        // 6% and 30% ember over the paper, made opaque.
        val FAIL_FILL = Color.rgb(250, 237, 229)
        val FAIL_LINE = Color.rgb(247, 196, 181)

        val SUGGESTIONS = listOf(
            "Add a total row and format it",
            "Chart this data",
            "Highlight values above average",
            "Build a monthly budget for a family of four",
        )
        val INVITES = listOf(
            "What should this sheet work out?",
            "Describe a table and I build it: columns, formulas and formats",
            "Ask me to total, chart or tidy your data",
            "Select some cells first, and I work on those",
        )
    }
}
