package com.jarvis.phone
import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {
    companion object { var instance: JarvisAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(i: android.content.Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, f: (AccessibilityNodeInfo) -> Boolean) {
        if (n == null) return
        if (f(n)) out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, f)
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null) {
            if (x.isClickable && x.isEnabled) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent
        }
        return false
    }

    /** Clicks the first button whose (short) text/description contains a label, or whose view id contains an id word. */
    fun clickAny(labels: List<String>, ids: List<String> = emptyList()): Boolean {
        val root = rootInActiveWindow ?: return false
        val hits = ArrayList<AccessibilityNodeInfo>()
        collect(root, hits) { nd ->
            val s = ((nd.text?.toString() ?: "") + " " + (nd.contentDescription?.toString() ?: "")).trim()
            val id = nd.viewIdResourceName ?: ""
            (s.length in 1..30 && labels.any { s.contains(it, ignoreCase = true) }) ||
                ids.any { id.contains(it, ignoreCase = true) && !id.contains("sender", ignoreCase = true) }
        }
        for (h in hits) if (clickNode(h)) return true
        return false
    }

    fun clickText(t: String): Boolean = clickAny(listOf(t))

    fun fillText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) {
            val l = ArrayList<AccessibilityNodeInfo>()
            collect(root, l) { it.isEditable }
            target = l.firstOrNull()
        }
        if (target == null) return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun typeText(t: String): Boolean = fillText(t)

    fun pressEnter(): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
    }

    /** Keeps looking for the Send button for ~10 seconds and taps it as soon as it appears. */
    fun sendWhenReady(tries: Int = 14) {
        val hnd = Handler(Looper.getMainLooper())
        var n = 0
        lateinit var r: Runnable
        r = Runnable {
            n++
            if (clickAny(listOf("send"), listOf("send")) || n >= tries) return@Runnable
            hnd.postDelayed(r, 700)
        }
        hnd.postDelayed(r, 1800)
    }

    fun scroll(forward: Boolean): Boolean {
        val l = ArrayList<AccessibilityNodeInfo>()
        collect(rootInActiveWindow, l) { it.isScrollable }
        val s = l.firstOrNull() ?: return false
        return s.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
}
