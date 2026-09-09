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
 *   - 여기서 실제로 넘어지는 곳을 미리 말해 준다
 *       ⓪ 설치할 때 Play 프로텍트가 '알 수 없는 개발자' 라며 겁을 준다
 *       ① 접근성 목록에서 우리 항목을 못 찾는다(삼성은 [설치된 앱] 안에 들어 있다)
 *       ② 접근성 스위치가 회색 → 안드로이드 13+ 의 '제한된 설정'
 *       ③ 화면 읽기에서 '앱 하나 공유' 를 그대로 두면 우리 앱만 찍힌다
 *
 * ⚠️ **기기마다 메뉴 이름과 자리가 다르다.** '여기 있습니다' 라고 한 곳만 찍어 주면
 *    거기 없는 사람은 그대로 막힌다. 그래서 찾는 자리를 여러 개 적고,
 *    마지막에는 **PC 로 확실히 푸는 길**(appops)을 남겨 둔다.
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
            background = t.chunky(fill, dpf(16f), dp(2), dp(4))
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

        // 설치할 때 겁을 먹고 여기까지 못 오는 사람이 있다. 이미 온 사람에게는
        // '그게 정상이었다' 고 알려 주는 것만으로 충분하고, 다음 업데이트 때 또 만나므로 미리 말해 둔다.
        root.addView(text("설치할 때 겁나는 창을 봤다면", 15f, t.label, medium).apply {
            setPadding(dp(20), dp(16), dp(20), dp(2))
        })
        root.addView(text(
            "구글 Play 프로텍트가 '알 수 없는 개발자' · '유해한 앱일 수 있음' 이라고 막았을 거예요. " +
            "정상입니다 — 스토어를 거치지 않은 앱은 전부 그렇게 뜹니다.\n" +
            "[세부정보] 또는 [자세히] → [무시하고 설치] 로 넘기면 돼요. " +
            "앱 안 [업데이트] 로 새 판을 받을 때도 똑같이 한 번 물어봅니다.",
            14f, t.label2).apply { setPadding(dp(20), 0, dp(20), dp(4)) })

        val s1 = step(1, "접근성 서비스", "봇이 화면을 대신 눌러 줘요. 이게 없으면 아무것도 못 합니다.",
            { TapService.isReady }) { body ->
            body.addView(button("접근성 설정 열기", t.blue, Color.WHITE) { openAccessibility() })

            // 여기서 제일 많이 헤맨다. 삼성은 접근성 첫 화면에 카테고리만 있고
            // 우리 항목은 [설치된 앱] 안에 들어 있다. '목록에서 찾으세요' 로는 못 찾는다.
            body.addView(head("① 목록에서 찾기"))
            body.addView(text(
                "설정이 열리면 화면을 아래로 내려 [설치된 앱] 을 누르세요. 그 안에 " +
                "'크럼블 폰봇 조작' 이 있습니다.\n" +
                "기기에 따라 [설치된 서비스] · [다운로드한 앱] · [다운로드한 서비스] 로 적혀 있어요. " +
                "첫 화면에 바로 보이는 기기도 있습니다.",
                14f, t.label2))

            body.addView(head("② 켜기"))
            body.addView(text(
                "스위치를 켜면 '기기를 완전히 제어하도록 허용할까요?' 창이 떠요. [허용] 을 누르면 끝이에요.\n" +
                "이 창이 무섭게 쓰여 있는데, 화면을 눌러 주려면 안드로이드가 이 등급을 요구합니다.",
                14f, t.label2))

            body.addView(warn("스위치가 회색이거나 '보안을 위해 이 설정은 사용할 수 없습니다' 로 막히면 " +
                "아래 [막혔을 때] 로 가세요. 스토어를 안 거친 앱이라 잠겨 있는 겁니다."))

            body.addView(head("막혔을 때 — 제한된 설정 풀기"))
            // '한 번 막혀야 메뉴가 생긴다' 를 안 적으면 앱 정보만 열어 보고 '그런 항목 없다' 로 끝난다.
            body.addView(text(
                "⚠ 먼저 ②에서 켜기를 한 번 시도해 막혀야 합니다. 막히기 전에는 푸는 메뉴가 아예 없어요.",
                14f, t.orange))
            body.addView(text(
                "① 아래 [앱 정보 열기] 를 누른다\n" +
                "② 오른쪽 위 ⋮ (점 세 개) → [제한된 설정 허용]\n" +
                "③ 지문이나 PIN 을 한 번 확인한다\n" +
                "④ 다시 [접근성 설정 열기] 로 가서 켠다",
                14f, t.label2).apply { setPadding(0, dp(10), 0, 0) })
            body.addView(button("앱 정보 열기", t.fill, t.label) { openAppInfo() })

            // 실제로 '하라는 대로 해도 안 된다' 는 말을 들었다. 자리가 기기마다 다르므로
            // 한 곳만 찍지 말고 찾을 만한 곳을 다 적는다.
            body.addView(head("⋮ 에 그런 메뉴가 없다면"))
            body.addView(text(
                "· 앱 정보 화면을 아래로 끝까지 내려 보세요. 목록 안에 [제한된 설정 허용] 이 " +
                "그냥 놓여 있는 기기가 있어요.\n" +
                "· 이름이 [제한 설정 허용] · [제한된 설정] 으로 적힌 기기도 있습니다.\n" +
                "· 그래도 없으면 접근성으로 돌아가 스위치를 한 번 더 눌러 막힌 뒤, 곧바로 앱 정보로 오세요. " +
                "막힌 직후에만 메뉴가 보이는 기기가 있어요.\n" +
                "· 폰을 껐다 켜고 다시 해 보세요.",
                14f, t.label2))

            body.addView(head("PC 가 있으면 확실한 길"))
            body.addView(text(
                "USB 로 연결하고(개발자 옵션 → USB 디버깅) PC 에서 이 한 줄이면 잠금이 풀려요. " +
                "눌러서 복사할 수 있어요.", 14f, t.label2))
            body.addView(code("adb shell appops set " + packageName + " ACCESS_RESTRICTED_SETTINGS allow"))
        }

        val s2 = step(2, "화면 읽기", "게임 화면을 읽어서 지금 무슨 화면인지 판단해요.",
            { CaptureService.instance != null }) { body ->
            body.addView(warn("동의 창 위쪽이 '앱 하나 공유' 로 되어 있으면 반드시 '전체 화면' 으로 바꿔 주세요. " +
                "그대로 두면 이 앱 자신만 찍혀서 게임을 못 봅니다."))
            body.addView(button("화면 읽기 허용", t.blue, Color.WHITE) { askProjection() })
            body.addView(text(
                "[전체 화면] 으로 바꾸고 → [다음] → [지금 시작] 을 누르면 돼요.\n" +
                "이 허락은 한 번만 유효해서, 폰을 다시 켜거나 앱을 껐다 켜면 다시 눌러야 해요. " +
                "녹화하거나 어디로 보내지 않습니다.",
                14f, t.label2).apply { setPadding(0, dp(10), 0, 0) })
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

    /** 작은 소제목. 한 단계 안이 길어지면 눈이 어디를 읽는지 잃는다. */
    private fun head(s: String) = text(s, 15f, t.label, medium).apply {
        setPadding(0, dp(18), 0, dp(4))
    }

    /**
     * 명령 한 줄. **눌러서 복사**된다 — 폰 화면의 긴 명령을 손으로 옮겨 적게 두면 안 된다.
     */
    private fun code(s: String) = text(s, 13f, t.gold).apply {
        typeface = Typeface.MONOSPACE
        background = t.round(Color.parseColor("#241E22"), dpf(12f))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", s))
            android.widget.Toast.makeText(this@SetupActivity, "복사했어요", android.widget.Toast.LENGTH_SHORT).show()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    /** 조심할 것을 노랗게 한 덩어리. 여기서 실제로 넘어지는 자리에만 쓴다. */
    private fun warn(s: String) = text(s, 14f, t.orange).apply {
        background = t.round(Color.parseColor("#3A2C18"), dpf(12f))
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
            background = t.card(dpf(18f), Math.max(1, dp(3)))
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
