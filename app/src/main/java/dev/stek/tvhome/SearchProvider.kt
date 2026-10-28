package dev.stek.tvhome

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri

/** Lets the TV's voice/global search list StreamingCommunity titles; picking one opens TVHome's detail page. */
class SearchProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): MatrixCursor {
        val c = MatrixCursor(arrayOf("_id", SearchManager.SUGGEST_COLUMN_TEXT_1, SearchManager.SUGGEST_COLUMN_TEXT_2, SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE, SearchManager.SUGGEST_COLUMN_INTENT_DATA, SearchManager.SUGGEST_COLUMN_CONTENT_TYPE))
        val q = (a?.firstOrNull() ?: u.lastPathSegment.orEmpty()).trim()
        if (q.length < 2) return c
        runCatching { Sc.get(context!!).search(q) }.getOrDefault(emptyList()).take(10).forEachIndexed { i, t ->
            c.addRow(arrayOf(i, t.name, "${if (t.isTv) "Serie" else "Film"} · ${t.year}", t.cover, "tvhome://title?t=" + Uri.encode(t.json().toString()), if (t.isTv) "video/*" else "video/*"))
        }
        return c
    }
    override fun getType(u: Uri) = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
