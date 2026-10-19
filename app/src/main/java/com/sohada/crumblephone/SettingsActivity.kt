package com.sohada.crumblephone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat

/**
 * 설정 화면(관제 화면 오른쪽 위 ⚙).
 *
 * **관제 화면은 '무엇을 돌릴까'만 남긴다.** 설정·도구·점검은 전부 여기로 모았다 —
 * 자주 누르는 것과 어쩌다 한 번 쓰는 것이 한 목록에 섞이면 스크롤만 길어지고 눈에 안 들어온다.
 *
 * 묶음 순서는 **얼마나 자주 쓰는가** 순이다: 게임 → 자동 실행 → 화면 → 점검 → 앱.
 *
 * 맨 끝의 **실험실**은 평소엔 아예 안 보인다 — [labSection] 을 볼 것.
 */
class SettingsActivity : ListActivity() {

    private lateinit var rowArSpend: LinearLayout
    private lateinit var rowOven: LinearLayout
    private lateinit var rowCharge: LinearLayout
    private lateinit var rowDPlan: LinearLayout
    private lateinit var rowDelay: LinearLayout
    private lateinit var rowGame: LinearLayout
    private lateinit var rowUpdate: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        window.statusBarColor = t.bg
        window.navigationBarColor = t.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !t.dark
            isAppearanceLightNavigationBars = !t.dark
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setPadding(0, dp(8), 0, dp(36))
        }

        // 제목 줄 — 왼쪽에 돌아가기, 그 옆에 제목과 한 줄 설명. 뒤로가기 버튼이 눈에 보여야 헤매지 않는다.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
            addView(text("‹", 22f, t.label2).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(3))
                background = t.rippleOver(t.box(t.cell, dpf(12f), Math.max(1, dp(1)), t.separator), dpf(12f))
                isClickable = true
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) }
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("설정", 20f, t.title, Typeface.DEFAULT_BOLD).apply {
                    setOnClickListener { onTitleTap() }   // 일곱 번 → 실험실
                })
                addView(text("바꾼 값은 바로 저장돼요", 12f, t.label3))
            })
        })

        // ── 게임 ──
        root.addView(sectionHeader("게임"))
        val gGame = group()
        rowGame = row("게임 앱", subtitle = "스토어마다 이름이 달라서 여기서 골라요") { pickGame() }
        gGame.addView(rowGame); gGame.addView(separator())
        gGame.addView(row("게임 켜기", subtitle = "봇 없이 게임만 열어요") { launchGame() })
        root.addView(gGame)

        // ── 봇 동작 ── 켜고 끄는 것(PC 설정창 '봇 동작' 카드와 같은 자리)
        root.addView(sectionHeader("봇 동작"))
        val gBot = group()
        val (rAd, _) = switchRow("광고 제거 있음",
            "일일 던전에서 [SKIP]으로 횟수를 더 받아요", Prefs.adFree) { Prefs.adFree = it }
        gBot.addView(rAd); gBot.addView(separator())
        val (rDim, _) = switchRow("화면 어둡게",
            "봇이 도는 동안만 · 알약을 탭하면 15초 밝아져요", Prefs.dimScreen) {
            Prefs.dimScreen = it
            if (it && !Overlay.canDraw(this)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("'게임 위에 표시'가 필요해요")
                    .setMessage("화면을 어둡게 하는 것도 게임 위에 띄우는 창으로 합니다.\n" +
                                "관제 화면의 준비 → '게임 위에 표시'를 먼저 허용해 주세요.")
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
        gBot.addView(rDim)
        root.addView(gBot)

        // ── 쿠키 조합 ── 창을 따로 띄우지 않고 여기서 바로 고른다(시안 B · PC 설정창과 같은 칩).
        root.addView(sectionHeader("쿠키 조합"))
        root.addView(bossOrderCard())

        // ── 일일 던전 고르기 ── 돌 던전만 켠다(2026-10-19 · PC 일일 던전 화면의 칩과 같다).
        root.addView(sectionHeader("일일 던전 · 돌 던전 고르기"))
        root.addView(dailyPickCard())

        // ── 아레나 도전 조건 ── 여러 개 같이(PC 설정창 '아레나 도전 조건' 카드와 같다 · 2026-10-17)
        root.addView(sectionHeader("아레나 도전 조건 · 여러 개 같이 고를 수 있어요"))
        val gAr = group()
        val (rArLow, _) = switchRow("나보다 전투력이 낮은 상대", "상대 ≤ 나 이면 도전", Prefs.arenaLower) {
            Prefs.arenaLower = it; arenaRuleCheck()
        }
        val (rArHigh, _) = switchRow("나보다 높아도 정한 만큼까지", "아래 범위 안쪽이면 높아도 도전", Prefs.arenaHigher) {
            Prefs.arenaHigher = it; arenaRuleCheck()
        }
        rowArMargin = row("얼마나 높아도 될까요", value = Prefs.arenaMargin.toString() + "% 까지",
            subtitle = "예: 10% 면 나 6.0M 일 때 6.6M 까지", strip = Tint.ARENA.color) {
            val c = Prefs.ARENA_MARGIN_CHOICES
            val i = c.indexOf(Prefs.arenaMargin)
            Prefs.arenaMargin = c[(if (i < 0) 0 else i + 1) % c.size]
            rowArMargin.setValue(Prefs.arenaMargin.toString() + "% 까지", t.label2)
        }
        val (rArFb, _) = switchRow("더 넘길 수 없으면 지금 상대에게 도전",
            "끄면 맞는 상대가 없을 때 멈춰요 · 크리스탈 새로고침은 언제나 안 해요", Prefs.arenaFallback) {
            Prefs.arenaFallback = it; arenaRuleCheck()
        }
        rowArSpend = row("아레나 재화 사용량", value = Prefs.arenaSpendLabel(),
            subtitle = "이만큼 쓰면 끝나요 (1판 1개 · 넘기지 않아요)", strip = Tint.ARENA.color) {
            val c = Prefs.ARENA_SPEND_CHOICES
            val i = c.indexOf(Prefs.arenaSpend)
            Prefs.arenaSpend = c[(if (i < 0) 0 else i + 1) % c.size]
            rowArSpend.setValue(Prefs.arenaSpendLabel(), t.label2)
        }
        gAr.addView(rowArSpend); gAr.addView(separator())
        gAr.addView(rArLow); gAr.addView(separator())
        gAr.addView(rArHigh); gAr.addView(separator())
        gAr.addView(rowArMargin); gAr.addView(separator())
        gAr.addView(rArFb)
        root.addView(gAr)

        // ── 자동 실행 ──
        root.addView(sectionHeader("자동 실행"))
        val gRun = group()
        // '아레나 판수' 줄은 뺐다(2026-10-17) — 아레나는 위 '아레나 재화 사용량' 하나로만 끝난다.
        // 오븐은 레벨마다 한 번에 여는 개수가 달라서, 게임 쪽 값을 여기에 맞춰 둬야
        // 봇이 '한 싸이클'이 얼마나 큰지 알고 상한을 제대로 잡는다.
        rowOven = row("오븐 1회 개수", value = Prefs.ovenPerRun.toString() + "개",
            subtitle = "게임의 '자동 열기 → 1회에 여는 개수'와 같게") { cycleOven() }
        rowDelay = row("돌진 시작 지연", value = delayLabel(),
            subtitle = "보스 소환 직후엔 아직 조이스틱이 안 먹어요", strip = Tint.STAGE.color) { cycleDelay() }
        rowCharge = row("보스전 돌진 시간", value = chargeLabel(),
            subtitle = "보스가 나오면 오른쪽으로 밀어붙여요 · 달려들면 잘 잡히는 보스가 많아요", strip = Tint.STAGE.color) { cycleCharge() }
        // 예전 두 줄('던전 돌진 시작 지연'·'경험치 던전 돌진 시간')을 던전별 타임라인 하나로 바꿨다(2026-10-01).
        rowDPlan = row("일일 던전 컨트롤", value = DailyPlan.shortSummary(),
            subtitle = "던전마다 몇 초에 어느 방향으로 움직일지 방향키로 정해요 · 보스전 돌진과는 별개",
            strip = Tint.DAILY.color) {
            startActivity(Intent(this, DailyPlanActivity::class.java))
        }
        gRun.addView(rowOven); gRun.addView(separator())
        gRun.addView(rowDelay); gRun.addView(separator())
        gRun.addView(rowCharge); gRun.addView(separator())
        gRun.addView(rowDPlan)
        root.addView(gRun)
        // (덱 구성·쿠키 사전·덱 이름은 2026-10-15 에 통째로 뺐다 — 쓸모가 없다는 사장님 판단)

        // ── 점검 ──
        root.addView(sectionHeader("점검"))
        root.addView(text("잘 안 될 때만 쓰면 돼요. 넷 다 게임을 진행시키지 않아요.",
            13f, t.label3).apply { setPadding(dp(22), 0, dp(22), dp(8)) })
        val gChk = group()
        gChk.addView(row("화면 점검", subtitle = "게임을 띄워 좌표와 판정값을 재요") {
            Overlay.show(applicationContext); Runner.checkCoords(applicationContext); finish()
        })
        gChk.addView(separator())
        gChk.addView(row("탭 점검", subtitle = "탭이 게임에 먹히는지 봐요") { tapTest() })
        gChk.addView(separator())
        gChk.addView(row("진단 보내기", value = "글", subtitle = "기기 정보와 최근 기록") { sendDiag() })
        gChk.addView(separator())
        gChk.addView(row("화면 보내기", value = "그림", subtitle = "게임을 띄워 5초 뒤 찍어요") { sendShot() })
        root.addView(gChk)

        // ── 앱 ──
        root.addView(sectionHeader("앱"))
        val gApp = group()
        rowUpdate = row("업데이트", subtitle = "새 판이 있으면 받아서 깔아요") { onUpdate() }
        gApp.addView(rowUpdate); gApp.addView(separator())
        gApp.addView(row("처음 안내 다시 보기", subtitle = "권한 켜는 순서를 하나씩") {
            startActivity(Intent(this, SetupActivity::class.java))
        })
        gApp.addView(separator())
        // '껐다' 고 생각했는데 알림과 녹화 표시가 남아 있으면 안 꺼진 것으로 보인다.
        // 한 번에 다 내리는 자리를 하나 만들어 둔다 — 끄는 길이 여러 개면 그것도 안 명확하다.
        gApp.addView(row("앱 끄기", subtitle = "봇 · 화면 읽기 · 알약 모두 끄고 나가요",
            chevron = false, tint = t.red) { quitAll() })
        root.addView(gApp)

        // ── 실험실 ── 평소엔 아예 안 보인다. [labSection] 참고.
        if (Prefs.labUnlocked) root.addView(labSection())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(t.bg)
            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        tick()
    }

    /**
     * **실험실** — 아직 다 못 만든 것들만 모아 둔 묶음. 기본으로는 안 보인다.
     *
     * 지우면 이어 만들 때 다시 짜야 하고, 그냥 두면 받은 사람이 눌러 보고 고장인 줄 안다.
     * 그래서 **코드는 그대로 두고 문만 닫았다** — 여는 법은 제목 '설정' 일곱 번 누르기([onTitleTap]).
     */
    private fun labSection(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectionHeader("실험실"))
        box.addView(text("아직 만드는 중인 것들이에요. 뜻대로 안 되는 게 섞여 있어요.",
            13f, t.label3).apply { setPadding(dp(22), 0, dp(22), dp(8)) })
        val g = group()

        val (rTest, _) = switchRow("시험 모드", "재화·입장권을 안 쓰고 진입까지만", Prefs.testMode) {
            Prefs.testMode = it
        }
        g.addView(rTest); g.addView(separator())

        g.addView(row("실험실 닫기", subtitle = "다시 숨겨요 · 시험 모드도 같이 꺼진 셈이 돼요",
            tint = t.red) {
            Prefs.labUnlocked = false
            Toast.makeText(this, "실험실을 닫았어요", Toast.LENGTH_SHORT).show()
            recreate()
        })

        box.addView(g)
        return box
    }

    private var titleTaps = 0

    /**
     * 제목 '설정' 연타 → 실험실 열기.
     *
     * 우연히 일곱 번 눌릴 자리가 아니라 이걸로 충분하다. 중간부터 남은 횟수를 알려 주는 건
     * **모르고 누른 사람이 거기서 멈추게** 하려는 것이다(다 열린 뒤에 놀라는 것보다 낫다).
     */
    private fun onTitleTap() {
        if (Prefs.labUnlocked) return
        titleTaps++
        val left = 7 - titleTaps
        when {
            left <= 0 -> {
                Prefs.labUnlocked = true
                Toast.makeText(this, "실험실이 열렸어요 — 맨 아래에 있어요", Toast.LENGTH_LONG).show()
                recreate()
            }
            left <= 4 -> Toast.makeText(this, left.toString() + "번 더", Toast.LENGTH_SHORT).show()
        }
    }

    /** 값이 바뀌는 줄(게임 앱·업데이트·화면 읽기)만 1초마다 갈아 끼운다. */
    private fun tick() {
        rowGame.setValue(GameApp.label(this) ?: "못 찾음",
            if (GameApp.pkg(this) == null) t.orange else t.label2)
        rowUpdate.setValue(
            when {
                Updater.progress >= 0 -> Updater.state + " " + Updater.progress + "%"
                Updater.state.isNotEmpty() -> Updater.state
                else -> "v" + Updater.currentName(this)
            },
            if (Updater.latestCode > Updater.currentCode(this)) t.gold else t.label2
        )
        ui.postDelayed({ tick() }, 1000)
    }

    override fun onResume() {
        super.onResume()
        Overlay.onAppForeground(true)
        // 던전별 컨트롤 화면에서 돌아오면 동작 수 요약을 다시 그린다
        if (::rowDPlan.isInitialized) rowDPlan.setValue(DailyPlan.shortSummary(), t.label2)
    }

    override fun onPause() {
        super.onPause()
        Overlay.onAppForeground(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 앱을 **완전히** 끈다 — 봇을 멈추고, 화면 읽기를 내리고, 알약을 지우고 나간다.
     *
     * 접근성만은 앱이 못 끈다(시스템 설정에서만 꺼진다). 그건 꺼져 있어도 아무 일도
     * 일어나지 않으니 남겨 둬도 된다 — 알림도 표시도 없다.
     */
    private fun quitAll() {
        Runner.stop()
        Overlay.hide()
        CaptureService.stop(applicationContext)
        finishAffinity()
    }

    /** 새 판이 있으면 바로 받고, 아직 모르면 먼저 확인한다. */
    private fun onUpdate() {
        if (Updater.busy) return
        if (Updater.latestCode > Updater.currentCode(this)) Updater.update(applicationContext)
        else Updater.check(applicationContext)
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

        // ⚠️ 게임을 먼저 띄운다. 이 앱이 앞에 있는 채로 누르면 **이 앱을 누른다**(문서 함정 5번).
        val gi = GameApp.launchIntent(this)
        if (gi == null) { Bot.log("게임을 찾지 못했어요 - '게임 앱'에서 골라 주세요"); pickGame(); return }
        gi.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(gi)
        Toast.makeText(this, "게임을 띄우고 시험해요 — 15초쯤 걸려요", Toast.LENGTH_LONG).show()

        Thread {
            Bot.log("── 탭 점검 ──")
            Runner.sleep(5000)
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
    private lateinit var rowArMargin: LinearLayout
    /** 아레나 조건을 셋 다 끄면 아레나가 바로 멈춘다 — 그때만 알려 준다. */
    private fun arenaRuleCheck() {
        if (!Prefs.arenaLower && !Prefs.arenaHigher && !Prefs.arenaFallback)
            Toast.makeText(this, "아레나 조건을 하나도 안 켜면 아레나가 바로 멈춰요", Toast.LENGTH_LONG).show()
    }

    /** 탭할 때마다 다음 값으로 돈다. 고르는 값이 몇 개뿐이라 별도 화면을 안 만든다. */
    private fun cycleOven() {
        val choices = Prefs.OVEN_CHOICES
        val i = choices.indexOf(Prefs.ovenPerRun)
        val next = choices[(if (i < 0) 0 else i + 1) % choices.size]
        Prefs.ovenPerRun = next; rowOven.setValue(next.toString() + "개", t.label2)
    }

    /** 0.5초 같은 소수를 정수 나눗셈으로 찍으면 '0초'가 된다. 딱 떨어질 때만 정수로 보인다. */
    private fun delayLabel(): String {
        val s = Prefs.bossDelayMs / 1000.0
        return (if (s == s.toInt().toDouble()) s.toInt().toString() else s.toString()) + "초"
    }

    /** 보스전 돌진 시간 표시. 0 이면 '안 함'. */
    private fun chargeLabel(): String {
        val ms = Prefs.bossChargeMs
        if (ms <= 0) return "안 함"
        val s = ms / 1000.0
        return (if (s == s.toInt().toDouble()) s.toInt().toString() else s.toString()) + "초"
    }

    private fun cycleCharge() {
        val c = Prefs.BOSS_CHARGE_CHOICES
        val i = c.indexOf(Prefs.bossChargeMs)
        Prefs.bossChargeMs = c[(if (i < 0) 0 else i + 1) % c.size]
        rowCharge.setValue(chargeLabel(), t.label2)
    }

    private fun cycleDelay() {
        val c = Prefs.BOSS_DELAY_CHOICES
        val i = c.indexOf(Prefs.bossDelayMs)
        Prefs.bossDelayMs = c[(if (i < 0) 0 else i + 1) % c.size]
        rowDelay.setValue(delayLabel(), t.label2)
    }

    /**
     * 보스에 쓸 **쿠키 조합과 그 순서**를 고르는 카드 — 1~5 칩을 누른 차례가 곧 도전 차례다.
     *
     * 예전엔 창(AlertDialog)을 띄웠는데, 시안 B(2026-10-16)에서 PC 설정창처럼 **화면에 바로** 둔다.
     * 고른 칩은 콘텐츠 색(스테이지 돌파 주황) 테두리 + 오른쪽 위 금색 **순서 배지**.
     * 이미 고른 것을 다시 누르면 빠지고 뒤 번호가 당겨진다. 누를 때마다 바로 저장한다.
     * 마지막 하나는 못 뺀다(하나도 없으면 보스를 아예 못 친다).
     */
    /**
     * 일일 던전에서 **돌 던전**을 고르는 칩 다섯 개(여러 개 같이). 누르면 바로 저장 · 마지막 하나는 못 뺀다.
     * 고르지 않은 던전은 도전·보상 없이 넘기고, 고른 던전을 다 돌면 끝낸다(Daily.pick).
     */
    private fun dailyPickCard(): LinearLayout {
        val names = Daily.NAME_LIST
        val tint = Tint.DAILY.color
        val on = BooleanArray(names.size) { names[it] in Daily.pick() }
        val chips = ArrayList<TextView>()
        val line = text("", 12f, t.label2).apply { setPadding(0, dp(10), 0, 0) }
        fun refresh() {
            for (i in names.indices) {
                chips[i].background = t.rippleOver(t.box(if (on[i]) t.track else t.bg, dpf(10f),
                    Math.max(1, dpf(1.4f).toInt()), if (on[i]) tint else t.separator), dpf(10f))
                chips[i].setTextColor(if (on[i]) t.title else t.label3)
                chips[i].text = (if (on[i]) "✓ " else "") + names[i]
            }
            val sel = names.filterIndexed { i, _ -> on[i] }
            line.text = if (sel.size == names.size) "지금: 다섯 던전 전부" else "지금: " + sel.joinToString(" · ") + " 만"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in names.indices) {
            val c = text("", 12f, t.label3, Typeface.DEFAULT_BOLD).apply {
                gravity = Gravity.CENTER
                isSingleLine = true
                isClickable = true
                setOnClickListener {
                    if (on[i] && on.count { it } == 1) {
                        Toast.makeText(this@SettingsActivity, "던전을 하나 이상 골라 주세요", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    on[i] = !on[i]
                    val sel = names.indices.filter { on[it] }.map { it + 1 }
                    Prefs.dailyPick = if (sel.size == names.size) "" else sel.joinToString(",")
                    refresh()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) }
            }
            chips.add(c); row.addView(c)
        }
        refresh()
        return group().apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(row); addView(line)
            addView(text("고르지 않은 던전은 도전·보상 없이 넘기고, 고른 던전을 다 돌면 끝내요.", 12f, t.label3).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun bossOrderCard(): LinearLayout {
        val picked = Boss.order().toMutableList()
        val tint = Tint.STAGE.color
        val chips = ArrayList<Pair<android.widget.FrameLayout, TextView>>()
        val nums = ArrayList<TextView>()
        val line = text("", 12f, t.label2).apply { setPadding(0, dp(10), 0, 0) }

        fun save() {
            Prefs.bossOrder = picked.joinToString(",")
            Bot.log("보스 조합 순서를 " + Boss.orderLabel() + " 로 바꿨어요")
        }
        fun refresh() {
            for (i in 1..Boss.PRESETS) {
                val (chip, badge) = chips[i - 1]
                val at = picked.indexOf(i)
                val on = at >= 0
                chip.background = t.rippleOver(t.box(if (on) t.track else t.bg, dpf(10f),
                    Math.max(1, dpf(1.4f).toInt()), if (on) tint else t.separator), dpf(10f))
                nums[i - 1].setTextColor(if (on) t.title else t.label3)
                badge.visibility = if (on) View.VISIBLE else View.GONE
                badge.text = (at + 1).toString()
            }
            line.text = "도전 순서:  " + picked.joinToString(" → ") { it.toString() + "번 덱" }
        }

        val all = text("1~5 전부", 11f, t.label2).apply {
            background = t.rippleOver(t.outline(dpf(8f), Math.max(1, dp(1)), t.borderDim), dpf(8f))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            setOnClickListener { picked.clear(); for (i in 1..Boss.PRESETS) picked.add(i); save(); refresh() }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@SettingsActivity).apply {
                background = t.round(tint, dpf(2f))
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(16)).apply { rightMargin = dp(9) }
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("보스 조합 순서", 15f, t.label))
                addView(text("고른 조합만, 누른 차례대로 도전해요", 12f, t.label3).apply { setPadding(0, dp(2), 0, 0) })
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(all)
        }
        val rowChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        for (i in 1..Boss.PRESETS) {
            val num = text(i.toString(), 15f, t.label3, Typeface.DEFAULT_BOLD).apply { gravity = Gravity.CENTER }
            val badge = text("", 10f, t.ink, Typeface.DEFAULT_BOLD).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(t.gold)
                }
            }
            val chip = android.widget.FrameLayout(this).apply {
                addView(num, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(badge, android.widget.FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                    gravity = Gravity.END or Gravity.TOP; topMargin = dp(4); rightMargin = dp(4)
                })
                isClickable = true
                setOnClickListener {
                    if (picked.contains(i)) {
                        if (picked.size == 1) {
                            Toast.makeText(this@SettingsActivity, "조합을 하나 이상 골라 주세요", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        picked.remove(i)
                    } else picked.add(i)
                    save(); refresh()
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) }
            }
            chips.add(chip to badge); nums.add(num); rowChips.addView(chip)
        }
        refresh()
        return group().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(head); addView(rowChips); addView(line)
        }
    }
}
