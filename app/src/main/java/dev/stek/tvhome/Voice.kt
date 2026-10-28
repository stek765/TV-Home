package dev.stek.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Google's assistant won't hand a search to a third-party app, but it prints what it heard ("Cerca Matrix su TV home"). We read that header and run the search ourselves. */
class Voice : AccessibilityService() {
    private val ask = Regex("^(?:cerca|trova)\\s+(.+?)\\s+su\\s+tv\\s*home$", RegexOption.IGNORE_CASE)
    private var last = ""; private var at = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        try {
            Log.d("Voice", "event fired")
            val root = rootInActiveWindow ?: return
            Log.d("Voice", "got root")
            val head = root.findAccessibilityNodeInfosByViewId("com.google.android.katniss:id/super_header").firstOrNull()?.text?.toString() ?: return
            Log.d("Voice", "head='$head'")
            val q = ask.find(head.trim())?.groupValues?.get(1)
            Log.d("Voice", "match=${q}")
            if (q == null) return
            val now = System.currentTimeMillis()
            if (q == last && now - at < 4000) return
            last = q; at = now
            Log.d("Voice", "launching search for '$q'")
            startActivity(Intent(this, SearchActivity::class.java).putExtra("q", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (ex: Exception) {
            Log.e("Voice", "CRASH", ex)
        }
    }
    override fun onInterrupt() {}
}
