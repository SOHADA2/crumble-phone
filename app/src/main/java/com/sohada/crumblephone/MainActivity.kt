package com.sohada.crumblephone

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 관제 화면. 이미지·커스텀 글꼴을 일부러 쓰지 않는다(가볍고 깔끔한 것이 우선).
 * 단색 몇 개 + 시스템 기본 글꼴 + 둥근 사각형만으로 만든다.
 */
class MainActivity : AppCompatActivity() {

    // ── 색 (어두운 중립 + 강조 하나) ──
    private val BG = Color.parseColor("#14161A")
    private val CARD = Color.parseColor("#1E2126")
    private val LINE = Color.parseColor("#2A2E35")
    private val TXT = Color.parseColor("#E9EBEE")
    private val SUB = Color.parseColor("#9AA1AA")
    private val ACCENT = Color.parseColor("#4C8DFF")
    private val STOP = Color.parseColor("#E5484D")

    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAP = 1001

    private lateinit var lblReady: TextView
    private lateinit var lblStatus: TextView
    private lateinit var lblDetail: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var setupRow: LinearLayout
    private lateinit var logView: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun round(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(s: String, fill: Int, fg: Int, outlined: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = s
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(fg)
            stateListAnimator = null
            background = if (outlined) round(Color.TRANSPARENT, 12, 1, fill) else round(fill, 12)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(8) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        root.addView(text("크럼블 폰봇", 24f, TXT, bold = true))
        lblReady = text("", 12f, SUB).apply { setPadding(0, dp(4), 0, dp(18)) }
        root.addView(lblReady)

        // 상태 카드
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 16, 1, LINE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        lblStatus = text("쉬는 중", 20f, TXT, bold = true)
        lblDetail = text("", 13f, SUB).apply { setPadding(0, dp(4), 0, 0) }
        card.addView(lblStatus); card.addView(lblDetail)
        root.addView(card)

        btnStart = button("토벌전 시작", ACCENT, Color.WHITE) { Runner.startTobol(applicationContext) }
        btnStop = button("멈추기", STOP, STOP, outlined = true) { Runner.stop() }
        root.addView(btnStart); root.addView(btnStop)

        // 준비 줄 — 아직 안 된 게 있을 때만 보인다
        setupRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        setupRow.addView(text("준비", 12f, SUB))
        setupRow.addView(button("접근성 서비스 켜기", LINE, TXT) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        setupRow.addView(button("화면 읽기 허용", LINE, TXT) { askProjection() })
        root.addView(setupRow)

        root.addView(button("게임 켜기", LINE, TXT) { launchGame() })

        root.addView(text("기록", 12f, SUB).apply { setPadding(0, dp(18), 0, dp(6)) })
        logView = text("", 11.5f, SUB)
        root.addView(ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(BG) })

        Bot.logger = { s -> ui.post { logView.append(s + "\n") } }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        tick()
    }

    /** 1초마다 상태만 갈아 끼운다(무거운 일은 하지 않는다). */
    private fun tick() {
        val accOk = TapService.isReady
        val capOk = CaptureService.instance != null
        lblReady.text = "접근성 " + (if (accOk) "✓" else "✗") + " · 화면 읽기 " + (if (capOk) "✓" else "✗")
        setupRow.visibility = if (accOk && capOk) View.GONE else View.VISIBLE

        lblStatus.text = Runner.status
        lblDetail.text = if (Runner.detail.isNotEmpty()) Runner.detail
                         else if (Runner.lastResult.isNotEmpty()) "지난 결과: " + Runner.lastResult else ""
        btnStart.isEnabled = !Runner.running && accOk && capOk
        btnStart.alpha = if (btnStart.isEnabled) 1f else 0.4f
        btnStop.visibility = if (Runner.running) View.VISIBLE else View.GONE

        ui.postDelayed({ tick() }, 1000)
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAP)
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage("com.devsisters.cc")
        if (i == null) Bot.log("게임을 찾지 못했습니다") else startActivity(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAP) {
            if (resultCode == RESULT_OK && data != null) CaptureService.start(this, resultCode, data)
            else Bot.log("화면 읽기를 거부했습니다")
        }
    }
}
