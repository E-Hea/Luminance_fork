package com.example.luminance.ui.vision

data class DetectionResult(
    val className: String,
    val confidence: Float,
    val left: Float,    // 0~1 비율
    val top: Float,
    val right: Float,
    val bottom: Float,
    val depthM: Float = -1f
) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}