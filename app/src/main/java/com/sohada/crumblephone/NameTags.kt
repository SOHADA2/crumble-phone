package com.sohada.crumblephone

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 게임 **편성 화면의 프리셋 1~5 탭 위에 내가 붙인 이름을 덧그린다.**
 *
 * 게임에는 조합 이름이 없고 번호뿐이다. 그래서 보스에 맞춰 덱을 짜 두고도
 * "2번이 물가였나 독이었나" 를 매번 헷갈렸다. 설정에서 붙인 이름([Prefs.presetName])을
 * 바로 그 탭 자리에 띄워 준다.
 *
 * 그리는 것은 둘이다.
 *   ① 탭마다 작은 이름 칩 — 어느 번호가 무엇인지 한눈에. 좁아서 긴 이름은 잘린다.
 *   ② 오른쪽 빈자리에 **지금 고른 조합의 이름**을 큼직하게 — 잘리지 않는 전체 이름.
 *
 * ## 안 지키면 사고 나는 것 셋
 *
 * 1. **절대 터치를 먹지 않는다**(`FLAG_NOT_TOUCHABLE`). 이 창은 탭 바로 위에 앉는데,
 *    터치를 가로채면 사장님이 1번을 눌러도 안 눌리고 **봇이 조합을 바꾸는 탭도 먹힌다.**
 * 2. **봇이 도는 동안에는 쉰다.** 화면 판정을 하려면 캡처를 해야 하는데,
 *    `acquireLatestImage` 는 한 프레임을 한 쪽만 가져간다 — 옆에서 같이 집으면
 *    봇이 빈손으로 돌아간다. 이름표는 사람이 직접 편성할 때 쓰는 것이라 쉬어도 된다.
 * 3. **`Runner.shot()` 을 쓰지 않는다.** 그건 마지막 장을 들고 있다가 다음에 recycle 하는데,
 *    다른 스레드가 그걸 들고 있으면 `getPixel() on a recycled bitmap` 으로 죽는다.
 *    `CaptureService.grab()` 은 매번 새 장이라 서로 안 엉킨다.
 */
object NameTags {

    private val ui = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private val chips = ArrayList<TextView>()
    private var big: TextView? = null

    @Volatile private var alive = false
    private var shownY = 0          // 지금 띄운 탭 줄의 설계 y (0 = 안 띄움)

