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
 * **쿠키 사전** 보기·고치기.
 *
 * `Deck.buildDict` 가 읽어 온 이름을 그대로 보여 주고, 잘못 읽힌 것만 손으로 고친다.
 * PC 봇도 같은 자리에 검수 창(`deck_verify.ps1`)을 뒀다 — OCR 이 90%를 하고 사람이 나머지를 채운다.
 *
 * ## 대부분은 안 고쳐도 된다
 * 찾을 때 **편집거리로 너그럽게** 보기 때문이다(`Deck.findIndex`). 73마리 실측 기준
 * `밀키웨이`↔`밀귀웨이`, `팬케이크`↔`팬궤이크`, `커피`↔`귀피` 는 전부 거리 1이라 그냥 붙는다.
 * 손이 필요한 건 **글자가 통째로 깨진 것**뿐이다(실측 73마리 중 2개: `lEA물` · `HY I2IH름`).
 */
class DeckDictActivity : ListActivity() {

    private val boxes = ArrayList<EditText>()

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
            addView(text("쿠키 사전", 30f, t.gold, Typeface.DEFAULT_BOLD).apply {
                setPadding(dp(14), dp(12), dp(20), 0)
            })
        })

        val names = Prefs.deckDict.split("\n").filter { it.isNotBlank() }

        root.addView(text(
            if (names.isEmpty()) "아직 비어 있어요. 점검 → [쿠키 사전 만들기] 를 먼저 눌러 주세요."
            else "편성 목록에 있는 " + names.size + "마리예요. 순서도 목록과 같아요.\n" +
                 "글자가 깨진 것만 고치면 돼요 — 한두 글자 오독은 찾을 때 알아서 맞춰 봅니다.",
            13f, t.label3).apply { setPadding(dp(22), dp(4), dp(22), dp(10)) })

        if (names.isNotEmpty()) {
            val g = group()
            names.forEachIndexed { i, n ->
                if (i > 0) g.addView(separator())
                g.addView(nameRow(i + 1, n))
            }
            root.addView(g)
        }

        addContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** 번호 + 고칠 수 있는 이름 칸. 번호는 편성 목록의 순번이라 눈으로 대조할 수 있다. */
    private fun nameRow(no: Int, name: String): LinearLayout {
        val box = EditText(this).apply {
            setText(name)
            setTextColor(t.label)
            textSize = 17f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        boxes.add(box)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(t.cell)
            minimumHeight = dp(50)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(text(no.toString(), 13f, t.label3).apply {
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(30), WRAP_CONTENT).apply { rightMargin = dp(12) }
            })
            addView(box)
        }
    }

    /** 나갈 때 조용히 저장한다 — [저장] 을 못 찾아 고친 게 날아가는 일이 없게. */
    private fun save() {
        if (boxes.isEmpty()) return
        val out = boxes.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
        if (out.isEmpty()) return
        if (out.joinToString("\n") != Prefs.deckDict) {
            Prefs.deckDict = out.joinToString("\n")
            Toast.makeText(this, "사전을 저장했어요", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() { save(); super.onPause() }
}
