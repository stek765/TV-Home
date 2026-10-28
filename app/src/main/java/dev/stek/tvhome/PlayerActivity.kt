package dev.stek.tvhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

class PlayerActivity : Activity() {
    companion object {
        fun open(ctx: Context, t: Title, season: Int, ep: Episode?, startMs: Long) = ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java).putExtra("t", t.json().toString()).putExtra("season", season)
                .putExtra("ep", ep?.json()?.toString()).putExtra("start", startMs))
    }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var t: Title
    private lateinit var sc: Sc
    private lateinit var store: Store
    private lateinit var view: PlayerView
    private lateinit var cover: FrameLayout
    private lateinit var coverText: TextView
    private lateinit var heading: LinearLayout
    private lateinit var headTitle: TextView
    private lateinit var headSub: TextView
    private var player: ExoPlayer? = null
    private var season = 0
    private var ep: Episode? = null
    private var eps = listOf<Episode>()
    private val tick = object : Runnable { override fun run() { save(); ui.postDelayed(this, 5000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        t = Title.from(JSONObject(intent.getStringExtra("t")!!)); sc = Sc.get(this); store = Store(this)
        season = intent.getIntExtra("season", 0); ep = intent.getStringExtra("ep")?.let { Episode.from(JSONObject(it)) }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); controllerShowTimeoutMs = 3500; keepScreenOn = true }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(30), dp(48), dp(60))
            background = gradient(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, Color.argb(200, 0, 0, 0), Color.TRANSPARENT) }
        headTitle = label(24f, Color.WHITE, "sans-serif-medium"); headSub = label(15f, C.dim)
        heading.addView(headTitle); heading.addView(headSub)
        root.addView(heading, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> heading.animate().alpha(if (v == View.VISIBLE) 1f else 0f).setDuration(200).start() })

        // loading curtain: backdrop + spinner until the first frame is on screen
        cover = FrameLayout(this)
        cover.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.45f; Img.into(this, t.backdrop) }, FrameLayout.LayoutParams(-1, -1))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        mid.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }, LinearLayout.LayoutParams(dp(54), dp(54)))
        coverText = label(18f, Color.WHITE, "sans-serif-medium", 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) }
        mid.addView(coverText, LinearLayout.LayoutParams(-1, -2))
        cover.addView(mid, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        newPlayer()
        if (t.isTv && season > 0) io.execute { eps = runCatching { sc.episodes(t, season) }.getOrDefault(emptyList()) }
        start(intent.getLongExtra("start", 0))
    }

    private var failures = 0
    private var netFails = 0
    private var startAt = 0L
    private var rendered = false // a frame was shown at least once with this title: the file itself is fine
    private val clean = Runnable { failures = 0 }
    // data trickling in a few bytes at a time never trips the read timeout (it resets on every byte), so a
    // stalled load can hang forever with no error at all: this is the backstop that forces a retry anyway.
    private val stall = Runnable {
        val at = player?.currentPosition ?: startAt
        if (netFails++ >= 40) { curtain("Connessione assente.\nControlla il Wi-Fi e premi Indietro.", spin = false); return@Runnable }
        curtain("Connessione debole…\nriprovo", spin = true)
        releasePlayer(); newPlayer(); start(at)
    }
    private var parkedAt: Long? = null
    private val softPrefs by lazy { getSharedPreferences("decoder", MODE_PRIVATE) }

    /**
     * This TV's hardware H.264 decoder (OMX.MS.AVC) crashes when HLS switches quality mid-film.
     * So: one fixed quality (the best the screen supports, never switched), fall back to another decoder
     * if the hardware one refuses, and on a crash rebuild the whole player and resume where it was (twice).
     */
    private fun newPlayer() {
        // weak Wi-Fi drops packets for seconds: the default 8 s handshake/read limits turn that into an error
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Referer" to "https://vixcloud.cc/"))
            .setConnectTimeoutMs(25_000).setReadTimeoutMs(25_000)
        // titles the hardware decoder refused once go straight to Android's software decoder (720p max, the CPU copes)
        val soft = softPrefs.getBoolean("t${t.id}", false)
        val selector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
            val all = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            if (soft) all.sortedBy { if (it.softwareOnly) 0 else 1 } else all
        }
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        // modest buffer: 15–30 s and at most 12 MB, so the decoder keeps enough memory on this 1 GB TV
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        Img.clear()
        player = ExoPlayer.Builder(this, renderers).setLoadControl(load)
            .setMediaSourceFactory(HlsMediaSource.Factory(http).setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8)))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().also { p ->
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("it")
                    .setForceHighestSupportedBitrate(true).setMaxVideoSize(if (soft) 1280 else 1920, if (soft) 720 else 1080).build()
                view.player = p
                attachCompressor(p)
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { rendered = true; netFails = 0; ui.removeCallbacks(stall)
                        if (!softPrefs.getBoolean("t${t.id}", false)) softPrefs.edit().putBoolean("hw${t.id}", true).apply(); ui.removeCallbacks(clean); ui.postDelayed(clean, 60_000); cover.animate().alpha(0f).setDuration(400).withEndAction { cover.visibility = View.GONE }.start() }
                    override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { save(); next() } }
                    override fun onPlayerError(e: PlaybackException) {
                        ui.removeCallbacks(stall)
                        val at = p.currentPosition
                        // paused: TCL's FreezeManager puts an app that stopped playing video to sleep after ~2 s and the
                        // decoder times out. Not a real failure: park on a pause screen and rebuild when OK is pressed.
                        if (!p.playWhenReady) {
                            ui.post { save(); releasePlayer(); parkedAt = at
                                curtain("❚❚   In pausa\nOK per riprendere", spin = false) }
                            return
                        }
                        // The TCL hardware H.264 decoder (OMX.MS.AVC) rejects some normal files outright. Only a failure
                        // before the first frame, at the start position, on a title that never played in hardware, means
                        // "incompatible file": remember it and use the software decoder from now on. A stall mid-film is
                        // the TV running out of memory, not the file: just resume and keep the hardware decoder.
                        val hwRefused = (e.cause?.message ?: e.message ?: "").contains("OMX.MS.AVC") || generateSequence(e as Throwable) { it.cause }.any { (it.message ?: "").contains("OMX.MS.AVC") }
                        if (!rendered && hwRefused && abs(at - startAt) < 2_000 && !softPrefs.getBoolean("hw${t.id}", false) &&
                            (e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
                            softPrefs.edit().putBoolean("t${t.id}", true).apply()
                        // network drop (timeout / no connection): the file is fine, wait for the Wi-Fi instead of burning the failure budget
                        if (e.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
                            if (netFails++ >= 40) { curtain("Connessione assente.\nControlla il Wi-Fi e premi Indietro.", spin = false); return }
                            curtain("Connessione debole…\nriprovo", spin = true)
                            ui.postDelayed({ releasePlayer(); newPlayer(); start(at) }, 3_000L)
                            return
                        }
                        // the hardware decoder refuses for a few seconds when the TV's video memory (CMA) is short: back off and retry
                        if (failures++ >= 6) { curtain("Questo video non si riesce a riprodurre.\nPremi Indietro.", spin = false); return }
                        // fresh player and a fresh signed link (they expire), same position
                        curtain("Un attimo…", spin = true)
                        ui.postDelayed({ releasePlayer(); newPlayer(); start(at) }, 1_000L * failures * failures) // 1, 4, 9, 16… s
                    }
                })
            }
    }

    private fun curtain(text: String, spin: Boolean = true) {
        cover.animate().cancel(); cover.visibility = View.VISIBLE; cover.alpha = 1f
        view.hideController(); heading.alpha = 0f
        ((cover.getChildAt(1) as LinearLayout).getChildAt(0)).visibility = if (spin) View.VISIBLE else View.GONE
        coverText.text = text
    }

    private fun start(at: Long) {
        startAt = at
        val e = ep
        headTitle.text = t.name
        headSub.text = if (e != null) "Stagione $season  ·  Episodio ${e.number}  ·  ${e.name}" else t.meta
        curtain(if (e != null) "${t.name}\nS$season E${e.number} · ${e.name}" else t.name)
        io.execute {
            val r = runCatching { sc.stream(t, e?.id) }
            ui.post {
                val p = player ?: return@post
                r.onSuccess { url -> p.setMediaItem(MediaItem.fromUri(url), at.coerceAtLeast(0)); p.prepare(); p.playWhenReady = true
                        ui.removeCallbacks(stall); ui.postDelayed(stall, 20_000) }
                    .onFailure { curtain("Non riesco ad aprire il video.\n${it.message ?: ""}", spin = false) }
            }
        }
    }

    private fun next() {
        val e = ep ?: return finish()
        io.execute {
            if (eps.isEmpty()) eps = runCatching { sc.episodes(t, season) }.getOrDefault(emptyList())
            val i = eps.indexOfFirst { it.id == e.id }
            var s = season; var n = eps.getOrNull(i + 1)
            if (n == null) { val more = runCatching { sc.episodes(t, season + 1) }.getOrDefault(emptyList()); if (more.isNotEmpty()) { s = season + 1; eps = more; n = more.first() } }
            ui.post {
                if (n == null) { finish(); return@post }
                season = s; ep = n; failures = 0; start(0)
            }
        }
    }

    private fun save() {
        val p = player ?: return
        val dur = p.duration
        if (dur == MC.TIME_UNSET || dur <= 0 || p.currentPosition < 5_000) return
        store.save(t, season, ep, p.currentPosition, dur)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val parked = parkedAt
        if (parked != null && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_SPACE)) {
            parkedAt = null; newPlayer(); start(parked); return true
        }
        val p = player
        if (cover.visibility == View.VISIBLE && e.keyCode != KeyEvent.KEYCODE_BACK) return true // still loading: nothing to pause or seek yet
        if (p != null && e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            // left/right always seek; holding the key speeds up from 10 s to 60 s per step
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val step = 10_000L * (1 + e.repeatCount / 4).coerceAtMost(6)
                val fwd = e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || e.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                p.seekTo((p.currentPosition + if (fwd) step else -step).coerceIn(0, dur - 1000))
                view.showController(); return true
            }
            // OK and the remote's play/pause always toggle, whatever the on-screen bar has focused
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE ->
                { if (e.repeatCount == 0) { if (p.isPlaying) p.pause() else p.play() }; view.showController(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); view.showController(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); view.showController(); return true }
        }
        if (e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) return true
        return super.dispatchKeyEvent(e) || view.dispatchKeyEvent(e)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick); save(); player?.pause() }
    override fun onDestroy() { releasePlayer(); super.onDestroy() }

    private var compressor: android.media.audiofx.DynamicsProcessing? = null

    /**
     * Night-mode compressor shaped like Dolby "Film Standard", tuned on measured StreamingCommunity audio
     * (~-27.5 LUFS integrated, dialogue -33..-41, action -20..-24, peaks <= -7.5 dBFS): above -32 dB squeeze 3:1,
     * knee 6 dB, attack 10 ms, release 300 ms (no pumping), +8 dB makeup, limiter at -2 dB (Netflix true-peak margin).
     * Android's own DynamicsProcessing on the player's audio session: no CPU-heavy work in the app.
     */
    private fun attachCompressor(p: ExoPlayer) = runCatching {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val session = am.generateAudioSessionId()
        p.setAudioSessionId(session)
        val dp = android.media.audiofx.DynamicsProcessing.Config.Builder(
            android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, true, 1, false, 0, true).build()
        for (ch in 0 until 2) {
            dp.getMbcByChannelIndex(ch).getBand(0).apply {
                isEnabled = true; cutoffFrequency = 20000f
                attackTime = 10f; releaseTime = 300f; ratio = 3f; threshold = -32f; kneeWidth = 6f; noiseGateThreshold = -90f; expanderRatio = 1f
                preGain = 0f; postGain = 8f
            }
            dp.getLimiterByChannelIndex(ch).apply { isEnabled = true; linkGroup = 0; attackTime = 1f; releaseTime = 60f; ratio = 10f; threshold = -2f; postGain = 0f }
        }
        compressor = android.media.audiofx.DynamicsProcessing(0, session, dp).apply { enabled = true }
    }.onFailure { android.util.Log.w("TVHome", "compressor not available", it) }

    private fun releasePlayer() {
        ui.removeCallbacks(stall)
        compressor?.release(); compressor = null
        player?.release(); player = null
    }
}
