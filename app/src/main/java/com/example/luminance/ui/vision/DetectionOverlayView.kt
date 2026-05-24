package com.example.luminance.ui.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<DetectionResult> = emptyList()
    private var imageWidth = 640
    private var imageHeight = 480

    private val paintImmediate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintNear = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintAhead = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)                          // ← 추가
        setLayerType(LAYER_TYPE_HARDWARE, null)        // ← 추가: 깜빡임 방지

        paintImmediate.color = Color.parseColor("#BA1A1A")
        paintImmediate.style = Paint.Style.STROKE
        paintImmediate.strokeWidth = 4f

        paintNear.color = Color.parseColor("#9A4100")
        paintNear.style = Paint.Style.STROKE
        paintNear.strokeWidth = 4f

        paintAhead.color = Color.parseColor("#0059BA")
        paintAhead.style = Paint.Style.STROKE
        paintAhead.strokeWidth = 4f

        textPaint.color = Color.WHITE
        textPaint.textSize = 36f
        textPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK)

        bgPaint.color = Color.parseColor("#99000000")
        bgPaint.style = Paint.Style.FILL
    }

    fun updateDetections(results: List<DetectionResult>, imgW: Int, imgH: Int) {
        detections = results
        imageWidth = imgW
        imageHeight = imgH
        invalidate()  // 항상 갱신
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty()) return               // ← 추가: 빈 리스트면 바로 종료

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (det in detections) {
            val left   = det.left   * width
            val top    = det.top    * height
            val right  = det.right  * width
            val bottom = det.bottom * height

            val paint = when {
                det.depthM in 0f..1.5f -> paintImmediate
                det.depthM in 1.5f..3f -> paintNear
                else                   -> paintAhead
            }

            canvas.drawRect(left, top, right, bottom, paint)

            val label = "${det.className}  ${"%.1f".format(det.depthM)}m"
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(left, top - 44f, left + textWidth + 8f, top, bgPaint)
            canvas.drawText(label, left + 4f, top - 10f, textPaint)
        }
    }
}