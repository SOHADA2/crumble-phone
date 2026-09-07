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
 */
class SettingsActivity : ListActivity() {

    private lateinit var rowArena: LinearLayout
    private lateinit var rowOven: LinearLayout
    private lateinit var rowGame: LinearLayout
    private lateinit var rowUpdate: LinearLayout
    private lateinit var rowCapOff: LinearLayout
    private lateinit var capOffSep: View
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

        // 제목 줄 — 왼쪽에 돌아가기, 그 옆에 큰 제목. 뒤로가기 버튼이 눈에 보여야 헤매지 않는다.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("‹", 26f, t.label, medium).apply {
                gravity = Gravity.CENTER
                background = t.chunky(t.cell, dpf(21f), dp(2), dp(3))
                isClickable = true
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    leftMargin = dp(18); topMargin = dp(12)
                }
            })
            addView(text("설정", 30f, t.gold, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(14), dp(12), dp(20), 0)
            })
        })
        root.addView(text("바꾼 값은 바로 저장돼요.", 14f, t.label2).apply {
            setPadding(dp(76), dp(2), dp(20), 0)
        })

        // ── 게임 ──
        root.addView(sectionHeader("게임"))
        val gGame = group()
        rowGame = row("게임 앱", subtitle = "스토어마다 이름이 달라서 여기서 골라요") { pickGame() }
        gGame.addView(rowGame); gGame.addView(separator())
        gGame.addView(row("게임 켜기", subtitle = "봇 없이 게임만 열어요") { launchGame() })
        root.addView(gGame)

        // ── 자동 실행 ──
        root.addView(sectionHeader("자동 실행"))
        val gRun = group()
        val (rTest, _) = switchRow("시험 모드", "재화·입장권을 안 쓰고 진입까지만", Prefs.testMode) {
            Prefs.testMode = it
        }
        rowArena = row("아레나 판수", value = Prefs.arenaFights.toString() + "판",
            subtitle = "재화가 먼저 떨어지면 거기서 끝나요") { cycle(Prefs.ARENA_CHOICES, true) }
        // 오븐은 레벨마다 한 번에 여는 개수가 달라서, 게임 쪽 값을 여기에 맞춰 둬야
        // 봇이 '한 싸이클'이 얼마나 큰지 알고 상한을 제대로 잡는다.
        rowOven = row("오븐 1회 개수", value = Prefs.ovenPerRun.toString() + "개",
            subtitle = "게임의 '자동 열기 → 1회에 여는 개수'와 같게") { cycle(Prefs.OVEN_CHOICES, false) }
        val (rAd, _) = switchRow("광고 제거 있음",
            "일일 던전에서 [SKIP]으로 횟수를 더 받아요", Prefs.adFree) { Prefs.adFree = it }
        gRun.addView(rTest); gRun.addView(separator())
        gRun.addView(rAd); gRun.addView(separator())
        gRun.addView(rowArena); gRun.addView(separator())
        gRun.addView(rowOven)
        root.addView(gRun)

        // ── 화면 ──
        root.addView(sectionHeader("화면"))
        val gScr = group()
        val (rDim, _) = switchRow("화면 어둡게",
            "봇이 도는 동안만. 알약을 탭하면 15초 밝아져요", Prefs.dimScreen) {
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
        gScr.addView(rDim)
        capOffSep = separator()
        rowCapOff = row("화면 읽기 끄기", subtitle = "봇도 같이 멈춰요", tint = t.red) {
            CaptureService.stop(applicationContext); finish()
        }
        gScr.addView(capOffSep); gScr.addView(rowCapOff)
        root.addView(gScr)

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
        root.addView(gApp)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(t.bg)
            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        tick()
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
        val capOk = CaptureService.instance != null
        rowCapOff.visibility = if (capOk) View.VISIBLE else View.GONE
        capOffSep.visibility = rowCapOff.visibility
        ui.postDelayed({ tick() }, 1000)
    }

    override fun onResume() {
        super.onResume()
        Overlay.onAppForeground(true)
    }

    override fun onPause() {
        super.onPause()
        Overlay.onAppForeground(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
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
    /** 탭할 때마다 다음 값으로 돈다. 고르는 값이 몇 개뿐이라 별도 화면을 안 만든다. */
    private fun cycle(choices: IntArray, arena: Boolean) {
        val cur = if (arena) Prefs.arenaFights else Prefs.ovenPerRun
        val i = choices.indexOf(cur)
        val next = choices[(if (i < 0) 0 else i + 1) % choices.size]
        if (arena) { Prefs.arenaFights = next; rowArena.setValue(next.toString() + "판", t.label2) }
        else { Prefs.ovenPerRun = next; rowOven.setValue(next.toString() + "개", t.label2) }
    }
}
