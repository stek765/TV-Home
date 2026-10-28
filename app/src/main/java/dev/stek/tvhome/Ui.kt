package dev.stek.tvhome

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import java.net.URL
import java.util.concurrent.Executors

object C {
    val bg = Color.parseColor("#07080C")
    val card = Color.parseColor("#171B26")
    val dim = Color.parseColor("#A3ACBF")
    val faint = Color.parseColor("#6E7790")
    val glass = Color.parseColor("#33FFFFFF")
    val accent = Color.parseColor("#4C8DFF")
}

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = Color.WHITE) = GradientDrawable().apply {
    cornerRadius = radius.toFloat(); setColor(color); if (stroke > 0) setStroke(stroke, strokeColor)
}

fun gradient(o: GradientDrawable.Orientation, vararg colors: Int) = GradientDrawable(o, colors)

fun Context.label(size: Float, color: Int = Color.WHITE, font: String = "sans-serif", lines: Int = 1) = TextView(this).apply {
    textSize = size; setTextColor(color); typeface = Typeface.create(font, Typeface.NORMAL)
    maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
}

fun View.zoomOnFocus(scale: Float = 1.1f, lift: Float = 18f, extra: ((Boolean) -> Unit)? = null) {
    setOnFocusChangeListener { v, f ->
        v.animate().scaleX(if (f) scale else 1f).scaleY(if (f) scale else 1f).translationZ(if (f) lift else 0f)
            .setInterpolator(DecelerateInterpolator()).setDuration(160).start()
        extra?.invoke(f)
    }
}

/** Small async image loader: byte-bounded memory cache, stale-request skip, fade-in. */
object Img {
    private val cache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Drop every cached picture: the video decoder needs that memory more on a 1 GB TV. */
    fun clear() = cache.evictAll()

    fun fetch(url: String, sample: Int = 1, alpha: Boolean = false, done: (Bitmap) -> Unit, stillWanted: () -> Boolean = { true }) {
        if (url.isEmpty()) return
        val key = "$url@$sample"
        cache.get(key)?.let { done(it); return }
        pool.execute {
            if (!stillWanted()) return@execute
            val b = runCatching {
                val c = URL(url).openConnection(); c.connectTimeout = 8000; c.readTimeout = 12000
                c.getInputStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample; inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                }) }
            }.getOrNull() ?: return@execute
            cache.put(key, b); main.post { done(b) }
        }
    }

    fun into(iv: ImageView, url: String, sample: Int = 1, alpha: Boolean = false) {
        iv.tag = url
        if (url.isEmpty()) { iv.setImageDrawable(null); return }
        cache.get("$url@$sample")?.let { iv.setImageBitmap(it); iv.alpha = 1f; return }
        iv.setImageDrawable(null)
        fetch(url, sample, alpha, { b -> if (iv.tag == url) { iv.setImageBitmap(b); iv.alpha = 0f; iv.animate().alpha(1f).setDuration(220).start() } }, { iv.tag == url })
    }
}

