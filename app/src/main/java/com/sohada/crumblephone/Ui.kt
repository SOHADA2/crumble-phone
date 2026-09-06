package com.sohada.crumblephone

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * iOS '묶음 목록(Inset Grouped)' 조각들. 관제 화면과 설정 화면이 같이 쓴다.
 *
 * 원래는 `MainActivity` 안에만 있었는데, 설정을 톱니바퀴로 빼면서 두 화면이 필요해졌다.
 * **복사하지 않고 여기로 올렸다** — 두 벌이 되면 한쪽만 고쳐 놓고 잊는다.
 *
 * 이미지도 커스텀 글꼴도 쓰지 않는다(APK 를 가볍게 유지한다). 앱처럼 보이는 것은
 * **색·간격·글자 크기의 위계**에서 나온다:
 *   34 굵게(제목) · 22 중간(지금 하는 일) · 17(버튼·목록) · 15(설명) · 13(구역 이름)
 */
open class ListActivity : AppCompatActivity() {

    protected lateinit var t: Theme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Theme.of(this)
    }

    protected fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    protected fun dpf(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    protected fun text(s: String, size: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (face != null) typeface = face
    }

    protected val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ── iOS 묶음 목록 조각들 ──────────────────────────────────

    /** 구역 이름. 목록 위에 작게 붙는 회색 글씨. */
    protected fun sectionHeader(title: String) = text(title, 13f, t.label2, medium).apply {
        setPadding(dp(20), dp(24), dp(20), dp(7))
        letterSpacing = 0.02f
    }

    /** 셀들을 담는 둥근 카드. 모서리 클리핑은 여기서 한 번만 한다. */
    protected fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = t.round(t.cell, dpf(12f))
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        }
    }

    /** 셀 사이 가는 선. 왼쪽은 글자 시작점까지 들여쓴다(iOS 방식). */
    protected fun separator(): View = View(this).apply {
        setBackgroundColor(t.separator)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, Math.max(1, (dpf(0.5f)).toInt())).apply {
            leftMargin = dp(16)
        }
    }

    /**
     * 목록 한 줄. 왼쪽 제목 · 오른쪽 값 · 그 옆 꺾쇠.
     * `tint` 를 주면 제목이 그 색이 된다(위험한 동작을 빨갛게 표시할 때).
     */
    protected fun row(
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
    protected fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): Pair<LinearLayout, Switch> {
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

    protected fun LinearLayout.setValue(s: String, color: Int) {
        (findViewWithTag<TextView>("value"))?.let { it.text = s; it.setTextColor(color) }
    }
}
