package com.example.luminance.ui.hazard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * HA-005: 주변 위험 2D 지도 커스텀 뷰
 * - 현재 위치 중심 표시
 * - 위험 핀 렌더링 (빨강/주황/파랑)
 * - 핀 탭 시 콜백
 */
class CustomMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 데이터 모델 ──────────────────────────────────────────────
    enum class HazardLevel { IMMEDIATE, NEAR, AHEAD }

    data class HazardPin(
        val id: String,
        val label: String,
        val detail: String,
        val level: HazardLevel,
        /** 현재 위치 기준 상대 좌표 (-1f ~ 1f, x=동서, y=남북) */
        val relX: Float,
        val relY: Float
    )

    // ── Paint ────────────────────────────────────────────────────
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F0F4F8")
        style = Paint.Style.FILL
    }

    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0059BA")
        style = Paint.Style.FILL
    }

    private val userRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#400059BA")
        style = Paint.Style.FILL
    }

    private val pinPaints = mapOf(
        HazardLevel.IMMEDIATE to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BA1A1A"); style = Paint.Style.FILL
        },
        HazardLevel.NEAR to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9A4100"); style = Paint.Style.FILL
        },
        HazardLevel.AHEAD to Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0059BA"); style = Paint.Style.FILL
        }
    )

    private val pinStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    // ── 상태 ─────────────────────────────────────────────────────
    private var pins: List<HazardPin> = emptyList()
    private var onPinTapped: ((HazardPin) -> Unit)? = null
    private val pinRects = mutableListOf<Pair<RectF, HazardPin>>()

    // ── 공개 API ─────────────────────────────────────────────────
    fun setPins(list: List<HazardPin>) {
        pins = list
        invalidate()
    }

    fun setOnPinTappedListener(listener: (HazardPin) -> Unit) {
        onPinTapped = listener
    }

    // ── 터치 ─────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x; val y = event.y
            pinRects.forEach { (rect, pin) ->
                if (rect.contains(x, y)) {
                    onPinTapped?.invoke(pin)
                    return true
                }
            }
        }
        return true
    }

    // ── 드로잉 ───────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pinRects.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 배경
        canvas.drawRoundRect(0f, 0f, w, h, 24f, 24f, bgPaint)

        // 그리드
        val step = w / 6f
        var gx = step
        while (gx < w) { canvas.drawLine(gx, 0f, gx, h, gridPaint); gx += step }
        var gy = step
        while (gy < h) { canvas.drawLine(0f, gy, w, gy, gridPaint); gy += step }

        // 방위 표시
        canvas.drawText("N", cx, 32f, compassPaint)
        canvas.drawText("S", cx, h - 8f, compassPaint)
        canvas.drawText("W", 16f, cy + 8f, compassPaint)
        canvas.drawText("E", w - 16f, cy + 8f, compassPaint)

        // 위험 핀
        val scale = (w.coerceAtMost(h) / 2f) * 0.85f
        val pinR = 22f

        pins.forEach { pin ->
            val px = cx + pin.relX * scale
            val py = cy + pin.relY * scale

            // 핀 그림자
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#33000000")
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(px, py + 3f, pinR, shadowPaint)

            // 핀 원
            pinPaints[pin.level]?.let { canvas.drawCircle(px, py, pinR, it) }
            canvas.drawCircle(px, py, pinR, pinStrokePaint)

            // 핀 첫 글자

// 탭 감지 영역
            pinRects.add(RectF(px - pinR * 1.5f,py - pinR * 1.5f,px + pinR * 1.5f,py + pinR * 1.5f) to pin
            )
        }

        // 현재 위치 (사용자)
        canvas.drawCircle(cx, cy, 28f, userRingPaint)
        canvas.drawCircle(cx, cy, 14f, userPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, 5f, dotPaint)
    }
}