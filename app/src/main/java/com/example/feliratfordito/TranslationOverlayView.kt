package com.example.feliratfordito

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * A kamera képén az angol felirat helyére rajzolja a magyar fordítást:
 * letakarja az eredetit, és hasonló betűmérettel ráírja a magyart.
 */
class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Item(val rect: RectF, val lineHeight: Float, var text: String = "")

    private var items: List<Item> = emptyList()
    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 12, 12, 14) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val count: Int get() = items.size

    /** Új felirat helyei (a szövegek később jönnek). */
    fun setRects(rects: List<RectF>, lineHeights: List<Float>) {
        items = rects.indices.map { Item(RectF(rects[it]), lineHeights.getOrElse(it) { rects[it].height() }) }
        invalidate()
    }

    /** Ugyanaz a felirat, csak kicsit elmozdult a kép: a helyeket frissítjük, a szöveg marad. */
    fun moveRects(rects: List<RectF>) {
        if (rects.size != items.size) return
        items.forEachIndexed { i, it -> it.rect.set(rects[i]) }
        invalidate()
    }

    /** Fordítások beírása. Ha a sorok száma eltér, egy közös dobozba kerül minden. */
    fun setTexts(texts: List<String>) {
        if (items.isEmpty()) return
        if (texts.size == items.size) {
            items.forEachIndexed { i, it -> it.text = texts[i] }
        } else {
            val union = RectF(items[0].rect)
            items.forEach { union.union(it.rect) }
            val lh = items.map { it.lineHeight }.average().toFloat()
            items = listOf(Item(union, lh, texts.joinToString("\n")))
        }
        invalidate()
    }

    fun clear() {
        if (items.isEmpty()) return
        items = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 6f * density
        val radius = 8f * density
        for (it in items) {
            if (it.text.isEmpty()) continue
            // betűméret az eredeti sormagassághoz igazítva, észszerű határok közt
            var size = (it.lineHeight * 0.78f).coerceIn(13f * density, 42f * density)
            val width = maxOf(it.rect.width(), 140f * density).toInt()
            var layout = makeLayout(it.text, size, width)
            // ha a magyar szöveg sokkal több helyet kér, kicsit kisebb betű
            while (layout.height > it.rect.height() * 2.2f && size > 13f * density) {
                size -= 1.5f * density
                layout = makeLayout(it.text, size, width)
            }
            val h = maxOf(layout.height.toFloat(), it.rect.height())
            val left = it.rect.centerX() - width / 2f
            val top = (it.rect.centerY() - h / 2f).coerceAtLeast(0f)
            canvas.drawRoundRect(left - pad, top - pad, left + width + pad, top + h + pad, radius, radius, bgPaint)
            canvas.save()
            canvas.translate(left, top + (h - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun makeLayout(text: String, size: Float, width: Int): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
    }
}
