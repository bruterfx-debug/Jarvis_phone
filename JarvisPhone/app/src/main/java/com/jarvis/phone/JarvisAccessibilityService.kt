package com.jarvis.phone
import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {
    companion object { var instance: JarvisAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(i: android.content.Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(t)
        for (n in nodes) {
            var x: AccessibilityNodeInfo? = n
            while (x != null) { if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK); x = x.parent }
        }
        return false
    }
    fun scroll(forward: Boolean): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val s = find(rootInActiveWindow) ?: return false
        return s.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
    fun typeText(t: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }
}
