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
    private val contentBtns = ArrayList<View>()
    private var stripView: View? = null      // 알약 왼쪽 콘텐츠 색 띠
    private var titleView: TextView? = null  // 첫 줄(무엇이 · 요약)
    private var textsBox: LinearLayout? = null
    private var shownTitle = ""
    private var bossLine: TextView? = null

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

    // ── 시안 B 색(2026-10-16) — 관제 화면 `Theme` 과 같은 값. 알약은 Context 만 있어 여기 따로 둔다. ──
    private val C_PANEL = Color.parseColor("#EE231A18")   // 어두운 갈색 반투명(게임이 살짝 비친다)
    private val C_EDGE = Color.parseColor("#6B4F42")
    private val C_ROW = Color.parseColor("#382A27")
    private val C_ROW_EDGE = Color.parseColor("#4A3A35")
    private val C_TITLE = Color.parseColor("#F2E4D5")
    private val C_CREAM = Color.parseColor("#E8D9C8")
    private val C_SUB = Color.parseColor("#BCA491")
    private val C_MUTED = Color.parseColor("#8C7266")
    private val C_GOLD = Color.parseColor("#FFC94A")
    private val C_ORANGE = Color.parseColor("#F58F0A")
    private val C_RED = Color.parseColor("#E0584F")
    private val C_DOT_IDLE = Color.parseColor("#6B5A52")
    private val C_BTN_EDGE = Color.parseColor("#8A6A52")

    private fun round(color: Int, r: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** 패널 맨 아래 한 줄에 들어가는 작은 버튼. 셋이 자리를 똑같이 나눠 갖는다. */
    private fun smallBtn(ctx: Context, label: String, color: Int, onTap: () -> Unit) =
        TextView(ctx).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true          // 좁아도 꺾이지 않게
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 4), dp(ctx, 7), dp(ctx, 4), dp(ctx, 7))
            background = round(Color.parseColor("#2A2422"), dp(ctx, 8).toFloat(), Math.max(1, dp(ctx, 1)), Color.parseColor("#3A302D"))
            isClickable = true
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(ctx, 2); rightMargin = dp(ctx, 2) }
        }

    /** 관제 화면에서 부르는 '띄워 줘'. 사용자가 숨겨 뒀더라도 다시 띄운다. */
    fun show(ctx: Context) { dismissed = false; create(ctx) }

    private fun create(ctx: Context) {
        if (view != null || !canDraw(ctx)) return
        wakeUntil = 0L
        appCtx = ctx.applicationContext
        dpf = ctx.resources.displayMetrics.density
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sans = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)

        // ── 알약 — 시안 B(2026-10-16): 어두운 갈색 반투명 + 둥근 모서리 + 얇은 갈색 테두리.
        //    도는 동안엔 왼쪽에 **콘텐츠 색 띠**가 붙는다(관제 화면 줄과 같은 말).
        //    띠는 네모로 두고 알약이 clipToOutline 으로 둥글게 잘라 낸다(모서리 밖으로 안 삐져나오게).
        val pr = dp(ctx, 14).toFloat()
        val pill = FrameLayout(ctx).apply {
            background = round(C_PANEL, pr)
            foreground = round(Color.TRANSPARENT, pr, Math.max(1, dp(ctx, 1)), C_EDGE)
            clipToOutline = true
            elevation = dp(ctx, 6).toFloat()
        }
        val strip = StripView(ctx, C_ORANGE).apply { visibility = View.GONE }   // ⚠️ 평범한 View 면 알약이 화면만 해진다(StripView 설명)
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 13), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }
        pill.addView(inner)
        pill.addView(strip, FrameLayout.LayoutParams(dp(ctx, 4), FrameLayout.LayoutParams.MATCH_PARENT))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 알약과 펼침 패널을 세로로 담는 뿌리. 바탕은 투명이라 알약 모양이 그대로 보인다.
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_DOT_IDLE) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 7), dp(ctx, 7)).apply {
                rightMargin = dp(ctx, 7)
            }
        }
        // 첫 줄: 무엇이 도는지(콘텐츠 색) + 요약(금색, 예: 21판 · 293.92G). 쉬는 중이면 '쉬는 중'.
        val ttl = TextView(ctx).apply {
            text = "쉬는 중"; setTextColor(C_CREAM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
            includeFontPadding = false
        }
        // 둘째 줄: 지금 하는 일. 길면 아래 `flow()` 가 왼쪽으로 흘려 보낸다(자르지 않는다).
        val txt = TextView(ctx).apply {
            setTextColor(C_SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 120), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 4); leftMargin = dp(ctx, 14) }
        }
        // [멈추기] 는 도는 동안 **늘 보인다.** 예전엔 알약을 탭해야 나왔는데, 그러면 있는 줄도 모른다.
        // 나머지 영역은 '펼치기 + 깨우기 + 드래그' 로 나눈다.
        val stop = TextView(ctx).apply {
            text = "■ 멈추기"
            setTextColor(C_CREAM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = sans
            background = round(Color.TRANSPARENT, dp(ctx, 8).toFloat(), Math.max(1, dp(ctx, 1)), C_BTN_EDGE)
            setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4))
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            setOnClickListener { Runner.stop() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(ctx, 10) }
        }
        // 펼침 표시. 열고 닫는 건 알약 전체가 받는다 → 여기는 '지금 열렸나' 를 알리는 표시일 뿐이다.
        // ⚠️ clickable 로 두면 자기 자리 탭을 가로채 알약 전체 판정과 어긋난다.
        val arw = TextView(ctx).apply {
            text = "▾"
            setTextColor(C_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(ctx, 8), 0, dp(ctx, 2), 0)
            isClickable = false
        }
        val titleBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot); addView(ttl)
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleBox); addView(host)
        }
        row.addView(texts); row.addView(stop); row.addView(arw)
        // 진행률 막대 — 얇은 주황(관제 화면과 같은 막대). 분모가 있을 때만 보인다.
        val fillView = View(ctx).apply {
            background = round(C_ORANGE, dp(ctx, 2).toFloat())
            pivotX = 0f
            scaleX = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val track = FrameLayout(ctx).apply {
            background = round(Color.parseColor("#3A2E2B"), dp(ctx, 2).toFloat())
            addView(fillView)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4)).apply { topMargin = dp(ctx, 7) }
        }
        inner.addView(row); inner.addView(track)

        // ── 펼침 패널: 여기서 바로 콘텐츠를 시작할 수 있다 ──
        // 게임을 보는 중에 관제 화면으로 나갔다 오지 않아도 되게.
        // 시안 B: 큰 그라데이션 버튼 대신 **콘텐츠 색 띠 + 아이콘 줄**(관제 화면 목록과 같은 말).
        val pnl = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.parseColor("#F2231A18"), pr, Math.max(1, dp(ctx, 1)), C_EDGE)
            elevation = dp(ctx, 6).toFloat()
            setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9))
            visibility = View.GONE
            // 폭을 못박는다 — WRAP_CONTENT 면 '보스 조합' 같은 긴 값이 패널을 늘린다.
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 232), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 8) }
        }
        contentBtns.clear()
        val items = listOf<Pair<Tint.C, (Context) -> Unit>>(
            Tint.QUEST to { c -> Chores.start(c) },
            Tint.STAGE to { c -> Chores.start(c, stage = true) },
            Tint.TOBOL to { c -> Runner.startTobol(c) },
            Tint.DAILY to { c -> Daily.start(c) },
            Tint.ARENA to { c -> Arena.start(c) },
            Tint.REWARD to { c -> Chores.startRewardsOnly(c) }
        )
        val rr = dp(ctx, 10).toFloat()
        for ((tc, go) in items) {
            val item = FrameLayout(ctx).apply {
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFE9C9")),
                    round(C_ROW, rr), round(Color.WHITE, rr))
                foreground = round(Color.TRANSPARENT, rr, Math.max(1, dp(ctx, 1)), C_ROW_EDGE)
                clipToOutline = true
                isClickable = true
                setOnClickListener {
                    val c = appCtx ?: return@setOnClickListener
                    closePanel()
                    // 알약은 창(Activity)이 아니라서 화면 읽기 동의 창을 띄울 수 없다.
                    // 꺼져 있으면 앱을 열어 준다 — 거기서 누르면 그 자리에서 물어본다.
                    if (CaptureService.instance == null) {
                        Runner.set("화면 읽기가 꺼져 있어요", "앱에서 다시 눌러 주세요")
                        c.startActivity(android.content.Intent(c, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@setOnClickListener
                    }
                    go(c)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 6) }
            }
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 13), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
                addView(IconView(ctx, tc.icon, tc.color),
                    LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16)).apply { rightMargin = dp(ctx, 10) })
                addView(TextView(ctx).apply {
                    text = tc.name
                    setTextColor(C_TITLE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            // [스테이지 돌파] 줄 오른쪽에 **어느 조합으로 도전할지**를 붙인다.
            // 게임을 보는 중에는 설정 화면까지 들어가지 않는다 — 여기서 바로 확인돼야 한다.
            if (tc === Tint.STAGE) {
                bossLine = TextView(ctx).apply {
                    setTextColor(C_MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = dp(ctx, 90)
                }
                line.addView(bossLine)
            }
            item.addView(line)
            item.addView(StripView(ctx, tc.color),   // ⚠️ 평범한 View 면 이 줄이 화면을 다 먹는다
                FrameLayout.LayoutParams(dp(ctx, 4), FrameLayout.LayoutParams.MATCH_PARENT))
            contentBtns.add(item)
            pnl.addView(item)
        }
        // ── 맨 아래 한 줄 ── 게임을 보는 중에도 손이 가는 것들.
        //
        // 앱을 한 번 켜고 나면 사람은 게임 화면만 본다. 그래서 관제 화면에만 있는 기능은
        // 사실상 없는 것과 같다 — 자주 쓰는 것은 여기까지 내려와 있어야 한다.
        //
        // ⚠️ 세로로 더 쌓지 않고 **한 줄에 셋**을 넣는다. 패널이 길어지면 알약이
        //    y120~900 띠를 넘어 내려앉아, 게임 판정 지점과 봇이 누르는 자리를 덮는다.
        pnl.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 2) }

            // 설정 — 오븐 개수·아레나 판수처럼 게임을 보다가 고치고 싶어지는 값들이 있다.
            addView(smallBtn(ctx, "설정", C_SUB) {
                val c = appCtx ?: return@smallBtn
                closePanel()
                c.startActivity(android.content.Intent(c, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            addView(smallBtn(ctx, "숨기기", C_SUB) { dismissed = true; hide() })
            // 끄기 — 다 하고 나서 '이제 그만' 할 자리가 알약에 없으면 앱을 찾아 들어가야 했다.
            addView(smallBtn(ctx, "앱 끄기", C_RED) {
                val c = appCtx ?: return@smallBtn
                closePanel()
                Runner.stop()
                CaptureService.stop(c)
                dismissed = true
                hide()
            })
        })

        box.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(pnl)
        stripView = strip; titleView = ttl; textsBox = texts

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
                    // 알약 **어디를 탭해도** 펼침 패널이 여닫힌다(작은 ▾ 를 겨냥하지 않아도 된다).
                    // 겸사겸사 '잠깐 깨우기' 도 같이 한다 — 만졌다는 건 볼 생각이라는 뜻이다.
                    // (오른쪽 [멈추기] 는 자기가 먼저 탭을 가져가므로 여기까지 안 온다.)
                    if (!moved) {
                        togglePanel()
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
            ctx.resources.displayMetrics.widthPixels - dp(ctx, 190))
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
        stripView = null; titleView = null; textsBox = null; shownTitle = ""
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

    /**
     * **0.4초마다** 글자만 갈아 끼운다. 돌고 있지 않으면 알약을 접어 둔다.
     *
     * 예전엔 1초였다. 읽는 값이 전부 메모리에 있어(캡처도 adb 도 안 부른다) 비용이 사실상 없는데,
     * 사장님이 *"현황이 실제 하는 것보다 딜레이가 있어 신뢰도가 떨어진다"* 고 하셨다 —
     * 눈에 보이는 지연 1초는 그냥 깎는 게 맞다.
     */
    private fun tick() {
        if (label == null) return
        val running = Runner.running
        val stopping = Runner.stopping
        if (running && Runner.task.isNotEmpty()) Runner.lastTask = Runner.task
        val tc = if (running || stopping) Tint.of(Runner.task) else null

        // ── 첫 줄: 무엇이 도는지(콘텐츠 색, 굵게) + 요약(금색) ──
        // 토벌전은 '21판 · 293.92G' 처럼 판 수와 이번 점수, 나머지는 진행률이 있으면 그 %.
        val pct = Runner.progress
        val name = when {
            running -> if (Runner.task.isEmpty()) "봇" else Runner.task
            stopping -> "멈추는 중"
            else -> "쉬는 중"
        }
        val summary = if (!running) "" else if (Runner.task == "토벌전" && Runner.tobolAttempts > 0)
            Runner.tobolAttempts.toString() + "판" +
                (if (Runner.lastScore > 0) " · " + Runner.fmtShort(Runner.lastScore) else "")
        else if (pct >= 0) "$pct%" else ""
        val key = name + "|" + summary + "|" + (tc?.color ?: 0)
        if (key != shownTitle) {
            shownTitle = key
            val sb = android.text.SpannableStringBuilder(name)
            sb.setSpan(android.text.style.ForegroundColorSpan(if (running && tc != null) tc.color else C_CREAM),
                0, name.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (summary.isNotEmpty()) {
                val st = sb.length + 2
                sb.append("  ").append(summary)
                sb.setSpan(android.text.style.ForegroundColorSpan(C_GOLD), st, sb.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            titleView?.text = sb
        }
        stripView?.visibility = if (tc != null) View.VISIBLE else View.GONE
        if (tc != null) stripView?.setBackgroundColor(tc.color)

        // ── 둘째 줄: 지금 하는 일 ── 쉬는 중엔 숨긴다(첫 줄이 다 말한다).
        // "토벌전 · 토벌전 3회차" 처럼 같은 말이 겹치면 앞을 뺀다(알약은 자리가 귀하다).
        val s = if (running) {
            val st = Runner.status
            (if (Runner.task.isNotEmpty() && st.startsWith(Runner.task)) st.substring(Runner.task.length).trimStart(' ', '·') else st)
                .ifEmpty { st } +
                (if (Runner.detail.isNotEmpty()) " · " + Runner.detail else "")
        } else if (stopping) "하던 동작을 마치는 중" else ""
        // **몇 초째인지 붙인다.** 대부분의 상태는 1~2초면 바뀌므로, 8초를 넘겼다는 건
        // '늦은 것' 이 아니라 **아직 그 일을 하고 있다**는 뜻이다(오븐·보스전처럼).
        // 멈춘 건지 오래 걸리는 건지가 이 숫자 하나로 갈린다.
        val age = if (running && Runner.statusAt > 0)
            (System.currentTimeMillis() - Runner.statusAt) / 1000 else 0
        scrollHost?.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE
        if (s.isNotEmpty()) flow(if (age >= 8) "$s · ${age}초째" else s)
        (dotView?.background as? GradientDrawable)?.setColor(
            if (running) C_ORANGE
            else if (stopping) C_SUB
            else C_DOT_IDLE)
        barTrack?.visibility = if (running && pct >= 0) View.VISIBLE else View.GONE
        if (running && pct >= 0) barFill?.animate()?.scaleX(pct / 100f)?.setDuration(280)?.start()
        applyBrightness()
        // 설정에서 조합 순서를 바꾸면 다음 tick 에 그대로 따라온다(따로 알릴 필요가 없다).
        bossLine?.text = Boss.orderShort()
        // 돌고 있을 때만 [멈추기] 를 보여 준다. 쉬는 중에 눌러 봐야 할 일이 없다.
        stopBtn?.visibility = if (Runner.running) View.VISIBLE else View.GONE

        // ⚠️ 도는 동안에는 펼침 패널을 반드시 접는다.
        //    알약은 **화면 캡처에 같이 찍히고 그 자리 탭도 가로챈다.** 알약 자체는 안전한 세로 띠
        //    (y 120~900) 안에 갇혀 있지만, 패널까지 펼치면 그 아래 판정 구역(아이콘열 y1100~)을
        //    침범한다. 그래서 시작하는 순간 접고, 도는 동안 다시 열리지 않게 한다.
        if (Runner.running) closePanel()
        // 멈추는 중에도 잠가 둔다 — 여기서 새로 시작하면 일꾼이 둘이 된다(guard 도 막지만,
        // 눌리는데 아무 일도 안 일어나는 버튼보다 잠긴 버튼이 낫다).
        val busy = Runner.running || Runner.stopping
        for (b in contentBtns) {
            b.isEnabled = !busy
            b.alpha = if (busy) 0.45f else 1f
        }
        // 쉬는 중에도 사라지지 않는다 — 이 알약이 콘텐츠를 시작하는 조작 패널이기 때문이다.
        ui.postDelayed({ tick() }, 400)
    }
}
