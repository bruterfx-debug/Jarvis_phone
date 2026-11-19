package com.jarvis.phone
import android.accessibilityservice.AccessibilityService as A
import android.content.*
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.*

object CommandRouter {
    fun run(c: Context, raw: String, say: (String) -> Unit) {
        val t = raw.lowercase().trim().removePrefix("please ").trim()
        val a = JarvisAccessibilityService.instance
        fun g(action: Int, ok: String) { if (a?.performGlobalAction(action) == true) say(ok) else say("Enable the accessibility service first") }
        fun start(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { (a ?: c).startActivity(i) } catch (e: Exception) { say("I could not do that") } }
        when {
            t in listOf("go back", "back") -> g(A.GLOBAL_ACTION_BACK, "Back")
            t in listOf("go home", "home") -> g(A.GLOBAL_ACTION_HOME, "Home")
            t.contains("recent") -> g(A.GLOBAL_ACTION_RECENTS, "Recents")
            t.contains("notification") -> g(A.GLOBAL_ACTION_NOTIFICATIONS, "Notifications")
            t.contains("quick settings") -> g(A.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
            t.contains("lock") && t.contains("screen") -> g(A.GLOBAL_ACTION_LOCK_SCREEN, "Locking")
            t.contains("screenshot") -> g(A.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot")
            t.startsWith("scroll down") -> a?.scroll(true)
            t.startsWith("scroll up") -> a?.scroll(false)
            t.startsWith("click ") || t.startsWith("tap ") || t.startsWith("press ") -> {
                val target = t.substringAfter(" ").trim()
                say(if (a?.clickText(target) == true) "Done" else "I could not find $target")
            }
            t.startsWith("type ") -> { a?.typeText(t.removePrefix("type ")) }
            t.startsWith("open ") -> openApp(c, t.removePrefix("open ").trim(), say, ::start)
            t.startsWith("call ") -> call(c, t.removePrefix("call ").trim(), say, ::start)
            t.startsWith("search ") -> start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(t.removePrefix("search for ").removePrefix("search ")))))
            t.contains("volume up") -> (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            t.contains("volume down") -> (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            t.contains("flashlight") || t.contains("torch") -> {
                val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                try { cm.setTorchMode(cm.cameraIdList[0], !t.contains("off")) } catch (_: Exception) { say("Torch unavailable") }
            }
            t.contains("wifi") -> start(Intent(Settings.ACTION_WIFI_SETTINGS))
            t.contains("bluetooth") -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            t.contains("what time") -> say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()))
            else -> say("Sorry, I don't know how to $t")
        }
    }

    private fun openApp(c: Context, name: String, say: (String) -> Unit, start: (Intent) -> Unit) {
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == name }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
        val i = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (i != null) { say("Opening $name"); start(i) } else say("I can't find $name")
    }

    private fun call(c: Context, who: String, say: (String) -> Unit, start: (Intent) -> Unit) {
        var num = who.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 3 }
        if (num == null) try {
            c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$who%"), null)?.use {
                if (it.moveToFirst()) num = it.getString(0)
            }
        } catch (_: Exception) {}
        if (num == null) { say("I can't find $who"); return }
        say("Calling $who"); start(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
    }
}
