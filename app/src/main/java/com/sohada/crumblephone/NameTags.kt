package com.sohada.crumblephone

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
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
 * 게임 **편성 화면의 프리셋 1~5 탭 아래에 내가 붙인 이름을 덧그린다.**
 *
 * 게임에는 조합 이름이 없고 번호뿐이다. 그래서 보스에 맞춰 덱을 짜 두고도
 * "2번이 물가였나 독이었나" 를 매번 헷갈렸다. 설정에서 붙인 이름([Prefs.presetName])을
 * 바로 그 탭 아래에 놓는다.
 *
 * ## 생김새 — **게임 글씨를 흉내 낸다**
 * 처음엔 어두운 판 위에 글자를 얹었는데, 사장님이 직접 예시를 만들어 보내 주셨다:
 * **판 없이 외곽선만 두른 금색 글씨.** 이 게임 UI 가 전부 그 문법이라 판을 깔면 혼자 튄다.
 * 안드로이드 TextView 에는 글자 외곽선이 없어서, **같은 글자를 두 번 겹쳐 그린다** —
 * 뒤에 `STROKE` 로 굵게 한 번(검정), 앞에 `FILL` 로 한 번(금색).
 * 고른 탭은 금색, 나머지는 크림색이라 어느 걸 눌렀는지 색으로 갈린다.
 *
 * 자리는 탭 **아래 빈 띠**다. 실측(2026-09-14): 탭 한가운데에서 92~151px 아래가
 * 아무것도 없는 어두운 줄이고(설계 y912~971), 그 아래부터 쿠키 카드가 시작한다.
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
    // 글자 하나를 두 장으로 그린다 — 뒤(외곽선) / 앞(속). 겹쳐 놓으면 게임 글씨처럼 보인다.
    private val backs = ArrayList<TextView>()
    private val fronts = ArrayList<TextView>()

    @Volatile private var alive = false
    private var shownY = 0          // 지금 띄운 탭 줄의 설계 y (0 = 안 띄움)

    private const val GOLD = "#F8E861"      // 고른 조합
    private const val CREAM = "#E8D9C8"     // 나머지
    private const val EDGE = "#0B0609"      // 외곽선 — 이 게임 UI 의 '거의 검정'

    /**
     * 탭 한가운데에서 이만큼 아래(설계 px).
     * 실측으로 92~151 이 빈 띠다. 글자 높이를 생각해 그 띠 한가운데에 오도록 잡았다.
     */
    private const val BELOW = 88

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
    /** 창과 글자들을 한 번만 만든다. 좌표는 기기에 맞춰 [Coords] 로 환산한다. */
    private fun build(ctx: Context) {
        if (box != null) return
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val f = FrameLayout(ctx)

        val tabs = Screen.PRESET_TABS
        val cellW = Coords.len(tabs[1][0] - tabs[0][0])    // 탭 한 칸 너비(설계 117px)
        // 3글자만 받으므로 32px 이면 96px — 한 칸(117) 안에 여유 있게 들어간다.
        val size = Coords.len(32).toFloat()
        val edge = Coords.len(7).toFloat()

        backs.clear(); fronts.clear()
        // 뒤(외곽선)를 다섯 개 먼저 다 깔고 앞(속)을 올린다. 나중에 넣은 것이 위에 그려진다.
        for (pass in 0..1) {
            for (i in 0 until Boss.PRESETS) {
                val t = TextView(ctx).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = FrameLayout.LayoutParams(cellW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = Coords.x(tabs[i][0]) - cellW / 2
                    }
                }
                if (pass == 0) {
                    // ⚠️ paint 를 직접 만진다. `setTextColor` 는 매 그리기마다 다시 칠해지지만
                    //    style/strokeWidth 는 TextView 가 건드리지 않아서 한 번만 줘도 남는다.
                    t.setTextColor(Color.parseColor(EDGE))
                    t.paint.style = Paint.Style.STROKE
                    t.paint.strokeWidth = edge
                    t.paint.strokeJoin = Paint.Join.ROUND
                    backs.add(t)
                } else {
                    fronts.add(t)
                }
                f.addView(t)
            }
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
                val nm = Boss.name(i + 1)
                // 고른 탭은 금색, 나머지는 크림색 — 어느 걸 눌렀는지 색으로 갈린다.
                val onTab = (i + 1) == sel
                for ((k, t) in listOf(backs[i], fronts[i]).withIndex()) {
                    if (nm.isEmpty()) { t.visibility = View.INVISIBLE; continue }
                    t.visibility = View.VISIBLE
                    t.text = nm
                    if (k == 1) t.setTextColor(Color.parseColor(if (onTab) GOLD else CREAM))
                    val q = t.layoutParams as FrameLayout.LayoutParams
                    if (q.topMargin != top) { q.topMargin = top; t.layoutParams = q }
                }
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
