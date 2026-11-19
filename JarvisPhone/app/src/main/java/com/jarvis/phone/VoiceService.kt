package com.jarvis.phone
import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private val h = Handler(Looper.getMainLooper())
    private var awaiting = false
    private var running = true
    private val wake = "jarvis"
    private lateinit var orb: OrbOverlay
    private val hideOrb = Runnable { orb.hide() }
    private fun showOrb(ms: Long) { orb.show(); h.removeCallbacks(hideOrb); h.postDelayed(hideOrb, ms) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = "jarvis"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(ch, "Jarvis listening", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" + command").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        orb = OrbOverlay(this)
        tts = TextToSpeech(this, this)
        h.post { listen() }
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { h.post { showOrb(800) } }
                override fun onDone(id: String?) { h.post { showOrb(1200) } }
            })
        }
    }
    private fun say(t: String) { h.post { showOrb(15000) }; tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j") }

    private fun listen() {
        if (!running) return
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    handle(text); h.postDelayed({ listen() }, 400)
                }
                override fun onError(e: Int) { h.postDelayed({ listen() }, if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000 else 600) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        }
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        if (awaiting) { awaiting = false; showOrb(3000); CommandRouter.run(this, text, ::say); return }
        val i = text.indexOf(wake)
        if (i < 0) return
        val cmd = text.substring(i + wake.length).trim()
        showOrb(3000)
        if (cmd.isEmpty()) { awaiting = true; say("Yes?") } else CommandRouter.run(this, cmd, ::say)
    }

    override fun onDestroy() { running = false; orb.hide(); rec?.destroy(); tts.shutdown(); super.onDestroy() }
}
