package com.example.luminance.ui.hazard

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.luminance.R
import com.example.luminance.ui.settings.SettingsActivity
import com.example.luminance.ui.vision.DetectionRepository
import com.example.luminance.ui.vision.DetectionResult
import com.example.luminance.ui.vision.VisionActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

class HazardActivity : AppCompatActivity() {

    private var ttsEnabled = true
    private var hapticEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hazard)
        setupPulseDot()
        setupQuickActions()
        setupMapView()
        setupBottomNav()
        setupHazardCards()  // ← 실시간 탐지 결과 카드
    }

    // ← 이거 추가
    override fun onResume() {
        super.onResume()
        DetectionRepository.detections.observe(this) { detections ->
            setupHazardCards()
            setupMapView()
        }
    }

    private fun setupHazardCards() {
        val detections = DetectionRepository.latestDetections
        val container = findViewById<LinearLayout>(R.id.hazardCardContainer)
        val tvCount = findViewById<TextView>(R.id.tvHazardCount)

        container.removeAllViews()

        if (detections.isEmpty()) {
            tvCount.text = "탐지된 위험 요소 없음"
            val tv = TextView(this).apply {
                text = "현재 주변에 위험 요소가 없습니다."
                textSize = 15f
                setTextColor(Color.parseColor("#414754"))
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            }
            container.addView(tv)
            return
        }

// 같은 클래스는 가장 가까운 것 하나만
        val filtered = detections
            .groupBy { it.className }
            .map { (_, list) -> list.minByOrNull { it.depthM }!! }
            .sortedBy { it.depthM }

        tvCount.text = "${detections.size}개의 위험 요소 탐지됨"

        for (det in detections) {
            val level = when {
                det.depthM in 0f..1.5f -> "즉시 대응"
                det.depthM in 1.5f..3f -> "가까움"
                else -> "전방"
            }
            val borderColor = when (level) {
                "즉시 대응" -> "#BA1A1A"
                "가까움" -> "#9A4100"
                else -> "#0059BA"
            }
            val direction = when {
                det.centerX < 0.33f -> "왼쪽"
                det.centerX > 0.66f -> "오른쪽"
                else -> "전방"
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12.dpToPx() }
                setBackgroundResource(R.drawable.bg_hazard_card)
            }

            val border = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(6.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.parseColor(borderColor))
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            }

            val badge = TextView(this).apply {
                text = level
                textSize = 11f
                setTextColor(Color.parseColor(borderColor))
                setTypeface(null, Typeface.BOLD)
                setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dpToPx() }
            }

            val title = TextView(this).apply {
                text = det.className
                textSize = 20f
                setTextColor(Color.parseColor("#191C1D"))
                setTypeface(null, Typeface.BOLD)
            }

            val desc = TextView(this).apply {
                text = "$direction 방향, ${"%.1f".format(det.depthM)}m 거리"
                textSize = 15f
                setTextColor(Color.parseColor("#414754"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4.dpToPx() }
            }

            inner.addView(badge)
            inner.addView(title)
            inner.addView(desc)
            card.addView(border)
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun setupQuickActions() {
        val btnTts = findViewById<ImageButton>(R.id.btnTtsToggle)
        val btnHaptic = findViewById<ImageButton>(R.id.btnHapticToggle)

        btnTts.setOnClickListener {
            ttsEnabled = !ttsEnabled
            updateTtsButton(btnTts)
            Toast.makeText(this, if (ttsEnabled) "음성 안내 켜짐" else "음성 안내 꺼짐", Toast.LENGTH_SHORT).show()
        }

        btnHaptic.setOnClickListener {
            hapticEnabled = !hapticEnabled
            updateHapticButton(btnHaptic)
            Toast.makeText(this, if (hapticEnabled) "진동 피드백 켜짐" else "진동 피드백 꺼짐", Toast.LENGTH_SHORT).show()
        }

        updateTtsButton(btnTts)
        updateHapticButton(btnHaptic)
    }

    private fun updateTtsButton(btn: ImageButton) {
        btn.setImageResource(if (ttsEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
        btn.imageTintList = ContextCompat.getColorStateList(this, if (ttsEnabled) R.color.md_primary else R.color.md_on_surface_variant)
        btn.alpha = if (ttsEnabled) 1f else 0.5f
    }

    private fun updateHapticButton(btn: ImageButton) {
        btn.setImageResource(if (hapticEnabled) R.drawable.ic_vibration_on else R.drawable.ic_vibration_off)
        btn.imageTintList = ContextCompat.getColorStateList(this, if (hapticEnabled) R.color.md_primary else R.color.md_on_surface_variant)
        btn.alpha = if (hapticEnabled) 1f else 0.5f
    }

    private fun setupPulseDot() {
        val dot = findViewById<View>(R.id.pulseDot)
        val scaleX = ObjectAnimator.ofFloat(dot, "scaleX", 1f, 1.4f, 1f).apply { repeatCount = ValueAnimator.INFINITE }
        val scaleY = ObjectAnimator.ofFloat(dot, "scaleY", 1f, 1.4f, 1f).apply { repeatCount = ValueAnimator.INFINITE }
        val alpha = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.4f, 1f).apply { repeatCount = ValueAnimator.INFINITE }
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1200
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun setupMapView() {
        val mapView = findViewById<CustomMapView>(R.id.customMapView)
        val detections = DetectionRepository.latestDetections

        val pins = detections.mapIndexed { i, det ->
            CustomMapView.HazardPin(
                id = "$i",
                label = det.className,
                detail = "${"%.1f".format(det.depthM)}m 거리",
                level = when {
                    det.depthM in 0f..1.5f -> CustomMapView.HazardLevel.IMMEDIATE
                    det.depthM in 1.5f..3f -> CustomMapView.HazardLevel.NEAR
                    else -> CustomMapView.HazardLevel.AHEAD
                },
                relX = det.centerX - 0.5f,
                relY = det.centerY - 0.5f
            )
        }

        mapView.setPins(pins)
        mapView.setOnPinTappedListener { pin ->
            Toast.makeText(this, "${pin.label}: ${pin.detail}", Toast.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnExpandMap).setOnClickListener {
            Toast.makeText(this, "전체 지도 보기 (준비 중)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.nav_hazard
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_hazard -> true
                R.id.nav_vision -> {
                    startActivity(Intent(this, VisionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    })
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    })
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }
}