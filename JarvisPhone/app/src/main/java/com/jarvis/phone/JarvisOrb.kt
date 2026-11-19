package com.jarvis.phone
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.provider.Settings
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator

class OrbView(c: Context) : View(c) {
    private var t = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f
        val s = 0.78f + 0.22f * t                       // breathing scale
        // soft outer glow
        p.shader = RadialGradient(cx, cy, r * s, intArrayOf(Color.argb(150, 0, 200, 255), Color.argb(60, 0, 150, 255), Color.TRANSPARENT), floatArrayOf(0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL; cv.drawCircle(cx, cy, r * s, p)
        p.shader = null
        // thin rings
        p.style = Paint.Style.STROKE
        p.color = Color.argb((140 + 90 * t).toInt(), 120, 230, 255); p.strokeWidth = 3f
        cv.drawCircle(cx, cy, r * 0.55f * s, p)
        p.color = Color.argb(90, 120, 230, 255); p.strokeWidth = 2f
        cv.drawCircle(cx, cy, r * 0.72f * s, p)
        // bright core
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r * 0.4f * s, intArrayOf(Color.WHITE, Color.argb(255, 120, 230, 255), Color.argb(0, 0, 160, 255)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r * 0.4f * s, p)
        p.shader = null
    }
}

class OrbOverlay(private val c: Context) {
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OrbView? = null
    fun show() {
        if (view != null || !Settings.canDrawOverlays(c)) return
        val size = (c.resources.displayMetrics.density * 140).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 160 }
        view = OrbView(c).also { try { wm.addView(it, lp) } catch (_: Exception) { view = null } }
    }
    fun hide() { view?.let { try { wm.removeView(it) } catch (_: Exception) {} }; view = null }
}