/** Full-bleed backdrop: crossfades between images and drifts slowly (Ken Burns). Drift pauses while the remote is in use. */
class Hero(ctx: Context) : FrameLayout(ctx) {
    private val a = img(); private val b = img()
    private var front = a
    private var current = ""
    private var running = false
    private val drift = ObjectAnimator.ofPropertyValuesHolder(this,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1.02f, 1.12f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.02f, 1.12f),
        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -ctx.dp(24).toFloat())).apply {
        duration = 26000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; interpolator = LinearInterpolator()
    }
    private val blobAnims = mutableListOf<ObjectAnimator>()
    private var ambient = false
    private val resume = Runnable { if (running) (listOf(drift) + blobAnims).forEach { if (!it.isStarted) it.start() else it.resume() } }

    companion object {
        /** One fixed background instead of the film backdrops: large soft colour glows over charcoal, drifting very slowly.
         *  Each glow: colour, centre x/y (fraction of screen), size (fraction of width). */
        val PALETTES = mapOf(
            "oceano" to listOf(Glow(0xFF0B4FD6.toInt(), 0.18f, 0.05f, 1.25f), Glow(0xFF0A7FA3.toInt(), 0.95f, 0.75f, 0.9f)),
            "acqua" to listOf(Glow(0xFF0E8C87.toInt(), 0.30f, 0.0f, 1.3f), Glow(0xFF0B3B6E.toInt(), 0.90f, 0.85f, 0.9f)),
            "aurora" to listOf(Glow(0xFF1F4FE0.toInt(), 0.15f, 0.10f, 1.0f), Glow(0xFF7A2BD9.toInt(), 0.80f, 0.15f, 0.95f), Glow(0xFF0E8FA6.toInt(), 0.55f, 1.0f, 1.0f)),
            // the Home: Linux dusk (orange, red, violet, blue, magenta), positions copied from the prototype
            "dusk" to listOf(Glow(0xFFFF6B2C.toInt(), 0.16f, 0.17f, 0.59f), Glow(0xFFF0244B.toInt(), 0.57f, 0.21f, 0.55f),
                Glow(0xFF7B2CF5.toInt(), 0.88f, 0.37f, 0.64f), Glow(0xFF2C4BFF.toInt(), 0.54f, 0.99f, 0.70f), Glow(0xFFC2187F.toInt(), 0.14f, 0.87f, 0.47f)),
            "notte" to listOf(Glow(0xFF27324A.toInt(), 0.50f, -0.15f, 1.5f), Glow(0xFF14245E.toInt(), 0.95f, 1.0f, 1.0f)),
        )
    }
    class Glow(val color: Int, val x: Float, val y: Float, val size: Float)

    /** Switches this hero to a fixed ambient background; film backdrops are ignored from now on. */
    fun ambient(glows: List<Glow>) {
        ambient = true
        removeAllViews(); setBackgroundColor(C.bg)
        val w = resources.displayMetrics.widthPixels; val h = resources.displayMetrics.heightPixels
        glows.forEachIndexed { i, g ->
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawCircle(128f, 128f, 128f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(128f, 128f, 128f, intArrayOf(Color.argb(235, Color.red(g.color), Color.green(g.color), Color.blue(g.color)),
                    Color.argb(110, Color.red(g.color), Color.green(g.color), Color.blue(g.color)), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            })
            val size = (w * g.size).toInt()
            val v = ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
            addView(v, LayoutParams(size, size).apply { leftMargin = (w * g.x - size / 2f).toInt(); topMargin = (h * g.y - size / 2f).toInt() })
            // each glow wanders on its own slow loop, so the picture never repeats exactly
            val dx = w * 0.12f * (if (i % 2 == 0) 1 else -1); val dy = h * 0.10f * (if (i % 3 == 0) 1 else -1)
            blobAnims += ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, dy), PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f)).apply {
                duration = 23_000L + i * 9_000L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
        }
    }

    init {
        addView(a); addView(b); b.visibility = INVISIBLE
        pivotX = 0f; pivotY = 0f
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Legibility scrims, outside the drifting layer, baked into one bitmap: one full-screen draw instead of three. */
    fun scrims(ctx: Context, left: Float = 0.92f): View = object : View(ctx) {
        private var bmp: Bitmap? = null
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w == 0 || h == 0) return
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bm ->
                val c = Canvas(bm)
                listOf(gradient(GradientDrawable.Orientation.LEFT_RIGHT, Color.argb((255 * left).toInt(), 7, 8, 12), Color.argb(150, 7, 8, 12), Color.TRANSPARENT),
                    if (left < 0.5f) gradient(GradientDrawable.Orientation.BOTTOM_TOP, Color.argb(150, 7, 8, 12), Color.argb(50, 7, 8, 12), Color.TRANSPARENT)
                    else gradient(GradientDrawable.Orientation.BOTTOM_TOP, C.bg, Color.argb(200, 7, 8, 12), Color.TRANSPARENT, Color.TRANSPARENT),
                    gradient(GradientDrawable.Orientation.TOP_BOTTOM, Color.argb(140, 7, 8, 12), Color.TRANSPARENT, Color.TRANSPARENT))
                    .forEach { it.setBounds(0, 0, w, h); it.draw(c) }
            }
        }
        override fun onDraw(c: Canvas) { bmp?.let { c.drawBitmap(it, 0f, 0f, null) } }
    }

    private fun img() = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LayoutParams(-1, -1) }

    fun show(url: String) {
        if (ambient || url.isEmpty() || url == current) return
        current = url
        Img.fetch(url, 1, false, { bmp ->
            if (url != current) return@fetch
            val back = if (front === a) b else a
            val old = front
            back.setImageBitmap(bmp); back.bringToFront(); back.visibility = VISIBLE
            back.alpha = 0f; back.animate().alpha(1f).setDuration(650).withEndAction { if (front === back) old.visibility = INVISIBLE }.start()
            front = back
        }, { url == current })
    }

    fun start() { running = true; resume.run() }
    fun stop() { running = false; removeCallbacks(resume); (listOf(drift) + blobAnims).forEach { it.pause() } }

    /** Called on every key press: give the GPU to navigation, drift again once the remote is idle. */
    fun nudge() { if (!running) return; (listOf(drift) + blobAnims).forEach { it.pause() }; removeCallbacks(resume); postDelayed(resume, 1500) }
}

