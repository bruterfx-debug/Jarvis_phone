package com.jarvis.phone
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import kotlin.math.cos
import kotlin.math.sin

/** Golden holographic sphere: many rings + particles, each moving and "breathing" on its own. */
class OrbView(c: Context) : View(c) {
    private val rnd = java.util.Random(7)
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val RN = 12
    private val rRad = FloatArray(RN) { 0.40f + 0.58f * it / RN }
    private val rTilt = FloatArray(RN) { rnd.nextFloat() * 3.14f }
    private val rZ = FloatArray(RN) { rnd.nextFloat() * 6.28f }
    private val rSpin = FloatArray(RN) { (0.3f + rnd.nextFloat() * 1.2f) * (if (rnd.nextBoolean()) 1f else -1f) }
    private val rSweep = FloatArray(RN) { 2.0f + rnd.nextFloat() * 3.8f }
    private val rBreath = FloatArray(RN) { 0.8f + rnd.nextFloat() * 1.8f }
    private val rPhase = FloatArray(RN) { rnd.nextFloat() * 6.28f }
    private val rPrec = FloatArray(RN) { 0.2f + rnd.nextFloat() * 0.7f }
    private val PN = 170
    private val pLat = FloatArray(PN) { (rnd.nextFloat() - 0.5f) * 3.1f }
    private val pLon = FloatArray(PN) { rnd.nextFloat() * 6.28f }
    private val pSpd = FloatArray(PN) { (0.15f + rnd.nextFloat() * 0.9f) * (if (rnd.nextBoolean()) 1f else -1f) }
    private val pRad = FloatArray(PN) { 0.3f + rnd.nextFloat() * 0.7f }
    private val pBr = FloatArray(PN) { 0.6f + rnd.nextFloat() * 2f }
    private val pPh = FloatArray(PN) { rnd.nextFloat() * 6.28f }
    private val pSz = FloatArray(PN) { 0.8f + rnd.nextFloat() * 1.8f }
    private var ox = 0f; private var oy = 0f; private var oz = 0f

    private fun glob(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        val x1 = x * cos(yaw) + z * sin(yaw)
        val z1 = -x * sin(yaw) + z * cos(yaw)
        val y1 = y * cos(pitch) - z1 * sin(pitch)
        val z2 = y * sin(pitch) + z1 * cos(pitch)
        ox = x1; oy = y1; oz = z2
    }

    override fun onDraw(cv: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val cx = width / 2f; val cy = height / 2f
        val R = width / 2f * 0.86f * (1f + 0.05f * sin(t * 2.1f))
        val yaw = t * 0.3f; val pitch = 0.45f + 0.15f * sin(t * 0.5f)

        // warm core glow, pulsing
        val g = 0.75f + 0.25f * sin(t * 2.6f)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, R * 0.55f, intArrayOf(Color.argb((200 * g).toInt(), 255, 210, 110),
            Color.argb((90 * g).toInt(), 255, 140, 20), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, R * 0.55f, p)
        p.shader = null

        // rings
        p.style = Paint.Style.STROKE
        val steps = 40
        for (k in 0 until RN) {
            val rr = R * rRad[k] * (1f + 0.07f * sin(t * rBreath[k] + rPhase[k]))
            val tilt = rTilt[k] + rPrec[k] * sin(t * 0.6f + rPhase[k])
            val start = t * rSpin[k]
            var px = 0f; var py = 0f
            for (j in 0..steps) {
                val a = start + rSweep[k] * j / steps
                val x = rr * cos(a); val z = rr * sin(a)
                val y1 = -z * sin(tilt); val z1 = z * cos(tilt)
                val x2 = x * cos(rZ[k]) - y1 * sin(rZ[k])
                val y2 = x * sin(rZ[k]) + y1 * cos(rZ[k])
                glob(x2, y2, z1, yaw, pitch)
                val sx = cx + ox; val sy = cy + oy
                val depth = ((oz / R) + 1f) / 2f
                if (j > 0) {
                    val fade = sin(3.1416f * j / steps)
                    val al = (255 * fade * (0.25f + 0.75f * depth)).toInt().coerceIn(0, 255)
                    p.color = Color.argb(al, 255, (150 + 80 * depth).toInt(), (30 + 60 * depth).toInt())
                    p.strokeWidth = d * (0.8f + 1.4f * depth)
                    cv.drawLine(px, py, sx, sy, p)
                }
                px = sx; py = sy
            }
        }

        // particles
        p.style = Paint.Style.FILL
        for (i in 0 until PN) {
            val lon = pLon[i] + t * pSpd[i]
            val rr = R * pRad[i] * (1f + 0.1f * sin(t * pBr[i] + pPh[i]))
            val cl = cos(pLat[i])
            glob(rr * cl * cos(lon), rr * sin(pLat[i]), rr * cl * sin(lon), yaw, pitch)
            val depth = ((oz / R) + 1f) / 2f
            val tw = 0.6f + 0.4f * sin(t * pBr[i] * 2f + pPh[i])
            p.color = Color.argb((60 + 195 * depth * tw).toInt().coerceIn(0, 255), 255, 190, 70)
            cv.drawCircle(cx + ox, cy + oy, d * pSz[i] * (0.6f + 0.8f * depth), p)
        }
        postInvalidateOnAnimation()
    }
}

class OrbOverlay(private val c: Context) {
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OrbView? = null
    fun show() {
        if (view != null || !Settings.canDrawOverlays(c)) return
        val size = (c.resources.displayMetrics.density * 220).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 120 }
        view = OrbView(c).also { try { wm.addView(it, lp) } catch (_: Exception) { view = null } }
    }
    fun hide() { view?.let { try { wm.removeView(it) } catch (_: Exception) {} }; view = null }
}
