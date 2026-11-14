package com.example.feliratfordito

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A felhasználó játékai. Mindegyiknek saját profilja, fordítási memóriája és felvételei vannak. */
object GameStore {

    class Game(
        val id: Long,
        var name: String,
        var notes: String = "",
        var useProfile: Boolean = false,
        var lastPlayed: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("notes", notes)
            .put("useProfile", useProfile).put("lastPlayed", lastPlayed)

        companion object {
            fun from(o: JSONObject) = Game(
                o.optLong("id"), o.optString("name"), o.optString("notes", ""),
                o.optBoolean("useProfile", false), o.optLong("lastPlayed", 0L)
            )
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "jatekok.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Game> {
        migrate(ctx)
        return try {
            val arr = JSONArray(file(ctx).readText())
            (0 until arr.length()).map { Game.from(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveAll(ctx: Context, games: List<Game>) {
        val arr = JSONArray()
        games.forEach { arr.put(it.toJson()) }
        file(ctx).writeText(arr.toString())
    }

    /** Legutóbb játszott elöl. */
    fun sorted(ctx: Context) = all(ctx).sortedByDescending { it.lastPlayed }

    fun find(ctx: Context, id: Long) = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun add(ctx: Context, name: String): Game {
        val g = Game(System.currentTimeMillis(), name.trim(), lastPlayed = System.currentTimeMillis())
        saveAll(ctx, all(ctx) + g)
        return g
    }

    @Synchronized
    fun update(ctx: Context, game: Game) {
        saveAll(ctx, all(ctx).map { if (it.id == game.id) game else it })
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        saveAll(ctx, all(ctx).filter { it.id != id })
        TranslationMemory.clearFor(ctx, id)
        if (prefs(ctx).getLong("activeGame", -1L) == id) prefs(ctx).edit().remove("activeGame").apply()
    }

    /** Az éppen fordított játék (a legutóbb megnyitott). */
    fun active(ctx: Context): Game? {
        val list = all(ctx)
        val id = prefs(ctx).getLong("activeGame", -1L)
        return list.firstOrNull { it.id == id } ?: list.maxByOrNull { it.lastPlayed }
    }

    fun setActive(ctx: Context, id: Long) {
        prefs(ctx).edit().putLong("activeGame", id).apply()
        find(ctx, id)?.let { it.lastPlayed = System.currentTimeMillis(); update(ctx, it) }
    }

    /** Egyszeri átköltöztetés a korábbi, egy-játékos beállításokból. */
    @Synchronized
    private fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("gamesMigrated", false)) return
        p.edit().putBoolean("gamesMigrated", true).apply()
        if (file(ctx).exists()) return
        val oldName = p.getString("game", "").orEmpty().trim()
        if (oldName.isEmpty()) {
            file(ctx).writeText("[]")
            return
        }
        val g = Game(
            System.currentTimeMillis(), oldName,
            p.getString("gameNotes", "").orEmpty(), p.getBoolean("useProfile", false), System.currentTimeMillis()
        )
        file(ctx).writeText(JSONArray().put(g.toJson()).toString())
        p.edit().putLong("activeGame", g.id).apply()
        // régi közös memória -> ehhez a játékhoz
        val oldMem = File(ctx.filesDir, "forditasi_memoria.json")
        if (oldMem.exists()) oldMem.renameTo(File(ctx.filesDir, "memoria_${g.id}.json"))
        // régi felvételek -> ehhez a játékhoz
        val arr = SessionStore.load(ctx)
        for (i in 0 until arr.length()) arr.getJSONObject(i).put("gameId", g.id)
        SessionStore.save(ctx, arr)
    }
}