/** One tile: landscape image (or an app banner/icon), focus ring, optional progress bar, caption under it. */
class Card(
    val key: String, val caption: String, val image: String = "", val sub: String = "",
    val progress: Float = -1f, val drawable: Drawable? = null, val icon: Boolean = false, val data: Any? = null,
)

class CardAdapter(
    private val ctx: Context, var items: List<Card>, private val w: Int, private val h: Int,
    private val onFocus: (Card) -> Unit, private val onClick: (Card) -> Unit, private val onLong: ((Card) -> Unit)? = null,
    private val sample: Int = 2,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** Card currently being moved (reorder mode): lifted, blue ring, ‹ caption ›. */
    var movingKey: String? = null
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
        val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; clipChildren = false }
        val frame = FrameLayout(ctx).apply {
            background = rounded(C.card, ctx.dp(10)); outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        frame.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(ctx.label(15f, Color.WHITE, "sans-serif-medium", 2).apply { gravity = Gravity.CENTER; setPadding(ctx.dp(10), 0, ctx.dp(10), 0) }, FrameLayout.LayoutParams(-1, -1))
        // progress: a faint track with the watched part filled
        frame.addView(View(ctx).apply { background = rounded(Color.parseColor("#66FFFFFF"), ctx.dp(2)) },
            FrameLayout.LayoutParams(-1, ctx.dp(4), Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, ctx.dp(10), ctx.dp(9)) })
        frame.addView(View(ctx).apply { background = rounded(C.accent, ctx.dp(2)) },
            FrameLayout.LayoutParams(0, ctx.dp(4), Gravity.BOTTOM).apply { setMargins(ctx.dp(10), 0, 0, ctx.dp(9)) })
        cell.addView(frame, LinearLayout.LayoutParams(w, h))
        cell.addView(ctx.label(13f, C.dim, "sans-serif-medium").apply { setPadding(ctx.dp(2), ctx.dp(8), 0, 0) }, LinearLayout.LayoutParams(w, -2))
        cell.addView(ctx.label(11f, C.faint).apply { setPadding(ctx.dp(2), ctx.dp(2), 0, 0) }, LinearLayout.LayoutParams(w, -2))
        cell.layoutParams = RecyclerView.LayoutParams(-2, -2).apply { rightMargin = ctx.dp(18) }
        return object : RecyclerView.ViewHolder(cell) {}
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
        val c = items[i]
        val cell = h.itemView as LinearLayout
        val frame = cell.getChildAt(0) as FrameLayout
        val img = frame.getChildAt(0) as ImageView
        val big = frame.getChildAt(1) as TextView
        val track = frame.getChildAt(2)
        val bar = frame.getChildAt(3)
        val cap = cell.getChildAt(1) as TextView
        val sub = cell.getChildAt(2) as TextView
        val moving = c.key == movingKey
        cap.text = if (moving) "‹  ${c.caption}  ›" else c.caption; sub.text = c.sub; sub.visibility = if (c.sub.isEmpty()) View.GONE else View.VISIBLE
        big.text = ""
        if (c.drawable != null) {
            img.tag = null; img.alpha = 1f; img.setImageDrawable(c.drawable)
            img.scaleType = if (c.icon) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
            img.setPadding(if (c.icon) ctx.dp(34) else 0, if (c.icon) ctx.dp(22) else 0, if (c.icon) ctx.dp(34) else 0, if (c.icon) ctx.dp(22) else 0)
        } else {
            img.setPadding(0, 0, 0, 0); img.scaleType = ImageView.ScaleType.CENTER_CROP
            if (c.image.isEmpty()) big.text = c.caption
            Img.into(img, c.image, sample)
        }
        // reorder mode: a clear "‹ Sposta ›" band over the card, otherwise nobody knows what ← → will do
        if (moving) { big.text = "‹   Sposta   ›"; big.setBackgroundColor(Color.argb(170, 0, 0, 0)) } else big.background = null
        track.visibility = if (c.progress > 0f) View.VISIBLE else View.GONE
        (bar.layoutParams as FrameLayout.LayoutParams).width = if (c.progress > 0f) ((w - ctx.dp(20)) * c.progress).toInt().coerceAtLeast(ctx.dp(6)) else 0
        bar.requestLayout()
        fun ring(f: Boolean) = when {
            moving -> rounded(Color.TRANSPARENT, ctx.dp(10), ctx.dp(4), C.accent)
            f -> rounded(Color.TRANSPARENT, ctx.dp(10), ctx.dp(3))
            else -> null
        }
        frame.foreground = ring(cell.hasFocus())
        cell.translationY = if (moving) -ctx.dp(12).toFloat() else 0f
        cap.setTextColor(if (moving || cell.hasFocus()) Color.WHITE else C.dim)
        cell.zoomOnFocus(1.1f) { f ->
            frame.foreground = ring(f)
            cap.setTextColor(if (f || moving) Color.WHITE else C.dim)
            if (f) onFocus(c)
        }
        cell.setOnClickListener { onClick(c) }
        // long press = OK held until the key starts repeating (works on the real remote and with adb --longpress)
        var held = false
        cell.setOnKeyListener { v, code, e ->
            if (onLong == null || (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)) return@setOnKeyListener false
            when {
                e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 1 -> { held = true; onLong.invoke(c); true }
                e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 1 -> true
                e.action == KeyEvent.ACTION_UP && held -> { held = false; v.isPressed = false; true } // swallow the click that would follow, and un-press
                else -> false
            }
        }
    }
}

