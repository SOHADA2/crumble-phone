package com.sohada.crumblephone

/**
 * 설계 좌표 ↔ 이 기기의 실제 화면 좌표.
 *
 * 코드의 모든 좌표는 **1440x3120 설계 좌표**로 적는다(PC 봇과 같은 기준).
 * 실제 기기가 다른 해상도면 여기서 한 번만 환산한다. 좌표가 바깥으로 나가는 곳은 네 군데뿐이다:
 *   1. `Screen.px` — 화면 한 점 읽기
 *   2. `TapService.tap` / `swipe` — 탭 넣기
 *   3. `Ocr` — 숫자 읽을 영역 자르기
 *   4. `Shortcut.findTobol` — 아이콘 찾는 창
 *
 * ⚠️ **비율이 다르면 배율로는 안 된다.** 게임이 UI 를 통째로 재배치하기 때문이다
 *    (태블릿 4:3·16:10). 그때는 좌표를 아예 다시 재야 하므로, 배율을 곱해서 눌러 보는 대신
 *    **시작을 막고 이유를 알려 준다.** 엉뚱한 자리를 누르면 재화가 나간다.
 */
object Coords {

    const val W = 1440
    const val H = 3120
    private const val RATIO = H.toDouble() / W          // 2.1666…

    @Volatile var screenW = W; private set
    @Volatile var screenH = H; private set
    /** 가로·세로 배율. 비율이 조금 달라도(3120 vs 3200) 축마다 따로 두면 더 정확하다. */
    @Volatile var sx = 1.0; private set
    @Volatile var sy = 1.0; private set

    /** 설계와 화면 비율이 같은가. 다르면 게임 UI 배치 자체가 달라서 좌표를 못 쓴다. */
    @Volatile var ratioOk = true; private set

    /** 설계 그대로인가(환산이 필요 없는 기기인가). */
    val exact: Boolean get() = screenW == W && screenH == H

    fun set(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == screenW && h == screenH) return
        screenW = w; screenH = h
        sx = w.toDouble() / W
        sy = h.toDouble() / H
        val r = h.toDouble() / w
        // 2% 안이면 같은 비율로 본다. 1440x3120 · 1080x2340 · 720x1560 이 모두 여기 든다.
        ratioOk = Math.abs(r - RATIO) / RATIO <= 0.02
        Bot.log("화면 " + w + "x" + h + (if (exact) " (설계 그대로)" else " · 배율 " + String.format("%.3f", sx)) +
                (if (ratioOk) "" else " ⚠️ 비율이 달라요"))
    }

    fun x(v: Int): Int = if (sx == 1.0) v else Math.round(v * sx).toInt()
    fun y(v: Int): Int = if (sy == 1.0) v else Math.round(v * sy).toInt()

    /** 넓이에 비례하는 값(픽셀 개수 임계 등)을 환산한다. */
    fun area(v: Int): Int = if (sx == 1.0 && sy == 1.0) v else Math.round(v * sx * sy).toInt()

    fun mismatchReason(): String =
        "이 기기는 화면 비율이 달라요 (" + screenW + "x" + screenH + ").\n" +
        "게임이 UI 를 다르게 배치해서 좌표가 안 맞습니다 — 좌표를 새로 재야 해요."
}
