package dev.stek.tvhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)

data class Title(
    val id: Int, val slug: String, val name: String, val isTv: Boolean,
    val cover: String, val backdrop: String, val logo: String, val score: String, val year: String, val seasons: Int,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("slug", slug).put("name", name).put("tv", isTv).put("cover", cover)
        .put("backdrop", backdrop).put("logo", logo).put("score", score).put("year", year).put("seasons", seasons)

    val meta: String get() = listOfNotNull(
        year.ifEmpty { null }, score.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
        if (isTv) (if (seasons > 0) "Serie · $seasons ${if (seasons == 1) "stagione" else "stagioni"}" else "Serie") else "Film",
    ).joinToString("  ·  ")

    companion object {
        fun from(o: JSONObject) = Title(o.getInt("id"), o.s("slug"), o.s("name"), o.optBoolean("tv"), o.s("cover"), o.s("backdrop"),
            o.s("logo"), o.s("score"), o.s("year"), o.optInt("seasons"))
    }
}

data class Episode(val id: Int, val number: Int, val name: String, val cover: String, val minutes: Int, val plot: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("number", number).put("name", name).put("cover", cover).put("minutes", minutes).put("plot", plot)
    companion object {
        fun from(o: JSONObject) = Episode(o.getInt("id"), o.optInt("number"), o.s("name"), o.s("cover"), o.optInt("minutes"), o.s("plot"))
        fun list(a: JSONArray) = (0 until a.length()).map { from(a.getJSONObject(it)) }
    }
}

data class Detail(val plot: String, val runtime: Int, val genres: List<String>, val seasons: List<Int>, val quality: String, val trailer: String = "")