fun Context.cardRow(adapter: CardAdapter, padStart: Int): HorizontalGridView = HorizontalGridView(this).apply {
    this.adapter = adapter
    setPadding(padStart, dp(14), padStart, dp(10)); clipToPadding = false; clipChildren = false
    windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
    windowAlignmentOffsetPercent = 0f; windowAlignmentOffset = padStart; itemAlignmentOffsetPercent = 0f
    setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT); setHasFixedSize(false); itemAnimator = null
    horizontalSpacing = dp(18)
}

/** Pill button: translucent at rest, solid white with dark text when focused (the standard TV affordance). */
fun Context.pill(text: String, icon: View? = null, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true
    setPadding(dp(20), 0, dp(22), 0); background = rounded(C.glass, dp(24))
    val tv = label(15f, Color.WHITE, "sans-serif-medium").apply { this.text = text }
    if (icon != null) addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
    addView(tv)
    layoutParams = LinearLayout.LayoutParams(-2, dp(46))
    zoomOnFocus(1.06f, lift = 0f) { f -> // pills: no shadow, it renders as a square box behind the round shape
        background = rounded(if (f) Color.WHITE else C.glass, dp(24))
        tv.setTextColor(if (f) Color.BLACK else Color.WHITE)
        (icon as? Glyph)?.tint(if (f) Color.BLACK else Color.WHITE)
    }
    setOnClickListener { onClick() }
}

