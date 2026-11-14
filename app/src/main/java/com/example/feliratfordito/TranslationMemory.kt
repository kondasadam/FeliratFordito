package com.example.feliratfordito

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Fordítási memória: a Gemini fordításait megjegyzi a telefonon.
 * Ha ugyanaz a szöveg újra előjön (menük, ismétlődő párbeszéd), ingyen és azonnal kiírja.
 */
object TranslationMemory {
    private const val MAX = 6000
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX
    }
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var savePending = false

    private fun file(ctx: Context) = File(ctx.filesDir, "forditasi_memoria.json")

    /** Kulcs: kisbetűs, csak betűk és számok – így az apró felismerési eltérések (írásjelek, szóközök) nem számítanak. */
    fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val json = JSONObject(file(ctx).readText())
            json.keys().forEach { k -> map[k] = json.getString(k) }
        } catch (e: Exception) {
            // még nincs memória
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
        scheduleSave(ctx.applicationContext)
    }

    @Synchronized
    fun size(ctx: Context): Int {
        ensureLoaded(ctx)
        return map.size
    }

    @Synchronized
    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        map.clear()
        scheduleSave(ctx.applicationContext)
    }

    private fun scheduleSave(ctx: Context) {
        if (savePending) return
        savePending = true
        io.execute {
            Thread.sleep(2000)   // több változást egyszerre mentünk
            savePending = false
            val snapshot = synchronized(this) { JSONObject(map as Map<*, *>).toString() }
            try {
                file(ctx).writeText(snapshot)
            } catch (e: Exception) { }
        }
    }
}
