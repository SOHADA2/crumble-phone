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
 * 게임 **편성 화면의 프리셋 1~5 탭 바로 아래에 내가 붙인 이름을 덧그린다.**
 *
 * 게임에는 조합 이름이 없고 번호뿐이다. 그래서 보스에 맞춰 덱을 짜 두고도
 * "2번이 물가였나 독이었나" 를 매번 헷갈렸다. 설정에서 붙인 이름([Prefs.presetName])을
 * 바로 그 탭 아래에 놓는다. 지금 고른 탭은 게임처럼 노란 판으로 뒤집힌다.
 *
 * ⚠️ 첫 판에는 오른쪽 빈자리에 '지금 고른 조합' 이름표를 하나 더 크게 뒀다가 뺐다.
 *    사장님: "덱 글자 위치가 좀 별로야, 왼쪽에 있는 1번 바로 아래쪽에 위치하게 해줘."
 *    탭마다 하나씩이면 충분하고, 둘을 띄우면 어느 쪽을 봐야 하는지가 헷갈린다.
 *    이름을 3글자로 묶은 것도 같은 이유다 — 탭 한 칸(설계 117px)에 안 잘리고 들어간다.
 *
 * ## 안 지키면 사고 나는 것 넷
 *
 * 1. **절대 터치를 먹지 않는다**(`FLAG_NOT_TOUCHABLE`). 이 창은 탭 바로 아래에 앉는데,
 *    터치를 가로채면 사장님이 1번을 눌러도 안 눌리고 **봇이 조합을 바꾸는 탭도 먹힌다.**
 * 2. **봇이 도는 동안에는 쉰다.** 화면 판정을 하려면 캡처를 해야 하는데,
 *    `acquireLatestImage` 는 한 프레임을 한 쪽만 가져간다 — 옆에서 같이 집으면
 *    봇이 빈손으로 돌아간다. 이름표는 사람이 직접 편성할 때 쓰는 것이라 쉬어도 된다.
 * 3. **`Runner.shot()` 을 쓰지 않는다.** 그건 마지막 장을 들고 있다가 다음에 recycle 하는데,
 *    다른 스레드가 그걸 들고 있으면 `getPixel() on a recycled bitmap` 으로 죽는다(v1.33 전례).
 *    `CaptureService.grab()` 은 매번 새 장이라 서로 안 엉킨다.
 * 4. **창을 화면 전체로 잡고 `FLAG_LAYOUT_IN_SCREEN` 을 준다.**
 *    이게 없으면 창의 y 원점이 **상태표시줄 아래**라, 실측 좌표를 그대로 넣어도 그만큼
 *    (폰에서 90px 남짓) 내려앉는다. 첫 판이 정확히 그래서 이름표가 탭이 아니라
 *    **쿠키 카드 위**에 얹혔다. 화면 전체로 잡고 자식의 `topMargin` 으로 놓으면
 *    실측 좌표와 그림이 1:1 로 맞는다.
 */
object NameTags {

    private val ui = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private val chips = ArrayList<TextView>()

    @Volatile private var alive = false
    private var shownY = 0          // 지금 띄운 탭 줄의 설계 y (0 = 안 띄움)

    private const val GOLD = "#F8E861"
    private const val INK = "#2E1D14"
    private const val PLATE = "#E6231A18"     // 게임의 어두운 판 + 살짝 비침

    /** 탭 한가운데에서 이만큼 아래에 놓는다(설계 px). 탭 그림을 안 가리는 자리다. */
    private const val BELOW = 34

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
        setStroke(2, Color.parseColor("#0B0609"))
    }

    /** 창과 글자들을 한 번만 만든다. 좌표는 기기에 맞춰 [Coords] 로 환산한다. */
    private fun build(ctx: Context) {
        if (box != null) return
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val f = FrameLayout(ctx)

        val tabs = Screen.PRESET_TABS
        val gap = Coords.len(tabs[1][0] - tabs[0][0])      // 탭 사이 간격(설계 117px)
        val chipW = gap - Coords.len(6)
        // 3글자만 받으므로 28 설계px 로 키워도 안 잘린다(3×28=84 < 칩 안쪽 103).
        val chipSize = Coords.len(28).toFloat()

        chips.clear()
        for (i in 0 until Boss.PRESETS) {
            val t = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, chipSize)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Coords.len(4), Coords.len(3), Coords.len(4), Coords.len(3))
                background = plate(PLATE, Coords.len(4))
                // 탭 한가운데에 맞춰 가로 정렬. 세로는 show() 가 탭 줄에 맞춰 다시 잡는다.
                layoutParams = FrameLayout.LayoutParams(chipW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = Coords.x(tabs[i][0]) - chipW / 2
                }
            }
            chips.add(t)
            f.addView(t)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // ⚠️ 세 가지가 다 있어야 한다.
        //    NOT_TOUCHABLE  — 탭 바로 위 창이라, 터치를 먹으면 사장님 탭도 봇 탭도 이 창이 가져간다.
        //    LAYOUT_IN_SCREEN + NO_LIMITS — 좌표를 **화면 절대 좌표**로 만든다.
        //                     없으면 상태표시줄 높이만큼 내려앉는다(첫 판의 그 증상).
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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

            val top = Coords.y(designY) + Coords.len(BELOW)
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
                val q = t.layoutParams as FrameLayout.LayoutParams
                if (q.topMargin != top) { q.topMargin = top; t.layoutParams = q }
            }

            if (v.parent == null) w.addView(v, p)
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
