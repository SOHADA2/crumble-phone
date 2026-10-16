package com.sohada.crumblephone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.core.graphics.PathParser

/**
 * 색과 치수 — **PC 관제창 '시안 B' 와 같은 한 벌** (2026-10-16 · 사장님이 고른 시안).
 *
 * ⚠️ 크럼블의 정체성은 **메인 화면의 따뜻한 갈색**이다. 길드·던전 팝업의 밝은 청록이 아니다
 *    (PC 에서 청록으로 칠했다가 "쿠키런의 다른 게임 같다"는 지적을 받고 되돌렸다).
 *
 * 시안 B 의 규칙:
 *  - 판은 둥글고(카드 14~18dp) **테두리는 얇은 갈색**(#8A6A52). 예전의 두꺼운 검은 테두리·입체 버튼은 뺐다.
 *  - 콘텐츠마다 **고유 색**이 있다([Tint]). 그 색은 줄 왼쪽 띠·아이콘·도는 중 카드 윗선·플로팅 띠에만 쓴다.
 *  - 숫자 강조는 금색 #FFC94A. 주 동작은 주황 #F58F0A.
 *
 * **한 색은 한 역할로만 쓴다**(바탕이면 바탕, 글씨면 글씨, 테두리면 테두리) — 톤을 통째로 바꿀 때 전역 치환이 된다.
 * 라이트/다크를 나누지 않는다(게임 스킨은 한 벌). 이미지·커스텀 글꼴도 안 쓴다 — 아이콘은 벡터 경로로 그린다.
 */
class Theme(val dark: Boolean = true) {

    val bg        = Color.parseColor("#231A18")   // 화면 바탕 (게임 하단 독 #281E1C 보다 한 단계 낮게)
    val cell      = Color.parseColor("#2A2422")   // 설정·기록 묶음 카드
    val card      = Color.parseColor("#382A27")   // 콘텐츠 줄·상태 카드 (게임 돌 패널 #3A2C2A)
    val fill      = Color.parseColor("#2E2422")   // 카드 안쪽 한 단계 낮은 칸(기록 상자)
    val separator = Color.parseColor("#3A302D")   // 묶음 테두리 · 줄 사이 선
    val track     = Color.parseColor("#3A2E2B")   // 막대 홈

    val border    = Color.parseColor("#8A6A52")   // 콘텐츠 줄·상태 카드 테두리
    val borderDim = Color.parseColor("#4A3A35")   // 칩·작은 버튼 테두리

    val title     = Color.parseColor("#F2E4D5")   // 제목·콘텐츠 이름
    val label     = Color.parseColor("#E8D9C8")   // 크림
    val label2    = Color.parseColor("#BCA491")
    val label3    = Color.parseColor("#8C7266")
    val ink       = Color.parseColor("#231A18")   // 금색·주황 바탕 위의 글씨

    val gold      = Color.parseColor("#FFC94A")   // 숫자·값 강조
    val blue      = Color.parseColor("#F58F0A")   // '주 동작' 자리(이름만 blue, 톤은 주황이다)
    val orange    = Color.parseColor("#F58F0A")
    val red       = Color.parseColor("#E0584F")
    val green     = Color.parseColor("#7CC24A")
    val dotIdle   = Color.parseColor("#6B5A52")

    private val rippleColor = Color.parseColor("#22FFE9C9")

    companion object {
        /** 라이트/다크를 따르지 않는다 — 게임 스킨은 한 벌이다. */
        fun of(ctx: Context): Theme = Theme(true)

        /** 색을 어둡게. 버튼 외곽선을 색마다 손으로 고르지 않으려고 계산으로 낸다. */
        fun shade(c: Int, f: Float): Int = Color.rgb(
            (Color.red(c) * f).toInt().coerceIn(0, 255),
            (Color.green(c) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) * f).toInt().coerceIn(0, 255))

        /** a 에서 b 쪽으로 k 만큼 섞는다(도는 줄의 '콘텐츠 색이 옅게 번지는' 배경). */
        fun mix(a: Int, b: Int, k: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }

