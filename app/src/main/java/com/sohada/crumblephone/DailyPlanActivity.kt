package com.sohada.crumblephone

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat

/**
 * ══ 일일 던전 — 던전별 컨트롤 편집 (2026-10-01 · 2차: 방향키 + 시간표) ══
 *
 * 1차는 설정 안의 작은 창에 글(`1 아래 2`)을 직접 치는 방식이었다. 사장님: *"알아먹기 힘든데 더 쉽게 해놓을 수
 * 있나? 방향키 같은 걸로 표시한다던지 (시간대랑)."* → PC 관제창과 같은 모양으로 바꿨다.
 *
 *  · **방향키판**(3x3) — 누르면 동작이 하나 붙는다(앞 동작이 끝나고 1초 뒤 · 1초 동안)
 *  · **동작 목록** — 줄마다 [-] 시각 [+] · 화살표(누르면 8방향 고르기) · [-] 길이 [+] · [✕]
 *  · **시간표** — 몇 초에 무엇을 하는지 막대와 화살표로. 반복 지점은 점선.
 *
 * ★ 저장은 1차와 **같은 글 형식**([DailyPlan.toText]) — 엔진([Daily.runPlan])·PC 형식은 그대로다.
 * ★ 화살표는 **그려서** 보여 준다([ArrowView]) — 글꼴마다 화살표 글자가 다르게 생겼거나 없다.
 * ★ 값은 [-][+] 로만(0.5초 눈금). PC 에서 `[Math]::Min(10, 1.5) = 2` 로 [+] 가 1씩 오르던 함정이 있었는데,
 *   코틀린은 타입이 정해져 있어 같은 일이 안 생긴다(그래도 상·하한은 Double 로 적었다).
 * 좁은 화면이라 설정 안의 작은 창이 아니라 **전용 화면**으로 띄운다(덱 구성과 같은 방식).
 */
class DailyPlanActivity : ListActivity() {

    /** 고칠 수 있는 동작 하나. [DailyPlan.Step] 은 읽기 전용이라 편집용을 따로 둔다. */
    class E(var at: Double, var dir: String, var dur: Double)

    private val names = DailyPlan.DUNGEONS
    private val model = HashMap<String, MutableList<E>>()
    private val repeat = HashMap<String, Double>()
    private var cur = names[0]
    private var dirty = false

