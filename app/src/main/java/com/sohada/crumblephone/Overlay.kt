package com.sohada.crumblephone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 게임 위에 떠 있는 작은 상태 알약(버블).
 * 게임이 전체 화면을 덮으므로, 관제 화면 대신 이것이 '지금 뭐 하는 중인지'와 [멈추기]를 맡는다.
 *
 * ⚠️ 오버레이는 화면 캡처에 **같이 찍히고** 그 자리의 탭도 **가로챈다**.
 *    그래서 세로 위치를 y 120~900 으로 가둔다 — 이 띠에는 판정 지점도, 봇이 누르는 자리도 하나도 없다.
 *    (판정: 아이콘열 y1100~ · 전투종료 y1600 · 로비 y2700 · 독 y2820~ / 탭: 왕관 y1864~ · 도전 y2715 · ✕ y2990)
 *    덕분에 캡처할 때마다 숨겼다 켤 필요가 없어(깜빡임 없음) 가볍다.
 */
@SuppressLint("StaticFieldLeak")
object Overlay {
    private const val Y_MIN = 120
    private const val Y_MAX = 900

    private var wm: WindowManager? = null
    private var view: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var stopBtn: TextView? = null
    private var dotView: View? = null
    private var idleTicks = 0

    /**
     * 이 시각까지는 화면을 원래 밝기로 둔다(알약을 탭하면 늘어난다).
     * 어둡게 해 두면 **알약이 유일한 조작점**이라, 만져서 되돌릴 길이 반드시 있어야 한다.
     */
    private var wakeUntil = 0L
    private const val WAKE_MS = 15_000L
    /**
     * 어두울 때의 밝기. **0(완전 최소)으로 두면 안 된다** — 알약이 안 보여서 되돌릴 수가 없다.
     * 아주 어둡되 손으로 찾을 수는 있는 값으로 둔다.
     */
    private const val DIM = 0.03f
    private val ui = Handler(Looper.getMainLooper())

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun show(ctx: Context) {
        if (view != null || !canDraw(ctx)) return
        // ★ 반드시 0 으로 되돌린다. 안 그러면 **두 번째부터는 뜨자마자 사라진다.**
        //   show() 는 콘텐츠를 시작하기 '전에' 불리므로 첫 tick 때 아직 running 이 false 다.
        //   지난 번에 쉬면서 7 까지 세어 둔 값이 남아 있으면 그 한 번으로 임계를 넘어 바로 hide 된다.
        idleTicks = 0
        wakeUntil = 0L
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 게임 위에 뜨는 것이라 게임이 밝든 어둡든 읽혀야 한다 → 관제 화면의 라이트/다크와
        // 무관하게 언제나 어두운 반투명 알약으로 둔다(iOS 의 다크 재질과 같은 결).
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#E61C1C1E"))
            cornerRadius = dp(ctx, 100).toFloat()      // 완전한 알약 모양
            // 화면을 어둡게 해 두면 이 알약이 **유일한 조작점**이다. 찾을 수 있어야 하므로
            // 옅은 테두리를 둬서 어두운 화면에서도 윤곽이 보이게 한다.
            setStroke(dp(ctx, 1), Color.parseColor("#66FFFFFF"))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = dp(ctx, 6).toFloat()
            setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#30D158"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 7), dp(ctx, 7)).apply {
                rightMargin = dp(ctx, 8)
            }
        }
        val txt = TextView(ctx).apply {
            text = "쉬는 중"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        // [멈추기] 는 **늘 보인다.** 예전엔 알약을 탭해야 나왔는데, 그러면 있는 줄도 모른다.
        // 게다가 그 탭에 밝기 깨우기까지 얹혀 있어서 한 동작이 두 가지 일을 했다.
        // 이제 버튼은 버튼대로, 나머지 영역은 '깨우기 + 드래그' 로 나눈다.
        val stop = TextView(ctx).apply {
            text = "멈추기"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF453A"))
                cornerRadius = dp(ctx, 100).toFloat()
            }
            setPadding(dp(ctx, 13), dp(ctx, 5), dp(ctx, 13), dp(ctx, 5))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { Runner.stop() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(ctx, 12) }
        }
        box.addView(dot); box.addView(txt); box.addView(stop)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ⚠️ 여기 단위는 **픽셀**이다(dp 가 아니다). 아래 Y_MIN/Y_MAX 안전 띠와 같은 자다.
            x = 40; y = 300
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        // 탭하면 [멈추기] 가 나왔다 들어간다. 끌면 위치가 옮겨진다(단, 안전한 세로 띠 안에서만).
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    p.x = startX + dx
                    p.y = (startY + dy).coerceIn(Y_MIN, Y_MAX)   // 판정·탭 자리를 절대 침범하지 않게
                    try { w.updateViewLayout(box, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 본문을 탭하면 '잠깐 깨우기'. 어둡게 해 뒀어도 이걸로 원래 밝기가 돌아온다.
                    // (오른쪽 [멈추기] 는 자기가 먼저 탭을 가져가므로 여기까지 안 온다.)
                    if (!moved) {
                        wakeUntil = System.currentTimeMillis() + WAKE_MS
                        applyBrightness()
                    }
                    true
                }
                else -> false
            }
        }

        try { w.addView(box, p) } catch (e: Exception) { Bot.log("오버레이 실패: ${e.message}"); return }
        wm = w; view = box; lp = p; label = txt; stopBtn = stop; dotView = dot
        tick()
    }

    fun hide() {
        val w = wm; val v = view
        ui.post { if (w != null && v != null) try { w.removeView(v) } catch (_: Exception) {} }
        wm = null; view = null; lp = null; label = null; stopBtn = null; dotView = null
    }

    /**
     * 화면 밝기를 지금 상태에 맞춘다.
     * 어둡게 하는 건 **봇이 도는 동안** + **설정을 켰을 때** + **최근에 탭하지 않았을 때** 뿐이다.
     * 셋 중 하나라도 아니면 원래 밝기로 되돌린다(`BRIGHTNESS_OVERRIDE_NONE`).
     */
    private fun applyBrightness() {
        val w = wm ?: return
        val v = view ?: return
        val p = lp ?: return
        val dim = Prefs.dimScreen && Runner.running && System.currentTimeMillis() >= wakeUntil
        val want = if (dim) DIM else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (p.screenBrightness == want) return
        p.screenBrightness = want
        try { w.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /** 봇이 도는 동안에는 알약이 떠 있어야 한다. 이미 떠 있으면 아무 일도 안 한다. */
    fun ensure(ctx: Context) {
        if (Runner.running) show(ctx)
    }

    /** 1초마다 글자만 갈아 끼운다. 돌고 있지 않으면 알약을 접어 둔다. */
    private fun tick() {
        val l = label ?: return
        // 무엇이 도는지(퀘스트·토벌전…)를 맨 앞에 둔다. 게임을 보는 중에는 이게 제일 궁금하다.
        val s = if (Runner.running) {
            val pct = Runner.progress
            (if (Runner.task.isNotEmpty()) Runner.task + " · " else "") +
                Runner.status +
                (if (pct >= 0) " " + pct + "%" else "") +
                (if (Runner.detail.isNotEmpty()) " · " + Runner.detail else "")
        } else "쉬는 중"
        if (l.text != s) l.text = s
        (dotView?.background as? GradientDrawable)?.setColor(
            if (Runner.running) Color.parseColor("#30D158") else Color.parseColor("#8E8E93"))
        applyBrightness()
        // 돌고 있을 때만 [멈추기] 를 보여 준다. 쉬는 중에 눌러 봐야 할 일이 없다.
        stopBtn?.visibility = if (Runner.running) View.VISIBLE else View.GONE
        if (!Runner.running) {
            // 다 끝났으면 결과를 잠깐 보여 준 뒤 알아서 사라진다(게임 화면을 가리지 않게).
            if (++idleTicks > 6) { hide(); return }
        } else idleTicks = 0
        ui.postDelayed({ tick() }, 1000)
    }
}
