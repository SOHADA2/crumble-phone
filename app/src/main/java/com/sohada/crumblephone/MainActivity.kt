package com.sohada.crumblephone

import android.content.Intent
import android.graphics.Bitmap
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
    private lateinit var rowGame: LinearLayout
    private lateinit var swTest: Switch
    private lateinit var swSweep: Switch
    private lateinit var swDim: Switch
    private lateinit var rowArenaCount: LinearLayout
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

    /** 켜고 끄는 줄. 오른쪽 끝에 스위치가 붙는다(꺾쇠 대신). */
    private fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): Pair<LinearLayout, Switch> {
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val lbl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 17f, t.label))
            addView(text(subtitle, 13f, t.label2).apply { setPadding(0, dp(2), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = t.ripple(t.cell)
            minimumHeight = dp(50)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(lbl); addView(sw)
            isClickable = true
            setOnClickListener { sw.toggle() }
        }
        return row to sw
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
        Prefs.init(this)
        // 화면 읽기를 켜기 전에도 비율을 알 수 있게 미리 잡아 둔다(캡처가 시작되면 그 값으로 확정된다).
        resources.displayMetrics.let { Coords.set(it.widthPixels, it.heightPixels) }
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

        // ── 설정 ──
        root.addView(sectionHeader("설정"))
        val g4 = group()
        // 재화를 쓰는 콘텐츠(일일 던전·아레나·오븐)를 공짜로 시험하는 길. 좌표 확인용이다.
        val (rTest, sTest) = switchRow("시험 모드", "재화·입장권을 안 쓰고 진입까지만", Prefs.testMode) {
            Prefs.testMode = it
        }
        swTest = sTest
        rowArenaCount = row("아레나 판수", value = Prefs.arenaFights.toString() + "판",
            subtitle = "재화가 먼저 떨어지면 거기서 끝난다") { cycleArena() }
        val (rSweep, sSweep) = switchRow("일일 던전 소탕", "SKIP 티켓까지 쓴다 (광고 제거 보유자용)", Prefs.dailySweep) {
            Prefs.dailySweep = it
        }
        swSweep = sSweep
        // 화면은 끌 수 없다(화면 읽기도 탭도 화면이 켜져 있어야 한다). 대신 백라이트만 내린다 —
        // 화면 읽기는 프레임버퍼를 읽으므로 봇에는 아무 영향이 없다.
        val (rDim, sDim) = switchRow("화면 어둡게",
            "봇이 도는 동안만. 알약을 탭하면 15초 밝아져요", Prefs.dimScreen) {
            Prefs.dimScreen = it
            if (it && !Overlay.canDraw(this)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("'게임 위에 표시'가 필요해요")
                    .setMessage("화면을 어둡게 하는 것도 게임 위에 띄우는 창으로 합니다.\n" +
                                "준비 → '게임 위에 표시'를 먼저 허용해 주세요.")
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
        swDim = sDim
        g4.addView(rTest); g4.addView(separator())
        g4.addView(rowArenaCount); g4.addView(separator())
        g4.addView(rSweep); g4.addView(separator())
        g4.addView(rDim)
        root.addView(g4)

        // ── 도구 ──
        root.addView(sectionHeader("도구"))
        val g3 = group()
        rowGame = row("게임 앱", subtitle = "스토어마다 이름이 달라서 여기서 고를 수 있어요") { pickGame() }
        g3.addView(rowGame); g3.addView(separator())
        g3.addView(row("게임 켜기") { launchGame() })
        g3.addView(separator())
        // 새 기기에서 좌표가 맞는지만 확인한다. 아무것도 누르지 않으니 재화를 안 쓴다.
        g3.addView(row("화면 좌표 확인", subtitle = "게임을 띄워 한 장만 보고 판단해요") {
            Overlay.show(applicationContext); Runner.checkCoords(applicationContext)
        })
        g3.addView(separator())
        // 스크린샷을 찍어 보내는 건 번거롭다. 글로 복사하면 채팅에 그냥 붙여넣을 수 있다.
        g3.addView(row("지금 화면 재기", subtitle = "게임을 원하는 화면에 두고 눌러요") { measureNow() })
        g3.addView(separator())
        g3.addView(row("탭 시험", subtitle = "게임 메인 화면에 두고 눌러요 — 탭이 먹는지 봅니다") { tapTest() })
        g3.addView(separator())
        g3.addView(row("화면 보내기", subtitle = "게임을 띄워 5초 뒤 찍어요 — 좌표를 다시 잴 때") { sendShot() })
        g3.addView(separator())
        g3.addView(row("진단 보내기", subtitle = "복사 + 공유창 — 채팅 앱을 골라 바로 보내요") { sendDiag() })
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

    /** 탭할 때마다 5 → 10 → 20 → 30 → 5 … 로 돈다. 고르는 값이 넷뿐이라 별도 화면을 안 만든다. */
    private fun cycleArena() {
        val c = Prefs.ARENA_CHOICES
        val i = c.indexOf(Prefs.arenaFights)
        Prefs.arenaFights = c[(if (i < 0) 0 else i + 1) % c.size]
        rowArenaCount.setValue(Prefs.arenaFights.toString() + "판", t.label2)
    }

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
            !Coords.ratioOk && !Coords.detected ->
                "화면 비율이 달라요 — 시작하면 게임 화면을 보고 다시 판단합니다"
            !Coords.ratioOk -> "이 기기는 게임 UI 배치가 달라 좌표가 안 맞아요"
            Runner.running -> "봇이 게임을 대신 하고 있어요"
            Prefs.testMode -> "시험 모드 · 재화를 쓰지 않고 진입까지만"
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
        // 비율은 여기서 막지 않는다 — 게임 화면을 보고 판단한 뒤(detected) 안 맞을 때만 막는다.
        // 미리 막으면 레터박스라서 사실은 잘 도는 기기까지 못 쓰게 된다.
        val coordsOk = Coords.ratioOk || !Coords.detected
        for (i in 0 until runRows.childCount) {
            (runRows.getChildAt(i) as? LinearLayout)?.enable(!running && ready && coordsOk)
        }

        rowGame.setValue(GameApp.label(this) ?: "못 찾음", if (GameApp.pkg(this) == null) t.orange else t.label2)

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

    /**
     * 지금 게임 화면의 판정값을 재서 기록에 남긴다. 게임을 원하는 화면(메인·로비…)에 두고 누른다.
     * 새 기기에서 '되는 것 같은데 뭔가 이상할' 때, 폰 기준값과 비교할 유일한 방법이다.
     */
    private fun measureNow() {
        if (CaptureService.instance == null) { Bot.log("화면 읽기를 먼저 켜 주세요"); return }
        Thread {
            val b = Runner.shot()
            if (b == null) Bot.log("화면을 읽지 못했어요")
            else Bot.log("판정값: " + Screen.debugLine(b) + " · 완료기준=" + String.format("%.2f", Chores.doneRatioNow()))
        }.start()
        Toast.makeText(this, "기록에 남겼어요 — [진단 보내기]를 눌러 주세요", Toast.LENGTH_LONG).show()
    }

    /**
     * **탭이 실제로 게임에 들어가는가**를 가린다. 게임을 메인 화면에 두고 누른다.
     *
     * 화면이 안 바뀔 때 원인은 셋인데 로그만으로는 못 가른다:
     *   ① 탭이 아예 안 들어감  ② 들어갔는데 엉뚱한 자리  ③ 판정이 틀림(화면은 바뀜)
     * 그래서 ⑴ 기준점 색을 찍고 ⑵ 결과를 받는 탭을 넣고 ⑶ 바뀌었는지 다시 본다.
     * 60ms 로 안 먹으면 200ms 로 한 번 더 — 느린 기기에서 짧은 탭을 흘리는 경우가 있다.
     *
     * 폰에서도 같은 걸 찍어 나란히 놓으면 좌표가 맞는지 바로 보인다.
     */
    private fun tapTest() {
        if (CaptureService.instance == null) { Bot.log("화면 읽기를 먼저 켜 주세요"); return }
        if (!TapService.isReady) { Bot.log("접근성을 먼저 켜 주세요"); return }
        Thread {
            Bot.log("── 탭 시험 ──")
            var b = Runner.shot()
            if (b == null) { Bot.log("화면을 읽지 못했어요"); return@Thread }
            Bot.log("전: " + Screen.debugLine(b))
            Bot.log("기준점: " + Screen.landmarks(b))

            for (ms in longArrayOf(60, 200)) {
                Bot.log("미션 아이콘 탭(" + ms + "ms): " +
                    TapService.tapChecked(Screen.ICON_MISSION[0], Screen.ICON_MISSION[1], ms))
                Runner.sleep(2800)
                b = Runner.shot()
                if (b == null) { Bot.log("후: 화면을 읽지 못했어요"); return@Thread }
                Bot.log("후(" + ms + "ms): " + Screen.debugLine(b))
                if (!Screen.atMain(b)) {
                    Bot.log("→ 화면이 바뀌었습니다. 탭은 잘 들어갑니다. 닫고 끝냅니다")
                    TapService.back(); Runner.sleep(1500)
                    return@Thread
                }
            }
            Bot.log("→ 두 번 다 화면이 그대로입니다 (탭이 안 먹거나 좌표가 다른 곳)")
        }.start()
        Toast.makeText(this, "시험 중… 5초 뒤 [진단 보내기]를 눌러 주세요", Toast.LENGTH_LONG).show()
    }

    /**
     * **봇이 지금 보고 있는 화면 그대로**를 PNG 로 만들어 공유창에 넘긴다.
     *
     * 값(비율·색)만으로는 좌표가 어디로 어긋났는지 못 잰다. 결국 한 장을 봐야 하는데,
     * 스크린샷을 찍어 보내는 건 번거롭고 **게임이 아니라 봇 화면을 찍기 십상**이다.
     * 이건 캡처 그대로라 잘릴 일도, 배율이 달라질 일도 없다.
     */
    private fun sendShot() {
        if (CaptureService.instance == null) { Bot.log("화면 읽기를 먼저 켜 주세요"); return }
        val f = java.io.File(java.io.File(cacheDir, "shots").apply { mkdirs() }, "screen.png")

        // ⚠️ 캡처는 **실시간**이라, 이 앱을 보고 있으면 이 앱이 찍힌다.
        //    그래서 게임을 먼저 띄우고 찍는다. 방금 찍어 둔 게 있으면 그걸 바로 보낸다
        //    (게임에서 돌아온 뒤 한 번 더 누르면 공유창이 뜨는 길 — 배경에서 창을 못 띄우는
        //     기기가 있어 안전망으로 남긴다).
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3 * 60_000L) {
            Bot.log("방금 찍어 둔 게임 화면을 보냅니다 (다시 찍으려면 3분 뒤에 눌러 주세요)")
            shareShot(f); return
        }

        Toast.makeText(this, "게임을 띄우고 5초 뒤에 찍어요", Toast.LENGTH_LONG).show()
        val gi = GameApp.launchIntent(this)
        if (gi == null) { Bot.log("게임을 찾지 못했어요 - '게임 앱'에서 골라 주세요"); pickGame(); return }
        gi.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(gi)

        Thread {
            Runner.sleep(5000)

            val b = Runner.shot()
            if (b == null) { Bot.log("화면을 읽지 못했어요"); return@Thread }
            try {
                java.io.FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (e: Throwable) { Bot.log("화면 저장 실패: " + e); return@Thread }
            Bot.log("게임 화면 한 장 저장: " + b.width + "x" + b.height)
            runOnUiThread { shareShot(f) }
        }.start()
    }

    /** 저장해 둔 화면을 공유창으로. 배경에서 창이 안 뜨면 안내만 하고 파일은 남겨 둔다. */
    private fun shareShot(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
            i.type = "image/png"
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(android.content.Intent.createChooser(i, "게임 화면 보내기")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            Bot.log("공유창을 못 열었어요: " + e)
            Bot.log("  봇으로 돌아와 [화면 보내기]를 한 번 더 눌러 주세요 (찍어 둔 걸 그대로 보냅니다)")
        }
    }

    /** 기기 정보 + 최근 기록 한 덩어리. 스크린샷을 찍어 보낼 일이 없게 하려는 것이다. */
    private fun diagText(): String {
        val t = StringBuilder()
        t.append("크럼블 폰봇 ").append(Updater.currentName(this)).append("\n")
        t.append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" · 안드로이드 ").append(Build.VERSION.RELEASE).append("\n")
        t.append(Coords.summary()).append("\n")
        t.append("게임 앱: ").append(GameApp.pkg(this) ?: "못 찾음").append("\n")
        t.append("접근성 ").append(if (TapService.isReady) "켜짐" else "꺼짐")
            .append(" · 화면 읽기 ").append(if (CaptureService.instance != null) "켜짐" else "꺼짐").append("\n")
        t.append("--- 최근 기록 ---\n").append(Bot.recentText())
        return t.toString()
    }

    /**
     * 진단을 클립보드에 넣고 **공유창**까지 띄운다.
     *
     * 왜 앱이 직접 어딘가로 올리지 않나 — 두 번 검토했지만 지금은 길이 없다.
     *   · 서버(Firebase 등)로 올리게 하려면 앱에 쓰기 권한이 들어가야 한다. 저장소가 공개라
     *     APK 를 뜯으면 누구나 그 권한을 갖는다. **토큰을 넣어 해결하려 하지 말 것.**
     *   · 올려 둔 걸 내가 읽으려 해도, 작업 환경 밖으로 나가는 통신이 GitHub 말고는 막혀 있다.
     * 그래서 '사람이 한 번 누르는' 경로가 제일 짧다. 복사→앱 전환→붙여넣기 3동작이던 걸
     * 공유창에서 채팅 앱 고르기 1동작으로 줄였다. 클립보드에도 같이 넣어 두어, 원하는 앱이
     * 공유창에 없으면 예전처럼 붙여넣으면 된다.
     */
    private fun sendDiag() {
        val text = diagText()
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("크럼블 폰봇 진단", text))
        } catch (e: Throwable) { Bot.log("클립보드 복사 실패: " + e) }

        val i = android.content.Intent(android.content.Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(android.content.Intent.EXTRA_SUBJECT, "크럼블 폰봇 진단")
        i.putExtra(android.content.Intent.EXTRA_TEXT, text)
        try {
            startActivity(android.content.Intent.createChooser(i, "진단 보내기"))
        } catch (e: Throwable) {
            Toast.makeText(this, "복사했어요 — 채팅에 붙여넣으면 됩니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGame() {
        val i = GameApp.launchIntent(this)
        if (i == null) { Bot.log("게임을 찾지 못했습니다 - '게임 앱'에서 골라 주세요"); pickGame() }
        else startActivity(i)
    }

    /**
     * 게임 앱을 손으로 고른다. 스토어마다 패키지가 달라서 자동 탐지가 빗나갈 수 있다
     * (구글 플레이로 깔았다가 갤럭시 스토어로 다시 깐 경우 등).
     */
    private fun pickGame() {
        val list = GameApp.candidates(this)
        if (list.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("게임을 찾지 못했어요")
                .setMessage("이 폰에 쿠키런: 크럼블이 설치돼 있는지 확인해 주세요.\n" +
                            "설치했는데도 안 보이면 알려 주세요 — 스토어판마다 이름이 다를 수 있습니다.")
                .setPositiveButton("확인", null)
                .show()
            return
        }
        val names = list.map { it.second + "\n" + it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("게임 앱 고르기")
            .setItems(names) { _, i -> GameApp.set(this, list[i].first) }
            .show()
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