    private lateinit var tabRow: LinearLayout
    private lateinit var timeline: PlanTimeline
    private lateinit var rows: LinearLayout
    private lateinit var emptyNote: TextView
    private lateinit var warn: TextView
    private lateinit var repText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        window.statusBarColor = t.bg
        window.navigationBarColor = t.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !t.dark
            isAppearanceLightNavigationBars = !t.dark
        }

        var dropped = 0
        for (d in names) {
            val p = DailyPlan.parse(DailyPlan.load(d))
            dropped += p.errors.size
            model[d] = p.steps.map { E(it.at, it.dir, it.dur) }.toMutableList()
            repeat[d] = p.repeat ?: 0.0
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.bg)
            setPadding(0, dp(8), 0, dp(36))
        }
        // 머리 — ‹ 를 누르면 저장할지 묻고 나간다
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("‹", 26f, t.label, medium).apply {
                gravity = Gravity.CENTER
                background = t.chunky(t.cell, dpf(21f), dp(2), dp(3))
                isClickable = true
                setOnClickListener { leave() }
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(18); topMargin = dp(12) }
            })
            addView(text("던전별 컨트롤", 26f, t.gold, Typeface.DEFAULT_BOLD).apply { setPadding(dp(14), dp(12), dp(20), 0) })
        })
        root.addView(text("방향키를 누르면 동작이 하나씩 붙어요. [도전하기] 를 누르고 3초 뒤가 0초예요. " +
            "동작이 없는 동안은 자동 전투가 싸워요.", 13f, t.label3).apply { setPadding(dp(22), dp(6), dp(22), dp(10)) })

        // 던전 탭
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), 0, dp(18), 0) }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })

        // 시간표
        root.addView(sectionHeader("시간표"))
        timeline = PlanTimeline(this, t)
        root.addView(timeline, LinearLayout.LayoutParams(MATCH_PARENT, dp(72)).apply { leftMargin = dp(18); rightMargin = dp(18) })

        // 동작 목록
        root.addView(sectionHeader("동작 (언제 · 방향 · 얼마나)"))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        root.addView(rows)
        emptyNote = text("아직 동작이 없어요 — 아래 방향키를 눌러 추가하세요. (없으면 자동 전투만 해요)", 13f, t.label3)
            .apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        root.addView(emptyNote)
        warn = text("", 13f, t.orange).apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        root.addView(warn)

        // 방향키판 + 반복
        root.addView(sectionHeader("방향을 눌러 동작 추가"))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            gravity = Gravity.TOP
        }
        bottom.addView(dirPad(56) { d -> addStep(d) })
        val repBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(t.cell); cornerRadius = dpf(14f) }
        }
        repBox.addView(text("반복", 13f, t.label2))
        repText = text("안 함", 16f, t.gold).apply { gravity = Gravity.CENTER; minWidth = dp(92) }
        repBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(sq("-") { repStep(-1) }); addView(repText); addView(sq("+") { repStep(+1) })
        })
        repBox.addView(text("그 시각이 되면\n처음부터 다시", 11f, t.label3).apply { gravity = Gravity.CENTER })
        bottom.addView(repBox, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(18) })
        root.addView(bottom)

        // 아래 단추
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), 0)
            addView(pill("이 던전 비우기", t.cell, t.label) { model[cur]?.clear(); repeat[cur] = 0.0; changed() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) })
            addView(pill("저장", t.orange, t.bg) { save(); Toast.makeText(this@DailyPlanActivity, "저장했어요 — 다음 판부터 이대로 움직여요", Toast.LENGTH_SHORT).show() },
                LinearLayout.LayoutParams(0, dp(46), 1f))
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(t.bg); addView(root) })
        for (d in names) {
            val tv = text(d, 14f, t.label).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { cur = d; render() }
            }
            tabRow.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        if (dropped > 0) Toast.makeText(this, "예전에 적은 글 중 알아듣지 못한 줄 " + dropped + "개는 빠졌어요", Toast.LENGTH_LONG).show()
        // 뒤로가기(버튼·제스처)도 ‹ 와 같게 — 고친 게 있으면 저장할지 묻는다.
        // `onBackPressed` 를 덮어쓰지 않고 콜백으로 받는다: 안드로이드 13+ 의 '예측 뒤로가기' 가 켜지면
        // onBackPressed 는 불리지 않아 묻는 창이 조용히 건너뛰어진다(사장님 폰은 안드로이드 16).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        render()
    }

    // ── 모델 조작 ──────────────────────────────────────────────
    private fun steps() = model.getOrPut(cur) { mutableListOf() }
    private fun lastEnd(): Double = steps().maxOfOrNull { it.at + it.dur } ?: 0.0
    private fun snap(v: Double) = Math.round(v * 2) / 2.0               // 0.5초 눈금
    private fun changed() { dirty = true; steps().sortBy { it.at }; render() }

    private fun addStep(d: String) {
        val at = if (steps().isEmpty()) 1.0 else snap(lastEnd() + 1.0)
        if (at > DailyPlan.MAX_AT) { Toast.makeText(this, "더 붙일 자리가 없어요", Toast.LENGTH_SHORT).show(); return }
        steps().add(E(at, d, 1.0))
        changed()
    }

    private fun repStep(delta: Int) {
        val r = repeat[cur] ?: 0.0
        repeat[cur] = when {
            delta > 0 && r <= 0.0 -> maxOf(5.0, Math.ceil(lastEnd()))
            delta > 0 -> minOf(120.0, r + 1.0)
            r <= 5.0 -> 0.0
            else -> r - 1.0
        }
        changed()
    }

    private fun save() {
        for (d in names) DailyPlan.save(d, DailyPlan.toText(model[d].orEmpty().map { DailyPlan.Step(it.at, it.dir, it.dur) }, repeat[d] ?: 0.0))
        dirty = false
        Bot.log("일일 던전 컨트롤을 저장했어요 — " + DailyPlan.summary())
    }

    /** 나갈 때 — 고친 게 있으면 저장할지 묻는다(몇 줄 고쳐 놓고 그냥 나가 날리는 일이 제일 흔하다). */
    private fun leave() {
        if (!dirty) { finish(); return }
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("저장할까요?")
            .setMessage("고친 내용이 아직 저장되지 않았어요.")
            .setPositiveButton("저장하고 나가기") { _, _ -> save(); finish() }
            .setNegativeButton("버리고 나가기") { _, _ -> finish() }
            .setNeutralButton("계속 고치기", null)
            .create()
        d.setOnShowListener { styleDialog(d) }
        d.show()
    }

    // ── 그리기 ────────────────────────────────────────────────
    private fun fmt(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty()) "0" else s
    }

    private fun render() {
        // 탭 — 동작 수를 붙인다
        for (i in 0 until tabRow.childCount) {
            val tv = tabRow.getChildAt(i) as TextView
            val d = names[i]
            val n = model[d]?.size ?: 0
            tv.text = if (n > 0) "$d · $n" else d
            val on = d == cur
            tv.setTextColor(if (on) t.bg else t.label)
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) t.orange else t.cell); cornerRadius = dpf(16f)
                setStroke(dp(1), if (on) t.orange else t.separator)
            }
        }
        // 목록
        rows.removeAllViews()
        val list = steps()
        for ((i, s) in list.withIndex()) rows.addView(stepRow(i, s))
        emptyNote.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        // 반복
        val r = repeat[cur] ?: 0.0
        repText.text = if (r > 0) fmt(r) + "초마다" else "안 함"
        // 알림 — 겹침 · 반복이 짧음(엔진과 같은 해석기로 확인)
        val notes = ArrayList<String>()
        for (i in 1 until list.size) {
            if (list[i].at < list[i - 1].at + list[i - 1].dur) notes.add((i + 1).toString() + "번 동작이 앞 동작이 끝나기 전에 시작해요 — 앞 동작이 끝난 뒤 이어서 움직여요")
        }
        notes.addAll(DailyPlan.parse(DailyPlan.toText(list.map { DailyPlan.Step(it.at, it.dir, it.dur) }, r)).errors)
        warn.text = notes.joinToString("\n")
        warn.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
        // 시간표
        timeline.set(list.map { Triple(it.at, it.dir, it.dur) }, r)
    }

    private fun stepRow(i: Int, s: E): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(sq("-") { s.at = maxOf(0.0, s.at - 0.5); changed() })
        addView(text(fmt(s.at) + "초", 15f, t.gold).apply { gravity = Gravity.CENTER; minWidth = dp(54) })
        addView(sq("+") { s.at = minOf(DailyPlan.MAX_AT, s.at + 0.5); changed() })
        addView(ArrowView(this@DailyPlanActivity, s.dir, t.label).apply {
            background = t.chunky(t.cell, dpf(10f), dp(1), dp(2))
            setOnClickListener { pickDir(s) }
        }, LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(8); rightMargin = dp(8) })
        addView(sq("-") { s.dur = maxOf(0.5, s.dur - 0.5); changed() })
        addView(text(fmt(s.dur) + "초", 15f, t.label).apply { gravity = Gravity.CENTER; minWidth = dp(54) })
        addView(sq("+") { s.dur = minOf(DailyPlan.MAX_DUR, s.dur + 0.5); changed() })
        addView(View(this@DailyPlanActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(sq("✕") { steps().remove(s); changed() })
    }

    /** 목록의 화살표를 누르면 — 8방향 중 하나를 고른다. */
    private fun pickDir(s: E) {
        var dlg: android.app.AlertDialog? = null
        val pad = dirPad(52) { d -> s.dir = d; dlg?.dismiss(); changed() }
        val box = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(4)); addView(pad) }
        val dd = android.app.AlertDialog.Builder(this).setTitle("방향 바꾸기").setView(box).setNegativeButton("취소", null).create()
        dlg = dd
        dd.setOnShowListener { styleDialog(dd) }
        dd.show()
    }

    /** 3x3 방향키판. 가운데는 비운다. */
    private fun dirPad(cellDp: Int, onPick: (String) -> Unit): View {
        val order = arrayOf("왼위", "위", "오른위", "왼쪽", "", "오른쪽", "왼아래", "아래", "오른아래")
        return GridLayout(this).apply {
            rowCount = 3; columnCount = 3
            for (d in order) {
                val v: View = if (d.isEmpty()) View(this@DailyPlanActivity)
                else ArrowView(this@DailyPlanActivity, d, t.label).apply {
                    background = t.chunky(t.cell, dpf(12f), dp(1), dp(3))
                    setOnClickListener { onPick(d) }
                }
                addView(v, GridLayout.LayoutParams().apply { width = dp(cellDp); height = dp(cellDp); setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
        }
    }

    /** 작은 네모 단추 — [-] [+] [✕] */
    private fun sq(label: String, onClick: () -> Unit): View = text(label, 17f, t.label).apply {
        gravity = Gravity.CENTER
        background = t.chunky(t.cell, dpf(9f), dp(1), dp(2))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun pill(label: String, bg: Int, fg: Int, onClick: () -> Unit): View = text(label, 15f, fg, medium).apply {
        gravity = Gravity.CENTER
        background = t.chunky(bg, dpf(14f), dp(2), dp(3))
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/**
 * 화살표 하나를 그린다. 오른쪽(0°)을 향한 화살을 돌려 8방향을 만든다(화면은 y 가 아래로 커진다).
 * PC 관제창 `New-DpArrow` 와 같은 모양(`M1,8 L11,8 L11,2 L20,10 L11,18 L11,12 L1,12 Z`).
 */
class ArrowView(ctx: Context, var dir: String, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@ArrowView.color; style = Paint.Style.FILL }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        // 0.36 — 대각선(45°)으로 돌리면 화살 끝이 칸 모서리 쪽으로 뻗는다. 그림의 가장 먼 점이 가운데서
        // 약 12.4칸(원래 그림 단위)이라, 계수가 0.46 이면 56dp 칸에서 반지름 32dp 로 칸(28dp)을 넘어 잘린다.
        drawArrow(c, width / 2f, height / 2f, minOf(width, height) * 0.36f, dir, paint)
    }

    companion object {
        val ANGLE = mapOf("오른쪽" to 0f, "오른아래" to 45f, "아래" to 90f, "왼아래" to 135f,
                          "왼쪽" to 180f, "왼위" to 225f, "위" to 270f, "오른위" to 315f)

        /** (cx,cy) 를 가운데로, 크기 size 의 화살표를 dir 방향으로 그린다. */
        fun drawArrow(c: Canvas, cx: Float, cy: Float, size: Float, dir: String, p: Paint) {
            val s = size / 10f                              // 원래 그림은 가로 1~20 · 세로 2~18 (가운데 10.5,10)
            val path = Path().apply {
                moveTo(1f, 8f); lineTo(11f, 8f); lineTo(11f, 2f); lineTo(20f, 10f)
                lineTo(11f, 18f); lineTo(11f, 12f); lineTo(1f, 12f); close()
            }
            c.save()
            c.translate(cx, cy)
            c.rotate(ANGLE[dir] ?: 0f)
            c.scale(s, s)
            c.translate(-10.5f, -10f)
            c.drawPath(path, p)
            c.restore()
        }
    }
}

/** 시간표 — 0초부터 동작을 막대로, 그 안에 방향 화살표. 반복 지점은 금색 점선. */
class PlanTimeline(ctx: Context, private val th: Theme) : View(ctx) {
    private var steps: List<Triple<Double, String, Double>> = emptyList()
    private var repeat = 0.0
    private val d = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.fill }
    private val track = Paint().apply { color = th.separator }
    private val block = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.orange }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.bg }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.label3; textSize = 10 * d }
    private val rep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = th.gold; strokeWidth = 1.6f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val repTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.gold; textSize = 10 * d }

    fun set(s: List<Triple<Double, String, Double>>, r: Double) { steps = s; repeat = r; invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRoundRect(RectF(0f, 0f, w, h), 10 * d, 10 * d, bg)
        val pad = 8 * d
        val iw = w - pad * 2
        val end = steps.maxOfOrNull { it.first + it.third } ?: 0.0
        val span = maxOf(10.0, Math.ceil(maxOf(end, repeat)) + 1)
        val sx = iw / span
        val top = 8 * d; val bh = 30 * d; val ty = top + bh + 4 * d
        c.drawRect(pad, ty, pad + iw, ty + 2 * d, track)
        val step = if (span > 50) 10 else if (span > 20) 5 else 2
        var tk = 0
        while (tk <= span) {
            // 반복 점선 바로 옆 눈금 숫자는 건너뛴다('반복' 글자와 겹친다 — PC 에서 '1반복' 으로 읽혔다)
            if (!(repeat > 0 && Math.abs((tk - repeat) * sx) < 30 * d)) {
                c.drawText(tk.toString(), (pad + tk * sx).toFloat(), ty + 14 * d, tick)
            }
            tk += step
        }
        for ((at, dir, dur) in steps) {
            val x = (pad + at * sx).toFloat()
            val bw = maxOf(6 * d, (dur * sx).toFloat() - 1)
            c.drawRoundRect(RectF(x, top, x + bw, top + bh), 6 * d, 6 * d, block)
            if (bw >= 16 * d) ArrowView.drawArrow(c, x + bw / 2, top + bh / 2, 9 * d, dir, arrow)
        }
        if (repeat > 0) {
            val rx = (pad + repeat * sx).toFloat()
            c.drawLine(rx, 2 * d, rx, ty, rep)
            val lx = if (rx + 30 * d > w) rx - 28 * d else rx + 3 * d
            c.drawText("반복", lx, ty + 14 * d, repTxt)
        }
    }
}
