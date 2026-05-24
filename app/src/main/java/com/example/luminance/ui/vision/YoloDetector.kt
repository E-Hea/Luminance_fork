package com.example.luminance.ui.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloDetector(context: Context) {

    private val obstacleInterpreter: Interpreter
    private val surfaceInterpreter: Interpreter
    private val inputSize = 640
    private val confThreshold = 0.25f

    companion object {
        var useARCore = false
    }

    private val classNameKo = mapOf(
        "person" to "사람", "bicycle" to "자전거", "motorcycle" to "오토바이",
        "scooter" to "킥보드", "wheelchair" to "휠체어", "carrier" to "카트",
        "stroller" to "유모차", "movable_signage" to "이동식 간판", "dog" to "개",
        "cat" to "고양이", "bus" to "버스", "car" to "자동차", "truck" to "트럭",
        "bollard" to "볼라드", "barricade" to "바리케이드", "kiosk" to "키오스크",
        "potted_plant" to "화분", "power_controller" to "전력함", "fire_hydrant" to "소화전",
        "pole" to "기둥", "tree_trunk" to "나무", "traffic_light" to "신호등",
        "traffic_light_controller" to "신호등 제어기", "traffic_sign" to "교통 표지판",
        "parking_meter" to "주차 미터기", "stop" to "정지 표지판", "bench" to "벤치",
        "chair" to "의자", "table" to "테이블",
        "sidewalk" to "인도", "bike_lane" to "자전거 도로", "alley" to "골목",
        "roadway" to "차도", "braille_guide_blocks" to "점자 블록", "caution_zone" to "주의 구역"
    )

    private val obstacleClasses = listOf(
        "person", "bicycle", "motorcycle", "scooter", "wheelchair",
        "carrier", "stroller", "movable_signage", "dog", "cat",
        "bus", "car", "truck", "bollard", "barricade",
        "kiosk", "potted_plant", "power_controller", "fire_hydrant", "pole",
        "tree_trunk", "traffic_light", "traffic_light_controller", "traffic_sign",
        "parking_meter", "stop", "bench", "chair", "table"
    )

    private val surfaceClasses = listOf(
        "sidewalk", "bike_lane", "alley", "roadway",
        "braille_guide_blocks", "caution_zone"
    )

    init {
        obstacleInterpreter = Interpreter(loadModel(context, "obstacle_model.tflite"))
        surfaceInterpreter = Interpreter(loadModel(context, "surface_model.tflite"))
    }

    private fun loadModel(context: Context, fileName: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(fileName)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = bitmapToInput(resized)

        val obstacles = runModel(obstacleInterpreter, input, obstacleClasses)
        val surfaces = runModel(surfaceInterpreter, input, surfaceClasses)

        return obstacles + surfaces
    }

    private fun bitmapToInput(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(inputSize) { Array(inputSize) { FloatArray(3) } } }
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = bitmap.getPixel(x, y)
                input[0][y][x][0] = ((px shr 16) and 0xFF) / 255f
                input[0][y][x][1] = ((px shr 8) and 0xFF) / 255f
                input[0][y][x][2] = (px and 0xFF) / 255f
            }
        }
        return input
    }

    private fun runModel(
        interpreter: Interpreter,
        input: Array<Array<Array<FloatArray>>>,
        classList: List<String>
    ): List<DetectionResult> {

        val numClasses = classList.size
        val outputShape = interpreter.getOutputTensor(0).shape()
        val totalChannels = outputShape[1]

        val output = Array(1) { Array(totalChannels) { FloatArray(8400) } }
        interpreter.run(input, output)

        val results = mutableListOf<DetectionResult>()

        for (i in 0 until 8400) {
            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]

            var maxConf = 0f
            var maxIdx = 0
            for (c in 0 until numClasses) {
                val conf = output[0][4 + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxIdx = c
                }
            }

            if (maxConf < confThreshold) continue

            val top = cy - h / 2f
            val bottom = cy + h / 2f
            val bboxHeight = bottom - top  // ← 수정

            val depthM = if (useARCore) {
                -1f
            } else {
                when {
                    bboxHeight > 0.5f -> 1.0f  // ← 수정
                    bboxHeight > 0.3f -> 2.0f  // ← 수정
                    bboxHeight > 0.1f -> 4.0f  // ← 수정
                    else -> 8.0f
                }
            }

            results.add(
                DetectionResult(
                    className = classNameKo[classList[maxIdx]] ?: classList[maxIdx],
                    confidence = maxConf,
                    left = (cx - w / 2f) ,
                    top = top,
                    right = (cx + w / 2f) ,
                    bottom = bottom,
                    depthM = depthM
                )
            )
        }

        return results
    }
}