/** StreamingCommunity, entirely on-device: domain discovery, catalogue, search, stream extraction. */
class Sc private constructor(ctx: Context) {
    companion object {
        @Volatile private var inst: Sc? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Sc(ctx.applicationContext).also { inst = it } }
    }

    private val prefs = ctx.getSharedPreferences("sc", Context.MODE_PRIVATE)
    private var base = prefs.getString("base", "https://streamingcommunityz.build")!!
    private var cdn = prefs.getString("cdn", "https://cdn.streamingcommunityz.build")!!
    @Volatile private var version = ""
    private val details = ConcurrentHashMap<Int, Detail>()

    private fun get(url: String, inertia: Boolean = false, referer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        if (inertia) {
            c.setRequestProperty("X-Inertia", "true"); c.setRequestProperty("X-Inertia-Version", version)
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        }
        if (referer != null) c.setRequestProperty("Referer", referer)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun dataPage(html: String): JSONObject {
        val m = Regex("data-page=\"([^\"]+)\"").find(html) ?: error("no data-page")
        return JSONObject(m.groupValues[1].replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"))
    }

    /** Saved domain first; if dead, streamingunity.fun tells the current one (and must point to vixcloud, clones don't). */
    @Synchronized private fun ensureDomain() {
        if (version.isNotEmpty()) return
        fun tryBase(b: String): Boolean = runCatching {
            val d = dataPage(get("$b/it"))
            d.getJSONObject("props").s("cdn_url").takeIf { it.isNotEmpty() }?.let { cdn = it }
            version = d.getString("version"); true
        }.getOrDefault(false)
        if (tryBase(base)) return
        val p = dataPage(get("https://streamingunity.fun/login")).getJSONObject("props")
        check(p.s("scws_url").contains("vixcloud")) { "dominio non verificato" }
        base = p.getString("app_url")
        check(tryBase(base)) { "sito non raggiungibile" }
        prefs.edit().putString("base", base).putString("cdn", cdn).apply()
    }

    private fun props(path: String): JSONObject {
        ensureDomain()
        return runCatching { JSONObject(get("$base$path", inertia = true)) }.getOrElse {
            version = ""; ensureDomain(); JSONObject(get("$base$path", inertia = true)) // site redeployed or moved: retry once
        }.getJSONObject("props")
    }

    private fun title(o: JSONObject): Title {
        val img = HashMap<String, String>()
        o.optJSONArray("images")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { img.putIfAbsent(it.s("type"), "$cdn/images/${it.s("filename")}") } }
        val date = o.s("last_air_date").ifEmpty { o.s("release_date") }
        return Title(o.getInt("id"), o.s("slug"), o.s("name"), o.s("type") == "tv",
            img["cover_mobile"] ?: img["background"] ?: img["cover"] ?: img["poster"] ?: "",
            img["background"] ?: img["cover_mobile"] ?: "", img["logo"] ?: "", o.s("score"), date.take(4), o.optInt("seasons_count"))
    }

    private fun titles(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { title(a.getJSONObject(it)) }

    /** Home rows. Raw JSON is cached so the next start paints instantly. */
    fun home(): List<Pair<String, List<Title>>> {
        val sl = props("/it").getJSONArray("sliders")
        val out = JSONArray()
        val rows = (0 until sl.length()).map { sl.getJSONObject(it) }.map { s ->
            val ts = titles(s.optJSONArray("titles"))
            out.put(JSONObject().put("label", s.s("label")).put("titles", JSONArray(ts.map { it.json() })))
            s.s("label") to ts
        }
        prefs.edit().putString("home", out.toString()).apply()
        return rows
    }

    fun cachedHome(): List<Pair<String, List<Title>>> = runCatching {
        val a = JSONArray(prefs.getString("home", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
            val ts = r.getJSONArray("titles"); r.s("label") to (0 until ts.length()).map { Title.from(ts.getJSONObject(it)) }
        }
    }.getOrDefault(emptyList())

    fun search(q: String): List<Title> = titles(props("/it/search?q=" + URLEncoder.encode(q, "UTF-8")).optJSONArray("titles"))

    fun cachedDetail(t: Title) = details[t.id]

    fun detail(t: Title): Detail = details[t.id] ?: run {
        val o = props("/it/titles/${t.id}-${t.slug}").getJSONObject("title")
        val g = o.optJSONArray("genres"); val se = o.optJSONArray("seasons")
        Detail(o.s("plot"), o.optInt("runtime"),
            if (g == null) emptyList() else (0 until g.length()).map { g.getJSONObject(it).s("name") },
            if (se == null) emptyList() else (0 until se.length()).map { se.getJSONObject(it).optInt("number", it + 1) },
            o.s("quality"), o.optJSONArray("trailers")?.optJSONObject(0)?.s("youtube_id").orEmpty()).also { details[t.id] = it }
    }

    fun episodes(t: Title, season: Int): List<Episode> {
        val a = props("/it/titles/${t.id}-${t.slug}/season-$season").getJSONObject("loadedSeason").getJSONArray("episodes")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { e ->
            val im = e.optJSONArray("images")?.optJSONObject(0)?.s("filename").orEmpty()
            Episode(e.getInt("id"), e.optInt("number"), e.s("name"), if (im.isEmpty()) "" else "$cdn/images/$im", e.optInt("duration"), e.s("plot"))
        }
    }

    /** iframe -> vixcloud embed -> signed HLS master playlist. */
    fun stream(t: Title, episodeId: Int? = null): String {
        ensureDomain()
        val ep = if (episodeId != null) "episode_id=$episodeId&" else ""
        val iframe = get("$base/it/iframe/${t.id}?${ep}canPlayFHD=1", referer = "$base/")
        val src = Regex("src=\"([^\"]+)\"").find(iframe)?.groupValues?.get(1)?.replace("&amp;", "&") ?: error("player non trovato")
        val block = get(src, referer = "$base/").substringAfter("masterPlaylist")
        fun field(re: String) = Regex(re).find(block)?.groupValues?.get(1) ?: error("video non disponibile")
        val url = field("url:\\s*'([^']+)'")
        // h=1 asks for Full HD: only allowed when the page says so, otherwise the playlist answers 403
        val fhd = Regex("canPlayFHD\\s*=\\s*true").containsMatchIn(block)
        return "$url${if ('?' in url) '&' else '?'}token=${field("'token':\\s*'([^']+)'")}&expires=${field("'expires':\\s*'([^']+)'")}${if (fhd) "&h=1" else ""}"
    }
}

/** Opens a YouTube trailer in the YouTube TV app (full screen, its own player and remote controls). */
fun Context.playTrailer(youtubeId: String): Boolean = runCatching {
    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$youtubeId"))
        .setPackage("com.google.android.youtube.tv").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)
