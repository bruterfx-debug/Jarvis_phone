package com.jarvis.phone
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Free AI brain. Text questions go to Groq (fast, ~30 requests/min free) if a Groq key is saved,
 * and fall back to Google Gemini. Camera questions use Gemini (it can see images).
 */
object AiBrain {
    private val history = ArrayList<Pair<String, String>>()   // role: "user" / "model"
    var lastError = ""
    var lastModel = ""

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", 0)
    fun key(c: Context): String = prefs(c).getString("key", "") ?: ""
    fun saveKey(c: Context, k: String) { prefs(c).edit().putString("key", k.trim()).apply() }
    fun groqKey(c: Context): String = prefs(c).getString("gkey", "") ?: ""
    fun saveGroqKey(c: Context, k: String) { prefs(c).edit().putString("gkey", k.trim()).apply() }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        return "You are Jarvis, the user's voice assistant on their Android phone. Speak like a calm, witty British butler and call the user sir. " +
            "Your reply is spoken aloud: keep \"say\" to 1-3 short sentences, no markdown, no lists. If you need more information to finish a request, ask ONE short question ending with a question mark. Current date and time: $now. " +
            "Reply with ONLY a JSON object: {\"say\": string, \"action\": string, \"args\": object}. " +
            "action is \"none\" for questions and chat. Use an action only when the user wants the phone to DO something: " +
            "open_app {name}; email {to,subject,body,auto_send}; sms {to,body,auto_send}; whatsapp {to,body,auto_send}; " +
            "call {to}; play_music {query, app (spotify, youtube music, amazon music or youtube; optional)}; media {command: play, pause, next or previous}; search {query}; maps {query}; alarm {hour,minute,label}; timer {seconds}; click {text}; type {text}; note {text}; camera {question}. " +
            "'to' may be a contact name or an email/number. For email, write a good subject and a polite complete body from what the user asked. " +
            "For sms and whatsapp set auto_send true whenever the user asks you to send, message or text someone, unless they say draft or do not send. For email set auto_send true only if the user says to send it. " +
            "For writing in the notes app use action note with the exact text to save. " +
            "If the user asks you to look at or identify something in the real world, use action camera with the question. " +
            "If a photo is attached, answer about the photo."
    }

    private fun errMsg(resp: String): String =
        (try { JSONObject(resp).optJSONObject("error")?.optString("message") ?: resp } catch (_: Exception) { resp }).take(300)

    private fun http(url: String, body: JSONObject?, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 40000
            setRequestProperty("User-Agent", "JarvisPhone/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { requestMethod = "POST"; doOutput = true; setRequestProperty("content-type", "application/json") }
        }
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to resp
    }

    // ---------- Groq (text only) ----------
    private const val GROQ = "https://api.groq.com/openai/v1"

    private fun groqModels(gk: String): List<String> {
        val r = http("$GROQ/models", null, mapOf("Authorization" to "Bearer $gk"))
        if (r.first !in 200..299) return emptyList()
        val arr = JSONObject(r.second).getJSONArray("data")
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val id = arr.getJSONObject(i).getString("id")
            if (listOf("whisper", "guard", "tts", "playai", "distil", "orpheus", "safeguard").none { id.contains(it) }) ids.add(id)
        }
        return ids.filter { it.contains("70b") }.sortedDescending() + ids.filter { !it.contains("70b") }.sortedDescending()
    }

    private fun viaGroq(c: Context, gk: String, q: String): String? {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system()))
        history.takeLast(6).forEach { (r, t) -> msgs.put(JSONObject().put("role", if (r == "model") "assistant" else r).put("content", t)) }
        msgs.put(JSONObject().put("role", "user").put("content", q))
        fun call(model: String) = http("$GROQ/chat/completions",
            JSONObject().put("model", model).put("messages", msgs).put("max_tokens", 500).put("temperature", 0.6)
                .put("response_format", JSONObject().put("type", "json_object")),
            mapOf("Authorization" to "Bearer $gk"))
        var model = prefs(c).getString("gmodel", null) ?: "llama-3.3-70b-versatile"
        var r = call(model)
        if (r.first == 400 || r.first == 404) {   // model retired -> pick a current one
            val alt = groqModels(gk).firstOrNull { it != model }
            if (alt != null) {
                val rr = call(alt)
                if (rr.first in 200..299) { model = alt; prefs(c).edit().putString("gmodel", alt).apply(); r = rr }
            }
        }
        if (r.first !in 200..299) { lastError = "Groq HTTP ${r.first}: " + errMsg(r.second); return null }
        lastModel = "groq: $model"
        return JSONObject(r.second).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    // ---------- Gemini (text + images) ----------
    private const val GEM = "https://generativelanguage.googleapis.com/v1beta/models"

    private fun geminiCandidates(k: String): List<String> {
        val r = http("$GEM?pageSize=200", null, mapOf("x-goog-api-key" to k))
        if (r.first !in 200..299) return emptyList()
        val arr = JSONObject(r.second).getJSONArray("models")
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val n = m.getString("name").removePrefix("models/")
            val ok = m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true
            val bad = listOf("image", "tts", "live", "audio", "exp", "thinking", "robotics", "computer", "veo", "imagen").any { n.contains(it) }
            if (ok && n.contains("flash") && !bad) names.add(n)
        }
        return names.filter { !it.contains("preview") }.sortedDescending() + names.filter { it.contains("preview") }.sortedDescending()
    }

    /** Returns (error code or 0, reply text or null). */
    private fun viaGemini(c: Context, k: String, q: String, imagePath: String?): Pair<Int, String?> {
        val contents = JSONArray()
        history.takeLast(6).forEach { (r, t) ->
            contents.put(JSONObject().put("role", r).put("parts", JSONArray().put(JSONObject().put("text", t))))
        }
        val parts = JSONArray()
        if (imagePath != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(imagePath))))
        parts.put(JSONObject().put("text", q))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system())))
        fun call(model: String): Pair<Int, String> {
            val gc = JSONObject().put("maxOutputTokens", 1500).put("responseMimeType", "application/json")
            if (model.contains("2.5")) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            return http("$GEM/$model:generateContent",
                JSONObject().put("systemInstruction", sys).put("contents", contents).put("generationConfig", gc),
                mapOf("x-goog-api-key" to k))
        }
        var model = prefs(c).getString("model", null) ?: "gemini-2.5-flash"
        val first = call(model)
        var r = first
        if (r.first == 404 || r.first == 429) {
            var tries = 0
            for (m in geminiCandidates(k)) {
                if (m == model) continue
                if (tries++ >= 3) break
                val rr = call(m)
                if (rr.first in 200..299) { model = m; prefs(c).edit().putString("model", m).apply(); r = rr; break }
            }
        }
        if (r.first !in 200..299) { lastError += "Gemini HTTP ${first.first}: " + errMsg(first.second); return first.first to null }
        lastModel = "gemini: $model"
        return 0 to JSONObject(r.second).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }

    // ---------- shared ----------
    fun test(c: Context, cb: (String) -> Unit) {
        ask(c, "Reply with the single word: ready", null) {
            cb(it + "\nused: " + lastModel + (if (lastError.isNotBlank()) "\n" + lastError else ""))
        }
    }

    fun ask(c: Context, q: String, imagePath: String?, say: (String) -> Unit) {
        lastError = ""; lastModel = ""
        val gk = groqKey(c); val k = key(c)
        if (k.isBlank() && gk.isBlank()) { say("Please paste an API key in the app first, sir."); return }
        Thread {
            try {
                var text: String? = null
                var code = 0
                if (imagePath == null && gk.isNotBlank()) text = try { viaGroq(c, gk, q) } catch (e: Exception) { lastError = "Groq: " + e.toString().take(100); null }
                if (text == null && k.isNotBlank()) { val r = viaGemini(c, k, q, imagePath); code = r.first; text = r.second }
                if (text == null) {
                    say(when (code) {
                        429 -> "I've reached the free limit for now, sir."
                        401, 403 -> "My Gemini key was rejected, sir. Please check it."
                        0 -> "I couldn't get an answer, sir."
                        else -> "The service returned error $code, sir."
                    }); return@Thread
                }
                var t = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val o = try { JSONObject(t) } catch (_: Exception) { JSONObject().put("say", t).put("action", "none") }
                history.add("user" to q); history.add("model" to t)
                val s = o.optString("say")
                if (s.isNotBlank()) say(s)
                val action = o.optString("action", "none")
                if (action != "none") Handler(Looper.getMainLooper()).post {
                    CommandRouter.perform(c, action, o.optJSONObject("args") ?: JSONObject(), say)
                }
            } catch (e: Exception) { lastError = e.toString().take(200); say("I couldn't reach my servers, sir.") }
        }.start()
    }

    private fun b64(path: String): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1; while (o.outWidth / s > 1600) s *= 2
        var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        val deg = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
