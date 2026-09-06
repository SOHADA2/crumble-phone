package com.sohada.crumblephone

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
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
    private var arrow: TextView? = null
    private var scrollHost: FrameLayout? = null
    private var barTrack: View? = null
    private var barFill: View? = null
    private var ticker: ValueAnimator? = null
    private var shownText = ""
    private var panel: LinearLayout? = null
    private var appCtx: Context? = null
    private val contentBtns = ArrayList<TextView>()

    /**
     * 사용자가 [알약 숨기기] 로 직접 지웠나. 지웠으면 1초 뒤 `ensure` 가 도로 띄우면 안 된다.
     * 관제 화면에서 `show()` 를 부르면 다시 풀린다(그건 명시적인 '띄워 줘' 요청이다).
     */
    private var dismissed = false

    /**
     * 봇 앱 화면이 앞에 나와 있나. 앞에 있으면 알약을 접는다 —
     * 관제 화면 제목 위에 알약이 겹쳐 앉아 있으면 볼썽사납고, 그 자리에 알약이 있을 이유도 없다
     * (알약은 **게임을 보는 중에** 쓰라고 있는 것이다).
     */
    @Volatile private var appForeground = false

    fun onAppForeground(v: Boolean) {
        appForeground = v
        if (v) hide()
    }

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

    /** 두 벌 사이에 넣는 사이. 이게 없으면 끝 글자와 첫 글자가 붙어 한 단어처럼 읽힌다. */
    private const val GAP = "     ·     "
    private var maxTextW = 0
    private var dpf = 1f

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** 관제 화면에서 부르는 '띄워 줘'. 사용자가 숨겨 뒀더라도 다시 띄운다. */
    fun show(ctx: Context) { dismissed = false; create(ctx) }

    private fun create(ctx: Context) {
        if (view != null || !canDraw(ctx)) return
        wakeUntil = 0L
        appCtx = ctx.applicationContext
        dpf = ctx.resources.displayMetrics.density
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // (Theme 은 관제 화면용이다. 알약은 게임 UI 를 직접 본떠 색을 여기서 쓴다.)

        // 게임의 **'스테이지 바' 판**을 그대로 본떴다(사용자 요청).
        // 실측: 어두운 판 (44,42,47)~(62,50,54) · 외곽선은 거의 검정 (0,0,12).
        // 게임 UI 는 테두리가 **얇은 색선이 아니라 두꺼운 검정**이라 만화처럼 보인다.
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#F02E272C"))
            cornerRadius = dp(ctx, 18).toFloat()
            setStroke(dp(ctx, 2), Color.parseColor("#0B0609"))
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            elevation = dp(ctx, 6).toFloat()
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        }
        // 게임의 스테이지 바처럼 — 검은 홈 위에 금색 막대가 차오른다.
        // 실측 단면: 검정 외곽선 → (255,217,44) → (249,193,20) → (254,173,4) → (106,68,6) → 검정.
        val fillView = View(ctx).apply {
            background = GradientDrawable().apply {
                colors = intArrayOf(
                    Color.parseColor("#FFD92C"), Color.parseColor("#F9C114"),
                    Color.parseColor("#FEAD04"), Color.parseColor("#8A5605"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = dp(ctx, 5).toFloat()
            }
            pivotX = 0f
            scaleX = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val track = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15100F"))
                cornerRadius = dp(ctx, 6).toFloat()
                setStroke(dp(ctx, 2), Color.parseColor("#0B0609"))
            }
            addView(fillView)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 9)).apply {
                leftMargin = dp(ctx, 2); rightMargin = dp(ctx, 2); bottomMargin = dp(ctx, 5)
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 알약과 펼침 패널을 세로로 담는 뿌리. 바탕은 투명이라 알약 모양이 그대로 보인다.
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#7CC24A"))
                setStroke(dp(ctx, 2), Color.parseColor("#0B0609"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply {
                rightMargin = dp(ctx, 7)
            }
        }
        val txt = TextView(ctx).apply {
            // 게임의 '스테이지 104-19' 글자와 같은 밝은 금색(실측 (248,240,97)).
            text = "쉬는 중"; setTextColor(Color.parseColor("#F8E861"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            // 자르지 않는다. 넘치면 아래 `flow()` 가 왼쪽으로 흘려 보낸다.
            isSingleLine = true
            includeFontPadding = false
        }
        // 글자를 담아 **잘라 내는 창**. 이 안에서 글자판이 왼쪽으로 미끄러진다.
        // ⚠️ 창이 WRAP_CONTENT 라 글자가 길면 알약이 화면 밖까지 커지고
        //    **오른쪽 끝의 [멈추기] 가 잘려 나간다**(빨간 조각만 보였다).
        //    그래서 이 창의 너비에 상한을 두어 버튼 자리를 먼저 확보한다.
        val host = FrameLayout(ctx).apply {
            clipChildren = true
            addView(txt, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 120), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // [멈추기] 는 **늘 보인다.** 예전엔 알약을 탭해야 나왔는데, 그러면 있는 줄도 모른다.
        // 게다가 그 탭에 밝기 깨우기까지 얹혀 있어서 한 동작이 두 가지 일을 했다.
        // 이제 버튼은 버튼대로, 나머지 영역은 '깨우기 + 드래그' 로 나눈다.
        val stop = TextView(ctx).apply {
            text = "멈추기"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            // 게임 버튼처럼 두툼하게 — 짙은 외곽선 + 위가 밝은 그라데이션.
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#F06A58"), Color.parseColor("#E8503C"))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = dp(ctx, 100).toFloat()
                setStroke(dp(ctx, 2), Color.parseColor("#0B0609"))
            }
            setPadding(dp(ctx, 11), dp(ctx, 4), dp(ctx, 11), dp(ctx, 4))
            // 글자가 아무리 길어도 이 버튼은 절대 줄어들지 않는다.
            minWidth = dp(ctx, 52)
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            setOnClickListener { Runner.stop() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(ctx, 9) }
            // 남는 자리를 글자에게 뺏기지 않게 한다.
            (layoutParams as LinearLayout.LayoutParams).weight = 0f
        }
        // 펼침 화살표. 눌러서 콘텐츠 버튼들을 여닫는다.
        // ⚠️ 스스로 clickable 이어야 한다 — 안 그러면 알약의 드래그 리스너가 탭을 먼저 먹는다.
        val arw = TextView(ctx).apply {
            text = "▾"
            setTextColor(Color.parseColor("#F8E861"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2))
            isClickable = true
            setOnClickListener { togglePanel() }
        }
        row.addView(dot); row.addView(host); row.addView(stop); row.addView(arw)
        pill.addView(track); pill.addView(row)

        // ── 펼침 패널: 여기서 바로 콘텐츠를 시작할 수 있다 ──
        // 게임을 보는 중에 관제 화면으로 나갔다 오지 않아도 되게.
        val pnl = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F02E272C"))
                cornerRadius = dp(ctx, 16).toFloat()
                setStroke(dp(ctx, 2), Color.parseColor("#0B0609"))
            }
            elevation = dp(ctx, 6).toFloat()
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        }
        contentBtns.clear()
        val items = listOf<Pair<String, (Context) -> Unit>>(
            "퀘스트" to { c -> Chores.start(c) },
            "보스전" to { c -> Boss.start(c) },
            "토벌전" to { c -> Runner.startTobol(c) },
            "일일 던전" to { c -> Daily.start(c) },
            "아레나" to { c -> Arena.start(c) },
            "일일 보상" to { c -> Chores.startRewardsOnly(c) }
        )
        for ((name, go) in items) {
            val b2 = TextView(ctx).apply {
                text = name
                // 게임의 '자동 소환 예약' 알약처럼 — 어두운 판 + 검은 외곽선 + 금색 글자.
                setTextColor(Color.parseColor("#F8E861"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    colors = intArrayOf(Color.parseColor("#4A4048"), Color.parseColor("#332C32"))
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    cornerRadius = dp(ctx, 14).toFloat()
                    setStroke(dp(ctx, 2), Color.parseColor("#0B0609"))
                }
                setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
                minWidth = dp(ctx, 112)
                isClickable = true
                setOnClickListener {
                    val c = appCtx ?: return@setOnClickListener
                    closePanel()
                    go(c)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }
            contentBtns.add(b2)
            pnl.addView(b2)
        }
        pnl.addView(TextView(ctx).apply {
            text = "알약 숨기기"
            setTextColor(Color.parseColor("#C8B79F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 4))
            isClickable = true
            setOnClickListener { dismissed = true; hide() }
        })

        box.addView(pill); box.addView(pnl)

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
        pill.setOnTouchListener { _, e ->
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
                        // 진행률 막대 — 분모가 있는 작업일 때만 보여 준다(없으면 -1).
        val pct = Runner.progress
        barTrack?.visibility = if (Runner.running && pct >= 0) View.VISIBLE else View.GONE
        if (Runner.running && pct >= 0) barFill?.animate()?.scaleX(pct / 100f)?.setDuration(280)?.start()

        applyBrightness()
                    }
                    true
                }
                else -> false
            }
        }

        try { w.addView(box, p) } catch (e: Exception) { Bot.log("오버레이 실패: ${e.message}"); return }
        wm = w; view = box; lp = p; label = txt; stopBtn = stop; dotView = dot
        arrow = arw; panel = pnl; scrollHost = host
        barTrack = track; barFill = fillView
        maxTextW = Math.max(dp(ctx, 120),
            ctx.resources.displayMetrics.widthPixels - dp(ctx, 168))
        shownText = ""
        tick()
    }

    fun hide() {
        val w = wm; val v = view
        ui.post { if (w != null && v != null) try { w.removeView(v) } catch (_: Exception) {} }
        wm = null; view = null; lp = null; label = null; stopBtn = null; dotView = null
        arrow = null; panel = null; contentBtns.clear()
        ticker?.cancel(); ticker = null; scrollHost = null; shownText = ""
        barTrack = null; barFill = null
    }

    /**
     * 글자가 창보다 길면 **끊기지 않고 계속 흐르게** 한다.
     *
     * 안드로이드 marquee 는 쓰지 않는다 — 끝까지 갔다가 **되돌아 튀는** 방식이라 순환이 아니고,
     * 1초마다 글자를 갈아 끼우면 그때마다 처음부터 다시 시작해 툭툭 끊긴다(실제로 그렇게 보였다).
     *
     * 대신 **같은 글을 두 벌 이어 붙여** 놓고 한 벌 너비만큼 왼쪽으로 민다.
     * 한 벌을 다 밀면 화면에 보이는 그림이 처음과 똑같아지므로 **이음매가 안 보인다.**
     * 글자가 바뀌어도 흐르던 진행률을 그대로 물려받아(`currentPlayTime`) 튀지 않는다.
     */
    private fun flow(s: String) {
        val l = label ?: return
        val host = scrollHost ?: return
        if (s == shownText) return
        shownText = s

        val frac = ticker?.let { if (it.duration > 0) it.animatedFraction else 0f } ?: 0f
        ticker?.cancel(); ticker = null
        l.translationX = 0f

        val one = l.paint.measureText(s + GAP)
        val plain = l.paint.measureText(s)
        val boxW = Math.min(Math.ceil(plain.toDouble()).toInt() + 2, maxTextW)
        (host.layoutParams as LinearLayout.LayoutParams).width = boxW
        host.requestLayout()

        if (plain <= boxW) {                    // 다 들어간다 — 흐를 필요가 없다
            l.text = s
            l.layoutParams.width = FrameLayout.LayoutParams.WRAP_CONTENT
            return
        }

        l.text = s + GAP + s + GAP              // 두 벌
        l.layoutParams.width = Math.ceil((one * 2).toDouble()).toInt()
        l.requestLayout()

        val a = ValueAnimator.ofFloat(0f, -one)
        // 초당 60dp 로 흐른다. 너무 빠르면 못 읽고 너무 느리면 안 움직이는 것 같다.
        a.duration = (one / dpf * 1000f / 60f).toLong().coerceIn(4000L, 40000L)
        a.interpolator = LinearInterpolator()
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.RESTART
        a.addUpdateListener { label?.translationX = it.animatedValue as Float }
        a.start()
        a.currentPlayTime = (a.duration * frac).toLong()
        ticker = a
    }

    private fun togglePanel() {
        val pnl = panel ?: return
        if (pnl.visibility == View.VISIBLE) closePanel()
        else { pnl.visibility = View.VISIBLE; arrow?.text = "▴" }
    }

    private fun closePanel() {
        panel?.visibility = View.GONE
        arrow?.text = "▾"
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

    /**
     * 화면 읽기가 켜져 있는 동안에는 알약이 떠 있어야 한다. 이미 떠 있으면 아무 일도 안 한다.
     *
     * 예전엔 `Runner.running` 일 때만 띄웠고, 쉬는 중이면 6초 뒤 스스로 사라졌다.
     * 알약이 **여기서 콘텐츠를 시작하는 조작 패널**이 된 뒤로는 그러면 안 된다 —
     * 쉬는 중에 쓰라고 있는 것인데 그때만 사라졌다. 지우고 싶으면 패널의 [알약 숨기기] 로 지운다.
     */
    fun ensure(ctx: Context) { if (!dismissed && !appForeground) create(ctx) }

    /** 1초마다 글자만 갈아 끼운다. 돌고 있지 않으면 알약을 접어 둔다. */
    private fun tick() {
        if (label == null) return
        // 무엇이 도는지(퀘스트·토벌전…)를 맨 앞에 둔다. 게임을 보는 중에는 이게 제일 궁금하다.
        val s = if (Runner.running) {
            val pct = Runner.progress
            // "퀘스트 · 퀘스트 기다리는 중" 처럼 같은 말이 겹치면 앞을 뺀다(알약은 자리가 귀하다).
            (if (Runner.task.isNotEmpty() && !Runner.status.startsWith(Runner.task)) Runner.task + " · " else "") +
                Runner.status +
                (if (pct >= 0) " " + pct + "%" else "") +
                (if (Runner.detail.isNotEmpty()) " · " + Runner.detail else "")
        } else "쉬는 중"
        flow(s)
        (dotView?.background as? GradientDrawable)?.setColor(
            if (Runner.running) Color.parseColor("#7CC24A") else Color.parseColor("#8A7565"))
        applyBrightness()
        // 돌고 있을 때만 [멈추기] 를 보여 준다. 쉬는 중에 눌러 봐야 할 일이 없다.
        stopBtn?.visibility = if (Runner.running) View.VISIBLE else View.GONE

        // ⚠️ 도는 동안에는 펼침 패널을 반드시 접는다.
        //    알약은 **화면 캡처에 같이 찍히고 그 자리 탭도 가로챈다.** 알약 자체는 안전한 세로 띠
        //    (y 120~900) 안에 갇혀 있지만, 패널까지 펼치면 그 아래 판정 구역(아이콘열 y1100~)을
        //    침범한다. 그래서 시작하는 순간 접고, 도는 동안 다시 열리지 않게 한다.
        if (Runner.running) closePanel()
        for (b in contentBtns) {
            b.isEnabled = !Runner.running
            b.alpha = if (Runner.running) 0.45f else 1f
        }
        // 쉬는 중에도 사라지지 않는다 — 이 알약이 콘텐츠를 시작하는 조작 패널이기 때문이다.
        ui.postDelayed({ tick() }, 1000)
    }
}
