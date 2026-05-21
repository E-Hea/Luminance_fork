package com.example.luminance.ui.vision

import androidx.lifecycle.MutableLiveData

object DetectionRepository {
    val detections = MutableLiveData<List<DetectionResult>>(emptyList())

    // 하위 호환용
    val latestDetections: List<DetectionResult>
        get() = detections.value ?: emptyList()
}