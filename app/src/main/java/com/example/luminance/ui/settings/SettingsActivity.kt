package com.example.luminance.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.luminance.R
import com.example.luminance.databinding.ActivitySettingsBinding
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import android.os.VibratorManager
import android.os.Build
import com.example.luminance.ui.hazard.HazardActivity
import com.example.luminance.ui.vision.VisionActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * SettingsActivity
 *
 * ST-001  라이브 안전 팁 배너 (서버 주기적 업데이트 — 현재는 로컬 더미 사용)
 * ST-002  음성 속도 세그먼트 컨트롤 (느림 / 보통 / 빠름) + 샘플 TTS 재생
 * ST-003  탐지 민감도 슬라이더 (느슨함 0 / 균형 50 / 정밀 100)
 * ST-004  소리 알림 ON/OFF 토글
 * ST-005  진동 ON/OFF 토글
 * ST-006  모든 설정 초기화 (확인 다이얼로그 포함)
 */
class SettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // SharedPreferences 키
    companion object {
        private const val PREFS_NAME      = "luminance_prefs"
        private const val KEY_VOICE_SPEED = "voice_speed"   // "slow" | "normal" | "fast"
        private const val KEY_SENSITIVITY = "sensitivity"   // 0f ~ 100f
        private const val KEY_SOUND       = "sound_enabled"
        private const val KEY_VIBRATION   = "vibration_enabled"

        // 기본값
        private const val DEFAULT_VOICE_SPEED = "normal"
        private const val DEFAULT_SENSITIVITY  = 50f
        private const val DEFAULT_SOUND        = true
        private const val DEFAULT_VIBRATION    = true

        // TTS 샘플 텍스트
        private const val TTS_SAMPLE = "안전 보행 안내 중입니다."
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // ───────────────────────────────────────────────────────────
    // Lifecycle
    // ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        setupToolbar()
        loadSavedSettings()
        setupSafetyTip()      // ST-001
        setupVoiceSpeed()     // ST-002
        setupSensitivity()    // ST-003
        setupSoundToggle()    // ST-004
        setupVibrationToggle()// ST-005
        setupResetButton()    // ST-006
        setupBottomNav()
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    // ───────────────────────────────────────────────────────────
    // TTS 초기화 콜백
    // ───────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            ttsReady = true
        }
    }

    // ───────────────────────────────────────────────────────────
    // Toolbar
    // ───────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        // 뒤로가기 버튼이 필요하면 아래 주석 해제
        // supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    // ───────────────────────────────────────────────────────────
    // 저장된 설정 불러오기
    // ───────────────────────────────────────────────────────────

    private fun loadSavedSettings() {
        // 음성 속도
        when (prefs.getString(KEY_VOICE_SPEED, DEFAULT_VOICE_SPEED)) {
            "slow"   -> binding.voiceSpeedGroup.check(R.id.btnSlow)
            "normal" -> binding.voiceSpeedGroup.check(R.id.btnNormal)
            "fast"   -> binding.voiceSpeedGroup.check(R.id.btnFast)
        }

        // 탐지 민감도
        binding.sensitivitySlider.value =
            prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)

        // 소리 알림
        binding.switchSound.isChecked =
            prefs.getBoolean(KEY_SOUND, DEFAULT_SOUND)

        // 진동
        binding.switchVibration.isChecked =
            prefs.getBoolean(KEY_VIBRATION, DEFAULT_VIBRATION)
    }

    // ───────────────────────────────────────────────────────────
    // ST-001 | 라이브 안전 팁
    // ───────────────────────────────────────────────────────────

    /**
     * 실제 구현에서는 Retrofit + ViewModel + LiveData 로 서버에서 주기적으로 팁을 받아와
     * tvSafetyTip.text 를 갱신합니다.
     * 현재는 로컬 더미 데이터로 대체합니다.
     */
    private fun setupSafetyTip() {
        val dummyTips = listOf(
            "180도 탐지를 위해 휴대폰을 앞쪽으로 기울여 주세요.",
            "혼잡한 구역에서는 탐지 민감도를 '느슨함'으로 설정해 보세요.",
            "이어폰 사용 시 음성 안내 볼륨을 최대로 높이세요."
        )
        binding.tvSafetyTip.text = dummyTips.random()

        // TODO: 서버 연동 시 아래 패턴 사용
        // viewModel.safetyTip.observe(this) { tip ->
        //     binding.tvSafetyTip.text = tip
        // }
    }

    // ───────────────────────────────────────────────────────────
    // ST-002 | 음성 속도 세그먼트
    // ───────────────────────────────────────────────────────────

    private fun setupVoiceSpeed() {
        binding.voiceSpeedGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val (speedKey, speechRate) = when (checkedId) {
                R.id.btnSlow   -> "slow"   to 0.7f
                R.id.btnFast   -> "fast"   to 1.4f
                else           -> "normal" to 1.0f
            }

            // 저장
            prefs.edit().putString(KEY_VOICE_SPEED, speedKey).apply()

            // 샘플 TTS 즉시 재생
            if (ttsReady) {
                tts.setSpeechRate(speechRate)
                tts.speak(TTS_SAMPLE, TextToSpeech.QUEUE_FLUSH, null, "sample")
            }
        }
    }

    // ───────────────────────────────────────────────────────────
    // ST-003 | 탐지 민감도 슬라이더
    // ───────────────────────────────────────────────────────────

    private fun setupSensitivity() {
        binding.sensitivitySlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener

            prefs.edit().putFloat(KEY_SENSITIVITY, value).apply()

            // 슬라이더 값 → AI 신뢰도 임계값 변환 예시
            // val threshold = value / 100f  → InferenceManager.setConfidenceThreshold(threshold)
        }
    }

    // ───────────────────────────────────────────────────────────
    // ST-004 | 소리 알림 토글
    // ───────────────────────────────────────────────────────────

    private fun setupSoundToggle() {
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SOUND, isChecked).apply()
            // AudioManager 또는 AlertManager 에 전달
            // AlertManager.setSoundEnabled(isChecked)
        }
    }

    // ───────────────────────────────────────────────────────────
    // ST-005 | 진동 토글
    // ───────────────────────────────────────────────────────────

    private fun setupVibrationToggle() {
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_VIBRATION, isChecked).apply()

            // ON으로 전환 시 짧은 햅틱 피드백으로 미리 체험
            if (isChecked) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
            // HapticManager.setEnabled(isChecked)
        }
    }

    // ───────────────────────────────────────────────────────────
    // ST-006 | 모든 설정 초기화
    // ───────────────────────────────────────────────────────────

    private fun setupResetButton() {
        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("설정 초기화")
                .setMessage("모든 설정을 기본값으로 되돌리시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
                .setPositiveButton("초기화") { _, _ ->
                    resetAllSettings()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun resetAllSettings() {
        // SharedPreferences 초기화
        prefs.edit()
            .putString(KEY_VOICE_SPEED, DEFAULT_VOICE_SPEED)
            .putFloat(KEY_SENSITIVITY,  DEFAULT_SENSITIVITY)
            .putBoolean(KEY_SOUND,      DEFAULT_SOUND)
            .putBoolean(KEY_VIBRATION,  DEFAULT_VIBRATION)
            .apply()

        // UI 초기화
        binding.voiceSpeedGroup.check(R.id.btnNormal)
        binding.sensitivitySlider.value  = DEFAULT_SENSITIVITY
        binding.switchSound.isChecked    = DEFAULT_SOUND
        binding.switchVibration.isChecked = DEFAULT_VIBRATION

        // TTS 속도도 기본값으로
        if (ttsReady) tts.setSpeechRate(1.0f)

        Snackbar.make(binding.root, "모든 설정이 초기화되었습니다.", Snackbar.LENGTH_SHORT).show()
    }

    // ── 하단 네비게이션 ─────────────────────────────────────────
    /**
     * 핵심 수정 포인트:
     * - 각 Activity에서 setSelectedItemId()로 현재 탭을 표시
     * - Intent에 FLAG_ACTIVITY_REORDER_TO_FRONT 사용 → 백스택 중복 방지
     * - HazardActivity 자신 탭은 아무 동작 안 함 (중복 생성 방지)
     */
    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.menu.findItem(R.id.nav_settings)?.isChecked = true

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> true  // 현재 화면, 이동 없음

                R.id.nav_hazard -> {
                    startActivity(Intent(this, HazardActivity::class.java))
                    overridePendingTransition(0, 0)  // 애니메이션 제거
                    finish()  // 뒤로가기 스택 정리
                    true
                }

                R.id.nav_vision -> {
                    startActivity(Intent(this, VisionActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }

                else -> false
            }
        }
    }
}