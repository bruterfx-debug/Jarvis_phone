package com.jarvis.phone
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        fun btn(t: String, f: () -> Unit) = l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        l.addView(TextView(this).apply {
            text = "Jarvis Phone\nSay \"Jarvis, open YouTube\", \"Jarvis, go back\", \"Jarvis, call Mom\"..."
            textSize = 18f
        })
        btn("1. Grant permissions") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 1)
        }
        btn("2. Enable Accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("3. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("4. Disable battery optimization") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        btn("5. START listening") {
            ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
            Toast.makeText(this, "Listening for \"Jarvis\"", Toast.LENGTH_SHORT).show()
        }
        btn("Stop") { stopService(Intent(this, VoiceService::class.java)) }
        setContentView(ScrollView(this).apply { addView(l) })
    }
}
