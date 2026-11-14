package com.example.feliratfordito

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemini-fogyasztás nyilvántartása a telefonon (a válaszokban kapott tokenszámokból).
 * Az egyenleg becslés: feltöltött összeg − azóta elhasznált becsült költség.
 */
object UsageStore {
    const val HUF_PER_USD = 350.0          // tájékoztató árfolyam a forintos kiíráshoz
    const val DEFAULT_PRICE_IN = 0.25      // USD / 1M bemeneti token (becslés, a beállításokban átírható)
    const val DEFAULT_PRICE_OUT = 1.50     // USD / 1M kimeneti token

    private fun p(ctx: Context) = ctx.getSharedPreferences("hasznalat", Context.MODE_PRIVATE)
    private fun settings(ctx: Context) = ctx.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun month() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun priceIn(ctx: Context) = settings(ctx).getFloat("priceIn", DEFAULT_PRICE_IN.toFloat()).toDouble()
    fun priceOut(ctx: Context) = settings(ctx).getFloat("priceOut", DEFAULT_PRICE_OUT.toFloat()).toDouble()

    fun cost(ctx: Context, inTok: Long, outTok: Long) =
        inTok / 1_000_000.0 * priceIn(ctx) + outTok / 1_000_000.0 * priceOut(ctx)

    @Synchronized
    fun record(ctx: Context, inTok: Long, outTok: Long) {
        val prefs = p(ctx)
        val e = prefs.edit()
        val d = today()
        val m = month()
        if (prefs.getString("day", "") != d) {
            e.putString("day", d).putLong("dReq", 0).putLong("dIn", 0).putLong("dOut", 0)
        }
        if (prefs.getString("month", "") != m) {
            e.putString("month", m).putLong("mReq", 0).putLong("mIn", 0).putLong("mOut", 0)
        }
        e.apply()
        val c = cost(ctx, inTok, outTok)
        prefs.edit()
            .putLong("dReq", prefs.getLong("dReq", 0) + 1)
            .putLong("dIn", prefs.getLong("dIn", 0) + inTok)
            .putLong("dOut", prefs.getLong("dOut", 0) + outTok)
            .putLong("mReq", prefs.getLong("mReq", 0) + 1)
            .putLong("mIn", prefs.getLong("mIn", 0) + inTok)
            .putLong("mOut", prefs.getLong("mOut", 0) + outTok)
            .putLong("tReq", prefs.getLong("tReq", 0) + 1)
            .putFloat("spentSinceTopup", (prefs.getFloat("spentSinceTopup", 0f) + c).toFloat())
            .apply()
    }

    class Stats(
        val todayReq: Long, val todayCost: Double,
        val monthReq: Long, val monthCost: Double,
        val balance: Double?, val spent: Double
    )

    fun stats(ctx: Context): Stats {
        val prefs = p(ctx)
        val isToday = prefs.getString("day", "") == today()
        val isMonth = prefs.getString("month", "") == month()
        val dReq = if (isToday) prefs.getLong("dReq", 0) else 0
        val dCost = if (isToday) cost(ctx, prefs.getLong("dIn", 0), prefs.getLong("dOut", 0)) else 0.0
        val mReq = if (isMonth) prefs.getLong("mReq", 0) else 0
        val mCost = if (isMonth) cost(ctx, prefs.getLong("mIn", 0), prefs.getLong("mOut", 0)) else 0.0
        val spent = prefs.getFloat("spentSinceTopup", 0f).toDouble()
        val topup = settings(ctx).getFloat("balanceTopup", -1f).toDouble()
        val balance = if (topup >= 0) (topup - spent).coerceAtLeast(0.0) else null
        return Stats(dReq, dCost, mReq, mCost, balance, spent)
    }

    /** Új feltöltés: az egyenleg innentől ebből számolódik. */
    fun setBalance(ctx: Context, usd: Double) {
        settings(ctx).edit().putFloat("balanceTopup", usd.toFloat()).apply()
        p(ctx).edit().putFloat("spentSinceTopup", 0f).apply()
    }

    fun usd(v: Double) = String.format(Locale.US, "$%.2f", v)
    fun huf(v: Double) = String.format(Locale("hu"), "%,.0f Ft", v * HUF_PER_USD)
    fun usdHuf(v: Double) = "${usd(v)} (kb. ${huf(v)})"
}
