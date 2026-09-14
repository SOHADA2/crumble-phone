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
 * 게임 **편성 화면의 프리셋 1~5 탭 바로 아래에 내가 붙인 이름을 덧그린다.**
 *
 * 게임에는 조합 이름이 없고 번호뿐이다. 그래서 보스에 맞춰 덱을 짜 두고도
 * "2번이 물가였나 독이었나" 를 매번 헷갈렸다. 설정에서 붙인 이름([Prefs.presetName])을
 * 바로 그 탭 아래에 놓는다.
 *
 * ## 생김새 — 사장님이 직접 그려 주신 그대로
 * "폰트도 그렇고, 획 그렇고, 뒷부분의 그림자도 이런 느낌인 거야."
 * 게임 글씨 문법이다 — **통통한 게임 글꼴 + 흰 속 + 청록 외곽선 + 오른쪽 아래 청록 그림자.**
 *
 * 안드로이드 TextView 로는 외곽선도 그림자도 한 번에 안 된다. 그래서 **같은 글자를 세 겹**으로
 * 겹쳐 그린다. 뒤에서부터:
 *   ① 그림자 — 오른쪽 아래로 밀어 놓고 `FILL_AND_STROKE` 청록 (속까지 꽉 찬 그림자)
 *   ② 외곽선 — 제자리에 `STROKE` 청록
 *   ③ 속    — 제자리에 `FILL` 흰색
 *
 * ⚠️ `onDraw` 안에서 색을 바꾸는 흔한 방법은 매 프레임 `invalidate` 를 불러 계속 다시 그린다.
 *    그래서 뷰를 세 장 겹쳐 두고 `paint.style` 만 만든 자리에서 한 번씩 준다
 *    (색은 매 그리기마다 다시 칠해지지만 style/strokeWidth 는 TextView 가 안 건드린다).
 *
 * 청록은 **게임에서 뽑은 값**이다(편성 화면 탭 색 계열 #0C7C8A).
 * 자리는 탭이 끝나는 바로 아래다 — 실측(2026-09-14): 탭 그림이 한가운데 기준 -32~+31,
 * 그 아래 22px 이 비었고 +56~+86 이 청록 구분선, +92 부터 다시 빈 줄이다.
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
 *    **쿠키 카드 위**에 얹혔다.
 */
object NameTags {

    private val ui = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    // 글자 한 개를 세 장으로 그린다 — 그림자 / 외곽선 / 속.
    private val shadows = ArrayList<TextView>()
    private val outlines = ArrayList<TextView>()
    private val fills = ArrayList<TextView>()

    @Volatile private var alive = false
    private var shownY = 0          // 지금 띄운 탭 줄의 설계 y (0 = 안 띄움)

    private const val INK = "#0C7C8A"       // 외곽선·그림자 — 게임 편성 화면의 청록 계열
    private const val ON = "#FFFFFF"        // 고른 조합
    private const val OFF = "#B7D3D7"       // 나머지 — 같은 글씨체, 한 톤 죽인 색

    /** 탭 한가운데에서 이만큼 아래(설계 px). 탭 그림이 +31 에서 끝난다. */
    private const val BELOW = 32
    /**
     * 글자 크기(설계 px).
     *
     * ⚠️ **36 은 넘쳤다.** 게임 글꼴로 '연타덱' 3글자를 재 보니 119.9px 인데 탭 한 칸은 117px 이라,
     *    실제로 "연타" 까지만 나오고 끝 글자가 잘렸다(사장님 지적: "모두 3줄일 때 잘 들어가도록").
     *    실측 폭 — 26px:86.6 / 28px:93.2 / 30px:99.9 / 32px:106.6 / 34px:113.2 / 36px:119.9
     *    28 로 잡으면 양옆에 12px 씩 남아, 다섯 칸이 다 3글자여도 서로 안 붙는다.
     */
    private const val SIZE = 28
    private const val EDGE = 4              // 외곽선 굵기 — 글자를 줄인 만큼 같이 줄였다
    private const val DROP_X = 2            // 그림자 오른쪽으로
    private const val DROP_Y = 3            // 그림자 아래로

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
        val size = Coords.len(SIZE).toFloat()
        val edge = Coords.len(EDGE).toFloat()
        val face = GameFont.black(ctx)

        shadows.clear(); outlines.clear(); fills.clear()
        // 그림자 → 외곽선 → 속 순서로 넣는다. 나중에 넣은 것이 위에 그려진다.
        for (layer in 0..2) {
            for (i in 0 until Boss.PRESETS) {
                val t = TextView(ctx).apply {
                    typeface = face
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = FrameLayout.LayoutParams(cellW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = Coords.x(tabs[i][0]) - cellW / 2 +
                            (if (layer == 0) Coords.len(DROP_X) else 0)
                    }
                }
                when (layer) {
                    0 -> {  // 그림자 — 속까지 꽉 찬 청록을 오른쪽 아래로
                        t.setTextColor(Color.parseColor(INK))
                        t.paint.style = Paint.Style.FILL_AND_STROKE
                        t.paint.strokeWidth = edge
                        t.paint.strokeJoin = Paint.Join.ROUND
                        shadows.add(t)
                    }
                    1 -> {  // 외곽선
                        t.setTextColor(Color.parseColor(INK))
                        t.paint.style = Paint.Style.STROKE
                        t.paint.strokeWidth = edge
                        t.paint.strokeJoin = Paint.Join.ROUND
                        outlines.add(t)
                    }
                    else -> fills.add(t)
                }
                f.addView(t)
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // ⚠️ 세 가지가 다 있어야 한다.
        //    NOT_TOUCHABLE  — 탭 바로 아래 창이라, 터치를 먹으면 사장님 탭도 봇 탭도 이 창이 가져간다.
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
            val drop = Coords.len(DROP_Y)
            for (i in 0 until Boss.PRESETS) {
                val nm = Boss.name(i + 1)
                val onTab = (i + 1) == sel
                for ((layer, t) in listOf(shadows[i], outlines[i], fills[i]).withIndex()) {
                    if (nm.isEmpty()) { t.visibility = View.INVISIBLE; continue }
                    t.visibility = View.VISIBLE
                    t.text = nm
                    // 고른 것은 흰색, 나머지는 한 톤 죽인 색 — 글씨체는 같게 둔다.
                    if (layer == 2) t.setTextColor(Color.parseColor(if (onTab) ON else OFF))
                    val want = if (layer == 0) top + drop else top
                    val q = t.layoutParams as FrameLayout.LayoutParams
                    if (q.topMargin != want) { q.topMargin = want; t.layoutParams = q }
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
