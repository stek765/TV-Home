package dev.stek.tvhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One "continue watching" entry per title: the last episode touched and where it stopped. */
data class Watch(val title: Title, val season: Int, val ep: Episode?, val pos: Long, val dur: Long, val done: Boolean) {
    val progress get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    fun json(): JSONObject = JSONObject().put("title", title.json()).put("season", season).put("ep", ep?.json())
        .put("pos", pos).put("dur", dur).put("done", done)
    companion object {
        fun from(o: JSONObject) = Watch(Title.from(o.getJSONObject("title")), o.optInt("season"),
            o.optJSONObject("ep")?.let { Episode.from(it) }, o.optLong("pos"), o.optLong("dur"), o.optBoolean("done"))
    }
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun pos(titleId: Int, epId: Int?) = p.getLong("p_${titleId}_${epId ?: 0}", 0)
    fun progress(titleId: Int, epId: Int?): Float {
        val d = p.getLong("d_${titleId}_${epId ?: 0}", 0)
        return if (d > 0) (pos(titleId, epId).toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun list(): List<Watch> = runCatching {
        val a = JSONArray(p.getString("list", "[]")); (0 until a.length()).map { Watch.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun last(titleId: Int) = list().firstOrNull { it.title.id == titleId }

    fun save(t: Title, season: Int, ep: Episode?, pos: Long, dur: Long) {
        val done = dur > 0 && pos > dur * 0.93
        val rest = list().filter { it.title.id != t.id }
        // a finished film leaves the row; a finished episode stays so the next one is offered
        val keep = if (done && !t.isTv) rest else listOf(Watch(t, season, ep, pos, dur, done)) + rest
        p.edit().putLong("p_${t.id}_${ep?.id ?: 0}", if (done) 0 else pos).putLong("d_${t.id}_${ep?.id ?: 0}", dur)
            .putString("list", JSONArray(keep.take(20).map { it.json() }).toString()).apply()
    }

    fun remove(titleId: Int) {
        p.edit().putString("list", JSONArray(list().filter { it.title.id != titleId }.map { it.json() }).toString()).apply()
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}