    fun round(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
    }

    /** 바탕 + 얇은 테두리. */
    fun box(color: Int, radiusPx: Float, strokePx: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx; setStroke(strokePx, stroke)
    }

    /** 테두리만(가운데 투명). 띠가 있는 카드의 `foreground` 로 얹어 **띠 위로도** 테두리가 지나가게 한다. */
    fun outline(radiusPx: Float, strokePx: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT); cornerRadius = radiusPx; setStroke(strokePx, stroke)
    }

    /** 설정·기록 묶음 카드. */
    fun card(radiusPx: Float, strokePx: Int): GradientDrawable = box(cell, radiusPx, strokePx, separator)

    /**
     * 버튼. 시안 B 는 **평평한 면 + 얇은 테두리**다(예전의 아래 두께·검은 외곽선은 뺐다).
     * 이름과 인자는 예전 그대로 둔다(여러 화면이 부른다) — `depthPx` 는 이제 안 쓴다.
     * 어두운 바탕이면 테두리를 갈색으로, 밝은 색(주황 등)이면 위를 살짝 밝힌 면 + 그 색을 어둡게 한 선.
     */
    @Suppress("UNUSED_PARAMETER")
    fun chunky(base: Int, radiusPx: Float, strokePx: Int, depthPx: Int): Drawable {
        val lum = (Color.red(base) * 299 + Color.green(base) * 587 + Color.blue(base) * 114) / 1000
        val face = GradientDrawable().apply {
            if (lum > 110) {
                colors = intArrayOf(mix(base, Color.WHITE, 0.18f), base)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            } else setColor(base)
            cornerRadius = radiusPx
            setStroke(Math.max(1, strokePx / 2), if (lum > 110) shade(base, 0.6f) else borderDim)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), face, round(Color.WHITE, radiusPx))
    }

    /** 눌린 느낌. 셀은 둥근 그룹 안에 있으므로 클리핑은 그룹이 맡는다. */
    fun ripple(base: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor),
            android.graphics.drawable.ColorDrawable(base),
            android.graphics.drawable.ColorDrawable(Color.WHITE))

    /** 이미 만든 바탕 위에 눌림 물결만 얹는다. */
    fun rippleOver(content: Drawable, radiusPx: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, round(Color.WHITE, radiusPx))
}

/**
 * 콘텐츠별 색·아이콘 — PC 관제창 `$CONTENT_TINT` 와 같은 값. 줄 띠·아이콘·도는 중 표시에 쓴다.
 * 이름은 `Runner.task` 에 들어가는 글자와 같다(그걸로 찾는다).
 */
object Tint {
    class C(val name: String, val color: Int, val icon: String)

    // 아이콘은 24×24 판 기준 SVG 경로(PC 타일과 같은 그림).
    val QUEST = C("퀘스트", Color.parseColor("#F58F0A"),
        "M5,2 L17,2 L20,5 L20,22 L5,22 Z M8,8 L17,8 L17,10 L8,10 Z M8,12 L17,12 L17,14 L8,14 Z M8,16 L14,16 L14,18 L8,18 Z")
    val STAGE = C("스테이지 돌파", Color.parseColor("#E8873A"),
        "M2.6,19.0 L4.4,6.6 L9.2,11.0 L12,3.4 L14.8,11.0 L19.6,6.6 L21.4,19.0 Z M3.4,19.9 L20.6,19.9 L20.6,22.2 L3.4,22.2 Z")
    val TOBOL = C("토벌전", Color.parseColor("#E0584F"),
        "M12,1.8 L21.2,5.4 L21.2,12.2 C21.2,17.6 12,22.4 12,22.4 C12,22.4 2.8,17.6 2.8,12.2 L2.8,5.4 Z")
    val DAILY = C("일일 던전", Color.parseColor("#3BB3B0"),
        "M8.2,3.4 A5.2,5.2 0 1,1 8.19,3.4 Z M8.2,6.6 A2,2 0 1,0 8.21,6.6 Z M12.4,7.2 L22.2,7.2 L22.2,10.0 L20.0,10.0 L20.0,12.8 L17.8,12.8 L17.8,10.0 L15.8,10.0 L15.8,12.4 L13.8,12.4 L13.8,10.0 L12.4,10.0 Z")
    val ARENA = C("아레나", Color.parseColor("#9B7BD4"),
        "M4.92,2.08 L20.92,18.08 L19.08,19.92 L3.08,3.92 Z M19.08,2.08 L3.08,18.08 L4.92,19.92 L20.92,3.92 Z M2.2,17.2 L6.8,21.8 L5.0,23.0 L1.0,19.0 Z M21.8,17.2 L17.2,21.8 L19.0,23.0 L23.0,19.0 Z")
    val REWARD = C("일일 보상", Color.parseColor("#FFC94A"),
        "M3,9 L21,9 L21,13 L3,13 Z M4.5,13 L19.5,13 L19.5,22 L4.5,22 Z M11,9 L13,9 L13,22 L11,22 Z M12,9 C9,9 6,7 7.5,4.5 C9,2.5 11.5,5.5 12,9 Z M12,9 C12.5,5.5 15,2.5 16.5,4.5 C18,7 15,9 12,9 Z")

