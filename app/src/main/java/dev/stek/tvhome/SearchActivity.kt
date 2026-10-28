package dev.stek.tvhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import java.util.concurrent.Executors

class SearchActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sc: Sc
    private lateinit var hero: Hero
    private lateinit var field: EditText
    private lateinit var status: TextView
    private lateinit var grid: VerticalGridView
    private lateinit var cards: CardAdapter
    private var seq = 0
    private var kbRow = 1
    private var kbOpen = false
    private var focusResults = false
    private val typing = Runnable { run(field.text.toString(), false) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sc = Sc.get(this)
        val pad = dp(56)
        val root = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        hero = Hero(this); hero.alpha = 0.55f
        root.addView(hero, FrameLayout.LayoutParams(-1, -1)); root.addView(hero.scrims(this, 0.97f))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, dp(26), pad, 0); clipChildren = false }
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), 0, dp(22), 0); background = rounded(C.glass, dp(28)) }
        val glass = Glyph(this, "search")
        box.addView(glass, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) })
        // Gboard keeps every arrow for itself, even Up on its top row. We see keys before it does (onKeyPreIme),
        // so we follow its row: it opens on "q" (row 1, under the suggestion row 0); Up on row 0 leaves the keyboard.
        field = object : EditText(this) {
            override fun onKeyPreIme(code: Int, e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_DOWN && kbOpen) when (code) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> kbRow = (kbRow + 1).coerceAtMost(4)
                    KeyEvent.KEYCODE_DPAD_UP -> if (kbRow == 0) { hideKeyboard(); return true } else kbRow--
                    KeyEvent.KEYCODE_BACK -> kbOpen = false
                }
                return super.onKeyPreIme(code, e)
            }
        }.apply {
            hint = "Film, serie, anime…"; setHintTextColor(C.faint); setTextColor(Color.WHITE); textSize = 22f
            background = null; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setOnFocusChangeListener { _, f -> box.background = rounded(if (f) Color.parseColor("#4DFFFFFF") else C.glass, dp(28), if (f) dp(2) else 0); if (!f) hideKeyboard() }
            setOnClickListener { showKeyboard() } // OK on the field brings it back
            setOnEditorActionListener { v, _, _ -> ui.removeCallbacks(typing); run(v.text.toString(), true); true }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) { ui.removeCallbacks(typing); if (s.trim().length >= 2) ui.postDelayed(typing, 450) }
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            })
        }
        box.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(box, LinearLayout.LayoutParams(0, dp(58), 1f))
        val voice = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        if (voice.resolveActivity(packageManager) != null)
            bar.addView(pill("Voce", Glyph(this, "mic")) { runCatching { startActivityForResult(voice, 7) } }.apply {
                (layoutParams as LinearLayout.LayoutParams).apply { leftMargin = dp(16); height = dp(58) } })
        col.addView(bar)

        status = label(15f, C.dim, "sans-serif-medium").apply { setPadding(pad, dp(18), pad, 0) }
        col.addView(status)

        cards = CardAdapter(this, emptyList(), dp(196), dp(110), { c -> (c.data as? Title)?.let { hero.show(it.backdrop) } }, { c -> (c.data as? Title)?.let { DetailActivity.open(this, it) } })
        grid = VerticalGridView(this).apply {
            adapter = cards; setNumColumns(4); clipChildren = false; clipToPadding = false; itemAnimator = null
            setPadding(pad, dp(16), pad, dp(40)); setVerticalSpacing(dp(10))
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // nothing typed yet: show what's hot, so the screen is never empty
        sc.cachedHome().firstOrNull()?.let { (l, ts) -> status.text = l; setResults(ts) }
        field.requestFocus()
        val spoken = intent.getStringExtra("q") ?: intent.getStringExtra(android.app.SearchManager.QUERY)
        if (spoken != null) { field.setText(spoken); status.text = "Cerco “$spoken”…"; run(spoken, true) } else ui.postDelayed({ showKeyboard() }, 250)
    }

    override fun onResume() { super.onResume(); hero.start() }
    override fun onPause() { super.onPause(); hero.stop() }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        hero.nudge()
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // Up from the first row of results: back to the field, keyboard closed until OK
            if (grid.hasFocus() && grid.selectedPosition < 4) { field.requestFocus(); hideKeyboard(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun showKeyboard() { field.requestFocus(); kbRow = 1; kbOpen = true; (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0) }

    private fun setResults(ts: List<Title>) {
        cards.items = ts.map { Card("t:${it.id}", it.name, it.cover, it.meta, data = it) }
        cards.notifyDataSetChanged()
        ts.firstOrNull()?.let { hero.show(it.backdrop) }
    }

    private fun run(q: String, submit: Boolean) {
        val query = q.trim()
        if (submit) { hideKeyboard(); focusResults = true }
        if (query.isEmpty()) return
        val my = ++seq
        status.text = "Cerco “$query”…"
        io.execute {
            val r = runCatching { sc.search(query) }
            ui.post {
                if (my != seq) return@post // a newer search already started
                r.onSuccess {
                    status.text = if (it.isEmpty()) "Nessun risultato per “$query”" else "${it.size} risultati per “$query”"
                    setResults(it)
                    if (focusResults && it.isNotEmpty()) { focusResults = false; grid.selectedPosition = 0; grid.post { grid.requestFocus() } }
                }.onFailure { status.text = "Non riesco a cercare: controlla la connessione" }
            }
        }
    }

    private fun hideKeyboard() { kbOpen = false; (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0) }

    @Deprecated("platform API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        field.setText(said); field.setSelection(said.length); run(said, true)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_SEARCH) { showKeyboard(); return true }
        return super.onKeyDown(code, e)
    }
}