/** Tiny vector glyphs drawn in code (no asset files). */
class Glyph(ctx: Context, private val kind: String) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    fun tint(c: Int) { p.color = c; invalidate() }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); p.strokeWidth = w * 0.11f; p.style = Paint.Style.STROKE
        when (kind) {
            "wifi" -> { val cx = w / 2; val by = h * 0.86f
                for (r in listOf(0.28f, 0.52f, 0.76f)) { val R = w * r; c.drawArc(RectF(cx - R, by - R, cx + R, by + R), 225f, 90f, false, p) }
                p.style = Paint.Style.FILL; c.drawCircle(cx, by, w * 0.08f, p) }
            "cast" -> { // the standard cast icon: a screen with waves in the bottom-left corner
                c.drawRoundRect(RectF(w * 0.22f, h * 0.18f, w * 0.92f, h * 0.78f), w * 0.06f, w * 0.06f, p.apply { strokeWidth = w * 0.09f })
                for (r in listOf(0.18f, 0.36f)) c.drawArc(RectF(w * (0.08f - r), h * (0.88f - r), w * (0.08f + r), h * (0.88f + r)), 270f, 90f, false, p)
                p.style = Paint.Style.FILL; c.drawCircle(w * 0.1f, h * 0.86f, w * 0.06f, p) }
            "search" -> { c.drawCircle(w * 0.42f, h * 0.42f, w * 0.28f, p); c.drawLine(w * 0.63f, h * 0.63f, w * 0.88f, h * 0.88f, p) }
            "mic" -> { c.drawRoundRect(RectF(w * 0.36f, h * 0.08f, w * 0.64f, h * 0.58f), w * 0.14f, w * 0.14f, p)
                c.drawArc(RectF(w * 0.22f, h * 0.28f, w * 0.78f, h * 0.72f), 0f, 180f, false, p); c.drawLine(w / 2, h * 0.72f, w / 2, h * 0.9f, p) }
            "play" -> { p.style = Paint.Style.FILL
                c.drawPath(android.graphics.Path().apply { moveTo(w * 0.25f, h * 0.15f); lineTo(w * 0.85f, h * 0.5f); lineTo(w * 0.25f, h * 0.85f); close() }, p) }
            "restart" -> { c.drawArc(RectF(w * 0.16f, h * 0.16f, w * 0.84f, h * 0.84f), -60f, 300f, false, p)
                p.style = Paint.Style.FILL; c.drawPath(android.graphics.Path().apply { moveTo(w * 0.62f, h * 0.02f); lineTo(w * 0.86f, h * 0.24f); lineTo(w * 0.56f, h * 0.32f); close() }, p) }
        }
    }
}

/** Sora (OFL, bundled in assets) at any weight: one variable font file, cached per weight. */
object Sora {
    private val cache = HashMap<Int, Typeface>()
    fun at(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        runCatching { Typeface.Builder(ctx.assets, "sora.ttf").setFontVariationSettings("'wght' $weight").build() }.getOrDefault(Typeface.DEFAULT)
    }
}

/**
 * The Home's Linux-dusk background, cheap enough for a 1 GB TV: every frame the five glows and the legibility shade
 * are painted into a 256×144 bitmap on the CPU, then that single bitmap is stretched (filtered) over the screen.
 * One full-screen draw instead of seven blended layers; the gradients are so soft that the low resolution never shows.
 */
