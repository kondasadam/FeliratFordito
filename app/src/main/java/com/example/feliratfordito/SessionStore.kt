package com.example.feliratfordito

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Felvett beszélgetések tárolása a telefonon (felvetelek.json).
 * Egy felvétel: {id, start, end, game, lines:[{en, hu}], summary}
 */
object SessionStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "felvetelek.json")

    @Synchronized
    fun load(ctx: Context): JSONArray = try {
        JSONArray(file(ctx).readText())
    } catch (e: Exception) {
        JSONArray()
    }

    @Synchronized
    fun save(ctx: Context, arr: JSONArray) {
        file(ctx).writeText(arr.toString())
    }

    @Synchronized
    fun add(ctx: Context, session: JSONObject) {
        val arr = load(ctx)
        arr.put(session)
        save(ctx, arr)
    }

    @Synchronized
    fun update(ctx: Context, id: Long, change: (JSONObject) -> Unit) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            if (s.optLong("id") == id) {
                change(s)
                break
            }
        }
        save(ctx, arr)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optLong("id") == id) {
                arr.remove(i)
                break
            }
        }
        save(ctx, arr)
    }

    fun find(ctx: Context, id: Long): JSONObject? {
        val arr = load(ctx)
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            if (s.optLong("id") == id) return s
        }
        return null
    }
}
