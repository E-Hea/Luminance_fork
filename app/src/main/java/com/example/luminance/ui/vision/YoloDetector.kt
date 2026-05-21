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
        var useARCore = false  // S22 연결 후 ARCore 구현 시 true로 변경
    }

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

            val top = (cy - h / 2f) / inputSize
            val bottom = (cy + h / 2f) / inputSize
            val bboxHeight = bottom - top

            val depthM = if (useARCore) {
                -1f  // ARCore에서 채워짐
            } else {
                when {
                    bboxHeight > 0.5f -> 1.0f
                    bboxHeight > 0.3f -> 2.0f
                    bboxHeight > 0.1f -> 4.0f
                    else -> 8.0f
                }
            }

            results.add(
                DetectionResult(
                    className = classList[maxIdx],
                    confidence = maxConf,
                    left = (cx - w / 2f) / inputSize,
                    top = top,
                    right = (cx + w / 2f) / inputSize,
                    bottom = bottom,
                    depthM = depthM
                )
            )
        }

        return results
    }
}