package pl.somaskan.questgpt2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class EnvironmentActions(val camera: () -> Unit, val voice: () -> Unit, val ask: (String) -> Unit,
    val minimize: () -> Unit, val restore: () -> Unit, val chat: () -> Unit, val position: () -> Unit,
    val exit: () -> Unit)

/** The same real Android controls are used by the MR panel and the 2D quick panel. */
class EnvironmentView(context: Context, private val compact: Boolean, private val actions: EnvironmentActions) : FrameLayout(context) {
    private val accent = 0xff77e3cf.toInt()
    private val ink = 0xffeff7ff.toInt()
    private val surface = 0xdd172338.toInt()
    private var previewBitmap: Bitmap? = null
    private var previewKey = ""
    private var lastPaintAt = 0L
    private val status = text("", 17f)
    private val preview = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Aktualny obraz dostępny dla AI" }
    private val caption = text("", 15f)
    private val transcript = text("", 19f)
    private val camera = button("Kamera", actions.camera)
    private val voice = button("Live", actions.voice)
    private val mute = button("Wycisz") { Hub.voiceService?.mute() ?: Hub.note("Najpierw włącz Live.") }
    private val icon = button("AI ●", actions.restore).apply { contentDescription = "Rozwiń menu QuestGPT" }
    private val observer: () -> Unit = { render() }
    private var cameraExplanation: View? = null

    /** Render consent inside the same VR texture as the controls, without a separate 2D window. */
    fun showCameraExplanation(accept: () -> Unit) {
        cameraExplanation?.let { removeView(it) }
        val card = column().apply {
            background = shape(0xf0141d2e.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isClickable = true; isFocusable = true
        }
        card.addView(text("Pokaż otoczenie asystentowi", 25f, true))
        val explanation = text("Kamera RGB gogli pokaże fizyczne otoczenie. W Live do OpenAI trafi zdjęcie co około 2 sekundy oraz przy pytaniu. Bez Live obraz zostaje lokalnie do chwili wysłania pytania. Obrazy zużywają środki API.\n\nPrzycisk Kamera wyłącza udostępnianie. Stop wszystko kończy kamerę, mikrofon i ekran. Passthrough dla Ciebie może nadal pozostać widoczny.", 19f)
        card.addView(ScrollView(context).apply { addView(explanation) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(20); bottomMargin = dp(20) })
        fun dismiss() { removeView(card); cameraExplanation = null }
        val buttons = row()
        buttons.addView(button("Anuluj") { dismiss() }, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(8) })
        buttons.addView(button("Włącz kamerę") { dismiss(); accept() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        card.addView(buttons)
        cameraExplanation = card
        addView(card, LayoutParams(-1, -1))
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        if (compact) {
            val strip = row().apply { background = shape(0xdd0c1220.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4)) }
            strip.addView(icon, LinearLayout.LayoutParams(dp(80), dp(60)))
            strip.addView(button("Stop") { Hub.stopAll() }, LinearLayout.LayoutParams(dp(72), dp(60)))
            addView(strip, LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        } else {
            val body = column().apply { background = shape(0xaa0c1220.toInt()); setPadding(dp(16), dp(12), dp(16), dp(12)) }
            val top = row()
            top.addView(text("Otoczenie · QuestGPT", 25f, true), LinearLayout.LayoutParams(0, dp(46), 1f))
            top.addView(button("−", actions.minimize).apply { contentDescription = "Zwiń do ikony" }, LinearLayout.LayoutParams(dp(60), dp(52)))
            body.addView(top)
            status.maxLines = 3; body.addView(status)
            body.addView(preview, LinearLayout.LayoutParams(-1, dp(156)).apply { topMargin = dp(8) })
            caption.maxLines = 2; body.addView(caption)
            val scroller = ScrollView(context).apply { addView(transcript) }
            body.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
            fun controls(vararg buttons: Button) {
                val r = row()
                buttons.forEach { r.addView(it, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }) }
                body.addView(r)
            }
            controls(voice, camera, mute)
            controls(button("Co widzisz?") { actions.ask("Co widzisz przede mną? Opisz krótko otoczenie.") },
                button("Czytaj tekst") { actions.ask("Przeczytaj widoczny tekst. Jeśli jest nieczytelny, powiedz to.") },
                button("Stop wszystko") { Hub.stopAll() })
            controls(button("Czat", actions.chat), button("Ustaw widok", actions.position), button("Wyjdź", actions.exit))
            addView(body, LayoutParams(-1, -1))
        }
        render()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Hub.observe(observer); post(ticker) }
    override fun onDetachedFromWindow() {
        Hub.unobserve(observer); removeCallbacks(ticker); preview.setImageDrawable(null); previewBitmap?.recycle(); previewBitmap = null; previewKey = ""
        super.onDetachedFromWindow()
    }
    private val ticker = object : Runnable { override fun run() { if (isAttachedToWindow) { render(); postDelayed(this, 1000) } } }
    private fun render() {
        val s = Hub.state
        icon.text = if (s.cameraActive || s.voiceActive || s.sharing) "AI ●" else "AI ○"
        icon.setTextColor(if (s.cameraActive || s.voiceActive || s.sharing) accent else ink)
        if (compact) return
        status.text = "${s.voice}\n${s.visionStatus()}"
        camera.text = if (s.cameraActive) "Stop kamera" else "Kamera"
        voice.text = if (s.voiceActive) "Stop Live" else "Live"
        mute.text = if (s.muted) "Włącz mikrofon" else "Wycisz"
        val f = s.activeFrame(SystemClock.elapsedRealtime())
        val key = f?.let { "${it.source}:${it.sequence}:${it.at}" }.orEmpty()
        if (key != previewKey && (key.isBlank() || SystemClock.elapsedRealtime() - lastPaintAt > 650)) {
            previewKey = key; lastPaintAt = SystemClock.elapsedRealtime()
            val next = f?.let { runCatching {
                val bytes = Base64.decode(it.dataUrl.substringAfter(','), Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            }.getOrNull() }
            preview.setImageBitmap(next); previewBitmap?.recycle(); previewBitmap = next
        }
        caption.text = when {
            f == null -> "Brak świeżego obrazu dla AI."
            s.voiceReady -> "Live: zdjęcia co ok. 2 s · potwierdzone: ${s.sentFrames}"
            else -> "Podgląd lokalny · zdjęcie wyślesz z pytaniem."
        }
        val answer = s.messages.lastOrNull { it.role == "assistant" }?.text
        transcript.text = if (s.note.isNotBlank()) s.note + (answer?.let { "\n\n$it" } ?: "")
            else answer ?: "Włącz kamerę i Live, a potem zapytaj głosem o to, co masz przed sobą. Minus zwija panel do ikony."
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 15f; isAllCaps = false; maxLines = 2
        setTextColor(ink); background = shape(surface); minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        setPadding(dp(6), dp(2), dp(6), dp(2)); setOnClickListener { action() }
    }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
}
