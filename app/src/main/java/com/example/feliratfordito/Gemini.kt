package com.example.feliratfordito

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gemini API hívás (fordítás és összefoglaló közösen használja). */
object Gemini {
    const val DEFAULT_MODEL = "gemini-flash-lite-latest"

    @Volatile var pausedUntil = 0L
    @Volatile var lastError = ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = prefs(ctx).getString("geminiKey", "").orEmpty().trim()
    fun game(ctx: Context): String = prefs(ctx).getString("game", "").orEmpty().trim()

    /** Szöveg generálása. Hiba esetén null, az ok a [lastError]-ban. */
    fun generate(
        ctx: Context, system: String, user: String, maxTokens: Int, timeoutMs: Int,
        modelOverride: String? = null, imageJpeg: ByteArray? = null
    ): String? {
        val key = apiKey(ctx)
        if (key.isEmpty()) {
            lastError = "nincs API-kulcs"
            return null
        }
        if (System.currentTimeMillis() < pausedUntil) {
            lastError = "keret elfogyott"
            return null
        }
        val model = modelOverride ?: prefs(ctx).getString("geminiModel", DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }

        for (attempt in 0..1) {
            val gen = JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens)
            if (attempt == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            val req = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put(
                    "contents", JSONArray().put(
                        JSONObject().put("role", "user").put("parts", JSONArray().apply {
                            if (imageJpeg != null) {
                                put(
                                    JSONObject().put(
                                        "inlineData", JSONObject()
                                            .put("mimeType", "image/jpeg")
                                            .put("data", android.util.Base64.encodeToString(imageJpeg, android.util.Base64.NO_WRAP))
                                    )
                                )
                            }
                            put(JSONObject().put("text", user))
                        })
                    )
                )
                .put("generationConfig", gen)
            try {
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 4000
                c.readTimeout = timeoutMs
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("x-goog-api-key", key)
                c.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 200) {
                    val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    c.disconnect()
                    // fogyasztás nyilvántartása (tokenszám a válaszból)
                    json.optJSONObject("usageMetadata")?.let { u ->
                        val inTok = u.optLong("promptTokenCount", 0)
                        val outTok = u.optLong("candidatesTokenCount", 0) + u.optLong("thoughtsTokenCount", 0)
                        UsageStore.record(ctx, inTok, outTok)
                    }
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts == null) {
                        lastError = "üres válasz"
                        return null
                    }
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        if (part.optBoolean("thought", false)) continue
                        sb.append(part.optString("text", ""))
                    }
                    lastError = ""
                    return sb.toString().trim().ifEmpty { null }
                }
                c.errorStream?.close()
                c.disconnect()
                if (code == 400 && attempt == 0) continue   // pl. a modell nem ismeri a thinkingConfig-ot
                lastError = when (code) {
                    402 -> "elfogyott a kredit"
                    400, 401, 403 -> "rossz kulcs? ($code)"
                    404 -> "ismeretlen modell"
                    429 -> "keret elfogyott"
                    else -> "hiba $code"
                }
                if (code == 429) pausedUntil = System.currentTimeMillis() + 60_000
                return null
            } catch (e: Exception) {
                lastError = "időtúllépés / nincs internet"
                return null
            }
        }
        return null
    }
}
