package com.sohada.crumblephone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.core.view.WindowCompat

/**
 * 관제 화면 — **시안 B**(2026-10-16 · PC 관제창과 같은 톤).
 *
 *   제목 줄(이름 · 버전 · ⚙) → 상태 카드 → 자동 실행(콘텐츠 줄) → 준비 → 작업 기록(접힘)
 *
 * 상태 카드 맨 위 줄이 **지금 무엇이 도는지**를 콘텐츠 색으로 말하고, 그 옆에 [멈추기] 가 있다.
 * 토벌전이 돌면 이번 판 점수(크게)·이번 최고·판 수·내 기록 막대를 보여 준다.
 * 콘텐츠 줄은 왼쪽 색 띠 + 아이콘 칸 + 이름/설명. 도는 줄은 그 색이 옅게 번진다.
 *
 * 이미지도 커스텀 글꼴도 쓰지 않는다(APK 를 가볍게 유지한다). 색은 `Theme.kt`, 콘텐츠 색·아이콘은 `Tint`.
 */
class MainActivity : ListActivity() {

    /**
     * 정산 창. 표를 그리므로 **고정폭 글꼴**로 띄운다 — 비례 글꼴이면 칸이 안 맞는다
     * (PC 관제창도 본문만 Consolas 로 바꿔 같은 문제를 풀었다).
     * 화면이 앞에 없을 때 띄우면 터지므로 `isFinishing` 을 본다.
     */
    private fun showSummaryDialog(title: String, text: String) {
        if (text.isEmpty() || isFinishing) return
        val tv = TextView(this).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(t.label2)
            val pad = dp(20)
            setPadding(pad, dp(14), pad, dp(4))
        }
        // 판별 기록이 몇십 줄이 될 수 있다(토벌전) → 스크롤.
        val sv = ScrollView(this).apply { addView(tv) }
        val d = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("확인", null)
            .create()
        d.show()
        styleDialog(d)
    }


    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAP = 1001

    private lateinit var lblSubtitle: TextView
    // 상태 카드
    private lateinit var heroStrip: View
    private lateinit var runDot: View
    private lateinit var lblRunTop: TextView
    private lateinit var btnStop: TextView
    private lateinit var idleBox: LinearLayout
    private lateinit var lastBox: LinearLayout
    private lateinit var lastStrip: View
    private lateinit var lblLastHead: TextView
    private lateinit var lblLast: TextView
    private lateinit var tobolBox: LinearLayout
    private lateinit var lblTbLast: TextView
    private lateinit var lblTbBest: TextView
    private lateinit var lblTbN: TextView
    private lateinit var recBox: LinearLayout
    private lateinit var lblRec: TextView
    private lateinit var lblRecNote: TextView
    private lateinit var recFill: View
    private lateinit var lblStatus: TextView
    private lateinit var lblDetail: TextView
    private lateinit var track: FrameLayout
    private lateinit var fill: View
    // 목록
    private val runRows = ArrayList<ContentRow>()
    private lateinit var rowBoss: ContentRow
    private lateinit var setupSection: LinearLayout
    private lateinit var rowAcc: LinearLayout
    private lateinit var rowCap: LinearLayout
    private lateinit var rowOverlay: LinearLayout
    private lateinit var logBox: LinearLayout
    private lateinit var btnLog: TextView
    private lateinit var logView: TextView
    private lateinit var lblVersion: TextView
    private lateinit var dotUpdate: View
    private var lastProgress = -1
    private var lastRec = -1f
    private var setupShown = false      // 이번에 켠 뒤로 안내를 한 번 띄웠나

    /** 콘텐츠 줄 하나 — 쉬는 중/도는 중 두 벌의 바탕과 설명을 들고 있다가 갈아 끼운다. */
    private class ContentRow(
        val c: Tint.C, val view: FrameLayout, val sub: TextView, val subIdle: String,
        val value: TextView, val bgIdle: Drawable, val bgRun: Drawable
    ) { var running = false }

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
            setPadding(0, dp(8), 0, dp(28))
        }

        // ── 제목 줄: 이름 · 버전 칩 · ⚙ ──
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(16), dp(4))
            addView(text("크럼블 폰봇", 20f, t.title, Typeface.DEFAULT_BOLD).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            // ── 버전 칩 ──
            // 설정 깊숙이 넣어 뒀더니 업데이트하러 들어가기가 번거로웠다. 제목 옆으로 꺼냈다.
            // 누르면 최신 확인, 새 판이 있으면 바로 받는다. 새 판이 있을 땐 **빨간 점**이 붙는다.
            addView(FrameLayout(this@MainActivity).apply {
                lblVersion = text("", 11f, t.label3).apply {
                    gravity = Gravity.CENTER
                    background = t.rippleOver(t.box(Color.TRANSPARENT, dpf(10f), Math.max(1, dp(1)), t.borderDim), dpf(10f))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    isClickable = true
                    setOnClickListener { onUpdate() }
                }
                addView(lblVersion, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    topMargin = dp(4); rightMargin = dp(4)
                })
                dotUpdate = View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(t.red)
                        setStroke(dp(2), t.bg)
                    }
                    visibility = View.GONE
                }
                addView(dotUpdate, FrameLayout.LayoutParams(dp(11), dp(11)).apply {
                    gravity = Gravity.END or Gravity.TOP
                })
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
            addView(text("⚙", 17f, t.label2).apply {
                gravity = Gravity.CENTER
                background = t.rippleOver(t.box(t.cell, dpf(12f), Math.max(1, dp(1)), t.separator), dpf(12f))
                isClickable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
        })
        // 주의할 때만 나오는 한 줄(비율이 다름 · 준비가 덜 됨 · 시험 모드). 평소엔 숨긴다.
        lblSubtitle = text("", 13f, t.orange).apply { setPadding(dp(20), dp(4), dp(20), 0) }
        root.addView(lblSubtitle)

        root.addView(buildHero())

        // ── 자동 실행 ──
        root.addView(sectionHeader("자동 실행"))
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        fun add(c: Tint.C, sub: String, value: String = "", go: () -> Unit): ContentRow =
            contentRow(c, sub, value, go).also { runRows.add(it); rows.addView(it.view) }
        add(Tint.QUEST, "보상 받고 · 뽑기 · 상자 · 오븐 · 보스") {
            withCapture { Chores.start(applicationContext) }
        }
        // 값 칸에 조합 순서를 그대로 보여 준다 — ⚙ 에서 2·3·4 만 골라 뒀는데
        // 여기는 '1~5' 라고 적혀 있으면, 안 도는 게 고장으로 보인다.
        // (설정에서 바꾸고 돌아올 수 있으니 onResume 에서 다시 그린다)
        // ★ 2026-10-14: [보스전](막힌 보스에 한 번) → [스테이지 돌파] — 퀘스트는 빼고 스테이지만 민다(PC 와 같이).
        rowBoss = add(Tint.STAGE, "퀘스트 없이 스테이지만 밀기", Boss.orderShort()) {
            withCapture { Chores.start(applicationContext, stage = true) }
        }
        add(Tint.TOBOL, "멈출 때까지 계속 도전") {
            withCapture { Runner.startTobol(applicationContext) }
        }
        add(Tint.DAILY, "기회 다 쓰고 달성 보상까지", "입장권") {
            withCapture { Daily.start(applicationContext) }
        }
        add(Tint.ARENA, "약한 상대만 골라 도전", "재화") {
            withCapture { Arena.start(applicationContext) }
        }
        add(Tint.REWARD, "미션 · 출석 한 번만", "안전") {
            withCapture { Chores.startRewardsOnly(applicationContext) }
        }
        root.addView(rows)

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

        // ── 작업 기록 — 평소엔 접어 둔다(시안 B). 눌러서 연다. ──
        btnLog = text("작업 기록 보기  ▾", 13f, t.label2).apply {
            gravity = Gravity.CENTER
            background = t.rippleOver(t.outline(dpf(14f), Math.max(1, dp(1)), t.separator), dpf(14f))
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true
            setOnClickListener {
                val open = logBox.visibility != View.VISIBLE
                logBox.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "작업 기록 접기  ▴" else "작업 기록 보기  ▾"
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(12)
            }
        }
        root.addView(btnLog)
        logView = text("", 12f, t.label2, Typeface.MONOSPACE).apply { setLineSpacing(dpf(2f), 1f) }
        logBox = group().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            addView(ScrollView(this@MainActivity).apply {
                addView(logView)
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(200))
            })
        }
        root.addView(logBox)

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

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** 작은 이름(위) + 값(아래) 한 칸. 토벌전 숫자 셋이 같은 꼴을 쓴다. */
    private fun stat(name: String, valueView: TextView, weight: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(name, 12f, t.label3))
        addView(valueView)
        layoutParams = lp(0, WRAP_CONTENT, weight)
    }

    /**
     * 상태 카드. 둥근 카드 + 맨 위 4dp 콘텐츠 색 띠.
     *
     * ⚠️ 띠에 둥근 모서리를 주면 반지름이 띠 두께보다 커서 **카드 밖으로 삐져나온다**(PC 에서 사장님 지적).
     *    그래서 띠는 네모로 두고 카드가 `clipToOutline` 으로 잘라 낸다. 테두리는 `foreground` 로 얹어
     *    띠 위로도 지나가게 한다.
     */
    private fun buildHero(): FrameLayout {
        val r = dpf(18f)
        val card = FrameLayout(this).apply {
            background = t.round(t.card, r)
            foreground = t.outline(r, Math.max(1, dpf(1.4f).toInt()), t.border)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(12)
            }
        }
        heroStrip = View(this).apply { visibility = View.GONE }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }

        // 맨 위 줄: 점 + '무엇이 도는지'(콘텐츠 색) + [멈추기]
        runDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.dotIdle) }
            layoutParams = lp(dp(8), dp(8)).apply { rightMargin = dp(8) }
        }
        lblRunTop = text("쉬는 중", 14f, t.label2, Typeface.DEFAULT_BOLD).apply { layoutParams = lp(0, WRAP_CONTENT, 1f) }
        btnStop = text("■  멈추기", 13f, t.label).apply {
            gravity = Gravity.CENTER
            background = t.rippleOver(t.outline(dpf(10f), Math.max(1, dp(1)), t.border), dpf(10f))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { Runner.stop() }
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(32)
            addView(runDot); addView(lblRunTop); addView(btnStop)
        })

        // 쉬는 중: 무엇을 돌릴까요? + 지난 결과
        idleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        idleBox.addView(text("무엇을 돌릴까요?", 22f, t.title, Typeface.DEFAULT_BOLD).apply { setPadding(0, dp(10), 0, 0) })
        idleBox.addView(text("아래에서 하나를 누르면 바로 시작해요", 13f, t.label3).apply { setPadding(0, dp(4), 0, 0) })
        lastStrip = View(this).apply {
            background = t.round(t.dotIdle, dpf(2f))
            layoutParams = lp(dp(4), MATCH_PARENT).apply { rightMargin = dp(10) }
        }
        lblLastHead = text("지난 결과", 12f, t.label3)
        lblLast = text("", 13f, t.label).apply { setPadding(0, dp(3), 0, 0) }
        lastBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = t.round(t.fill, dpf(12f))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(lastStrip)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(lblLastHead); addView(lblLast)
                layoutParams = lp(0, WRAP_CONTENT, 1f)
            })
            layoutParams = lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        idleBox.addView(lastBox)
        body.addView(idleBox)

        // 토벌전: 이번 판(크게) · 이번 최고 · 판 수 + 내 기록 막대
        lblTbLast = text("-", 30f, t.gold, Typeface.DEFAULT_BOLD)
        lblTbBest = text("-", 16f, t.label, Typeface.DEFAULT_BOLD)
        lblTbN = text("-", 16f, t.label, Typeface.DEFAULT_BOLD)
        tobolBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(14), 0, 0)
                addView(stat("이번 판", lblTbLast, 1.5f))
                addView(stat("이번 최고", lblTbBest, 1f).apply { setPadding(0, 0, 0, dp(5)) })
                addView(stat("판 수", lblTbN, 0.7f).apply { setPadding(0, 0, 0, dp(5)) })
            })
        }
        lblRec = text("", 12f, t.label2).apply { layoutParams = lp(0, WRAP_CONTENT, 1f) }
        lblRecNote = text("", 12f, t.label2)
        recFill = View(this).apply {
            background = t.round(t.gold, dpf(3f))
            pivotX = 0f; scaleX = 0f
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        recBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = t.round(t.fill, dpf(12f))
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(lblRec); addView(lblRecNote)
            })
            addView(FrameLayout(this@MainActivity).apply {
                background = t.round(t.track, dpf(3f))
                addView(recFill)
                layoutParams = lp(MATCH_PARENT, dp(6)).apply { topMargin = dp(8) }
            })
            layoutParams = lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        tobolBox.addView(recBox)
        body.addView(tobolBox)

        // 지금 하는 일 + 진행 막대(진짜 분모가 있을 때만)
        lblStatus = text("", 14f, t.label).apply { setPadding(0, dp(14), 0, 0) }
        lblDetail = text("", 12f, t.label2).apply { setPadding(0, dp(2), 0, 0) }
        body.addView(lblStatus); body.addView(lblDetail)
        // 진행률 막대. 너비를 직접 바꾸면 애니메이션이 안 되므로,
        // 꽉 찬 막대를 왼쪽 기준으로 scaleX 만 줄여 둔다(그건 부드럽게 움직인다).
        fill = View(this).apply {
            background = t.round(t.orange, dpf(2f))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            pivotX = 0f
            scaleX = 0f
        }
        track = FrameLayout(this).apply {
            background = t.round(t.cell, dpf(2f))
            addView(fill)
            visibility = View.GONE
            layoutParams = lp(MATCH_PARENT, dp(4)).apply { topMargin = dp(10) }
        }
        body.addView(track)

        card.addView(body)
        card.addView(heroStrip, FrameLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { gravity = Gravity.TOP })
        return card
    }

    /**
     * 콘텐츠 줄 — 왼쪽 콘텐츠 색 띠 + 아이콘 칸 + 이름/설명 + 값.
     * 띠는 네모, 줄이 `clipToOutline` 으로 둥글게 잘라 낸다(삐져나오지 않게). 테두리는 `foreground`.
     */
    private fun contentRow(c: Tint.C, subtitle: String, value: String, go: () -> Unit): ContentRow {
        val r = dpf(14f)
        val bgIdle = t.rippleOver(t.round(t.card, r), r)
        val bgRun = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            Theme.mix(t.card, c.color, 0.30f), Theme.mix(t.card, c.color, 0.10f), Theme.mix(t.card, c.color, 0.04f)
        )).apply { cornerRadius = r }
        val sub = text(subtitle, 12f, t.label3).apply {
            setPadding(0, dp(2), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val v = text(value, 12f, t.label2).apply {
            visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
            layoutParams = lp(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) }
        }
        val frame = FrameLayout(this).apply {
            background = bgIdle
            foreground = t.outline(r, Math.max(1, dpf(1.2f).toInt()), t.border)
            clipToOutline = true
            isClickable = true
            setOnClickListener { go() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        frame.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(12), dp(16), dp(12))
            addView(FrameLayout(this@MainActivity).apply {
                background = t.round(t.bg, dpf(12f))
                addView(IconView(this@MainActivity, c.icon, c.color),
                    FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER })
                layoutParams = lp(dp(40), dp(40)).apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(c.name, 16f, t.title, Typeface.DEFAULT_BOLD))
                addView(sub)
                layoutParams = lp(0, WRAP_CONTENT, 1f)
            })
            addView(v)
        })
        frame.addView(View(this).apply { setBackgroundColor(c.color) },
            FrameLayout.LayoutParams(dp(5), MATCH_PARENT).apply { gravity = Gravity.START })
        return ContentRow(c, frame, sub, subtitle, v, bgIdle, bgRun)
    }

    override fun onPause() {
        super.onPause()
        Overlay.onAppForeground(false)
    }

    override fun onResume() {
        super.onResume()
        Overlay.onAppForeground(true)
        // ⚙ 에서 조합 순서를 바꾸고 돌아올 수 있다. 화면은 여기서만 다시 그려진다.
        rowBoss.value.text = Boss.orderShort()
        // 켤 것이 남아 있으면 안내부터 보여 준다. 목록만 던져 두면 무엇부터 눌러야 할지 알 수 없다.
        //
        // ⚠️ 여기에 '화면 읽기' 를 넣으면 안 된다. 그건 **1회용 허락**이라 앱을 켤 때마다 없는 상태다
        //    → 켤 때마다 안내가 뜨고, 아무것도 안 할 사람에게까지 허락을 받아 냈다.
        //    화면 읽기는 [withCapture] 가 '진짜 필요한 순간' 에 묻는다.
        if (!setupShown && !TapService.isReady) {
            setupShown = true
            openSetup()
        }
    }

    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    /** 버전 칩을 눌렀을 때 — 새 판이 있으면 바로 받고, 아직 모르면 먼저 확인한다. */
    private fun onUpdate() {
        if (Updater.busy) return
        if (Updater.latestCode > Updater.currentCode(this)) Updater.update(applicationContext)
        else {
            Updater.check(applicationContext)
            Toast.makeText(this, "최신 판을 확인하는 중…", Toast.LENGTH_SHORT).show()
        }
    }


    /** 1초마다 상태만 갈아 끼운다(무거운 일은 하지 않는다). */
    private fun tick() {
        val accOk = TapService.isReady
        val capOk = CaptureService.instance != null
        val ovOk = Overlay.canDraw(this)
        // 시작 버튼을 여는 조건에서 화면 읽기를 뺐다 — 누르는 순간 물어보기 때문이다.
        val ready = accOk

        rowAcc.setValue(if (accOk) "켜짐" else "필요", if (accOk) t.green else t.orange)
        rowCap.setValue(if (capOk) "켜짐" else "필요", if (capOk) t.green else t.orange)
        rowOverlay.setValue(if (ovOk) "켜짐" else "권장", if (ovOk) t.green else t.label2)
        setupSection.visibility = if (accOk && ovOk) View.GONE else View.VISIBLE

        // 주의할 때만 한 줄 띄운다(평소 문구는 상태 카드가 대신 말한다).
        lblSubtitle.text = when {
            !Coords.ratioOk && !Coords.detected ->
                "화면 비율이 달라요 — 시작하면 게임 화면을 보고 다시 판단합니다"
            !Coords.ratioOk -> "이 기기는 게임 UI 배치가 달라 좌표가 안 맞아요"
            Prefs.testMode -> "시험 모드 · 재화를 쓰지 않고 진입까지만"
            !ready         -> "아래 '준비'를 먼저 해 주세요"
            else           -> ""
        }
        lblSubtitle.visibility = if (lblSubtitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // ── 상태 카드 ──
        val running = Runner.running
        val stopping = Runner.stopping
        if (running && Runner.task.isNotEmpty()) Runner.lastTask = Runner.task
        val tint = if (running || stopping) Tint.of(Runner.task) else null
        heroStrip.visibility = if (tint != null) View.VISIBLE else View.GONE
        if (tint != null) heroStrip.setBackgroundColor(tint.color)
        (runDot.background as GradientDrawable).setColor(
            if (running) t.orange else if (stopping) t.label2 else t.dotIdle)
        lblRunTop.text = when {
            running  -> (if (Runner.task.isEmpty()) "봇" else Runner.task) + " 실행 중"
            stopping -> Runner.task + " 멈추는 중"
            else     -> "쉬는 중"
        }
        lblRunTop.setTextColor(tint?.color ?: t.label2)
        btnStop.visibility = if (running) View.VISIBLE else View.GONE

        idleBox.visibility = if (running || stopping) View.GONE else View.VISIBLE
        if (!running && !stopping) {
            val last = Runner.lastResult
            lastBox.visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
            val lt = Tint.of(Runner.lastTask)
            lblLastHead.text = if (lt != null) "지난 결과 · " + lt.name else "지난 결과"
            lblLast.text = last
            (lastStrip.background as GradientDrawable).setColor(lt?.color ?: t.dotIdle)
        }

        // 토벌전 숫자 — 도는 동안만(멈추는 중에도 마지막 값을 보여 준다).
        val tobol = (running || stopping) && Runner.task == "토벌전"
        tobolBox.visibility = if (tobol) View.VISIBLE else View.GONE
        if (tobol) {
            lblTbLast.text = if (Runner.lastScore > 0) Runner.fmtShort(Runner.lastScore) else "-"
            lblTbBest.text = if (Runner.bestScore > 0) Runner.fmtShort(Runner.bestScore) else "-"
            lblTbN.text = Runner.tobolAttempts.toString() + "판"
            val rec = Runner.myRecord
            recBox.visibility = if (rec > 0) View.VISIBLE else View.GONE
            if (rec > 0) {
                lblRec.text = "내 기록 " + Runner.fmtShort(rec)
                val start = if (Runner.myRecordStart > 0) Runner.myRecordStart else rec
                val best = Runner.bestScore
                // 이번에 기록을 넘었으면 '경신 +얼마', 아니면 '기록까지 얼마'.
                if (best > start) {
                    lblRecNote.text = "▲ 경신 +" + Runner.fmtShort(best - start)
                    lblRecNote.setTextColor(t.gold)
                } else {
                    lblRecNote.text = if (best > 0) "기록까지 " + Runner.fmtShort(start - best) else ""
                    lblRecNote.setTextColor(t.label2)
                }
                val f = if (best <= 0) 0f else Math.min(1f, best.toFloat() / start.toFloat())
                if (f != lastRec) { lastRec = f; recFill.animate().scaleX(f).setDuration(280).start() }
            }
        }

        // 지금 하는 일 — 도는 동안만. 쉬는 중엔 카드가 '무엇을 돌릴까요?' 를 말한다.
        val age = if (running && Runner.statusAt > 0) (System.currentTimeMillis() - Runner.statusAt) / 1000 else 0
        lblStatus.text = Runner.status + (if (age >= 8) "  " + age + "초째" else "")
        lblStatus.visibility = if (running || stopping) View.VISIBLE else View.GONE
        lblDetail.text = if (running || stopping) Runner.detail else ""
        lblDetail.visibility = if (lblDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // ★ 정산 창 — 돌다가 끝나면 '무엇을 얼마나 했나' 를 창으로 보여 준다 (2026-09-28).
        //   Chores 가 끝에서 lastSummary 를 채우고, 여기서 **한 번만** 꺼내 띄운다
        //   (takeSummary 가 읽으면서 지우므로 tick 이 계속 돌아도 다시 안 뜬다).
        if (!Runner.running && Runner.lastSummary.isNotEmpty()) { val st = Runner.lastSummaryTitle; showSummaryDialog(st, Runner.takeSummary()) }

        // 진행률 — 진짜 분모가 있는 작업만 막대를 보여 준다(-1 이면 숨긴다).
        val p = Runner.progress
        if (p < 0 || !running) {
            track.visibility = View.GONE
            lastProgress = -1
        } else {
            track.visibility = View.VISIBLE
            if (p != lastProgress) {
                lastProgress = p
                fill.animate().scaleX(p / 100f).setDuration(280).start()
            }
        }

        // ── 버전 칩 ──
        // 받는 중에는 진행률을, 새 판이 있으면 그 버전을, 그 밖에는 지금 버전을 보여 준다.
        val newer = Updater.latestCode > Updater.currentCode(this)
        lblVersion.text = when {
            Updater.progress >= 0 -> "받는 중 " + Updater.progress.toString() + "%"
            newer -> "새 버전 받기"
            else -> "v" + Updater.currentName(this)
        }
        lblVersion.setTextColor(if (newer || Updater.progress >= 0) t.gold else t.label3)
        dotUpdate.visibility = if (newer) View.VISIBLE else View.GONE

        // ── 콘텐츠 줄 ──
        // 도는 줄은 그 색이 옅게 번지고 설명 자리에 지금 상태가 금색으로 나온다.
        // 나머지는 흐리게 잠근다(한 번에 하나만 돈다).
        // 비율은 여기서 막지 않는다 — 게임 화면을 보고 판단한 뒤(detected) 안 맞을 때만 막는다.
        // 미리 막으면 레터박스라서 사실은 잘 도는 기기까지 못 쓰게 된다.
        val coordsOk = Coords.ratioOk || !Coords.detected
        val curTint = Tint.of(Runner.task)
        for (r in runRows) {
            val me = (running || stopping) && curTint != null && curTint.name == r.c.name
            if (me != r.running) {
                r.running = me
                r.view.background = if (me) r.bgRun else r.bgIdle
                r.sub.setTextColor(if (me) t.gold else t.label3)
                if (!me) r.sub.text = r.subIdle
            }
            if (me) r.sub.text = "● " + if (r.c === Tint.TOBOL && Runner.tobolAttempts > 0)
                Runner.tobolAttempts.toString() + "판째" +
                    (if (Runner.lastScore > 0) " · " + Runner.fmtShort(Runner.lastScore) else "")
            else Runner.status
            val on = !running && !stopping && ready && coordsOk
            r.view.isEnabled = on
            r.view.alpha = if (on || me) 1f else 0.4f
        }

        ui.postDelayed({ tick() }, 1000)
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAP)
    }

    /** 허락을 받고 나서 이어서 할 일. 동의 창이 다른 화면이라 여기 맡겨 둔다. */
    private var pending: (() -> Unit)? = null

    /**
     * **화면 읽기가 있어야 하는 일**을 감싼다.
     *
     * 이 허락은 안드로이드가 저장해 주지 않아서 앱을 켤 때마다 다시 받아야 한다. 그래서
     * 켜자마자 묻지 않고 **콘텐츠를 시작하는 순간** 묻는다 — 그때가 실제로 화면이 필요한 때고,
     * 사용자도 '왜 지금 묻는지' 를 안다. 이미 켜져 있으면 아무것도 안 묻고 바로 시작한다.
     */
    private fun withCapture(action: () -> Unit) {
        if (CaptureService.instance != null) {
            Overlay.show(applicationContext); action(); return
        }
        pending = action
        askProjection()
    }

    /**
     * 서비스가 실제로 올라온 뒤에 이어서 한다.
     * `startForegroundService` 는 바로 뜨지 않아서, 곧장 시작하면 첫 캡처가 빈손으로 돌아온다.
     */
    private fun resumePending(tries: Int = 0) {
        val act = pending ?: return
        if (CaptureService.instance != null) {
            pending = null
            Overlay.show(applicationContext)
            act()
            return
        }
        if (tries > 50) {                       // 5초를 기다려도 안 뜨면 포기한다
            pending = null
            Bot.log("화면 읽기가 안 켜졌어요 - 다시 눌러 주세요")
            return
        }
        ui.postDelayed({ resumePending(tries + 1) }, 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAP) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.start(this, resultCode, data)
                resumePending()
            } else {
                pending = null
                Bot.log("화면 읽기를 거부했습니다")
            }
        }
    }
}
