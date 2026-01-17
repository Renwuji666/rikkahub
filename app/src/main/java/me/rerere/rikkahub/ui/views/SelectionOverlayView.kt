package me.rerere.rikkahub.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SelectionOverlayView(context: Context) : View(context) {
    private val dimPaint = Paint().apply {
        color = 0x88000000.toInt()
    }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var hasSelection = false

    fun getSelectionRect(): RectF? {
        if (!hasSelection) return null
        val left = min(startX, endX)
        val top = min(startY, endY)
        val right = max(startX, endX)
        val bottom = max(startY, endY)
        if (right - left < 10 || bottom - top < 10) return null
        return RectF(left, top, right, bottom)
    }

    fun clearSelection() {
        hasSelection = false
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                hasSelection = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endX = event.x
                endY = event.y
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val rect = getSelectionRect() ?: return
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, borderPaint)
    }
}
