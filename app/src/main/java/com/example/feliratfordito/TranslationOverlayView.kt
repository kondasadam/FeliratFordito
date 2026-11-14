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
 * A kamera képén az angol szöveg helyére rajzolja a magyar fordítást (mint a Google Lens):
 * a háttérhez illő színnel letakarja az eredetit, és hasonló méretű betűkkel ráírja a magyart.
 */
class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Item(val rect: RectF, val lineHeight: Float, val lines: Int, var bg: Int, var text: String = "")

    private var items: List<Item> = emptyList()
    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    val count: Int get() = items.size

    /** Új szöveg helyei (a fordítás később jön). */
    fun setRects(rects: List<RectF>, lineHeights: List<Float>, lineCounts: List<Int>, colors: List<Int>) {
        items = rects.indices.map {
            Item(
                RectF(rects[it]),
                lineHeights.getOrElse(it) { rects[it].height() },
                lineCounts.getOrElse(it) { 1 },
                colors.getOrElse(it) { Color.rgb(20, 20, 24) }
            )
        }
        invalidate()
    }

    /** Ugyanaz a szöveg, csak kicsit elmozdult a kép: a helyeket frissítjük, a fordítás marad. */
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
            items = listOf(Item(union, lh, items.sumOf { it.lines }, items[0].bg, texts.joinToString("\n")))
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
        val padX = 4f * density
        val padY = 2f * density
        val radius = 4f * density
        val minSize = 8f * density
        for (it in items) {
            if (it.text.isEmpty()) continue
            val r = it.rect
            val width = maxOf(r.width() * 1.12f, 40f * density).toInt()

            // betűméret: az eredeti sormagasságból indulunk, és addig kicsinyítünk, amíg elfér
            var size = (it.lineHeight * 0.82f).coerceIn(minSize, 48f * density)
            var layout = makeLayout(it.text, size, width)
            val maxLines = it.lines + if (it.lines >= 2) 1 else 0
            while (size > minSize && (layout.lineCount > maxLines || layout.height > r.height() * 1.6f)) {
                size -= 1f * density
                layout = makeLayout(it.text, size, width)
            }

            val h = maxOf(layout.height.toFloat(), r.height())
            var left = r.centerX() - width / 2f
            var top = r.centerY() - h / 2f
            // ne lógjon ki a képernyőről
            left = left.coerceIn(padX, maxOf(padX, this.width - width - padX))
            top = top.coerceIn(padY, maxOf(padY, this.height - h - padY))

            // háttér: az eredeti szöveg körüli szín, szöveg: ahhoz jól olvasható (fehér vagy fekete)
            bgPaint.color = Color.argb(245, Color.red(it.bg), Color.green(it.bg), Color.blue(it.bg))
            textPaint.color = if (luminance(it.bg) > 150) Color.rgb(15, 15, 15) else Color.WHITE
            canvas.drawRoundRect(left - padX, top - padY, left + width + padX, top + h + padY, radius, radius, bgPaint)

            // a szín miatt újra kell rajzolni a layoutot (a TextPaint színe benne van)
            layout = makeLayout(it.text, size, width)
            canvas.save()
            canvas.translate(left, top + (h - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun luminance(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    private fun makeLayout(text: String, size: Float, width: Int): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()
    }
}