    private const val GOLD = "#F8E861"
    private const val INK = "#2E1D14"
    private const val PLATE = "#E6231A18"     // 게임의 어두운 판 + 살짝 비침

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (alive) return
        alive = true
        thread(name = "nametags") { loop() }
    }

    fun stop() {
        alive = false
        ui.post { hide() }
    }

    // ══════════════════════════════════════════════════════════
    //  화면을 지켜본다
    // ══════════════════════════════════════════════════════════
    private fun loop() {
        while (alive) {
            var wait = 1500L
            try {
                val cap = CaptureService.instance
                // 이름을 하나도 안 붙였으면 띄울 것도 없다 — 캡처도 하지 않는다.
                val on = appCtx != null && Prefs.showNameTags && Boss.anyNamed()
                val idle = !Runner.running && !Runner.stopping
                if (!on || !idle || cap == null || !TapService.gameIsFront) {
                    if (shownY != 0) ui.post { hide() }
                } else {
                    val b = cap.grab()
                    if (b == null) {
                        wait = 600L
                    } else {
                        // 편집 모드(y1010)를 먼저 본다. 그냥 보는 화면(y820)과 탭 줄 높이가 다르다.
                        var y = 0
                        var sel = 0
                        if (Screen.atDeckEdit(b)) {
                            y = Screen.PRESET_TABS_EDIT[0][1]
                            sel = Screen.selectedPreset(b, Screen.PRESET_TABS_EDIT)
                        } else if (Screen.atCookieRoster(b)) {
                            y = Screen.PRESET_TABS[0][1]
                            sel = Screen.selectedPreset(b, Screen.PRESET_TABS)
                        }
                        b.recycle()
                        val fy = y
                        val fs = sel
                        ui.post { if (fy == 0) hide() else show(fy, fs) }
                        // 편성 화면에 있을 때만 자주 본다. 아니면 느긋하게(배터리).
                        wait = if (y == 0) 1500L else 700L
                    }
                }
            } catch (e: Exception) {
                Bot.log("조합 이름표: " + (e.message ?: "알 수 없음"))
                wait = 2500L
            }
            try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
        }
        ui.post { hide() }
    }

    // ══════════════════════════════════════════════════════════
    //  그리기
    // ══════════════════════════════════════════════════════════
    private fun plate(color: String, radiusPx: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = radiusPx.toFloat()
        setStroke(if (radiusPx > 2) 2 else 1, Color.parseColor("#0B0609"))
    }

    /** 창과 글자들을 한 번만 만든다. 좌표는 기기에 맞춰 [Coords] 로 환산한다. */
    private fun build(ctx: Context) {
        if (box != null) return
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val f = FrameLayout(ctx)

        val tabs = Screen.PRESET_TABS
        val gap = Coords.len(tabs[1][0] - tabs[0][0])      // 탭 사이 간격
        val chipW = gap - Coords.len(6)
        // 24 설계px — 111px 칩에 한글 4~5자가 들어간다. 더 키우면 두 글자 만에 잘린다.
        // (잘려도 괜찮다. 전체 이름은 오른쪽 큰 이름표가 보여 준다.)
        val chipSize = Coords.len(24).toFloat()

        chips.clear()
        for (i in 0 until Boss.PRESETS) {
            val t = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, chipSize)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Coords.len(4), Coords.len(3), Coords.len(4), Coords.len(3))
                background = plate(PLATE, Coords.len(4))
                layoutParams = FrameLayout.LayoutParams(chipW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Coords.x(tabs[i][0]) - chipW / 2
                }
            }
            chips.add(t)
            f.addView(t)
        }

        // 오른쪽 빈자리 — 탭 다섯 개가 x543 에서 끝나고 [편성] 버튼은 한참 오른쪽에 있다.
        // 그 사이가 비어 있어서 **잘리지 않는 전체 이름**을 여기 둔다.
        big = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Coords.len(38).toFloat())
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.parseColor(GOLD))
            setPadding(Coords.len(14), Coords.len(5), Coords.len(14), Coords.len(5))
            background = plate(PLATE, Coords.len(6))
            // 이름 길이에 맞춰 줄어든다 — 고정 폭으로 두면 짧은 이름일 때 빈 판이 길게 남는다.
            maxWidth = Coords.len(500)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Coords.x(625) }
        }
        f.addView(big)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // ⚠️ NOT_TOUCHABLE 이 핵심이다. 이 창은 탭 바로 위에 앉으므로,
        //    터치를 가로채면 사장님의 탭도 봇의 탭도 이 창이 먹어 버린다.
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        wm = w
        box = f
    }

    private fun show(designY: Int, sel: Int) {
        val ctx = appCtx ?: return
        try {
            build(ctx)
            val w = wm ?: return
            val v = box ?: return
            val p = lp ?: return

            for (i in 0 until Boss.PRESETS) {
                val t = chips[i]
                val nm = Boss.name(i + 1)
                if (nm.isEmpty()) { t.visibility = View.INVISIBLE; continue }
                t.visibility = View.VISIBLE
                t.text = nm
                // 고른 탭은 게임처럼 노란 판에 짙은 글씨로 뒤집는다(어느 걸 눌렀는지 한눈에).
                val onTab = (i + 1) == sel
                t.setTextColor(Color.parseColor(if (onTab) INK else GOLD))
                t.background = plate(if (onTab) GOLD else PLATE, Coords.len(4))
            }

            val selName = if (sel in 1..Boss.PRESETS) Boss.name(sel) else ""
            big?.let {
                it.visibility = if (selName.isEmpty()) View.GONE else View.VISIBLE
                it.text = "▸ " + selName
            }

            // 탭 한가운데보다 조금 아래 — 탭 그림을 가리지 않는 자리다.
            val y = Coords.y(designY) + Coords.len(42)
            val first = v.parent == null
            if (first || p.y != y) {
                p.y = y
                if (first) w.addView(v, p) else w.updateViewLayout(v, p)
            }
            shownY = designY
        } catch (e: Exception) {
            Bot.log("조합 이름표를 못 띄웠어요: " + (e.message ?: "알 수 없음"))
        }
    }

    private fun hide() {
        shownY = 0
        val w = wm ?: return
        val v = box ?: return
        if (v.parent == null) return
        try { w.removeView(v) } catch (e: Exception) { }
    }
}
