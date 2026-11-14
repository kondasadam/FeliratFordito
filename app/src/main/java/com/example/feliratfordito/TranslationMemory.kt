package com.example.feliratfordito

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Fordítási memória játékonként: a Gemini fordításait megjegyzi a telefonon.
 * Ha ugyanaz a szöveg újra előjön (menük, ismétlődő párbeszéd), ingyen és azonnal kiírja.
 */
object TranslationMemory {
    private const val MAX = 6000
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX
    }
    private var loadedFor = Long.MIN_VALUE
    private val io = Executors.newSingleThreadExecutor()

    private fun file(ctx: Context, gameId: Long) = File(ctx.filesDir, "memoria_$gameId.json")
    private fun activeId(ctx: Context) = GameStore.active(ctx)?.id ?: 0L

    /** Kulcs: kisbetűs, csak betűk és számok – az apró felismerési eltérések nem számítanak. */
    fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        val id = activeId(ctx)
        if (loadedFor == id) return
        map.clear()
        loadedFor = id
        try {
            val json = JSONObject(file(ctx, id).readText())
            json.keys().forEach { k -> map[k] = json.getString(k) }
        } catch (e: Exception) {
            // még nincs memória ehhez a játékhoz
        }
    }

    @Synchronized
    fun get(ctx: Context, text: String): String? {
        ensureLoaded(ctx)
        val k = key(text)
        if (k.length < 2) return null
        return map[k]
    }

    @Synchronized
    fun put(ctx: Context, text: String, translation: String) {
        ensureLoaded(ctx)
        val k = key(text)
        if (k.length < 2 || translation.isBlank()) return
        map[k] = translation
        val id = loadedFor
        val snapshot = JSONObject(map as Map<*, *>).toString()
        val f = file(ctx.applicationContext, id)
        io.execute {
            try { f.writeText(snapshot) } catch (e: Exception) { }
        }
    }

    /** Hány szöveget jegyzett meg egy játékhoz. */
    @Synchronized
    fun countFor(ctx: Context, gameId: Long): Int {
        if (gameId == loadedFor) return map.size
        return try {
            JSONObject(file(ctx, gameId).readText()).length()
        } catch (e: Exception) {
            0
        }
    }

    @Synchronized
    fun clearFor(ctx: Context, gameId: Long) {
        if (gameId == loadedFor) map.clear()
        file(ctx, gameId).delete()
    }
}
