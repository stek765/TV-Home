package dev.stek.tvhome

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign

/** One card of the fan: a title, a "continue" entry or an app. */
class Item(
    val key: String, val name: String, val cover: String = "", val backdrop: String = "", val logo: String = "",
    val meta: String = "", val sub: String = "", val progress: Float = -1f, val art: Drawable? = null, val iconOnly: Boolean = false,
    val payload: Any? = null,
)

class Section(val label: String, var items: List<Item>)

/**
 * Home "Dusk": Linux-dusk background, big clock, title block, one fan of cards.
 * ← → browse the fan, ↑ ↓ change section (the next one peeks from the bottom edge), long OK = app menu.
 * All sizes are the prototype's pixels (the TV draws at 1280×720, so k is 1 there).
 */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sc: Sc
    private lateinit var store: Store
    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private var k = 1f
    private fun px(v: Number) = (v.toFloat() * k)

    private lateinit var root: FrameLayout
    private lateinit var dusk: DuskView
    private lateinit var film: ImageView
    private lateinit var fan: FrameLayout
    private lateinit var rim: RimView
    private lateinit var logo: ImageView
    private lateinit var name: TextView
    private lateinit var meta: TextView
    private lateinit var secLabel: TextView
    private lateinit var dots: LinearLayout
    private lateinit var secPill: LinearLayout
    private lateinit var peek: LinearLayout
    private lateinit var peekLabel: TextView
    private lateinit var peekArrow: TextView
    private lateinit var holdHint: TextView
    private var hintKind: String? = null
    private val hintShow: Runnable = Runnable { if (hintKind != null) { holdHint.text = "Tieni premuto OK per spostare o eliminare le card, o riaggiungerle"; holdHint.animate().alpha(0.6f).setDuration(500).start(); holdHint.postDelayed(hintHide, 4_000) } }
    private val hintHide: Runnable = Runnable { holdHint.animate().alpha(0f).setDuration(600).start(); holdHint.postDelayed(hintShow, 75_000) }
    private lateinit var pills: List<LinearLayout>
    private lateinit var barTop: View
    private lateinit var barBottom: View
    private lateinit var black: TextView
    private val chrome = mutableListOf<View>()

    private var sections = listOf<Section>()
    private var remote = listOf<Pair<String, List<Title>>>()
    private var si = 0
    private val sel = HashMap<String, Int>()
    private var zone = "fan"
    private var ti = 0
    private var switching = false
    private var moving: String? = null
    private var okDown = false
    private var held = false
    private var busy = false
    private var dwell: Runnable? = null

    private val spring = PathInterpolator(.2f, .9f, .25f, 1.05f)
    private val settle = PathInterpolator(.22f, 1f, .36f, 1f)
    private val leave = PathInterpolator(.55f, 0f, .75f, .4f)

    // every installed TV app shows up; these come first, in this order
    private val priority = listOf(
        "com.tcl.tv", "com.netflix.ninja", "org.jellyfin.androidtv", "com.tailscale.ipn",
        "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.google.android.youtube.tv", "it.rainet.androidtv",
        "it.mediaset.infinitytv", "com.dazn", "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "tv.twitch.android.app",
    )
    private val last = listOf("com.android.vending", "com.android.tv.settings")
    private val names = mapOf("com.tcl.tv" to "Canali TV", "com.android.vending" to "Play Store", "com.android.tv.settings" to "Impostazioni")
    // TCL/Google background services that only clutter the row
    private val hidden = setOf("dev.stek.tvhome", "com.tcl.guard", "com.tcl.messagebox", "com.tcl.partnercustomizer", "com.tcl.suspension",
        "com.tcl.smartalexa", "com.tcl.dashboard", "com.tcl.waterfall.overseas", "com.google.android.play.games")
    private val short = mapOf("I titoli del momento" to "Del momento", "Aggiunti di recente" to "Novità", "Top 10 titoli oggi" to "Top 10")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sc = Sc.get(this); store = Store(this)
        k = resources.displayMetrics.widthPixels / 1280f
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0A0614")); clipChildren = false }

        dusk = DuskView(this)
        root.addView(dusk, FrameLayout.LayoutParams(-1, -1))
        // the film of the title you stop on (1.5 s) fades in over the dusk
        film = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(film, FrameLayout.LayoutParams(-1, -1))

        // clock + date
        val clock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock.addView(TextClock(this).apply { format24Hour = "HH:mm"; format12Hour = "HH:mm"; typeface = Sora.at(context, 200); setTextColor(Color.WHITE)
            setTextSize(0, px(92)); letterSpacing = -0.03f; includeFontPadding = false })
        clock.addView(TextClock(this).apply { format24Hour = "EEEE d MMMM"; format12Hour = "EEEE d MMMM"; typeface = Sora.at(context, 300)
            setTextColor(Color.argb(160, 255, 255, 255)); setTextSize(0, px(17)); setPadding(0, px(10).toInt(), 0, 0) })
        root.addView(clock, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = px(64).toInt(); topMargin = px(34).toInt() })

        // section name + dots, top centre
        val sec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(26).toInt(), px(10).toInt(), px(26).toInt(), px(10).toInt()); background = glassBg(px(24)) }
        secPill = sec
        secLabel = text(18, 600, Color.WHITE).apply { letterSpacing = 0.03f }
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8).toInt(), 0, 0) }
        sec.addView(secLabel); sec.addView(dots)
        root.addView(sec, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = px(44).toInt() })
        chrome += sec

        // Cerca + Wi-Fi, top right
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pills = listOf(glassPill("Cerca", "search"), glassPill("Trasmetti", "cast"), glassPill("Wi-Fi", "wifi"))
        pills.forEachIndexed { i, p -> top.addView(p, LinearLayout.LayoutParams(-2, px(50).toInt()).apply { if (i > 0) leftMargin = px(10).toInt() }) }
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = px(60).toInt(); topMargin = px(48).toInt() })
        chrome += top

        // title block
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        info.addView(logo, LinearLayout.LayoutParams(px(400).toInt(), px(84).toInt()))
        name = text(40, 700, Color.WHITE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; letterSpacing = -0.02f }
        info.addView(name, LinearLayout.LayoutParams(px(560).toInt(), -2))
        meta = text(15, 300, Color.argb(160, 255, 255, 255)).apply { setPadding(0, px(14).toInt(), 0, 0) }
        info.addView(meta)
        root.addView(info, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = px(64).toInt(); topMargin = px(188).toInt() })
        chrome += info

        // under each card's bottom edge (prototype geometry: centre card bottom at y≈504, sides drop with the arc)
        rim = RimView(this, px(640), px(590), px(380), px(22), px(3))
        root.addView(rim, FrameLayout.LayoutParams(-1, -1))
        chrome += rim
        fan = FrameLayout(this).apply { clipChildren = false }
        root.addView(fan, FrameLayout.LayoutParams(-1, -1))
        chrome += fan

        // the next section peeks in from the bottom edge
        peek = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f }
        peekArrow = text(26, 800, Color.WHITE).apply { text = "↓"; setPadding(0, 0, px(12).toInt(), 0) }
        peekLabel = text(16, 600, Color.WHITE).apply { letterSpacing = 0.06f }
        val chip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18).toInt(), px(6).toInt(), px(22).toInt(), px(6).toInt()); background = glassBg(px(22)); addView(peekArrow); addView(peekLabel) }
        android.animation.ObjectAnimator.ofFloat(peekArrow, View.TRANSLATION_Y, -px(3), px(4)).apply {
            duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator() }.start()
        peek.addView(chip, LinearLayout.LayoutParams(-2, -2))
        root.addView(peek, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = px(30).toInt() })
        chrome += peek
        // a quiet grey reminder of the hold-OK gesture, shown now and then (not part of `chrome`: it keeps its own faint alpha)
        holdHint = text(15, 500, Color.argb(255, 163, 172, 191)).apply { alpha = 0f; letterSpacing = 0.02f }
        root.addView(holdHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(88).toInt() })

        // cinema: bars + blackout when a film starts
        barTop = View(this).apply { setBackgroundColor(Color.BLACK) }; barBottom = View(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(barTop, FrameLayout.LayoutParams(-1, 0, Gravity.TOP)); root.addView(barBottom, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        black = text(13, 400, Color.argb(160, 255, 255, 255)).apply { gravity = Gravity.CENTER; letterSpacing = 0.45f; isAllCaps = true; setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(black, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        remote = sc.cachedHome()
        build(0, animate = false)
        io.execute {
            val fresh = runCatching { sc.home() }
            ui.post {
                fresh.onSuccess { if (remote.isEmpty()) { remote = it; build(0, animate = false) } } // a cached home on screen waits for the next start: no jumps
                if (fresh.isFailure && remote.isEmpty()) toast("Nessuna connessione: controlla il Wi-Fi in alto a destra")
            }
        }
    }

    // ---------- small view helpers ----------
    private fun text(size: Int, weight: Int, color: Int) = TextView(this).apply {
        typeface = Sora.at(context, weight); setTextSize(0, px(size)); setTextColor(color); includeFontPadding = false
    }

    private fun glassBg(r: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(12, 255, 255, 255), Color.argb(22, 255, 255, 255))).apply {
        cornerRadius = r; setStroke(1, Color.argb(48, 255, 255, 255))
    }

    private fun glassPill(label: String, glyph: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(18).toInt(), 0, px(18).toInt(), 0); background = glassBg(px(25))
        addView(Glyph(context, glyph), LinearLayout.LayoutParams(px(19).toInt(), px(19).toInt()).apply { rightMargin = px(9).toInt() })
        // long network names are cut, so the three pills never reach the section title in the middle
        addView(text(15, 400, Color.WHITE).apply { text = label; maxWidth = px(120).toInt(); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }

    /** Legibility shade, baked once: light at the top, dark at the bottom and on the left (the prototype's two gradients). */
    private fun shade() = object : View(this) {
        private var bmp: Bitmap? = null
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w == 0 || h == 0) return
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bm ->
                val c = Canvas(bm); val p = Paint()
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.argb(38, 10, 6, 20), Color.argb(0, 10, 6, 20), Color.argb(90, 10, 6, 20), Color.argb(220, 6, 3, 12)),
                    floatArrayOf(0f, .3f, .62f, 1f), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Color.argb(140, 10, 6, 20), Color.argb(0, 10, 6, 20)), floatArrayOf(0f, .55f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
        }
        override fun onDraw(c: Canvas) { bmp?.let { c.drawBitmap(it, 0f, 0f, null) } }
    }

    // ---------- data → sections ----------
    private fun buildSections(): List<Section> {
        val out = mutableListOf<Section>()
        store.list().takeIf { it.isNotEmpty() }?.let { w ->
            out += Section("Continua", w.map { x ->
                val sub = if (x.ep != null) "S${x.season} E${x.ep.number}  ·  ${if (x.done) "Prossimo episodio" else x.ep.name}" else "Rimangono ${fmtTime(x.dur - x.pos)}"
                Item("t:${x.title.id}", x.title.name, x.title.cover, x.title.backdrop, x.title.logo, x.title.meta, sub, if (x.done) -1f else x.progress, payload = x)
            })
        }
        out += Section("App", appItems())
        remote.forEach { (l, ts) -> if (ts.isNotEmpty()) out += Section(short[l] ?: l, ts.map { t -> Item("t:${t.id}", t.name, t.cover, t.backdrop, t.logo, t.meta, payload = t) }) }
        out.forEach { it.items = it.items + searchItem } // Cerca closes every list: through the loop it is the first thing left of the start
        return out
    }

    private val searchItem = Item(SEARCH, "Cerca un film o una serie", meta = "Su StreamingCommunity  ·  OK per cercare")

    private fun removed() = prefs.getString("apps_removed", "")!!.split(",").filter { it.isNotEmpty() }.toSet()

    private fun appItems(): List<Item> {
        val pm = packageManager
        val pkgs = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.plus(last).distinct().filter { it !in hidden && it !in removed() }
        fun rank(p: String) = priority.indexOf(p).takeIf { it >= 0 } ?: last.indexOf(p).takeIf { it >= 0 }?.let { 10_000 + it } ?: 1_000
        val saved = prefs.getString("apps_order2", "")!!.split(",")
        return pkgs.mapNotNull { pkg ->
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = names[pkg] ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val banner = runCatching { pm.getActivityBanner(launch) ?: pm.getApplicationBanner(pkg) }.getOrNull()
            Item("app:$pkg", label, art = banner ?: pm.getApplicationIcon(pkg), iconOnly = banner == null, meta = "App", payload = launch)
        }.sortedWith(compareBy({ rank(it.key.removePrefix("app:")) }, { it.name.lowercase() }))
            .sortedBy { c -> saved.indexOf(c.key).let { if (it < 0) Int.MAX_VALUE else it } } // the user's own order wins
    }

    private fun cur() = sections.getOrNull(si)
    private fun selOf(s: Section) = (sel[s.label] ?: 0).coerceIn(0, max(0, s.items.size - 1))

    // ---------- the fan ----------
    private fun cardView(it: Item): FrameLayout {
        val w = px(300).toInt(); val h = px(169).toInt()
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = px(18); setColor(Color.parseColor("#1B1630")) }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            pivotX = w / 2f; pivotY = h * 1.4f
        }
        if (it.key == SEARCH) { // a glass card with the magnifier, same size as the others
            card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(20, 255, 255, 255))).apply {
                cornerRadius = px(18); setStroke(px(1).toInt(), Color.argb(70, 255, 255, 255)) }
        }
        val img = ImageView(this).apply { scaleType = if (it.iconOnly) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP }
        if (it.art != null) { img.setImageDrawable(it.art.constantState?.newDrawable()?.mutate() ?: it.art); if (it.iconOnly) img.setPadding(w / 3, h / 4, w / 3, h / 4) } else Img.into(img, it.cover, 2)
        card.addView(img, FrameLayout.LayoutParams(-1, -1))
        if (it.key == SEARCH) card.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(Glyph(context, "search"), LinearLayout.LayoutParams(px(40).toInt(), px(40).toInt()))
            addView(text(19, 700, Color.WHITE).apply { text = "Cerca film e serie"; gravity = Gravity.CENTER; setPadding(0, px(12).toInt(), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
            addView(text(13, 400, Color.argb(190, 255, 255, 255)).apply { text = "su StreamingCommunity"; gravity = Gravity.CENTER; setPadding(0, px(5).toInt(), 0, 0) }, LinearLayout.LayoutParams(-2, -2)) }, FrameLayout.LayoutParams(-1, -1))
        if (it.progress > 0f) {
            card.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(2); setColor(Color.argb(76, 255, 255, 255)) } },
                FrameLayout.LayoutParams(-1, px(4).toInt(), Gravity.BOTTOM).apply { setMargins(px(14).toInt(), 0, px(14).toInt(), px(12).toInt()) })
            card.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#FF6B2C"), Color.parseColor("#F0244B"))).apply { cornerRadius = px(2) } },
                FrameLayout.LayoutParams(((w - px(28)) * it.progress).toInt().coerceAtLeast(px(6).toInt()), px(4).toInt(), Gravity.BOTTOM or Gravity.START).apply { setMargins(px(14).toInt(), 0, 0, px(12).toInt()) })
        }
        // dimmer (side cards are darker, never see-through) + move badge + focus ring, on top
        card.addView(text(22, 700, Color.WHITE).apply { text = "‹   Sposta   ›"; gravity = Gravity.CENTER; setBackgroundColor(Color.argb(170, 0, 0, 0)); visibility = View.GONE; tag = "move" },
            FrameLayout.LayoutParams(-1, -1))
        card.addView(View(this).apply { tag = "ring" }, FrameLayout.LayoutParams(-1, -1))
        // 2) jagged edges: a rotated, clipped view has no anti-aliased border. Rendered as a filtered layer with a
        // transparent 2 px margin, the rotation smooths the edge like any picture.
        val pad = px(2).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad, pad, pad); pivotX = w / 2f + pad; pivotY = h * 1.4f + pad
            setLayerType(View.LAYER_TYPE_HARDWARE, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)); tag = it.key }
        frame.addView(card, FrameLayout.LayoutParams(w, h))
        fan.addView(frame, FrameLayout.LayoutParams(w + 2 * pad, h + 2 * pad).apply { leftMargin = (px(640) - w / 2f - pad).toInt(); topMargin = px(360).toInt() })
        return frame
    }

    /** Ghost cards: the list's far end shown blurred beyond each edge (loop), with their position in the virtual line. */
    private val ghosts = java.util.WeakHashMap<View, Int>()

    private fun ghostView(it: Item, vi: Int) { ghosts[cardView(it)] = vi }

    /** Places every card on the arc around the selected one (the prototype's formula). */
    private fun layout(cascade: Boolean, entering: Boolean = false, instant: Boolean = false) {
        val s = cur() ?: return; val c = selOf(s)
        var real = 0
        for (i in 0 until fan.childCount) {
            val v = fan.getChildAt(i); val ghost = ghosts[v]; val d = (ghost ?: real++) - c; val a = abs(d)
            val lifted = moving != null && s.items.getOrNull(i)?.key == moving
            // the other end of the list sits apart (a gap marks where the list ends) and darker
            val gap = if (ghost == null) 0f else if (ghost < 0) -px(260) else px(260)
            val tx = px(d * 165) + gap; val ty = px(if (a == 0) -20f else a.toFloat().pow(1.35f) * 9 - 6) - if (lifted) px(14) else 0f
            val sc = (if (a == 0) 1.1f else .9f) - a * .03f
            v.translationZ = (100 - a).toFloat()
            v.animate().cancel()
            v.visibility = if (a > 5 || (ghost != null && moving != null)) View.INVISIBLE else View.VISIBLE
            val al = if (a > 4) 0f else if (ghost != null) .55f else 1f
            if (instant) { v.translationX = tx; v.translationY = ty; v.rotation = d * 4f; v.scaleX = sc; v.scaleY = sc; v.alpha = al }
            else v.animate().translationX(tx).translationY(ty).rotation(d * 4f).scaleX(sc).scaleY(sc).alpha(al)
                .setInterpolator(if (entering) settle else spring).setDuration(if (entering) 700 else 500)
                .setStartDelay(if (cascade) a * (if (entering) 50L else 30L) else 0L).start()
            // side cards darker: a colour filter on the picture, no extra layer to blend
            val b = if (a == 0) 1f else if (ghost != null) .4f else max(.35f, .85f - a * .12f)
            // the picture is always the first child of the card (its tag belongs to the image loader)
            ((v as FrameLayout).getChildAt(0) as FrameLayout).getChildAt(0).let { it as ImageView }.colorFilter = if (b >= 1f) null else android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(b, b, b, 1f) })
            v.findViewWithTag<View>("ring").background = if (a == 0 && zone == "fan") GradientDrawable().apply {
                cornerRadius = px(18); setStroke(px(3).toInt(), if (lifted) Color.parseColor("#FF6B2C") else Color.WHITE); setColor(Color.TRANSPARENT) } else null
            v.findViewWithTag<View>("move").visibility = if (lifted) View.VISIBLE else View.GONE
        }
        s.items.getOrNull(c)?.let { show(it) }
    }

    /** Section change: the old cards leave upwards, then the new ones rise one after another. */
    private fun build(dir: Int, animate: Boolean = true) {
        val keepLabel = cur()?.label
        sections = buildSections()
        if (dir == 0) si = sections.indexOfFirst { it.label == keepLabel }.takeIf { it >= 0 } ?: si.coerceIn(0, max(0, sections.size - 1))
        val old = (0 until fan.childCount).map { fan.getChildAt(it) }
        peek.animate().alpha(0f).translationY(px(30)).setDuration(200).start()
        if (animate && old.isNotEmpty()) {
            switching = true
            old.forEach { it.animate().translationYBy(-dir * px(140)).alpha(0f).setInterpolator(leave).setDuration(320).setStartDelay(0).withLayer().start() }
            ui.postDelayed({ fill(dir, true); switching = false }, 300)
        } else fill(dir, animate)
    }

    private fun fill(dir: Int, animate: Boolean) {
        fan.removeAllViews()
        val s = cur() ?: return
        if (dir != 0) sel[s.label] = 0
        s.items.forEach { cardView(it) }
        val n = s.items.size
        if (n >= 6) { // loop: the end of the list waits, blurred, left of the start (and the start right of the end)
            for (k in 1..3) ghostView(s.items[n - k], -k)
            for (k in 0..2) ghostView(s.items[k], n + k)
        }
        if (animate) for (i in 0 until fan.childCount) fan.getChildAt(i).apply { translationY = dir * px(300); scaleX = .7f; scaleY = .7f; alpha = 0f }
        header()
        rim.reveal()
        layout(cascade = animate, entering = animate)
    }

    private fun header() {
        secLabel.text = cur()?.label ?: ""
        secPill.scaleX = 1.18f; secPill.scaleY = 1.18f
        secPill.animate().scaleX(1f).scaleY(1f).setInterpolator(spring).setDuration(650).start()
        dots.removeAllViews()
        sections.forEachIndexed { i, _ ->
            dots.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(3); setColor(if (i == si) Color.WHITE else Color.argb(76, 255, 255, 255)) } },
                LinearLayout.LayoutParams(px(if (i == si) 22 else 6).toInt(), px(6).toInt()).apply { if (i > 0) leftMargin = px(8).toInt() })
        }
        val next = sections.getOrNull(si + 1)
        peekArrow.text = if (next == null) "↑" else "↓"
        peekLabel.text = next?.label ?: "Torna all'inizio"
        val sec = sections[si]
        holdHint.removeCallbacks(hintShow); holdHint.removeCallbacks(hintHide); holdHint.animate().cancel(); holdHint.alpha = 0f
        hintKind = if (sec.label == "App") "App" else null
        if (hintKind != null) holdHint.postDelayed(hintShow, 4_000)
        peek.animate().alpha(1f).translationY(0f).setStartDelay(200).setDuration(600).setInterpolator(spring).start()
    }

    // ---------- title block, halo, film ----------
    private var shownKey: String? = null
    private fun show(it: Item) {
        if (moving != null) { name.text = "Sposta ${it.name}"; logo.visibility = View.GONE; name.visibility = View.VISIBLE; meta.text = "←  →  per spostarla   ·   OK per confermare"; return }
        if (shownKey == it.key) return
        shownKey = it.key
        logo.visibility = if (it.logo.isEmpty()) View.GONE else View.VISIBLE
        name.visibility = if (it.logo.isEmpty()) View.VISIBLE else View.GONE
        name.text = it.name
        Img.into(logo, it.logo, alpha = true)
        meta.text = when {
            it.payload is Intent -> "App  ·  OK per aprire"
            else -> listOf(it.sub, it.meta).filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        }
        filmFor(it)
    }

    private fun filmFor(it: Item) {
        dwell?.let { r -> ui.removeCallbacks(r) }
        if (dusk.visibility != View.VISIBLE) { dusk.visibility = View.VISIBLE; dusk.start() }
        film.animate().alpha(0f).setDuration(400).start()
        if (it.backdrop.isEmpty() || busy) return
        dwell = Runnable {
            titleOf(it)?.let { t -> io.execute { // hint the trailer only when there is one
                val has = runCatching { sc.detail(t).trailer.isNotEmpty() }.getOrDefault(false)
                ui.post { if (has && shownKey == it.key && moving == null) meta.text = "${meta.text}   ·   ▶ Play: trailer" } } }
            Img.fetch(it.backdrop, 1, false, { b ->
                if (shownKey != it.key) return@fetch
                film.setImageBitmap(b); film.scaleX = 1.06f; film.scaleY = 1.06f
                film.animate().alpha(1f).setDuration(800).withEndAction { if (film.alpha == 1f) { dusk.stop(); dusk.visibility = View.INVISIBLE } }.start()
                film.animate().scaleX(1f).scaleY(1f).setDuration(9000).start()
            }, { shownKey == it.key })
        }.also { r -> ui.postDelayed(r, 700) }
    }

    // ---------- remote ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        dusk.nudge()
        if (busy) return true
        val code = e.keyCode
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (isOk) { // short press = open, held until the key repeats = menu
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { okDown = true; held = false }
                else if (e.repeatCount == 1 && moving == null && zone == "fan") { held = true; longPress() }
            } else if (e.action == KeyEvent.ACTION_UP && okDown) { okDown = false; if (!held) ok() }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (code == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY) { trailer(); return true }
        if (moving != null) {
            when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> moveApp(-1); KeyEvent.KEYCODE_DPAD_RIGHT -> moveApp(1); KeyEvent.KEYCODE_BACK -> endMove() }
            return true
        }
        if (zone == "top") {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> ti = max(0, ti - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> ti = minOf(pills.size - 1, ti + 1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> { zone = "fan"; layout(false) }
            }
            paintTop(); return true
        }
        val s = cur() ?: return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (selOf(s) < s.items.size - 1) { sel[s.label] = selOf(s) + 1; layout(false) } else if (moving == null) wrap(s, 0)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (selOf(s) > 0) { sel[s.label] = selOf(s) - 1; layout(false) } else if (moving == null) wrap(s, s.items.size - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!switching) { if (si < sections.size - 1) { si++; build(1) } else if (si > 0) { si = 0; build(-1) } } // past the last: back to the start
            KeyEvent.KEYCODE_DPAD_UP -> if (!switching) { if (si > 0) { si--; build(-1) } else { zone = "top"; ti = 0; layout(false); paintTop() } }
            KeyEvent.KEYCODE_BACK -> if (selOf(s) > 0) { sel[s.label] = 0; layout(false) } else if (si > 0) { si = 0; build(-1) } // Home: Back goes to the start
        }
        return true
    }

    private fun titleOf(it: Item?): Title? = when (val p = it?.payload) { is Title -> p; is Watch -> p.title; else -> null }

    private fun trailer() {
        val s = cur() ?: return; val t = titleOf(s.items.getOrNull(selOf(s))) ?: return
        io.execute {
            val id = runCatching { sc.detail(t).trailer }.getOrDefault("")
            ui.post { if (id.isEmpty() || !playTrailer(id)) toast("Nessun trailer per ${t.name}") }
        }
    }

    private fun wrap(s: Section, to: Int) {
        if (s.items.size < 6 || switching) return
        switching = true
        fan.animate().alpha(0f).setDuration(140).withEndAction {
            sel[s.label] = to; layout(false, instant = true)
            fan.animate().alpha(1f).setDuration(220).withEndAction { switching = false }.start()
        }.start()
    }

    /** The TV has Chromecast built in: most people don't know, so the pill explains it in three steps. */
    private fun explainCast() {
        val name = runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull() ?: "Smart TV"
        AlertDialog.Builder(this).setTitle("Trasmetti dal telefono a questa TV")
            .setMessage("1.  Telefono e TV sulla stessa rete Wi-Fi (la vedi qui accanto).\n\n" +
                "2.  Apri YouTube, Netflix, Spotify, RaiPlay, Chrome o le foto e premi l'icona Trasmetti (lo schermo con le onde).\n\n" +
                "3.  Scegli «$name».\n\nIl video parte sulla TV; il telefono diventa il telecomando e resta libero per altro.")
            .setPositiveButton("Ho capito", null).show()
    }

    private fun paintTop() = pills.forEachIndexed { i, p ->
        val on = zone == "top" && i == ti
        p.background = if (on) GradientDrawable().apply { cornerRadius = px(25); setColor(Color.WHITE) } else glassBg(px(25))
        (p.getChildAt(1) as TextView).setTextColor(if (on) Color.parseColor("#0A0614") else Color.WHITE)
        (p.getChildAt(0) as Glyph).tint(if (on) Color.parseColor("#0A0614") else Color.WHITE)
        p.animate().scaleX(if (on) 1.08f else 1f).scaleY(if (on) 1.08f else 1f).setInterpolator(spring).setDuration(350).start()
    }

    private fun ok() {
        if (moving != null) { endMove(); return }
        if (zone == "top") { when (ti) { 0 -> openSearch(); 1 -> explainCast(); else -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }; return }
        val s = cur() ?: return; val item = s.items.getOrNull(selOf(s)) ?: return
        if (item.key == SEARCH) { openSearch(); return }
        when (val p = item.payload) {
            is Intent -> runCatching { startActivity(p) }.onFailure { toast("Impossibile aprire ${item.name}") }
            is Title -> DetailActivity.open(this, p)
            is Watch -> if (p.done || p.ep == null && p.title.isTv) DetailActivity.open(this, p.title)
                else cinema(item.name) { PlayerActivity.open(this, p.title, p.season, p.ep, p.pos) }
        }
    }

    /** Lights down: cinemascope bars close, everything leaves, black with the title, then the film starts. */
    private fun cinema(title: String, go: () -> Unit) {
        busy = true
        dwell?.let { ui.removeCallbacks(it) }
        val bars = PathInterpolator(.65f, 0f, .35f, 1f)
        listOf(barTop, barBottom).forEach { bar ->
            android.animation.ValueAnimator.ofInt(0, px(92).toInt()).apply { duration = 1100; interpolator = bars
                addUpdateListener { a -> bar.layoutParams.height = a.animatedValue as Int; bar.requestLayout() } }.start()
        }
        chrome.forEach { it.animate().alpha(0f).setDuration(500).start() }
        black.text = title
        ui.postDelayed({ black.animate().alpha(1f).setDuration(900).start() }, 900)
        ui.postDelayed(go, 2000)
    }

    private fun resetCinema() {
        busy = false
        barTop.layoutParams.height = 0; barBottom.layoutParams.height = 0; barTop.requestLayout(); barBottom.requestLayout()
        black.alpha = 0f; chrome.forEach { it.alpha = 1f }
        peek.alpha = if (sections.getOrNull(si + 1) != null) 1f else 0f
    }

    // ---------- long press: app menu / remove from continue ----------
    private fun longPress() {
        val s = cur() ?: return; val it = s.items.getOrNull(selOf(s)) ?: return
        if (it.payload is Watch) {
            AlertDialog.Builder(this).setTitle(it.name).setItems(arrayOf("Togli da Continua a guardare")) { _, _ ->
                store.remove((it.payload as Watch).title.id); toast("Tolto da Continua a guardare"); build(0, animate = false) }.show()
            return
        }
        if (!it.key.startsWith("app:")) return
        val pkg = it.key.removePrefix("app:"); val gone = removed()
        val menu = listOf("Sposta", "Togli dalla Home") + if (gone.isNotEmpty()) listOf("Rimetti un'app tolta… (${gone.size})") else emptyList()
        AlertDialog.Builder(this).setTitle(it.name).setItems(menu.toTypedArray()) { _, i ->
            when (i) {
                0 -> { moving = it.key; shownKey = null; layout(false) }
                1 -> { prefs.edit().putString("apps_removed", (gone + pkg).joinToString(",")).apply(); refreshApps(); toast("${it.name} tolta dalla Home") }
                2 -> pickRestore(gone)
            }
        }.show()
    }

    private fun moveApp(d: Int) {
        val s = cur() ?: return
        val list = s.items.toMutableList(); val i = list.indexOfFirst { it.key == moving }; val j = i + d
        if (i < 0 || j !in list.indices) return
        if (list[j].key == SEARCH) return // "Cerca" stays at the end
        java.util.Collections.swap(list, i, j); s.items = list
        val v = fan.getChildAt(i); fan.removeViewAt(i); fan.addView(v, j) // real cards come first among the children, ghosts after
        sel[s.label] = j
        prefs.edit().putString("apps_order2", list.filter { it.key != SEARCH }.joinToString(",") { it.key }).apply()
        layout(false)
    }

    private fun endMove() { moving = null; shownKey = null; layout(false) }

    private fun refreshApps() {
        val s = cur() ?: return
        val at = selOf(s)
        s.items = appItems() + searchItem; sel[s.label] = at.coerceAtMost(max(0, s.items.size - 1))
        fill(0, false)
    }

    private fun pickRestore(gone: Set<String>) {
        val pkgs = gone.toList()
        val labels = pkgs.map { p -> names[p] ?: runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
        AlertDialog.Builder(this).setTitle("Rimetti nella Home").setItems(labels.toTypedArray()) { _, i ->
            prefs.edit().putString("apps_removed", (gone - pkgs[i]).joinToString(",")).apply(); refreshApps(); toast("${labels[i]} rimessa nella Home")
        }.show()
    }

    // ---------- lifecycle ----------
    override fun onResume() {
        super.onResume(); dusk.start(); refreshWifi(); ensureVoice()
        if (busy) resetCinema()
        if (sections.isNotEmpty()) { // "Continua" may have changed while watching, and cards were freed in onStop
            val had = sections.firstOrNull()?.label == "Continua"; val now = store.list().isNotEmpty()
            if (had || now || fan.childCount == 0) { shownKey = null; build(0, animate = false) }
        }
    }

    /** An update switches the voice-search service off; with the one-time grant (adb pm grant ... WRITE_SECURE_SETTINGS) the Home switches it back on. */
    private fun ensureVoice() = runCatching {
        val id = "$packageName/${Voice::class.java.name}"
        val cur = Settings.Secure.getString(contentResolver, "enabled_accessibility_services").orEmpty()
        if (id !in cur.split(':')) {
            Settings.Secure.putString(contentResolver, "enabled_accessibility_services", if (cur.isEmpty()) id else "$cur:$id")
            Settings.Secure.putInt(contentResolver, "accessibility_enabled", 1)
        }
    }

    /** Behind a film the Home gives its memory back (cards, layers, backdrop, picture cache); onResume rebuilds it. */
    override fun onStop() {
        holdHint.removeCallbacks(hintShow); holdHint.removeCallbacks(hintHide); holdHint.alpha = 0f
        super.onStop()
        fan.removeAllViews(); film.setImageDrawable(null); film.alpha = 0f
        dusk.visibility = View.VISIBLE
        Img.clear()
    }

    override fun onPause() { super.onPause(); dusk.stop(); dwell?.let { ui.removeCallbacks(it) } }

    private fun refreshWifi() {
        val ssid = runCatching { (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ssid.trim('"') }.getOrNull()
        (pills[2].getChildAt(1) as TextView).text = if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") "Wi-Fi" else ssid
    }

    private fun openSearch() = startActivity(Intent(this, SearchActivity::class.java))
    companion object { const val SEARCH = "search" }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
