package com.jarvis.phone
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared "how loud is it" state that drives the orb's waves. */
object OrbState {
    @Volatile var speaking = false   // Jarvis is talking
    @Volatile var mic = 0f           // your voice level 0..1
    @Volatile var kick = 0f          // bumped on each spoken word
}

/** Cyan holo-orb: 3D mesh sphere core, equalizer ring, and wobbling wave rings that move with the voice. */
class OrbView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var level = 0f
    private val twoPi = 6.2831855f

    // geodesic sphere (icosahedron subdivided once) for the 3D core
    private val verts = ArrayList<FloatArray>()
    private val edgeList = ArrayList<IntArray>()
    init {
        val t0 = (1f + sqrt(5f)) / 2f
        val base = arrayOf(floatArrayOf(-1f, t0, 0f), floatArrayOf(1f, t0, 0f), floatArrayOf(-1f, -t0, 0f), floatArrayOf(1f, -t0, 0f),
            floatArrayOf(0f, -1f, t0), floatArrayOf(0f, 1f, t0), floatArrayOf(0f, -1f, -t0), floatArrayOf(0f, 1f, -t0),
            floatArrayOf(t0, 0f, -1f), floatArrayOf(t0, 0f, 1f), floatArrayOf(-t0, 0f, -1f), floatArrayOf(-t0, 0f, 1f))
        fun norm(v: FloatArray): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }
        base.forEach { verts.add(norm(it)) }
        val faces = intArrayOf(0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11, 1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9, 4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1)
        val mid = HashMap<Long, Int>()
        fun midpoint(i: Int, j: Int): Int {
            val key = minOf(i, j).toLong() * 1000 + maxOf(i, j)
            return mid.getOrPut(key) {
                val a = verts[i]; val b = verts[j]
                verts.add(norm(floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])))
                verts.size - 1
            }
        }
        val seen = HashSet<Long>()
        fun edge(i: Int, j: Int) { val key = minOf(i, j).toLong() * 1000 + maxOf(i, j); if (seen.add(key)) edgeList.add(intArrayOf(i, j)) }
        var k = 0
        while (k < faces.size) {
            val a = faces[k]; val b = faces[k + 1]; val c = faces[k + 2]
            val ab = midpoint(a, b); val bc = midpoint(b, c); val ca = midpoint(c, a)
            edge(a, ab); edge(ab, b); edge(b, bc); edge(bc, c); edge(c, ca); edge(ca, a); edge(ab, bc); edge(bc, ca); edge(ca, ab)
            k += 3
        }
    }
    private val sx = FloatArray(verts.size)
    private val sy = FloatArray(verts.size)
    private val sz = FloatArray(verts.size)

    private fun cyan(a: Int) = Color.argb(a.coerceIn(0, 255), 90, 215, 255)

    private fun wave(cx: Float, cy: Float, R: Float, k: Int, t: Float) {
        path.rewind()
        val steps = 120
        val base = R * (0.80f + 0.06f * k)
        val amp = R * (0.012f + 0.055f * level)
        for (j in 0..steps) {
            val th = twoPi * j / steps
            val w = 0.5f * sin(3 * th + t * (0.9f + 0.3f * k) + k) + 0.3f * sin(5 * th - t * (1.3f + 0.2f * k) + 2 * k) +
                0.2f * sin(8 * th + t * 2.1f + 3 * k) + level * 0.25f * sin(13 * th + t * 7f)
            val r = base + amp * w
            val x = cx + r * cos(th); val y = cy + r * sin(th)
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    override fun onDraw(cv: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        val cx = width / 2f; val cy = height / 2f
        val R = width / 2f * 0.9f

        // voice level: your mic while you talk, a speech-like envelope (+ word kicks) while Jarvis talks
        val target = when {
            OrbState.speaking -> 0.35f + 0.25f * (0.5f + 0.5f * sin(t * 13f) * sin(t * 5.3f + 1f)) + OrbState.kick * 0.4f
            OrbState.mic > 0.05f -> OrbState.mic
            else -> 0.08f
        }
        OrbState.kick *= 0.85f
        level += (target - level) * 0.25f

        // soft blue glow
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        p.shader = RadialGradient(cx, cy, R, intArrayOf(Color.argb((70 + 90 * level).toInt().coerceIn(0, 255), 20, 140, 255), Color.argb(35, 20, 120, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, R, p); p.shader = null
        // dark disc behind the bars
        p.color = Color.argb(120, 4, 16, 40); cv.drawCircle(cx, cy, R * 0.74f, p)

        // outline rings
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.2f
        p.color = cyan(130); cv.drawCircle(cx, cy, R * 0.44f, p)
        p.color = cyan(80); cv.drawCircle(cx, cy, R * 0.74f, p)

        // equalizer bars
        val n = 54; val r0 = R * 0.50f
        p.strokeWidth = d * 3.2f; p.strokeCap = Paint.Cap.BUTT
        for (i in 0 until n) {
            val a = twoPi * i / n + t * 0.15f
            val e = 0.5f + 0.5f * sin(i * 0.9f + t * 2.2f) * sin(i * 0.37f - t * 1.3f)
            val len = R * (0.05f + 0.15f * e * (0.4f + 0.9f * level))
            p.color = Color.argb((110 + 120 * e).toInt().coerceIn(0, 255), 40, 150, 255)
            cv.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * (r0 + len), cy + sin(a) * (r0 + len), p)
        }

        // wobbling wave rings (glow pass + bright pass)
        p.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 3) {
            cv.save()
            if (k == 1) { cv.rotate(t * 8f, cx, cy); cv.scale(1f, 0.93f, cx, cy) }   // slight tilt for depth
            wave(cx, cy, R, k, t)
            p.strokeWidth = d * 6f; p.color = cyan(28); cv.drawPath(path, p)
            p.strokeWidth = d * 3f; p.color = cyan(70); cv.drawPath(path, p)
            p.strokeWidth = d * 1.2f; p.color = cyan(235); cv.drawPath(path, p)
            cv.restore()
        }

        // 3D mesh sphere core
        val rc = R * 0.34f * (1f + 0.06f * level)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - rc * 0.3f, cy - rc * 0.3f, rc * 1.5f,
            intArrayOf(Color.argb(255, 170, 235, 255), Color.argb(255, 40, 160, 235), Color.argb(255, 10, 70, 160)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, rc, p); p.shader = null
        val yaw = t * 0.55f; val pitch = 0.45f
        for (i in verts.indices) {
            val v = verts[i]
            val x1 = v[0] * cos(yaw) + v[2] * sin(yaw)
            val z1 = -v[0] * sin(yaw) + v[2] * cos(yaw)
            val y1 = v[1] * cos(pitch) - z1 * sin(pitch)
            val z2 = v[1] * sin(pitch) + z1 * cos(pitch)
            sx[i] = cx + x1 * rc; sy[i] = cy + y1 * rc; sz[i] = z2
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 0.9f
        for (e in edgeList) {
            val depth = ((sz[e[0]] + sz[e[1]]) / 2f + 1f) / 2f
            p.color = Color.argb((40 + 190 * depth).toInt().coerceIn(0, 255), 215, 245, 255)
            cv.drawLine(sx[e[0]], sy[e[0]], sx[e[1]], sy[e[1]], p)
        }
        p.strokeWidth = d * 6f; p.color = cyan(70); cv.drawCircle(cx, cy, rc + d * 2f, p)
        p.strokeWidth = d * 2.4f; p.color = Color.WHITE; cv.drawCircle(cx, cy, rc, p)

        postInvalidateOnAnimation()
    }
}

class OrbOverlay(private val c: Context) {
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: OrbView? = null
    fun show() {
        if (view != null || !Settings.canDrawOverlays(c)) return
        val size = (c.resources.displayMetrics.density * 240).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 110 }
        view = OrbView(c).also { try { wm.addView(it, lp) } catch (_: Exception) { view = null } }
    }
    fun hide() { view?.let { try { wm.removeView(it) } catch (_: Exception) {} }; view = null }
}
