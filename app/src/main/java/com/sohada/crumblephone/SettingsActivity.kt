package com.sohada.crumblephone

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import androidx.core.view.WindowCompat

/**
 * 설정 화면(관제 화면 오른쪽 위 톱니바퀴).
 *
 * 원래는 관제 화면 목록 한가운데에 설정이 늘어져 있었다. 콘텐츠가 늘수록 스크롤이 길어지고,
 * **자주 누르는 것(콘텐츠)과 어쩌다 한 번 바꾸는 것(설정)이 섞여** 보기 나빴다.
 * iOS 앱이 하는 대로 톱니바퀴 뒤로 옮겼다.
 */
class SettingsActivity : ListActivity() {

    private lateinit var rowArena: LinearLayout
    private lateinit var rowOven: LinearLayout

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
            setPadding(0, dp(8), 0, dp(32))
        }
        root.addView(text("설정", 34f, t.label, android.graphics.Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))
        })

        // ── 안전 ──
        root.addView(sectionHeader("안전"))
        val g1 = group()
        val (rTest, _) = switchRow("시험 모드", "재화·입장권을 안 쓰고 진입까지만", Prefs.testMode) {
            Prefs.testMode = it
        }
        g1.addView(rTest)
        root.addView(g1)

        // ── 콘텐츠 ──
        root.addView(sectionHeader("콘텐츠"))
        val g2 = group()
        rowArena = row("아레나 판수", value = Prefs.arenaFights.toString() + "판",
            subtitle = "재화가 먼저 떨어지면 거기서 끝나요") { cycle(Prefs.ARENA_CHOICES, true) }
        // 오븐은 레벨마다 한 번에 여는 개수가 달라서, 게임 쪽 값을 여기에 맞춰 둬야
        // 봇이 '한 싸이클'이 얼마나 큰지 알고 상한을 제대로 잡는다.
        rowOven = row("오븐 1회 개수", value = Prefs.ovenPerRun.toString() + "개",
            subtitle = "게임의 '자동 열기 → 1회에 여는 개수'와 같게") { cycle(Prefs.OVEN_CHOICES, false) }
        g2.addView(rowArena); g2.addView(separator())
        g2.addView(rowOven)
        root.addView(g2)

        // ── 화면 ──
        root.addView(sectionHeader("화면"))
        val g3 = group()
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
        g3.addView(rDim)
        root.addView(g3)

        root.addView(text("설정은 이 기기에만 저장돼요.", 13f, t.label3).apply {
            setPadding(dp(20), dp(16), dp(20), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(t.bg)
            layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
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
