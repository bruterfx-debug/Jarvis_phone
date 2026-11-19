package com.jarvis.phone
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File

/** Invisible activity: grabs one photo from the BACK camera, hands it to the service, closes. */
class CameraActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val q = intent.getStringExtra("q") ?: "What is this?"
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                val provider = fut.get()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cap)
            } catch (e: Exception) { fail(); return@addListener }
            Handler(Looper.getMainLooper()).postDelayed({
                val f = File(cacheDir, "shot.jpg")
                cap.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(o: ImageCapture.OutputFileResults) { toService(Intent().putExtra("cmd", q).putExtra("image", f.absolutePath)) }
                        override fun onError(e: ImageCaptureException) { fail() }
                    })
            }, 1200)   // short warm-up so exposure/focus settle
        }, ContextCompat.getMainExecutor(this))
    }
    private fun fail() = toService(Intent().putExtra("say", "I couldn't use the camera, sir. Check the camera permission."))
    private fun toService(i: Intent) {
        i.setClass(this, VoiceService::class.java)
        ContextCompat.startForegroundService(this, i)
        finish()
    }
}
