package com.jarvis.phone
import android.accessibilityservice.AccessibilityService as A
import android.content.*
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object CommandRouter {
    var onThinking: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val vision = Regex("(camera|look at|see this|what is this|what's this|what am i holding|what do you see|read this|identify|in front of me)")

    private fun start(c: Context, i: Intent, say: (String) -> Unit) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { (JarvisAccessibilityService.instance ?: c).startActivity(i) } catch (e: Exception) { say("I could not do that, sir") }
    }

    fun run(c: Context, raw: String, say: (String) -> Unit) {
        val t = raw.lowercase().trim().removePrefix("please ").trim()
        val a = JarvisAccessibilityService.instance
        fun g(action: Int, ok: String) { if (a?.performGlobalAction(action) == true) say(ok) else say("Enable the accessibility service first") }
        fun st(i: Intent) = start(c, i, say)
        if (vision.containsMatchIn(t)) { onThinking?.invoke(); st(Intent(c, CameraActivity::class.java).putExtra("q", raw)); return }
        when {
            t in listOf("go back", "back") -> g(A.GLOBAL_ACTION_BACK, "Back")
            t in listOf("go home", "home") -> g(A.GLOBAL_ACTION_HOME, "Home")
            t.contains("recent apps") -> g(A.GLOBAL_ACTION_RECENTS, "Recents")
            t == "open notifications" || t == "show notifications" -> g(A.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
            t.contains("quick settings") -> g(A.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
            t.contains("lock") && t.contains("screen") -> g(A.GLOBAL_ACTION_LOCK_SCREEN, "Locking")
            t.contains("screenshot") -> g(A.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot")
            t.startsWith("scroll down") -> a?.scroll(true)
            t.startsWith("scroll up") -> a?.scroll(false)
            t.startsWith("click ") || t.startsWith("tap ") || t.startsWith("press ") -> {
                val target = t.substringAfter(" ").trim()
                say(if (a?.clickText(target) == true) "Done, sir" else "I could not find $target")
            }
            t.startsWith("open ") -> openApp(c, t.removePrefix("open ").trim(), say, ::st)
            t.contains("volume up") -> (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            t.contains("volume down") -> (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            t.contains("flashlight") || t.contains("torch") -> {
                val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                try { cm.setTorchMode(cm.cameraIdList[0], !t.contains("off")) } catch (_: Exception) { say("Torch unavailable") }
            }
            Regex("(what.?s the time|what time|current time|the time now|time is it|^time$)").containsMatchIn(t) -> say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + ", sir")
            Regex("(today.?s date|what.?s the date|what date|what day)").containsMatchIn(t) -> say("Today is " + SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date()) + ", sir")
            else -> { onThinking?.invoke(); AiBrain.ask(c, raw, null, say) }   // anything else -> AI
        }
    }

    /** Actions chosen by the AI. */
    fun perform(c: Context, action: String, o: JSONObject, say: (String) -> Unit) {
        fun st(i: Intent) = start(c, i, say)
        fun autoSend() { if (o.optBoolean("auto_send")) main.postDelayed({ JarvisAccessibilityService.instance?.clickText("Send") }, 3000) }
        val to = o.optString("to")
        when (action) {
            "open_app" -> openApp(c, o.optString("name").lowercase(), say, ::st)
            "email" -> {
                val i = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    if (to.contains("@")) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, o.optString("subject"))
                    putExtra(Intent.EXTRA_TEXT, o.optString("body"))
                }
                st(i); autoSend()
            }
            "sms" -> {
                val num = numberFor(c, to) ?: to.filter { it.isDigit() || it == '+' }
                st(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", o.optString("body"))); autoSend()
            }
            "whatsapp" -> {
                val num = (numberFor(c, to) ?: to).filter { it.isDigit() }
                if (num.isBlank()) say("I can't find that number, sir.")
                else { st(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(o.optString("body"))))); autoSend() }
            }
            "call" -> call(c, to, say, ::st)
            "search" -> st(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(o.optString("query")))))
            "maps" -> st(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(o.optString("query")))))
            "alarm" -> st(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, o.optInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, o.optInt("minute")).putExtra(AlarmClock.EXTRA_MESSAGE, o.optString("label"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "timer" -> st(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, o.optInt("seconds"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "click" -> JarvisAccessibilityService.instance?.clickText(o.optString("text"))
            "type" -> JarvisAccessibilityService.instance?.typeText(o.optString("text"))
            "camera" -> st(Intent(c, CameraActivity::class.java).putExtra("q", o.optString("question", "What is this?")))
        }
    }

    private fun openApp(c: Context, name: String, say: (String) -> Unit, start: (Intent) -> Unit) {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == name }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
        val i = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (i != null) { say("Opening $name, sir"); start(i) } else say("I can't find $name, sir")
    }

    private fun numberFor(c: Context, who: String): String? {
        if (who.isBlank()) return null
        who.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 3 }?.let { return it }
        var num: String? = null
        try {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$who%"), null)?.use {
                if (it.moveToFirst()) num = it.getString(0)
            }
        } catch (_: Exception) {}
        return num
    }

    private fun call(c: Context, who: String, say: (String) -> Unit, start: (Intent) -> Unit) {
        val num = numberFor(c, who)
        if (num == null) { say("I can't find $who, sir"); return }
        say("Calling $who, sir"); start(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
    }
}
