package com.jarvis.phone
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/** Needed so Android lets Jarvis see/control the active music player (user turns on "notification access"). */
class JarvisNotificationListener : NotificationListenerService()

object Music {
    private val h = Handler(Looper.getMainLooper())

    fun hasAccess(c: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    private fun sessions(c: Context): List<MediaController> = try {
        (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
            .getActiveSessions(ComponentName(c, JarvisNotificationListener::class.java))
    } catch (e: Exception) { emptyList() }

    private fun isPlaying(m: MediaController) = m.playbackState?.state == PlaybackState.STATE_PLAYING

    /** pause / play(resume) / next / previous on whatever player is active. */
    fun control(c: Context, cmd: String, say: (String) -> Unit) {
        val s = sessions(c)
        val ctl = if (cmd == "pause") (s.firstOrNull { isPlaying(it) } ?: s.firstOrNull()) else s.firstOrNull()
        if (ctl != null) {
            when (cmd) {
                "pause" -> ctl.transportControls.pause()
                "next" -> ctl.transportControls.skipToNext()
                "previous" -> ctl.transportControls.skipToPrevious()
                else -> ctl.transportControls.play()
            }
        } else {
            val code = when (cmd) {
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY
            }
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            if (!hasAccess(c)) { say("Please allow notification access in my app, so I can control your music, sir."); return }
        }
        say(when (cmd) { "pause" -> "Paused, sir"; "next" -> "Next track, sir"; "previous" -> "Previous track, sir"; else -> "Playing, sir" })
    }

    private fun pkgFor(app: String?): String? = when {
        app == null -> null
        app.contains("spotify") -> "com.spotify.music"
        app.contains("amazon") || app.contains("prime") -> "com.amazon.mp3"
        app.contains("music") -> "com.google.android.apps.youtube.music"
        else -> null
    }

    fun play(c: Context, query: String, app: String?, say: (String) -> Unit, start: (Intent) -> Unit) {
        val pm = c.packageManager
        if (app != null && app.contains("youtube") && !app.contains("music")) {
            say("Playing $query on YouTube, sir")
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).setPackage("com.google.android.youtube"))
            return
        }
        val pkg = pkgFor(app)
        say("Playing $query, sir")
        if (pkg == null) {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(SearchManager.QUERY, query)
            start(if (i.resolveActivity(pm) != null) i else Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
            return
        }
        if (pm.getLaunchIntentForPackage(pkg) == null) { say("That music app isn't installed, sir."); return }
        fun viaUi() = uiSearchPlay(c, pkg, query, start)
        val svc = try { pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0).firstOrNull() } catch (e: Exception) { null }
        if (svc == null) { viaUi(); return }
        lateinit var mb: MediaBrowser
        mb = MediaBrowser(c, ComponentName(pkg, svc.serviceInfo.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                try { MediaController(c, mb.sessionToken).transportControls.playFromSearch(query, Bundle()) } catch (e: Exception) { viaUi(); return }
                h.postDelayed({ try { mb.disconnect() } catch (_: Exception) {} }, 2500)
                // if we can see the player and it still isn't playing after 6s, drive the app's screen instead
                h.postDelayed({ if (hasAccess(c) && sessions(c).none { it.packageName == pkg && isPlaying(it) }) viaUi() }, 6000)
            }
            override fun onConnectionFailed() { viaUi() }
        }, null)
        try { mb.connect() } catch (e: Exception) { viaUi() }
    }

    /** Last resort: open the app, tap Search, type the song, press enter, tap Play. */
    private fun uiSearchPlay(c: Context, pkg: String, query: String, start: (Intent) -> Unit) {
        val launch = c.packageManager.getLaunchIntentForPackage(pkg) ?: return
        start(launch)
        val a = JarvisAccessibilityService.instance ?: return
        h.postDelayed({ a.clickAny(listOf("search")) }, 3500)
        h.postDelayed({ a.fillText(query) }, 5000)
        h.postDelayed({ a.pressEnter() }, 6200)
        h.postDelayed({ a.clickAny(listOf("play", "shuffle")) }, 9000)
    }
}
