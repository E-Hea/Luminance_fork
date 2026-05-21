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
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.Locale
import java.util.concurrent.Executors

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
            val detections = detector.detect(bitmap)
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

    // 진동 패턴: 즉시대응=3회, 가까움=2회, 전방=1회
    private fun vibrateForHazard(detections: List<DetectionResult>) {
        if (detections.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastVibrationTime < VIBRATION_COOLDOWN_MS) return

        val top = detections.minByOrNull { it.depthM } ?: return

        val pattern = when {
            top.depthM < 1.5f -> longArrayOf(0, 200, 100, 200, 100, 200)  // 즉시대응: 3회
            top.depthM < 3.0f -> longArrayOf(0, 200, 100, 200)             // 가까움: 2회
            else -> longArrayOf(0, 200)                                      // 전방: 1회
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
            top.depthM < 1.5f -> "${direction}에 ${top.className} 있습니다. 즉시 주의하세요."
            top.depthM < 3.0f -> "${direction}에서 ${top.className}이 접근 중입니다."
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
        if (!isCameraStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(handler.toString().let { { } })
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        inferenceExecutor.shutdown()
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        // 먼저 선택 상태를 강제 지정 (딜레이 없이 즉시 반영)
        bottomNav.menu.findItem(R.id.nav_vision)?.isChecked = true

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_vision -> true  // 현재 화면

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
        val response = when {
            command.contains("뭐") || command.contains("무엇") || command.contains("있") -> {
                if (detections.isEmpty()) "주변에 탐지된 위험 요소가 없습니다."
                else {
                    val top = detections.minByOrNull { it.depthM }!!
                    val direction = when {
                        top.centerX < 0.33f -> "왼쪽"
                        top.centerX > 0.66f -> "오른쪽"
                        else -> "전방"
                    }
                    "$direction 에 ${top.className} 있습니다. 거리는 약 ${"%.1f".format(top.depthM)}미터입니다."
                }
            }
            command.contains("위험") -> {
                if (detections.isEmpty()) "현재 위험 요소가 없습니다."
                else "${detections.size}개의 위험 요소가 탐지되었습니다."
            }
            command.contains("안전") -> "현재 전방을 분석 중입니다. 주의하며 이동하세요."
            command.contains("멈춰") || command.contains("정지") -> "정지합니다. 주변을 확인하세요."
            else -> "죄송합니다. 다시 말씀해 주세요."
        }
        tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, null)
        updateGuidanceText(response)
    }


    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}