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

object AiBrain {
    private const val MODEL = "gemini-2.5-flash"   // free-tier model with image support
    private val history = ArrayList<Pair<String, String>>()

    fun key(c: Context): String = c.getSharedPreferences("jarvis", 0).getString("key", "") ?: ""
    fun saveKey(c: Context, k: String) { c.getSharedPreferences("jarvis", 0).edit().putString("key", k.trim()).apply() }

    private fun system(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        return "You are Jarvis, the user's voice assistant on their Android phone. Speak like a calm, witty British butler and call the user sir. " +
            "Your reply is spoken aloud: keep \"say\" to 1-3 short sentences, no markdown, no lists. Current date and time: $now. " +
            "Reply with ONLY a JSON object: {\"say\": string, \"action\": string, \"args\": object}. " +
            "action is \"none\" for questions and chat. Use an action only when the user wants the phone to DO something: " +
            "open_app {name}; email {to,subject,body,auto_send}; sms {to,body,auto_send}; whatsapp {to,body,auto_send}; " +
            "call {to}; search {query}; maps {query}; alarm {hour,minute,label}; timer {seconds}; click {text}; type {text}; camera {question}. " +
            "'to' may be a contact name or an email/number. For email, write a good subject and a polite complete body from what the user asked. " +
            "Set auto_send true ONLY if the user explicitly said to send it now; otherwise the draft stays open for them. " +
            "If the user asks you to look at or identify something in the real world, use action camera with the question. " +
            "If a photo is attached, answer about the photo."
    }

    fun ask(c: Context, q: String, imagePath: String?, say: (String) -> Unit) {
        val k = key(c)
        if (k.isBlank()) { say("Please paste your Gemini key in the app first, sir."); return }
        Thread {
            try {
                val contents = JSONArray()
                history.takeLast(6).forEach { (r, t) ->
                    contents.put(JSONObject().put("role", r).put("parts", JSONArray().put(JSONObject().put("text", t))))
                }
                val parts = JSONArray()
                if (imagePath != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(imagePath))))
                parts.put(JSONObject().put("text", q))
                contents.put(JSONObject().put("role", "user").put("parts", parts))
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 500).put("responseMimeType", "application/json")
                        .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
                val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 40000
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-goog-api-key", k)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
                if (code !in 200..299) {
                    say(when (code) {
                        429 -> "I've reached the free limit for now, sir. Try again in a minute."
                        400, 403 -> "My Gemini key was rejected, sir. Please check it."
                        else -> "The service returned error $code, sir."
                    }); return@Thread
                }
                var text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text").trim()
                text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val o = try { JSONObject(text) } catch (_: Exception) { JSONObject().put("say", text).put("action", "none") }
                history.add("user" to q); history.add("model" to text)
                val s = o.optString("say")
                if (s.isNotBlank()) say(s)
                val action = o.optString("action", "none")
                if (action != "none") Handler(Looper.getMainLooper()).post {
                    CommandRouter.perform(c, action, o.optJSONObject("args") ?: JSONObject(), say)
                }
            } catch (e: Exception) { say("I couldn't reach my servers, sir.") }
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
