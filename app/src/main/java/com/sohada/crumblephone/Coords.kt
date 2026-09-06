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

    /** 게임이 실제로 그려진 영역(레터박스를 뺀 자리). 화면 전체일 수도 있다. */
    @Volatile var ox = 0; private set
    @Volatile var oy = 0; private set
    @Volatile var cw = W; private set
    @Volatile var ch = H; private set

    /** 그 영역 기준의 가로·세로 배율. */
    @Volatile var sx = 1.0; private set
    @Volatile var sy = 1.0; private set

    /** 게임 영역의 비율이 설계와 같은가. 다르면 게임이 UI 를 재배치한 것이라 좌표를 못 쓴다. */
    @Volatile var ratioOk = true; private set

    /** 게임 화면을 실제로 보고 판단했나. */
    @Volatile var detected = false; private set

    val exact: Boolean get() = ox == 0 && oy == 0 && cw == W && ch == H

    /** 화면 크기만 먼저 잡아 둔다(캡처 전에도 대략 알 수 있게). */
    fun set(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == screenW && h == screenH && !detected) return
        screenW = w; screenH = h
        applyRect(0, 0, w, h, "화면 " + w + "x" + h)
        detected = false
    }

    private fun applyRect(x: Int, y: Int, w: Int, h: Int, why: String) {
        ox = x; oy = y; cw = w; ch = h
        sx = w.toDouble() / W
        sy = h.toDouble() / H
        val r = h.toDouble() / w
        // 2% 안이면 같은 비율로 본다. 1440x3120 · 1080x2340 · 720x1560 이 모두 여기 든다.
        ratioOk = Math.abs(r - RATIO) / RATIO <= 0.02
        Bot.log(why + (if (exact) " (설계 그대로)" else " · 배율 " + String.format("%.3f", sx)) +
                (if (ratioOk) "" else " ⚠️ 비율이 달라요"))
    }

    /**
     * **게임 화면을 실제로 보고** 그려진 영역을 찾는다(레터박스 제거).
     *
     * 태블릿처럼 화면이 넓으면 두 경우가 있다:
     *   ① 게임이 9:19.5 를 유지하고 남는 자리를 검은 띠로 채운다 → 그 안쪽만 쓰면 **그대로 돈다**
     *   ② 게임이 넓은 화면을 실제로 활용해 UI 를 재배치한다 → 좌표를 새로 재야 한다
     * 검은 띠를 찾아 ①이면 자동으로 맞추고, 아니면 ②로 보고 `ratioOk = false` 를 남긴다.
     *
     * 게임 화면 자체가 어두울 수 있으므로 **거의 완전한 검정**만 띠로 인정하고,
     * 잘라 낸 결과의 비율이 설계와 맞을 때만 받아들인다. 아니면 화면 전체로 되돌린다.
     */
    fun detect(b: android.graphics.Bitmap) {
        if (detected) return
        detected = true
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) return
        screenW = w; screenH = h

        val left = scanX(b, 0, 1)
        val right = scanX(b, w - 1, -1)
        val top = scanY(b, 0, 1)
        val bottom = scanY(b, h - 1, -1)
        val rx = left
        val ry = top
        val rw = (right - left + 1)
        val rh = (bottom - top + 1)

        // 띠를 찾았어도 결과가 말이 안 되면(너무 작거나 비율이 여전히 다르면) 쓰지 않는다.
        if (rw >= w / 2 && rh >= h / 2) {
            val r = rh.toDouble() / rw
            if (Math.abs(r - RATIO) / RATIO <= 0.02) {
                if (rw != w || rh != h) {
                    applyRect(rx, ry, rw, rh, "게임 영역 " + rw + "x" + rh + " (검은 띠 제외)")
                } else {
                    applyRect(0, 0, w, h, "화면 " + w + "x" + h)
                }
                return
            }
        }
        applyRect(0, 0, w, h, "화면 " + w + "x" + h)
    }

    /** 세로줄 하나가 통째로 (거의) 검정인가를 보며 안쪽으로 훑는다. */
    private fun scanX(b: android.graphics.Bitmap, from: Int, step: Int): Int {
        var x = from
        val limit = b.width / 4          // 화면의 1/4 넘게 잘라내는 일은 없다
        var moved = 0
        while (moved < limit && x >= 0 && x < b.width) {
            if (!lineBlack(b, x, true)) break
            x += step; moved++
        }
        return x.coerceIn(0, b.width - 1)
    }

    private fun scanY(b: android.graphics.Bitmap, from: Int, step: Int): Int {
        var y = from
        val limit = b.height / 4
        var moved = 0
        while (moved < limit && y >= 0 && y < b.height) {
            if (!lineBlack(b, y, false)) break
            y += step; moved++
        }
        return y.coerceIn(0, b.height - 1)
    }

    /** 그 줄이 거의 완전한 검정인가. 게임 화면도 어두울 수 있어 문턱을 아주 낮게 잡는다. */
    private fun lineBlack(b: android.graphics.Bitmap, at: Int, vertical: Boolean): Boolean {
        val n = if (vertical) b.height else b.width
        var i = 0
        while (i < n) {
            val c = if (vertical) b.getPixel(at, i) else b.getPixel(i, at)
            if (((c shr 16) and 0xFF) > 12 || ((c shr 8) and 0xFF) > 12 || (c and 0xFF) > 12) return false
            i += 8
        }
        return true
    }

    /** 다음 실행 때 게임 화면을 다시 보고 판단하게 한다. */
    fun redetect() { detected = false }

    fun x(v: Int): Int = ox + (if (sx == 1.0) v else Math.round(v * sx).toInt())
    fun y(v: Int): Int = oy + (if (sy == 1.0) v else Math.round(v * sy).toInt())

    /** 넓이에 비례하는 값(픽셀 개수 임계 등)을 환산한다. */
    fun area(v: Int): Int = if (sx == 1.0 && sy == 1.0) v else Math.round(v * sx * sy).toInt()

    fun mismatchReason(): String =
        "이 기기는 게임 화면 비율이 달라요 (" + cw + "x" + ch + ").\n" +
        "게임이 넓은 화면에 맞춰 UI 를 다시 배치해서 좌표가 안 맞습니다 — 좌표를 새로 재야 해요."
}
