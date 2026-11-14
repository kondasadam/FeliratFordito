package com.example.feliratfordito

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A kamerakép fölötti sárga keret: ezt kell a felirat sávjára igazítani.
 * Közepén megfogva mozgatható, a sarkainál fogva átméretezhető.
 */
class SubtitleBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onBoxChanged: ((RectF) -> Unit)? = null

    private val box = RectF(0.1f, 0.6f, 0.9f, 0.85f)   // 0..1 arányban
    private val px = RectF()
    private val density = resources.displayMetrics.density

    private val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 79)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 79)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 79)
        textSize = 14f * density
    }

    private enum class Mode { NONE, MOVE, TL, TR, BL, BR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    fun setBox(r: RectF) {
        box.set(r)
        invalidate()
    }

    private fun updatePx() {
        px.set(box.left * width, box.top * height, box.right * width, box.bottom * height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePx()
        val w = width.toFloat()
        val h = height.toFloat()
        // elsötétítés a kereten kívül
        canvas.drawRect(0f, 0f, w, px.top, dimPaint)
        canvas.drawRect(0f, px.bottom, w, h, dimPaint)
        canvas.drawRect(0f, px.top, px.left, px.bottom, dimPaint)
        canvas.drawRect(px.right, px.top, w, px.bottom, dimPaint)

        canvas.drawRoundRect(px, 10f * density, 10f * density, borderPaint)
        val rad = 9f * density
        canvas.drawCircle(px.left, px.top, rad, handlePaint)
        canvas.drawCircle(px.right, px.top, rad, handlePaint)
        canvas.drawCircle(px.left, px.bottom, rad, handlePaint)
        canvas.drawCircle(px.right, px.bottom, rad, handlePaint)
        canvas.drawText("Felirat helye", px.left + 6f * density, px.top - 8f * density, labelPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        updatePx()
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tol = 36f * density
                fun near(cx: Float, cy: Float) = abs(x - cx) < tol && abs(y - cy) < tol
                mode = when {
                    near(px.left, px.top) -> Mode.TL
                    near(px.right, px.top) -> Mode.TR
                    near(px.left, px.bottom) -> Mode.BL
                    near(px.right, px.bottom) -> Mode.BR
                    px.contains(x, y) -> Mode.MOVE
                    else -> Mode.NONE
                }
                lastX = x
                lastY = y
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE || width == 0 || height == 0) return false
                val dx = (x - lastX) / width
                val dy = (y - lastY) / height
                lastX = x
                lastY = y
                val minW = 0.1f
                val minH = 0.05f
                when (mode) {
                    Mode.MOVE -> {
                        val ndx = dx.coerceIn(-box.left, 1f - box.right)
                        val ndy = dy.coerceIn(-box.top, 1f - box.bottom)
                        box.offset(ndx, ndy)
                    }
                    Mode.TL -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minW)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minH)
                    }
                    Mode.TR -> {
                        box.right = (box.right + dx).coerceIn(box.left + minW, 1f)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minH)
                    }
                    Mode.BL -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minW)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minH, 1f)
                    }
                    Mode.BR -> {
                        box.right = (box.right + dx).coerceIn(box.left + minW, 1f)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minH, 1f)
                    }
                    Mode.NONE -> {}
                }
                onBoxChanged?.invoke(RectF(box))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = mode != Mode.NONE
                mode = Mode.NONE
                return handled
            }
        }
        return super.onTouchEvent(event)
    }
}
