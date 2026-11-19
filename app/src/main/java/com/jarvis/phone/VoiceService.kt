package com.jarvis.phone
import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener {
    private var rec: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var orb: OrbOverlay
    private val h = Handler(Looper.getMainLooper())
    private var awaiting = false
    private var running = true
    private val wake = "jarvis"
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

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        i?.getStringExtra("cmd")?.let { showOrb(3000); CommandRouter.run(this, it.lowercase().trim(), ::say) }
        return START_STICKY
    }

    override fun onInit(s: Int) {
        if (s != TextToSpeech.SUCCESS) return
        tts.language = Locale.UK
        tts.setPitch(0.78f); tts.setSpeechRate(0.9f)   // deeper, calmer butler-like voice
        val uk = tts.voices.orEmpty().filter { it.locale.language == "en" && it.locale.country == "GB" }
        val pick = uk.firstOrNull { v -> listOf("gbb", "gbd", "rjs").any { v.name.contains(it) } } ?: uk.firstOrNull()
        if (pick != null) tts.voice = pick
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { h.post { showOrb(800) } }
            override fun onDone(id: String?) { h.post { showOrb(1200) } }
        })
    }
    private fun say(t: String) { h.post { showOrb(15000) }; tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j") }

    private val recIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
    }

    private fun ensureRec() {
        if (rec != null) return
        rec = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    handle(text); h.postDelayed({ listen() }, 80)
                }
                override fun onPartialResults(p: Bundle?) {
                    val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    if (text.contains(wake) || awaiting) showOrb(4000)   // react instantly
                }
                override fun onError(e: Int) {
                    if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                        rec?.destroy(); rec = null; h.postDelayed({ listen() }, 800)
                    } else h.postDelayed({ listen() }, 120)
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
    }

    private fun listen() {
        if (!running) return
        ensureRec()
        try { rec?.startListening(recIntent) } catch (_: Exception) { rec?.destroy(); rec = null; h.postDelayed({ listen() }, 800) }
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        if (awaiting) { awaiting = false; showOrb(3000); CommandRouter.run(this, text, ::say); return }
        val i = text.indexOf(wake)
        if (i < 0) return
        val cmd = text.substring(i + wake.length).trim()
        showOrb(3000)
        if (cmd.isEmpty()) { awaiting = true; say("Yes, sir?") } else CommandRouter.run(this, cmd, ::say)
    }

    override fun onDestroy() { running = false; orb.hide(); rec?.destroy(); tts.shutdown(); super.onDestroy() }
}
