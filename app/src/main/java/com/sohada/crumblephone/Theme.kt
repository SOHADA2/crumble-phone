package com.sohada.crumblephone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable

/**
 * 색과 치수 — **쿠키런: 크럼블 메인 화면에서 딴 따뜻한 갈색 톤.**
 *
 * ⚠️ 크럼블의 정체성은 **메인 화면의 갈색**이다. 길드·던전 팝업의 밝은 청록이 아니다.
 *    PC 관제창에서 그 청록으로 칠했다가 "쿠키런의 다른 게임 같다"는 지적을 받고 되돌렸다.
 *    아래 값은 그때 게임에서 실측해 확정한 것이다 — **다시 고르지 말 것.**
 *
 * | 요소 | 게임 실측 | 여기 |
 * |---|---|---|
 * | 하단 독(제일 어두운 판) | #281E1C | 바탕 #231A18 |
 * | 돌 패널(플레이트 강화) | #3A2C2A | 카드 #382A27 |
 * | 테두리 | 탄/황토 | #8A6A52 / #6B4F42 |
 * | 글씨 | 크림 | #E8D9C8 / #BCA491 |
 * | 숫자 강조 | 금색 | #FFC94A |
 * | 주 버튼 | 주황 #FD9500 | #F0921E · 짙은 갈색 외곽선 |
 *
 * **한 색은 한 역할로만 쓴다**(바탕이면 바탕, 글씨면 글씨, 테두리면 테두리).
 * 겹쳐 쓰면 톤을 통째로 바꿀 때 전역 치환이 안 된다 — 실제로 이 규칙 덕에 한 번에 바꿨다.
 *
 * 라이트/다크를 나누지 않는다. 게임 스킨은 **하나의 룩**이어야 게임처럼 보인다.
 * 이미지도 커스텀 글꼴도 여전히 쓰지 않는다(APK 를 가볍게 유지한다).
 */
class Theme(val dark: Boolean = true) {

    val bg        = Color.parseColor("#231A18")   // 화면 바탕
    val bgDeep    = Color.parseColor("#1B1412")   // 한 단계 더 어두운 판
    val cell      = Color.parseColor("#382A27")   // 카드/셀
    val fill      = Color.parseColor("#2A201D")   // 기록처럼 한 단계 안쪽
    val separator = Color.parseColor("#4A3733")

    val border    = Color.parseColor("#8A6A52")   // 카드 테두리(탄)
    val borderDim = Color.parseColor("#6B4F42")

    val label     = Color.parseColor("#E8D9C8")   // 크림
    val label2    = Color.parseColor("#BCA491")
    val label3    = Color.parseColor("#8A7565")

    val gold      = Color.parseColor("#FFC94A")   // 숫자·강조
    val blue      = Color.parseColor("#F0921E")   // '주 동작' 자리(이름만 blue, 톤은 주황이다)
    val orange    = Color.parseColor("#FD9500")
    val red       = Color.parseColor("#E8503C")
    val green     = Color.parseColor("#7CC24A")

    private val rippleColor = Color.parseColor("#33FFE9C9")

    companion object {
        /** 라이트/다크를 따르지 않는다 — 게임 스킨은 한 벌이다. */
        fun of(ctx: Context): Theme = Theme(true)

        /** 색을 어둡게. 버튼 외곽선·그림자를 색마다 손으로 고르지 않으려고 계산으로 낸다. */
        fun shade(c: Int, f: Float): Int = Color.rgb(
            (Color.red(c) * f).toInt().coerceIn(0, 255),
            (Color.green(c) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) * f).toInt().coerceIn(0, 255))
    }

    fun round(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
    }

    /** 카드/셀 묶음 — 게임 패널처럼 **두툼한 테두리**를 두른다. */
    fun card(radiusPx: Float, strokePx: Int): GradientDrawable = GradientDrawable().apply {
        setColor(cell); cornerRadius = radiusPx; setStroke(strokePx, borderDim)
    }

    /**
     * 게임 버튼처럼 **두툼하고 아래쪽에 입체감**이 있는 버튼.
     * 아래 칸에 어두운 판을 깔고 그 위에 밝은 면을 올려, 눌리지 않은 동안 두께가 보이게 한다.
     */
    fun chunky(base: Int, radiusPx: Float, strokePx: Int, depthPx: Int): Drawable {
        val under = round(shade(base, 0.55f), radiusPx)
        val face = GradientDrawable().apply {
            colors = intArrayOf(shade(base, 1.18f), base)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadius = radiusPx
            setStroke(strokePx, shade(base, 0.45f))
        }
        val stack = LayerDrawable(arrayOf(under, face))
        stack.setLayerInset(1, 0, 0, 0, depthPx)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), stack, round(Color.WHITE, radiusPx))
    }

    /** 눌린 느낌. 셀은 둥근 그룹 안에 있으므로 클리핑은 그룹이 맡는다. */
    fun ripple(base: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor),
            android.graphics.drawable.ColorDrawable(base),
            android.graphics.drawable.ColorDrawable(Color.WHITE))

    /** 홀로 서 있는 버튼용 — 리플이 둥근 모서리를 넘지 않게 마스크도 같은 모양으로 준다. */
    fun rippleRound(base: Int, radiusPx: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), round(base, radiusPx), round(Color.WHITE, radiusPx))
}
