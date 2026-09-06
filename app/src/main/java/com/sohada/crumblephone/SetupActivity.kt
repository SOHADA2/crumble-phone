package com.sohada.crumblephone

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * 첫 실행 안내. 켤 것이 셋인데 **하나씩만** 보여 준다.
 *
 * 할 일의 개수는 줄일 수 없다 — 접근성도 화면 읽기도 안드로이드가 사용자에게 직접 받는 허가라
 * 앱이 대신 켤 방법이 없다. 대신 **막히는 지점**을 없앤다:
 *   - 지금 할 것 하나만 펼쳐 두고, 끝난 것은 접어서 ✓ 로 바꾼다
 *   - 각 단계에서 해당 설정 화면으로 바로 보낸다
 *   - 여기서 실제로 넘어지는 두 곳을 미리 말해 준다
 *       ① 접근성 스위치가 회색 → 안드로이드 13+ 의 '제한된 설정'
 *       ② 화면 읽기에서 '앱 하나 공유' 를 그대로 두면 우리 앱만 찍힌다
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var t: Theme
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAP = 2001

    private lateinit var steps: List<Step>
    private lateinit var btnDone: TextView
    private lateinit var lblSub: TextView

    /** 한 단계를 이루는 조각들. `done` 이 참이 되면 접히고 ✓ 로 바뀐다. */
    private class Step(
        val card: LinearLayout,
        val badge: TextView,
        val title: TextView,
        val body: LinearLayout,
        val isDone: () -> Boolean,
        val optional: Boolean = false
    )

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun dpf(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun text(s: String, size: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        setLineSpacing(dpf(3f), 1f)
        if (face != null) typeface = face
    }

    private fun button(label: String, fill: Int, fg: Int, onClick: () -> Unit) =
        text(label, 16f, fg, medium).apply {
            gravity = Gravity.CENTER
            background = t.rippleRound(fill, dpf(11f))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(12) }
        }

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
            setPadding(0, dp(8), 0, dp(28))
        }
        root.addView(text("시작하기", 34f, t.label, Typeface.DEFAULT_BOLD).apply {
            setPadding(dp(20), dp(12), dp(20), 0)
        })
        lblSub = text("", 15f, t.label2).apply { setPadding(dp(20), dp(2), dp(20), dp(4)) }
        root.addView(lblSub)

        val s1 = step(1, "접근성 서비스", "봇이 화면을 대신 눌러 주려면 필요해요.\n목록에서 '크럼블 폰봇 조작'을 찾아 켜 주세요.",
            { TapService.isReady }) { body ->
            body.addView(button("접근성 설정 열기", t.blue, Color.WHITE) { openAccessibility() })

            body.addView(text("'보안을 위해…' 라며 막히나요?", 14f, t.label, medium).apply {
                setPadding(0, dp(18), 0, dp(2))
            })
            body.addView(text(
                "안드로이드 13부터는 스토어 밖에서 설치한 앱의 접근성을 막아 둡니다. 푸는 방법이 있는데, " +
                "순서가 중요해요.", 14f, t.label2))
            // 여기서 실제로 헤맸다. '제한된 설정 허용' 은 **막히기 전에는 메뉴에 아예 없다.**
            // 그 사실을 안 적어 두면 앱 정보만 열어 보고 "그런 항목이 없다" 로 끝난다.
            body.addView(warn("먼저 위 버튼으로 들어가 **켜기를 시도해야** 합니다.\n" +
                "한 번 막히기 전에는 '제한된 설정 허용' 메뉴가 아예 나타나지 않아요."))
            body.addView(text(
                "① 위 [접근성 설정 열기] → '크럼블 폰봇 조작' 스위치를 눌러 본다 (여기서 막힘)\n" +
                "② 아래 버튼 → 오른쪽 위 ⋮ → '제한된 설정 허용' (지문·PIN 확인)\n" +
                "③ 다시 ① 로 가서 켠다",
                14f, t.label2).apply { setPadding(0, dp(10), 0, 0) })
            body.addView(button("앱 정보 열기 (② 단계)", t.fill, t.label) { openAppInfo() })
            body.addView(text(
                "①에서 그냥 켜졌다면 이 과정은 필요 없습니다. PC 가 있다면 " +
                "adb install -r -g 로 깔면 이 제한 자체가 안 걸려요.",
                13f, t.label3).apply { setPadding(0, dp(10), 0, 0) })
        }

        val s2 = step(2, "화면 읽기", "게임 화면을 읽어서 지금 무슨 화면인지 판단해요.",
            { CaptureService.instance != null }) { body ->
            body.addView(warn("동의 창에서 '앱 하나 공유'를 반드시 '전체 화면'으로 바꿔 주세요. " +
                "그대로 두면 이 앱 자신만 찍혀서 게임을 못 봅니다."))
            body.addView(button("화면 읽기 허용", t.blue, Color.WHITE) { askProjection() })
        }

        val s3 = step(3, "게임 위에 표시", "게임 위에 작은 상태 알약을 띄웁니다. 무엇을 하는 중인지 게임을 보면서 알 수 있어요.",
            { Overlay.canDraw(this) }, optional = true) { body ->
            body.addView(button("허용하기", t.fill, t.label) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)))
            })
        }
        steps = listOf(s1, s2, s3)
        for (s in steps) root.addView(s.card)

        btnDone = button("시작하러 가기", t.blue, Color.WHITE) { finish() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(24); height = dp(50)
            }
        }
        root.addView(btnDone)

        setContentView(ScrollView(this).apply {
            addView(root)
            setBackgroundColor(t.bg)
            isVerticalScrollBarEnabled = false
            fitsSystemWindows = true
        })
        tick()
    }

    /** 조심할 것을 노랗게 한 덩어리. 여기서 실제로 넘어지는 자리에만 쓴다. */
    private fun warn(s: String) = text(s, 14f, t.orange).apply {
        background = t.round(if (t.dark) Color.parseColor("#2A2113") else Color.parseColor("#FFF6E5"), dpf(10f))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun step(
        n: Int, title: String, desc: String,
        isDone: () -> Boolean, optional: Boolean = false,
        fill: (LinearLayout) -> Unit
    ): Step {
        val badge = text(n.toString(), 15f, Color.WHITE, medium).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.label3) }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) }
        }
        val lbl = text(title + (if (optional) "  (선택)" else ""), 19f, t.label, medium)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge); addView(lbl)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(6), 0, 0)
        }
        body.addView(text(desc, 15f, t.label2))
        fill(body)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = t.round(t.cell, dpf(14f))
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(head); addView(body)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(14)
            }
        }
        return Step(card, badge, lbl, body, isDone, optional)
    }

    /**
     * 지금 할 것 하나만 펼친다. 끝난 것은 접고 ✓ 로 바꾼다.
     * 1초마다 다시 본다 — 설정 화면에 다녀오면 알아서 다음 칸으로 넘어간다.
     */
    private fun tick() {
        var current = -1
        for ((i, s) in steps.withIndex()) {
            if (!s.isDone() && !s.optional && current < 0) current = i
        }
        if (current < 0) for ((i, s) in steps.withIndex()) if (!s.isDone() && current < 0) current = i

        for ((i, s) in steps.withIndex()) {
            val done = s.isDone()
            s.badge.text = if (done) "✓" else (i + 1).toString()
            (s.badge.background as GradientDrawable).setColor(
                when {
                    done -> t.green
                    i == current -> t.blue
                    else -> t.label3
                })
            s.body.visibility = if (done || i != current) View.GONE else View.VISIBLE
            s.title.setTextColor(if (done) t.label2 else t.label)
        }

        val ready = TapService.isReady && CaptureService.instance != null
        lblSub.text = if (ready) "다 됐어요. 이제 시작할 수 있습니다" else "세 가지만 켜면 됩니다 (마지막은 선택)"
        btnDone.isEnabled = ready
        btnDone.alpha = if (ready) 1f else 0.35f
        ui.postDelayed({ tick() }, 1000)
    }

    // ── 설정 화면으로 보내기 ─────────────────────────────────

    /** 접근성 목록을 열되, 되는 기기에서는 우리 항목을 짚어서 연다. */
    private fun openAccessibility() {
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val comp = ComponentName(packageName, TapService::class.java.name).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", comp) }
        i.putExtra(":settings:fragment_args_key", comp)
        i.putExtra(":settings:show_fragment_args", args)
        try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + packageName)))
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAP) {
            if (resultCode == RESULT_OK && data != null) CaptureService.start(this, resultCode, data)
            else Bot.log("화면 읽기를 거부했습니다")
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }
}
