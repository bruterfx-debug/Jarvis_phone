package com.jarvis.phone
import android.content.*
import androidx.core.content.ContextCompat
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { ContextCompat.startForegroundService(c, Intent(c, VoiceService::class.java)) } catch (_: Exception) {}
    }
}
