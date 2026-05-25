package com.example.luminance.ui.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.luminance.R
import com.example.luminance.databinding.ActivityVisionBinding
import com.example.luminance.ui.hazard.HazardActivity
import com.example.luminance.ui.settings.SettingsActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Locale
import java.util.concurrent.Executors
import com.example.luminance.BuildConfig

class VisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionBinding
    private lateinit var tts: TextToSpeech
    private lateinit var detector: YoloDetector
    private lateinit var vibrator: Vibrator
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private var isProcessing = false
    private var lastSpokenTime = 0L
    private var lastVibrationTime = 0L
    private val TTS_COOLDOWN_MS = 3000L
    private val VIBRATION_COOLDOWN_MS = 3000L
    private var isCameraStarted = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ARCore
    private var arSession: Session? = null
    private var arCoreAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        checkAndRequestPermissions()
        setupBottomNav()
        setupMicButton()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
            }
        }

        detector = YoloDetector(this)
        initARCore()
    }

    private fun initARCore() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            if (availability.isSupported) {
                arSession = Session(this).also { session ->
                    val config = Config(session).apply {
                        depthMode = Config.DepthMode.AUTOMATIC
                    }
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        session.configure(config)
                        arCoreAvailable = true
                        YoloDetector.useARCore = true
                        android.util.Log.d("LUMINANCE", "ARCore Depth 사용 가능!")
                    } else {
                        android.util.Log.d("LUMINANCE", "Depth 모드 미지원 기기")
                    }
                }
            } else {
                android.util.Log.d("LUMINANCE", "ARCore 미지원 기기")
            }
        } catch (e: Exception) {
            android.util.Log.e("LUMINANCE", "ARCore 초기화 실패: ${e.message}")
            arCoreAvailable = false
        }
    }

    private fun getDepthFromARCore(x: Float, y: Float): Float {
        val session = arSession ?: return -1f
        return try {
            session.resume()
            val frame = session.update()
            val depthImage = frame.acquireDepthImage16Bits()
            val imgW = depthImage.width
            val imgH = depthImage.height
            val px = (x * imgW).toInt().coerceIn(0, imgW - 1)
            val py = (y * imgH).toInt().coerceIn(0, imgH - 1)
            val buffer = depthImage.planes[0].buffer
            val depthMm = buffer.getShort((py * imgW + px) * 2).toInt() and 0xFFFF
            depthImage.close()
            depthMm / 1000f  // mm → m 변환
        } catch (e: Exception) {
            -1f
        }
    }

    private fun checkAndRequestPermissions() {
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            setupCamera()
            return
        }
        val needsRationale = denied.any { shouldShowRequestPermissionRationale(it) }
        if (needsRationale) {
            AlertDialog.Builder(this)
                .setTitle("권한이 필요합니다")
                .setMessage(buildRationaleMessage(denied))
                .setPositiveButton("허용하기") { _, _ ->
                    permissionLauncher.launch(denied.toTypedArray())
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            permissionLauncher.launch(denied.toTypedArray())
        }
    }

    private fun buildRationaleMessage(denied: List<String>): String {
        return buildString {
            if (Manifest.permission.CAMERA in denied)
                append("• 카메라: 전방 위험 감지에 필요합니다.\n")
            if (Manifest.permission.RECORD_AUDIO in denied)
                append("• 마이크: 음성 명령 기능에 필요합니다.\n")
            if (Manifest.permission.ACCESS_FINE_LOCATION in denied)
                append("• 위치: 길 안내 기능에 필요합니다.\n")
        }.trimEnd()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                setupCamera()
                return@registerForActivityResult
            }
            if (Manifest.permission.CAMERA in denied) {
                updateGuidanceText("카메라 권한이 없어 감지 기능을 사용할 수 없습니다.")
            } else {
                setupCamera()
            }
            val permanentlyDenied = denied.filter { !shouldShowRequestPermissionRationale(it) }
            if (permanentlyDenied.isNotEmpty()) {
                showGoToSettingsDialog(permanentlyDenied.toSet())
            }
        }

    private fun showGoToSettingsDialog(denied: Set<String>) {
        AlertDialog.Builder(this)
            .setTitle("권한 설정 필요")
            .setMessage(
                "일부 권한이 차단되어 설정에서 직접 허용해야 합니다.\n\n" +
                        buildRationaleMessage(denied.toList())
            )
            .setPositiveButton("설정으로 이동") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun setupCamera() {
        if (isCameraStarted) return
        isCameraStarted = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(inferenceExecutor) { imageProxy ->
                if (!isProcessing) {
                    isProcessing = true
                    processFrame(imageProxy)
                } else {
                    imageProxy.close()
                }
            }
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        android.util.Log.d("LUMINANCE", "processFrame 호출됨 - ${imageProxy.width}x${imageProxy.height}")
        try {
            val bitmap = imageProxy.toBitmap()
            var detections = detector.detect(bitmap)

            // ARCore로 각 탐지 객체 거리 측정
            if (arCoreAvailable) {
                detections = detections.map { det ->
                    val depthM = getDepthFromARCore(det.centerX, det.centerY)
                    if (depthM > 0) det.copy(depthM = depthM) else det
                }
            }

            android.util.Log.d("LUMINANCE", "탐지 결과: ${detections.size}개")

            DetectionRepository.detections.postValue(detections)

            runOnUiThread {
                binding.detectionOverlay.updateDetections(detections, bitmap.width, bitmap.height)
                speakTopHazard(detections)
                vibrateForHazard(detections)
            }
        } catch (e: Exception) {
            android.util.Log.e("LUMINANCE", "추론 오류: ${e.message}", e)
        } finally {
            imageProxy.close()
            isProcessing = false
        }
    }

    private fun vibrateForHazard(detections: List<DetectionResult>) {
        if (detections.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastVibrationTime < VIBRATION_COOLDOWN_MS) return
        val top = detections.minByOrNull { it.depthM } ?: return
        val pattern = when {
            top.depthM < 1.5f -> longArrayOf(0, 200, 100, 200, 100, 200)
            top.depthM < 3.0f -> longArrayOf(0, 200, 100, 200)
            else -> longArrayOf(0, 200)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        lastVibrationTime = now
    }

    private fun speakTopHazard(detections: List<DetectionResult>) {
        if (detections.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSpokenTime < TTS_COOLDOWN_MS) return
        val top = detections.minByOrNull { it.depthM } ?: return
        val direction = when {
            top.centerX < 0.33f -> "왼쪽"
            top.centerX > 0.66f -> "오른쪽"
            else -> "전방"
        }
        val sentence = when {
            top.depthM in 0f..1.5f -> "${direction}에 ${top.className} 있습니다. 즉시 주의하세요."
            top.depthM in 1.5f..3f -> "${direction}에서 ${top.className}이 접근 중입니다."
            top.depthM > 0 -> "전방에 ${top.className} 있습니다."
            else -> "전방에 ${top.className} 있습니다."
        }
        tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, null)
        lastSpokenTime = now
        updateGuidanceText(sentence)
    }

    fun updateGuidanceText(message: String) {
        binding.tvGuidanceText.text = message
        binding.tvGuidanceText.contentDescription = "실시간 안내: $message"
    }

    override fun onResume() {
        super.onResume()
        try { arSession?.resume() } catch (e: Exception) { }
        if (!isCameraStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        try { arSession?.pause() } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        inferenceExecutor.shutdown()
        arSession?.close()
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.menu.findItem(R.id.nav_vision)?.isChecked = true
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_vision -> true
                R.id.nav_hazard -> {
                    startActivity(Intent(this, HazardActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupMicButton() {
        binding.btnMic.setOnClickListener { startVoiceCommand() }
        binding.fabMic.setOnClickListener { startVoiceCommand() }
    }

    private fun startVoiceCommand() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "말씀하세요...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            updateGuidanceText("음성 인식을 사용할 수 없습니다.")
        }
    }

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val matches = result.data
                    ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                val command = matches?.firstOrNull() ?: return@registerForActivityResult
                handleVoiceCommand(command)
            }
        }

    private fun handleVoiceCommand(command: String) {
        android.util.Log.d("LUMINANCE", "음성 명령: $command")
        val detections = DetectionRepository.latestDetections

        val detectionContext = if (detections.isEmpty()) {
            "현재 탐지된 객체 없음"
        } else {
            detections.take(5).joinToString(", ") {
                val dir = when {
                    it.centerX < 0.33f -> "왼쪽"
                    it.centerX > 0.66f -> "오른쪽"
                    else -> "전방"
                }
                "${it.className}(${dir}, ${"%.1f".format(it.depthM)}m)"
            }
        }

        val prompt = """
        당신은 시각장애인을 돕는 AI 보조기입니다.
        현재 카메라로 탐지된 주변 상황: $detectionContext
        사용자 질문: $command
        짧고 명확하게 한국어로 답변하세요. 2문장 이내로.
    """.trimIndent()

        android.os.AsyncTask.execute {
            var conn: java.net.HttpURLConnection? = null
            try {
                Log.d("KEY_TEST", BuildConfig.GEMINI_API_KEY)
                val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
                conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val body = """{"contents": [{"parts": [{"text": "${prompt.replace("\"", "\\\"")}"}]}]}"""
                conn.outputStream.write(body.toByteArray())

                val responseCode = conn.responseCode
                android.util.Log.d("LUMINANCE", "Gemini 응답코드: $responseCode")

                if (responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(response)
                    val answer = json
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    runOnUiThread {
                        tts.speak(answer, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                        updateGuidanceText(answer)
                    }
                } else {
                    val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "에러 없음"
                    android.util.Log.e("LUMINANCE", "Gemini 에러 응답($responseCode): $errorBody")
                    runOnUiThread {
                        updateGuidanceText("AI 응답 오류: $responseCode")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LUMINANCE", "Gemini 오류: ${e.message}", e)
                runOnUiThread {
                    updateGuidanceText("AI 응답 오류가 발생했습니다.")
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}