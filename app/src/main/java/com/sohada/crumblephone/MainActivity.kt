package com.sohada.crumblephone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * 관제 화면. iOS 의 '묶음 목록(Inset Grouped)' 구조를 따랐다 —
 * 큰 제목 → 지금 상태 카드 → 주 버튼 → 묶음 목록 → 기록.
 *
 * 이미지도 커스텀 글꼴도 쓰지 않는다(APK 를 가볍게 유지한다). 앱처럼 보이는 것은
 * **색·간격·글자 크기의 위계**에서 나온다:
 *   34 굵게(제목) · 22 중간(지금 하는 일) · 17(버튼·목록) · 15(설명) · 13(구역 이름)
 * 색은 `Theme.kt` 가 라이트/다크 한 벌씩 들고 있다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var t: Theme
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAP = 1001

    private lateinit var lblSubtitle: TextView
    private lateinit var dot: View
    private lateinit var lblTask: TextView
    private lateinit var lblPercent: TextView
    private lateinit var track: FrameLayout
    private lateinit var fill: View
    private lateinit var lblStatus: TextView
    private lateinit var lblDetail: TextView
    private lateinit var btnPrimary: TextView
    private lateinit var runRows: LinearLayout
    private lateinit var setupSection: LinearLayout
    private lateinit var rowAcc: LinearLayout
    private lateinit var rowCap: LinearLayout
    private lateinit var rowOverlay: LinearLayout
    private lateinit var rowCapOff: LinearLayout
    private lateinit var capOffSep: View
    private lateinit var logView: TextView
    private lateinit var rowUpdate: LinearLayout
    private var lastProgress = -1
    private var setupShown = false      // 이번에 켠 뒤로 안내를 한 번 띄웠나

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun dpf(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun text(s: String, size: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (face != null) typeface = face
    }

    private val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ── iOS 묶음 목록 조각들 ──────────────────────────────────

    /** 구역 이름. 목록 위에 작게 붙는 회색 글씨. */
    private fun sectionHeader(title: String) = text(title, 13f, t.label2, medium).apply {
        setPadding(dp(20), dp(24), dp(20), dp(7))
        letterSpacing = 0.02f
    }

    /** 셀들을 담는 둥근 카드. 모서리 클리핑은 여기서 한 번만 한다. */
    private fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = t.round(t.cell, dpf(12f))
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        }
    }

    /** 셀 사이 가는 선. 왼쪽은 글자 시작점까지 들여쓴다(iOS 방식). */
    private fun separator(): View = View(this).apply {
        setBackgroundColor(t.separator)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, Math.max(1, (dpf(0.5f)).toInt())).apply {
            leftMargin = dp(16)
        }
    }

    /**
     * 목록 한 줄. 왼쪽 제목 · 오른쪽 값 · 그 옆 꺾쇠.
     * `tint` 를 주면 제목이 그 색이 된다(위험한 동작을 빨갛게 표시할 때).
     */
    private fun row(
        title: String,
        value: String = "",
        subtitle: String = "",
        chevron: Boolean = true,
        tint: Int? = null,
        onClick: () -> Unit
    ): LinearLayout {
        // 부제가 있으면 제목 아래 한 줄 더. 콘텐츠 줄이 무슨 일을 하는지 한눈에 보이게 한다.
        val lbl: View = if (subtitle.isEmpty()) {
            text(title, 17f, tint ?: t.label).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17f, tint ?: t.label))
                addView(text(subtitle, 13f, t.label2).apply { setPadding(0, dp(2), 0, 0) })
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        }
        val v = text(value, 17f, t.label2).apply { tag = "value" }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = t.ripple(t.cell)
            minimumHeight = dp(50)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(lbl)
            addView(v)
            if (chevron) addView(text("›", 20f, t.label3).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) }
            })
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.setValue(s: String, color: Int) {
        (findViewWithTag<TextView>("value"))?.let { it.text = s; it.setTextColor(color) }
    }

    private fun LinearLayout.enable(on: Boolean) {
        isEnabled = on; alpha = if (on) 1f else 0.35f
    }

    // ── 화면 ─────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Theme.of(this)
        window.statusBarColor = t.bg
        window.navigationBarColor = t.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !t.dark
            isAppearanceLightNavigationBars = !t.dark
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setPadding(0, dp(8), 0, dp(32))
        }

        // 큰 제목
        root.addView(text("크럼블 폰봇", 34f, t.label, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(20), dp(12), dp(20), 0)
        })
        lblSubtitle = text("", 15f, t.label2).apply { setPadding(dp(20), dp(2), dp(20), 0) }
        root.addView(lblSubtitle)

        // ── 지금 상태 ──
        val hero = group().apply {
            setPadding(dp(18), dp(16), dp(18), dp(18))
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        }
        dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.label3) }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) }
        }
        lblTask = text("대기 중", 13f, t.label2, medium).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        lblPercent = text("", 13f, t.label2, medium)
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot); addView(lblTask); addView(lblPercent)
        })
        lblStatus = text("쉬는 중", 22f, t.label, medium).apply { setPadding(0, dp(8), 0, 0) }
        lblDetail = text("", 15f, t.label2).apply { setPadding(0, dp(3), 0, 0) }
        hero.addView(lblStatus); hero.addView(lblDetail)

        // 진행률 막대. 너비를 직접 바꾸면 애니메이션이 안 되므로,
        // 꽉 찬 막대를 왼쪽 기준으로 scaleX 만 줄여 둔다(그건 부드럽게 움직인다).
        fill = View(this).apply {
            background = t.round(t.blue, dpf(2f))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            pivotX = 0f
            scaleX = 0f
        }
        track = FrameLayout(this).apply {
            background = t.round(t.separator, dpf(2f))
            addView(fill)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { topMargin = dp(14) }
        }
        hero.addView(track)
        root.addView(hero)

        // ── 주 버튼은 **돌고 있을 때만** 나온다(멈추기) ──
        // 무엇을 시작할지는 아래 '자동 실행' 목록에서 고른다. 콘텐츠가 늘어도 구조가 그대로다.
        btnPrimary = text("멈추기", 17f, Color.WHITE, medium).apply {
            gravity = Gravity.CENTER
            background = t.rippleRound(t.red, dpf(12f))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(16)
            }
            isClickable = true
            setOnClickListener { Runner.stop() }
        }
        root.addView(btnPrimary)

        // ── 자동 실행 ──
        root.addView(sectionHeader("자동 실행"))
        runRows = group()
        val rQuest = row("퀘스트", subtitle = "보상 받고 · 뽑기 · 상자 · 오븐 · 보스") {
            Overlay.show(applicationContext); Chores.start(applicationContext)
        }
        val rTobol = row("토벌전", subtitle = "반복 도전하고 점수 기록") {
            Overlay.show(applicationContext); Runner.startTobol(applicationContext)
        }
        val rDaily = row("일일 던전", value = "입장권", subtitle = "기회 다 쓰고 달성 보상까지") {
            Overlay.show(applicationContext); Daily.start(applicationContext)
        }
        val rArena = row("아레나", value = "재화", subtitle = "약한 상대만 골라 도전") {
            Overlay.show(applicationContext); Arena.start(applicationContext)
        }
        val rReward = row("보상만 받기", value = "안전", subtitle = "미션 · 출석 한 번만") {
            Overlay.show(applicationContext); Chores.startRewardsOnly(applicationContext)
        }
        runRows.addView(rQuest); runRows.addView(separator())
        runRows.addView(rTobol); runRows.addView(separator())
        runRows.addView(rDaily); runRows.addView(separator())
        runRows.addView(rArena); runRows.addView(separator())
        runRows.addView(rReward)
        root.addView(runRows)

        // ── 준비 (다 되면 통째로 사라진다) ──
        setupSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupSection.addView(sectionHeader("준비"))
        val g2 = group()
        g2.addView(row("차근차근 안내 받기") { openSetup() })
        g2.addView(separator())
        rowAcc = row("접근성 서비스") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        rowCap = row("화면 읽기") { askProjection() }
        rowOverlay = row("게임 위에 표시") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + packageName)))
        }
        g2.addView(rowAcc); g2.addView(separator()); g2.addView(rowCap); g2.addView(separator()); g2.addView(rowOverlay)
        setupSection.addView(g2)
        root.addView(setupSection)

        // ── 도구 ──
        root.addView(sectionHeader("도구"))
        val g3 = group()
        g3.addView(row("게임 켜기") { launchGame() })
        capOffSep = separator()
        rowCapOff = row("화면 읽기 끄기", tint = t.red) { CaptureService.stop(applicationContext) }
        g3.addView(capOffSep); g3.addView(rowCapOff)
        g3.addView(separator())
        rowUpdate = row("업데이트") { onUpdate() }
        g3.addView(rowUpdate)
        root.addView(g3)

        // ── 기록 ──
        root.addView(sectionHeader("기록"))
        logView = text("", 12f, t.label2, Typeface.MONOSPACE).apply { setLineSpacing(dpf(2f), 1f) }
        root.addView(group().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(ScrollView(this@MainActivity).apply {
                addView(logView)
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(180))
            })
        })

        setContentView(ScrollView(this).apply {
            addView(root)
            setBackgroundColor(t.bg)
            isVerticalScrollBarEnabled = false
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })

        Bot.logger = { s -> ui.post { logView.append(s + "\n") } }
        // 켤 때 조용히 한 번 본다. 릴리스 저장소가 없거나 인터넷이 안 되면 아무 말도 안 하고 넘어간다.
        Updater.check(applicationContext, quiet = true)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        tick()
    }

    override fun onResume() {
        super.onResume()
        // 켤 것이 남아 있으면 안내부터 보여 준다. 목록만 던져 두면 무엇부터 눌러야 할지 알 수 없다.
        // 한 번 띄우고 나면 다시 강요하지 않는다(목록의 '차근차근 안내 받기' 로 언제든 다시 열 수 있다).
        if (!setupShown && (!TapService.isReady || CaptureService.instance == null)) {
            setupShown = true
            openSetup()
        }
    }

    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    /** 1초마다 상태만 갈아 끼운다(무거운 일은 하지 않는다). */
    private fun tick() {
        val accOk = TapService.isReady
        val capOk = CaptureService.instance != null
        val ovOk = Overlay.canDraw(this)
        val ready = accOk && capOk

        rowAcc.setValue(if (accOk) "켜짐" else "필요", if (accOk) t.green else t.orange)
        rowCap.setValue(if (capOk) "켜짐" else "필요", if (capOk) t.green else t.orange)
        rowOverlay.setValue(if (ovOk) "켜짐" else "권장", if (ovOk) t.green else t.label2)
        setupSection.visibility = if (accOk && capOk && ovOk) View.GONE else View.VISIBLE
        rowCapOff.visibility = if (capOk) View.VISIBLE else View.GONE
        capOffSep.visibility = rowCapOff.visibility

        lblSubtitle.text = when {
            Runner.running -> "봇이 게임을 대신 하고 있어요"
            ready          -> "무엇을 자동으로 돌릴지 골라 주세요"
            else           -> "아래 '준비'를 먼저 해 주세요"
        }

        // 지금 상태
        val running = Runner.running
        (dot.background as GradientDrawable).setColor(if (running) t.green else t.label3)
        lblTask.text = if (running) (if (Runner.task.isEmpty()) "실행 중" else Runner.task + " 도는 중") else "대기 중"
        lblTask.setTextColor(if (running) t.green else t.label2)
        lblStatus.text = Runner.status
        lblDetail.text = if (Runner.detail.isNotEmpty()) Runner.detail
                         else if (Runner.lastResult.isNotEmpty()) "지난 결과 · " + Runner.lastResult else ""
        lblDetail.visibility = if (lblDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // 진행률 — 진짜 분모가 있는 작업만 막대를 보여 준다(-1 이면 숨긴다).
        val p = Runner.progress
        if (p < 0) {
            track.visibility = View.GONE
            lblPercent.text = ""
            lastProgress = -1
        } else {
            track.visibility = View.VISIBLE
            lblPercent.text = p.toString() + "%"
            if (p != lastProgress) {
                lastProgress = p
                fill.animate().scaleX(p / 100f).setDuration(280).start()
            }
        }

        btnPrimary.visibility = if (running) View.VISIBLE else View.GONE

        // 도는 동안에는 다른 콘텐츠를 못 고르게 흐린다(한 번에 하나만 돈다).
        for (i in 0 until runRows.childCount) {
            (runRows.getChildAt(i) as? LinearLayout)?.enable(!running && ready)
        }

        // 업데이트 줄 — 상태가 곧 값이다(최신 / 새 버전 1.12 있음 / 받는 중 47% …)
        rowUpdate.setValue(
            when {
                Updater.progress >= 0 -> Updater.state + " " + Updater.progress + "%"
                Updater.state.isNotEmpty() -> Updater.state
                else -> "v" + Updater.currentName(this)
            },
            if (Updater.latestCode > Updater.currentCode(this)) t.blue else t.label2
        )

        ui.postDelayed({ tick() }, 1000)
    }

    /** 새 판이 있으면 바로 받고, 아직 모르면 먼저 확인한다. */
    private fun onUpdate() {
        if (Updater.busy) return
        if (Updater.latestCode > Updater.currentCode(this)) Updater.update(applicationContext)
        else Updater.check(applicationContext)
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
