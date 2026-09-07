package com.sohada.crumblephone

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.WindowCompat

/**
 * **덱 1~5 구성** — 넣을 쿠키 이름을 적어 두면 봇이 편성 화면에서 찾아 넣는다.
 *
 * 보스전은 조합 1~5를 하나씩 바꿔 가며 도전하므로, 다섯 덱이 **서로 달라야** 의미가 있다.
 *
 * ## 이름은 대충 적어도 된다
 * 찾을 때 편집거리로 본다(`Deck.findIndex`) — 사전 쪽 OCR 오독(`밀귀웨이`)과
 * 사용자가 친 이름(`밀키웨이`)이 거리 1이라 그냥 붙는다.
 *
 * ## [시험]을 먼저 쓰는 이유
 * 편집 모드에서 칸을 누르면 팀이 바뀌는지는 **아직 실기로 못 본 동작**이다.
 * [시험]은 똑같이 다 해 보고 **저장만 안 한다** — 틀려도 덱이 안 망가진다.
 * PC 봇의 `-MaxFights 0` 과 같은 자리다.
 */
class DeckSetupActivity : ListActivity() {

    private val boxes = HashMap<Int, EditText>()

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

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("‹", 26f, t.label, medium).apply {
                gravity = Gravity.CENTER
                background = t.chunky(t.cell, dpf(21f), dp(2), dp(3))
                isClickable = true
                setOnClickListener { save(); finish() }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    leftMargin = dp(18); topMargin = dp(12)
                }
            })
            addView(text("덱 구성", 30f, t.gold, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(14), dp(12), dp(20), 0)
            })
        })

        if (Prefs.deckDictSize == 0) {
            root.addView(text("먼저 ⚙ → 점검 → [쿠키 사전 만들기] 를 해 주세요.\n" +
                "어느 칸이 어느 쿠키인지 알아야 이름으로 넣을 수 있어요.",
                14f, t.label2).apply { setPadding(dp(22), dp(10), dp(22), dp(10)) })
        } else {
            root.addView(text("넣을 쿠키 이름을 한 줄에 하나씩 적어 주세요. 조금 틀려도 알아서 찾아요.\n" +
                "[시험] 은 똑같이 해 보고 저장만 안 해요 — 먼저 이걸로 확인하세요.",
                13f, t.label3).apply { setPadding(dp(22), dp(4), dp(22), dp(10)) })

            for (n in 1..5) {
                root.addView(sectionHeader("덱 " + n + "번"))
                val g = group()
                g.addView(deckBox(n))
                g.addView(separator())
                g.addView(row("시험 (저장 안 함)", subtitle = "좌표가 맞는지 공짜로 봐요") {
                    save(); Overlay.show(applicationContext)
                    Deck.applyPreset(applicationContext, n, true); finish()
                })
                g.addView(separator())
                g.addView(row("지금 적용", value = "저장", tint = t.gold,
                    subtitle = "실제로 덱 " + n + "번을 바꿔요") {
                    save(); Overlay.show(applicationContext)
                    Deck.applyPreset(applicationContext, n, false); finish()
                })
                root.addView(g)
            }
        }

        addContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun deckBox(n: Int): LinearLayout {
        val box = EditText(this).apply {
            setText(Prefs.deckNames(n))
            setTextColor(t.label)
            textSize = 16f
            background = null
            hint = "예) 다크초코 쿠키"
            setHintTextColor(t.label3)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        boxes[n] = box
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.cell)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(box)
        }
    }

    private fun save() {
        var changed = false
        for ((n, box) in boxes) {
            val v = box.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (v != Prefs.deckNames(n)) { Prefs.setDeckNames(n, v); changed = true }
        }
        if (changed) Toast.makeText(this, "덱 구성을 저장했어요", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() { save(); super.onPause() }
}
