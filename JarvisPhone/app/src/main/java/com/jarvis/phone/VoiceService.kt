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
    private val wake = "jarvis"
    private var running = true
    private var listeningNow = false
    private var speaking = false
    private var awaiting = false          // waiting for your answer (4 s window)
    private var awaitDeadline = 0L
    private var speechStarted = false
    private var acked = false
    private var followUp = false
    private var wakeOnly = false
    private val hideOrb = Runnable { orb.hide() }
    private val ackRun = Runnable { quickAck() }
    private val awaitTimeout = Runnable { closeAwait() }
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
        CommandRouter.onThinking = { h.post { showOrb(15000) } }
        tts = TextToSpeech(this, this)
        h.post { listen() }
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        i?.getStringExtra("say")?.let { say(it) }
        val img = i?.getStringExtra("image")
        i?.getStringExtra("cmd")?.let {
            showOrb(15000)
            if (img != null) AiBrain.ask(this, it, img, ::say) else CommandRouter.run(this, it.trim(), ::say)
        }
        return START_STICKY
    }

    // ---------- voice out ----------
    override fun onInit(s: Int) {
        if (s != TextToSpeech.SUCCESS) return
        tts.language = Locale.UK
        tts.setPitch(0.78f); tts.setSpeechRate(0.92f)
        val uk = tts.voices.orEmpty().filter { it.locale.language == "en" && it.locale.country == "GB" }
        val local = uk.filter { !it.isNetworkConnectionRequired }     // offline voices answer faster
        val pool = if (local.isNotEmpty()) local else uk
        val pick = pool.firstOrNull { v -> listOf("gbb", "gbd", "rjs").any { v.name.contains(it) } } ?: pool.firstOrNull()
        if (pick != null) tts.voice = pick
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { OrbState.speaking = true }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { OrbState.kick = 1f }   // wave pulses per word
            override fun onError(id: String?) { h.post { ttsDone(id) } }
            override fun onDone(id: String?) { h.post { ttsDone(id) } }
        })
        tts.playSilentUtterance(10, TextToSpeech.QUEUE_FLUSH, "warm")   // warm the engine so the first reply isn't slow
    }

    private fun say(t: String) {
        h.post {
            speaking = true; OrbState.speaking = true
            followUp = t.trim().endsWith("?")          // a question -> wait for your answer afterwards
            h.removeCallbacks(awaitTimeout)
            rec?.cancel(); listeningNow = false         // don't listen to ourselves
            showOrb(25000)
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "j")
        }
    }

    private fun ttsDone(id: String?) {
        if (id == "warm") return
        speaking = false; OrbState.speaking = false
        if (followUp) { followUp = false; beginAwait() }
        else { showOrb(1500); listen() }
    }

    // ---------- 4-second answer window ----------
    private fun beginAwait() {
        awaiting = true; speechStarted = false
        awaitDeadline = SystemClock.uptimeMillis() + 4000
        showOrb(12000)
        h.removeCallbacks(awaitTimeout); h.postDelayed(awaitTimeout, 4000)
        listen()
    }

    private fun closeAwait() {
        if (!awaiting || speechStarted) return      // you're mid-sentence: let the recognizer finish
        awaiting = false; acked = false
        rec?.cancel(); listeningNow = false
        OrbState.mic = 0f
        orb.hide()
        h.postDelayed({ listen() }, 200)
    }

    // ---------- voice in ----------
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
                    listeningNow = false; speechStarted = false; wakeOnly = false
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    h.removeCallbacks(ackRun)
                    handle(text)
                    h.postDelayed({ listen() }, 80)
                }
                override fun onPartialResults(p: Bundle?) {
                    val text = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                    if (awaiting) { showOrb(12000); return }
                    val i = text.indexOf(wake)
                    if (i < 0) return
                    showOrb(6000)
                    val cmd = text.substring(i + wake.length).trim()
                    wakeOnly = cmd.isEmpty()
                    if (wakeOnly) { if (!acked) { h.removeCallbacks(ackRun); h.postDelayed(ackRun, 350) } }
                    else h.removeCallbacks(ackRun)
                }
                override fun onEndOfSpeech() { if (wakeOnly) { h.removeCallbacks(ackRun); quickAck() } }
                override fun onBeginningOfSpeech() { speechStarted = true }
                override fun onRmsChanged(v: Float) { OrbState.mic = ((v - 1f) / 9f).coerceIn(0f, 1f) }
                override fun onError(e: Int) {
                    listeningNow = false; speechStarted = false; wakeOnly = false
                    if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                        rec?.destroy(); rec = null; h.postDelayed({ listen() }, 800); return
                    }
                    if (awaiting && SystemClock.uptimeMillis() >= awaitDeadline) closeAwait() else h.postDelayed({ listen() }, 120)
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
    }

    private fun listen() {
        if (!running || speaking || listeningNow) return
        ensureRec()
        listeningNow = true
        try { rec?.startListening(recIntent) } catch (_: Exception) {
            listeningNow = false; rec?.destroy(); rec = null; h.postDelayed({ listen() }, 800)
        }
    }

    /** Heard just "Jarvis": answer right away instead of waiting for the full recognition result. */
    private fun quickAck() {
        if (awaiting || acked || speaking) return
        acked = true; wakeOnly = false
        say("Yes, sir?")
    }

    private fun handle(text: String) {
        if (awaiting) {
            awaiting = false; acked = false; h.removeCallbacks(awaitTimeout)
            if (text.isNotBlank()) { showOrb(15000); CommandRouter.run(this, text.trim(), ::say) } else orb.hide()
            return
        }
        if (text.isBlank()) { acked = false; return }
        val i = text.indexOf(wake)
        if (i < 0) { acked = false; return }
        val cmd = text.substring(i + wake.length).trim()
        if (cmd.isEmpty()) { if (!acked) { acked = true; say("Yes, sir?") }; return }
        acked = false
        showOrb(8000)
        CommandRouter.run(this, cmd, ::say)
    }

    override fun onDestroy() {
        running = false; OrbState.speaking = false
        orb.hide(); rec?.destroy(); tts.shutdown(); super.onDestroy()
    }
}
