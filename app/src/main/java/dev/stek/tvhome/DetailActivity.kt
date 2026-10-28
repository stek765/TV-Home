package dev.stek.tvhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.util.concurrent.Executors

class DetailActivity : Activity() {
    companion object {
        fun open(ctx: Context, t: Title) = ctx.startActivity(Intent(ctx, DetailActivity::class.java).putExtra("t", t.json().toString()))
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var t: Title
    private lateinit var sc: Sc
    private lateinit var store: Store
    private lateinit var hero: Hero
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var seasonsRow: HorizontalGridView
    private lateinit var epRow: HorizontalGridView
    private lateinit var epCards: CardAdapter
    private var detail: Detail? = null
    private var seasons = listOf<Int>()
    private var season = 1
    private var eps = listOf<Episode>()
    private var loadSeason: Runnable? = null
    private var titleMeta = ""

    private fun showTitleInfo() { meta.text = titleMeta.ifEmpty { t.meta }; plot.text = detail?.plot ?: "" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        t = Title.from(JSONObject(intent.data?.getQueryParameter("t") ?: intent.getStringExtra("t")!!)); sc = Sc.get(this); store = Store(this)
        val pad = dp(64)
        val root = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        hero = Hero(this); root.addView(hero, FrameLayout.LayoutParams(-1, -1)); root.addView(hero.scrims(this, 0.96f)); hero.show(t.backdrop)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(54), 0, 0); clipChildren = false; clipToPadding = false }
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; isFillViewport = true; isFocusable = false }
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        if (t.logo.isNotEmpty()) col.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; Img.into(this, t.logo, alpha = true) },
            LinearLayout.LayoutParams(dp(420), dp(92)).apply { leftMargin = pad })
        else col.addView(label(40f, Color.WHITE, "sans-serif-black", 2).apply { text = t.name }, LinearLayout.LayoutParams(dp(700), -2).apply { leftMargin = pad })

        meta = label(14f, C.dim, "sans-serif-medium").apply { text = t.meta; setPadding(pad, dp(16), pad, 0) }
        col.addView(meta)
        plot = label(15f, Color.parseColor("#D5DAE5"), "sans-serif", 4).apply { setLineSpacing(0f, 1.18f) }
        col.addView(plot, LinearLayout.LayoutParams(dp(600), -2).apply { leftMargin = pad; topMargin = dp(12) })

        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, dp(24), 0, dp(6)); clipChildren = false; clipToPadding = false }
        col.addView(buttons)

        if (t.isTv) {
            seasonsRow = HorizontalGridView(this).apply {
                setPadding(pad, dp(18), pad, dp(4)); clipToPadding = false; clipChildren = false; itemAnimator = null; adapter = Chips(); horizontalSpacing = dp(12); setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                windowAlignmentOffsetPercent = 0f; windowAlignmentOffset = pad; itemAlignmentOffsetPercent = 0f
            }
            col.addView(seasonsRow, LinearLayout.LayoutParams(-1, -2))
            epCards = CardAdapter(this, emptyList(), dp(186), dp(104), ::focusEpisode, { c -> play(c.data as Episode) }, sample = 1)
            epRow = cardRow(epCards, pad)
            col.addView(epRow, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        renderButtons()
        // seasons/episodes sit low on screen: slide the page up while they have focus so nothing is cut off
        if (t.isTv) root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            var v: View? = now; var low = false
            while (v != null) { if (v === epRow || v === seasonsRow) { low = true; break }; v = v.parent as? View }
            if (!low && now != null) showTitleInfo()
            val shift = if (low) (epRow.bottom + dp(28) - scroll.height).coerceAtLeast(0) else 0
            scroll.post { scroll.smoothScrollTo(0, shift) }
        }

        io.execute {
            val d = runCatching { sc.detail(t) }.getOrNull()
            ui.post {
                detail = d ?: return@post
                renderButtons()
                titleMeta = listOfNotNull(t.meta, d.runtime.takeIf { it > 0 && !t.isTv }?.let { "${it / 60}h ${it % 60}m" },
                    d.genres.take(3).joinToString(", ").ifEmpty { null }, d.quality.ifEmpty { null }).joinToString("  ·  ")
                showTitleInfo()
                if (t.isTv) {
                    seasons = d.seasons.ifEmpty { listOf(1) }
                    seasonsRow.adapter?.notifyDataSetChanged()
                    val w = store.last(t.id)
                    selectSeason(if (w != null && w.season in seasons) w.season else seasons.first())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume(); hero.start()
        renderButtons()
        if (t.isTv && eps.isNotEmpty()) refreshEpisodes()
    }

    override fun onPause() { super.onPause(); hero.stop() }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean { hero.nudge(); return super.dispatchKeyEvent(e) }

    /** What the big button should do: resume, next episode, or start. */
    private fun renderButtons() {
        val hadFocus = buttons.hasFocus() || buttons.childCount == 0
        buttons.removeAllViews()
        fun add(text: String, glyph: String, go: () -> Unit) =
            buttons.addView(pill(text, Glyph(this, glyph), go).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(14) })
        if (!t.isTv) {
            val pos = store.pos(t.id, null)
            if (pos > 30_000) { add("Riprendi da ${fmtTime(pos)}", "play") { PlayerActivity.open(this, t, 0, null, pos) }; add("Dall'inizio", "restart") { PlayerActivity.open(this, t, 0, null, 0) } }
            else add("Riproduci", "play") { PlayerActivity.open(this, t, 0, null, 0) }
        } else {
            val w = store.last(t.id)
            when {
                w?.ep != null && !w.done -> {
                    add("Riprendi S${w.season} E${w.ep.number}  ·  ${fmtTime(w.pos)}", "play") { PlayerActivity.open(this, t, w.season, w.ep, w.pos) }
                    add("Dall'inizio", "restart") { PlayerActivity.open(this, t, w.season, w.ep, 0) }
                }
                w?.ep != null && w.done -> add("Prossimo episodio", "play") { nextAfter(w) }
                else -> add("Guarda S1 E1", "play") { firstEpisode() }
            }
        }
        detail?.trailer?.takeIf { it.isNotEmpty() }?.let { id -> add("Trailer", "play") { playTrailer(id) } }
        if (hadFocus) buttons.post { buttons.getChildAt(0)?.requestFocus() }
    }

    private fun firstEpisode() {
        val s = seasons.firstOrNull() ?: 1
        if (season == s && eps.isNotEmpty()) { PlayerActivity.open(this, t, s, eps.first(), 0); return }
        io.execute { val e = runCatching { sc.episodes(t, s) }.getOrNull()?.firstOrNull(); ui.post { if (e != null) PlayerActivity.open(this, t, s, e, 0) } }
    }

    private fun nextAfter(w: Watch) {
        io.execute {
            val cur = runCatching { sc.episodes(t, w.season) }.getOrDefault(emptyList())
            val i = cur.indexOfFirst { it.id == w.ep?.id }
            val (s, e) = if (i >= 0 && i + 1 < cur.size) w.season to cur[i + 1]
                else (w.season + 1) to runCatching { sc.episodes(t, w.season + 1) }.getOrNull()?.firstOrNull()
            ui.post { if (e != null) PlayerActivity.open(this, t, s, e, 0) else plot.text = "Hai visto tutto. Non ci sono altri episodi." }
        }
    }

    private fun selectSeason(s: Int) {
        if (s == season && eps.isNotEmpty()) return
        season = s
        seasonsRow.adapter?.notifyDataSetChanged()
        io.execute {
            val list = runCatching { sc.episodes(t, s) }.getOrDefault(emptyList())
            ui.post { if (season == s) { eps = list; refreshEpisodes() } }
        }
    }

    private fun refreshEpisodes() {
        epCards.items = eps.map { e ->
            Card("e:${e.id}", "${e.number}. ${e.name}", e.cover.ifEmpty { t.cover }, if (e.minutes > 0) "${e.minutes} min" else "", store.progress(t.id, e.id).takeIf { it > 0.01f } ?: -1f, data = e)
        }
        epCards.notifyDataSetChanged()
    }

    private fun focusEpisode(c: Card) {
        val e = c.data as Episode
        meta.text = "Stagione $season  ·  Episodio ${e.number}${if (e.minutes > 0) "  ·  ${e.minutes} min" else ""}"
        plot.text = e.plot.ifEmpty { detail?.plot ?: "" }
    }

    private fun play(e: Episode) {
        val pos = store.pos(t.id, e.id)
        PlayerActivity.open(this, t, season, e, if (pos > 30_000) pos else 0)
    }

    private inner class Chips : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = seasons.size
        override fun onCreateViewHolder(p: ViewGroup, v: Int): RecyclerView.ViewHolder {
            val tv = label(15f, Color.WHITE, "sans-serif-medium").apply {
                gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), 0); isFocusable = true
                layoutParams = RecyclerView.LayoutParams(-2, dp(40)).apply { rightMargin = dp(10) }
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val tv = h.itemView as TextView; val s = seasons[i]
            tv.text = "Stagione $s"
            fun paint(f: Boolean) {
                tv.background = rounded(when { f -> Color.WHITE; s == season -> Color.parseColor("#55FFFFFF"); else -> Color.TRANSPARENT }, dp(20), if (!f && s != season) dp(1) else 0, Color.parseColor("#55FFFFFF"))
                tv.setTextColor(if (f) Color.BLACK else Color.WHITE)
            }
            paint(tv.hasFocus())
            tv.zoomOnFocus(1.06f, lift = 0f) { f ->
                paint(f)
                if (f) { loadSeason?.let { ui.removeCallbacks(it) }; loadSeason = Runnable { selectSeason(s) }.also { ui.postDelayed(it, 300) } }
            }
            tv.setOnClickListener { selectSeason(s); epRow.requestFocus() }
        }
    }
}
