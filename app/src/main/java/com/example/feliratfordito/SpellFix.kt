package com.example.feliratfordito

import android.content.Context

/**
 * Angol helyesírás-javítás a szövegfelismerés tipikus hibáira ("Asslgnnments" -> "Assignments").
 * A beépített szólista feliratokból készült gyakorisági lista (FrequencyWords, OpenSubtitles 2018).
 */
object SpellFix {
    @Volatile private var words: HashMap<String, Int>? = null
    private var byLen: Array<Array<String>> = emptyArray()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3000
    }
    private const val NONE = "\u0000"

    /** Háttérben betölti a szólistát (kb. 45 000 szó). */
    fun load(ctx: Context) {
        if (words != null) return
        Thread {
            try {
                val map = HashMap<String, Int>(60_000)
                val lists = HashMap<Int, MutableList<String>>()
                ctx.assets.open("words_en.txt").bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        val sp = line.indexOf(' ')
                        if (sp > 0) {
                            val w = line.substring(0, sp)
                            map[w] = line.substring(sp + 1).trim().toIntOrNull() ?: 1
                            lists.getOrPut(w.length) { ArrayList() }.add(w)
                        }
                    }
                }
                val maxLen = (lists.keys.maxOrNull() ?: 0) + 3
                byLen = Array(maxLen + 1) { lists[it]?.toTypedArray() ?: emptyArray() }
                words = map
            } catch (e: Exception) {
                // szólista nélkül nincs javítás
            }
        }.start()
    }

    private val tokenRegex = Regex("[A-Za-z0-9|']+")

    /** A teljes szöveg javítása. A mondat közbeni nagybetűs szavakhoz (nevek) nem nyúl. */
    fun fixText(text: String): String {
        if (words == null) return text
        return text.split('\n').joinToString("\n") { line -> fixLine(line) }
    }

    private fun fixLine(line: String): String {
        val sb = StringBuilder()
        var last = 0
        var first = true
        for (m in tokenRegex.findAll(line)) {
            sb.append(line, last, m.range.first)
            val tok = m.value
            val next = line.getOrNull(m.range.last + 1)
            val isCap = tok[0].isUpperCase()
            val allCaps = tok.length > 1 && tok.all { !it.isLetter() || it.isUpperCase() }
            // mondat közbeni nagybetűs szó (név) vagy beszélő neve ("Név:") -> marad
            val skip = (isCap && !allCaps && !first) || next == ':' || tok.any { it.isDigit() } && tok.any { it.isLetter() }.not()
            sb.append(if (skip) tok else fixWord(tok, isCap, allCaps))
            last = m.range.last + 1
            first = false
            // új mondat után a következő szó is "mondatkezdő"
            val tail = line.substring(last, minOf(line.length, last + 2))
            if (tail.contains('.') || tail.contains('!') || tail.contains('?')) first = true
        }
        sb.append(line, last, line.length)
        return sb.toString()
    }

    private fun fixWord(tok: String, isCap: Boolean, allCaps: Boolean): String {
        val lw = tok.lowercase()
        val fixed = correct(lw) ?: return tok
        return when {
            allCaps -> fixed.uppercase()
            isCap -> fixed.replaceFirstChar { it.uppercase() }
            else -> fixed
        }
    }

    @Synchronized
    private fun correct(lw: String): String? {
        val dict = words ?: return null
        if (lw.length < 3 || dict.containsKey(lw)) return null
        cache[lw]?.let { return if (it == NONE) null else it }

        var result: String? = null
        // tipikus felismerési tévesztések
        for ((a, b) in listOf("rn" to "m", "vv" to "w", "1" to "l", "0" to "o", "|" to "l", "5" to "s")) {
            val t = lw.replace(a, b)
            if (t != lw && dict.containsKey(t)) { result = t; break }
        }
        if (result == null && lw[0] in "l1|" && dict.containsKey("i" + lw.substring(1))) result = "i" + lw.substring(1)

        if (result == null) {
            val maxD = if (lw.length < 7) 1 else 2
            var bestD = Int.MAX_VALUE
            var bestF = -1
            for (len in maxOf(1, lw.length - maxD)..minOf(byLen.size - 1, lw.length + maxD)) {
                for (c in byLen[len]) {
                    val d = boundedLevenshtein(lw, c, maxD)
                    if (d <= maxD) {
                        val f = dict[c] ?: 0
                        if (d < bestD || (d == bestD && f > bestF)) {
                            bestD = d; bestF = f; result = c
                        }
                    }
                }
            }
        }
        cache[lw] = result ?: NONE
        return result
    }

    /** Levenshtein-távolság korláttal (ha nagyobb, korán kilép). */
    private fun boundedLevenshtein(a: String, b: String, maxD: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > maxD) return maxD + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                val v = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxD) return maxD + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
