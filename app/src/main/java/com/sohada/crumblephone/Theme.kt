package com.sohada.crumblephone

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * 색과 치수. iOS 의 시스템 색을 그대로 따왔다 —
 * 손으로 고른 색보다 서로 잘 맞고, 라이트/다크가 이미 짝지어져 있다.
 *
 * 이미지와 커스텀 글꼴은 여전히 쓰지 않는다(APK 를 가볍게 유지한다).
 * '앱처럼 보이는 것'은 색·간격·글자 크기의 위계에서 나오지 파일에서 나오지 않는다.
 */
class Theme(val dark: Boolean) {

    // systemGroupedBackground — 화면 바탕
    val bg        = if (dark) Color.parseColor("#000000") else Color.parseColor("#F2F2F7")
    // secondarySystemGroupedBackground — 카드/셀 바탕
    val cell      = if (dark) Color.parseColor("#1C1C1E") else Color.parseColor("#FFFFFF")
    // tertiarySystemFill — 기록처럼 한 단계 안쪽
    val fill      = if (dark) Color.parseColor("#2C2C2E") else Color.parseColor("#EFEFF4")
    val separator = if (dark) Color.parseColor("#38383A") else Color.parseColor("#C6C6C8")

    val label     = if (dark) Color.parseColor("#FFFFFF") else Color.parseColor("#000000")
    val label2    = if (dark) Color.parseColor("#98989E") else Color.parseColor("#6C6C70")
    val label3    = if (dark) Color.parseColor("#6C6C70") else Color.parseColor("#A0A0A5")

    val blue      = if (dark) Color.parseColor("#0A84FF") else Color.parseColor("#007AFF")
    val red       = if (dark) Color.parseColor("#FF453A") else Color.parseColor("#FF3B30")
    val green     = if (dark) Color.parseColor("#30D158") else Color.parseColor("#34C759")
    val orange    = if (dark) Color.parseColor("#FF9F0A") else Color.parseColor("#FF9500")

    private val rippleColor = if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")

    companion object {
        fun of(ctx: Context): Theme {
            val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return Theme(mode == Configuration.UI_MODE_NIGHT_YES)
        }
    }

    fun round(color: Int, radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
    }

    /** 눌린 느낌. 셀은 모서리가 둥근 그룹 안에 있으므로 클리핑은 그룹이 맡는다. */
    fun ripple(base: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), ColorDrawable(base), ColorDrawable(Color.WHITE))

    /** 홀로 서 있는 버튼용 — 리플이 둥근 모서리를 넘지 않게 마스크도 같은 모양으로 준다. */
    fun rippleRound(base: Int, radiusPx: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            round(base, radiusPx), round(Color.WHITE, radiusPx))
}