    val ALL = listOf(QUEST, STAGE, TOBOL, DAILY, ARENA, REWARD)

    /** `Runner.task` 로 찾는다. '보상 받기' 는 일일 보상이다. 모르는 이름(좌표 확인 등)이면 주황(봇). */
    fun of(task: String): C? = when (task) {
        "" -> null
        "보상 받기" -> REWARD
        else -> ALL.firstOrNull { it.name == task } ?: C(task, QUEST.color, QUEST.icon)
    }
}

/**
 * 칸 왼쪽(또는 위)에 붙는 **콘텐츠 색 띠**. 칸의 크기를 **따라가기만** 하고 스스로는 크기를 요구하지 않는다.
 *
 * ⚠️ 2026-10-16 사고: 띠를 평범한 `View` + `FrameLayout.LayoutParams(4dp, MATCH_PARENT)` 로 넣었다.
 *    FrameLayout 은 높이가 WRAP 인데 부모가 '최대 N px 까지(AT_MOST)' 로 잴 때, MATCH_PARENT 인 자식을
 *    먼저 그 N 으로 재고 **그 높이를 자기 높이에 그대로 반영한다.** 평범한 View 는 AT_MOST N 이면 N 을 고른다 →
 *    띠가 붙은 칸이 **남은 화면 높이 전부**로 늘어났다. 게임 위 알약이 화면만 해졌고(봇이 돌 때 띠가 켜진다),
 *    펼침 패널은 첫 줄(퀘스트)이 화면을 다 먹어 **나머지 옵션이 하나도 안 보였다.**
 *    (관제 화면은 ScrollView 안이라 높이를 '제한 없음' 으로 재서 이 사고가 안 났다 — 그래서 알약만 터졌다.)
 *    → 높이가 정확히(EXACTLY) 주어질 때만 그 높이를 받고, 아니면 0 을 고른다. FrameLayout 은 둘째 바퀴에
 *      MATCH_PARENT 자식을 자기 높이로 다시 재 주므로 띠는 결국 칸 높이에 딱 맞는다.
 */
class StripView(ctx: Context, color: Int) : View(ctx) {
    init { setBackgroundColor(color) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun pick(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(pick(widthMeasureSpec), pick(heightMeasureSpec))
    }
}

/** 벡터 경로 아이콘 한 개. 이모지는 기기마다 그림이 달라 쓰지 않는다. */
class IconView(ctx: Context, data: String, color: Int) : View(ctx) {
    // 경로 글자가 잘못돼도 앱이 죽지 않게 — 아이콘만 빈칸이 된다.
    private val src: Path = try { PathParser.createPathFromPathData(data) } catch (e: Throwable) { Path() }
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val m = android.graphics.Matrix()

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height) / 24f
        m.reset(); m.setScale(s, s); m.postTranslate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
        src.transform(m, path)
        c.drawPath(path, paint)
    }
}