class DuskView(ctx: Context) : View(ctx), android.view.Choreographer.FrameCallback {
    private class G(val color: Int, val x: Float, val y: Float, val r: Float, val dx: Float, val dy: Float, val ds: Float, val period: Float)
    // centre, radius (fractions of the screen), drift and period: the prototype's CSS keyframes
    private val glows = listOf(
        G(0xFFFF6B2C.toInt(), .156f, .167f, .30f, .172f, .167f, .2f, 27f),
        G(0xFFF0244B.toInt(), .570f, .208f, .27f, -.125f, .25f, -.15f, 33f),
        G(0xFF7B2CF5.toInt(), .883f, .375f, .32f, -.203f, .125f, .15f, 39f),
        G(0xFF2C4BFF.toInt(), .539f, .986f, .35f, .141f, -.194f, .1f, 45f),
        G(0xFFC2187F.toInt(), .141f, .875f, .23f, .203f, -.111f, .25f, 31f),
    )
    private val bw = 256; private val bh = 144
    private val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    private val c = Canvas(bmp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN) }
    private val out = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.Rect()
    private val shade = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { s ->
        val sc = Canvas(s); val p = Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, bh.toFloat(), intArrayOf(Color.argb(38, 10, 6, 20), Color.argb(0, 10, 6, 20), Color.argb(90, 10, 6, 20), Color.argb(220, 6, 3, 12)),
            floatArrayOf(0f, .3f, .62f, 1f), android.graphics.Shader.TileMode.CLAMP); sc.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        p.shader = android.graphics.LinearGradient(0f, 0f, bw.toFloat(), 0f, intArrayOf(Color.argb(140, 10, 6, 20), Color.argb(0, 10, 6, 20)), floatArrayOf(0f, .55f), android.graphics.Shader.TileMode.CLAMP)
        sc.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
    }
    private var t0 = 0L
    private var running = false
    private var last = 0L

    init { paintFrame(0f) }

    private fun paintFrame(sec: Float) {
        c.drawColor(Color.parseColor("#0A0614"))
        for (g in glows) {
            // ease-in-out back and forth, like CSS "alternate"
            val ph = (sec / g.period) % 2f; val u = if (ph < 1f) ph else 2f - ph
            val e = (1 - kotlin.math.cos(Math.PI * u).toFloat()) / 2f
            val cx = (g.x + g.dx * e) * bw; val cy = (g.y + g.dy * e) * bh; val r = g.r * (1 + g.ds * e) * bw
            paint.shader = android.graphics.RadialGradient(cx, cy, r, intArrayOf(g.color, Color.argb(85, Color.red(g.color), Color.green(g.color), Color.blue(g.color)), Color.TRANSPARENT),
                floatArrayOf(0f, .45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
        }
        c.drawBitmap(shade, 0f, 0f, null)
    }

    override fun doFrame(now: Long) {
        if (!running) return
        if (now - last >= 50_000_000L) { last = now; paintFrame((now - t0) / 1e9f); invalidate() } // 20 fps is plenty for this slow drift
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }

    private var wanted = false
    private var pausedAt = 0L
    private val resume = Runnable { if (wanted) go() }
    private fun go() {
        if (running) return; running = true
        val now = System.nanoTime(); if (t0 == 0L) t0 = now else if (pausedAt > 0) t0 += now - pausedAt // continue where it stopped, no jump
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }
    private fun halt() { if (running) { running = false; pausedAt = System.nanoTime() } }

    fun start() { wanted = true; go() }
    fun stop() { wanted = false; removeCallbacks(resume); halt() }

    /** A key was pressed: give the frame budget to the cards, drift again 1.5 s after the remote goes quiet. */
    fun nudge() { if (!wanted) return; halt(); removeCallbacks(resume); postDelayed(resume, 1500) }

    override fun onDraw(cv: Canvas) { dst.set(0, 0, width, height); cv.drawBitmap(bmp, null, dst, out) }
}

/**
 * Silver horizon under the fan, like the rim of a planet seen from space: one gentle arc, thickest in the
 * middle and tapering to nothing at both ends (the curve of the Tesla "T" top), with a few still points of light.
 * Static: drawn once into a bitmap.
 */
class RimView(ctx: Context, private val cx: Float, private val cy: Float, private val half: Float, private val sag: Float, private val thick: Float) : View(ctx) {
    private var bmp: Bitmap? = null
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w == 0) return
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            val l = cx - half; val r = cx + half; val endY = cy + sag
            // crescent: upper and lower quadratic edges meet at the tips, `thick` apart at the centre
            val shape = android.graphics.Path().apply {
                moveTo(l, endY); quadTo(cx, 2 * cy - endY, r, endY)
                quadTo(cx, 2 * (cy + thick) - endY, l, endY); close()
            }
            val metal = android.graphics.LinearGradient(l, 0f, r, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(160, 150, 156, 172), Color.argb(255, 236, 238, 245), Color.WHITE, Color.argb(255, 236, 238, 245), Color.argb(160, 150, 156, 172), Color.TRANSPARENT),
                floatArrayOf(0f, .18f, .42f, .5f, .58f, .82f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = metal; alpha = 70
                maskFilter = android.graphics.BlurMaskFilter(thick * 3, android.graphics.BlurMaskFilter.Blur.NORMAL) })
            c.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = metal })
            // one solid silver block sitting on the crest, with a faint glow
            val bw = 28f; val bh = 5f
            val block = RectF(cx - bw / 2, cy - bh / 2 + thick / 2, cx + bw / 2, cy + bh / 2 + thick / 2)
            c.drawRoundRect(RectF(block).apply { inset(-6f, -6f) }, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 230, 235, 255)
                maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL) })
            c.drawRoundRect(block, bh / 2, bh / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(0f, block.top, 0f, block.bottom, Color.WHITE, Color.argb(255, 196, 200, 214), android.graphics.Shader.TileMode.CLAMP) })
        }
    }
    override fun onDraw(c: Canvas) { bmp?.let { c.drawBitmap(it, 0f, 0f, null) } }
    fun reveal() { alpha = 0f; animate().alpha(1f).setDuration(500).start() }
}

