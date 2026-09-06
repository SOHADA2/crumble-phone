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

    private fun applyRect(x: Int, y: Int, w: Int, h: Int, why: String?) {
        ox = x; oy = y; cw = w; ch = h
        sx = w.toDouble() / W
        sy = h.toDouble() / H
        val r = h.toDouble() / w
        // 2% 안이면 같은 비율로 본다. 1440x3120 · 1080x2340 · 720x1560 이 모두 여기 든다.
        ratioOk = Math.abs(r - RATIO) / RATIO <= 0.02
        if (why != null) {
            Bot.log(why + (if (exact) " (설계 그대로)" else " · 배율 " + String.format("%.3f", sx)) +
                    (if (ratioOk) "" else " ⚠️ 비율이 달라요"))
        }
    }

    /**
     * **게임 화면을 실제로 보고** 게임이 그려진 영역을 찾는다.
     *
     * 검은 띠만 찾던 방식으로는 부족했다. 게임이 남는 자리를 자기 배경 그림으로 채우기도 하고,
     * 아예 UI 를 늘려 버리기도 한다. 그래서 **후보를 몇 개 만들어 각각으로 화면을 읽어 보고,
     * 아는 화면(메인·토벌 로비·결과창…)이 알아보이는 매핑을 채택한다.**
     * 매핑이 맞으면 알아보이고 틀리면 아무것도 안 걸리므로, 이 검증이 곧 정답 판정이다.
     *
     * 후보는 **실제로 화면에서 찾아낸 것 둘뿐**이다:
     *   ① 화면 전체 — 비율이 이미 맞을 때만. 폰은 여기서 끝난다
     *   ② 균일한 띠를 걷어낸 영역 — 검정이든 무슨 색이든 한 색으로 채워진 가장자리
     *
     * ⚠️ **"레터박스일 것이다"라고 지어낸 후보를 넣지 말 것.** 예전엔 높이/너비에 맞춘 가운데
     *    정렬을 후보로 넣었는데, 갤럭시 탭 S7+ 는 띠가 **아예 없는데도**(양쪽 끝까지 게임 그림)
     *    그 지어낸 매핑이 채택돼 봇이 엉뚱한 자리를 눌렀다. 검증으로 쓰던 `looksLikeGame` 은
     *    "하단이 갈색이고 주황 ✕가 없다" 수준이라 틀린 매핑도 그냥 통과시킨다.
     *    못 찾으면 지어내지 말고 **비율 불일치로 두어 시작을 막는다** — 그게 이 파일의 정책이다.
     */
    fun detect(b: android.graphics.Bitmap) {
        if (detected) return
        detected = true
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) return
        screenW = w; screenH = h

        val cands = ArrayList<IntArray>()
        if (ratioOf(w, h)) cands.add(intArrayOf(0, 0, w, h))
        trimUniform(b)?.let { cands.add(it) }

        for (c in cands) {
            if (!ratioOf(c[2], c[3])) continue
            applyRect(c[0], c[1], c[2], c[3], null)          // 조용히 걸어 보고
            if (Screen.looksLikeGame(b)) {                    // 아는 화면이 보이면 이게 정답이다
                applyRect(c[0], c[1], c[2], c[3],
                    "게임 영역 " + c[2] + "x" + c[3] + " @(" + c[0] + "," + c[1] + ") — 아는 화면이 보여요")
                return
            }
        }
        applyRect(0, 0, w, h, "화면 " + w + "x" + h + " — 아는 화면을 못 찾았어요")
    }

    private fun ratioOf(w: Int, h: Int): Boolean {
        if (w <= 0) return false
        val r = h.toDouble() / w
        return Math.abs(r - RATIO) / RATIO <= 0.02
    }

    /**
     * 가장자리에서 **한 색으로 균일하게 채워진 띠**를 걷어낸 영역.
     * 검정만 보면 안 된다 — 게임이 남는 자리를 자기 배경색·그림으로 채우기도 한다.
     */
    private fun trimUniform(b: android.graphics.Bitmap): IntArray? {
        val left = scan(b, 0, 1, true)
        val right = scan(b, b.width - 1, -1, true)
        val top = scan(b, 0, 1, false)
        val bottom = scan(b, b.height - 1, -1, false)
        val rw = right - left + 1
        val rh = bottom - top + 1
        if (rw < b.width / 2 || rh < b.height / 2) return null
        if (rw == b.width && rh == b.height) return null      // 걷어낼 게 없었다
        return intArrayOf(left, top, rw, rh)
    }

    private fun scan(b: android.graphics.Bitmap, from: Int, step: Int, vertical: Boolean): Int {
        val max = if (vertical) b.width else b.height
        val limit = max / 4                                   // 1/4 넘게는 안 걷어낸다
        var at = from; var moved = 0
        while (moved < limit && at >= 0 && at < max) {
            if (!lineUniform(b, at, vertical)) break
            at += step; moved++
        }
        return at.coerceIn(0, max - 1)
    }

    /** 그 줄 전체가 (거의) 한 색인가. */
    private fun lineUniform(b: android.graphics.Bitmap, at: Int, vertical: Boolean): Boolean {
        val n = if (vertical) b.height else b.width
        val first = if (vertical) b.getPixel(at, 0) else b.getPixel(0, at)
        val fr = (first shr 16) and 0xFF; val fg = (first shr 8) and 0xFF; val fb = first and 0xFF
        var i = 8
        while (i < n) {
            val c = if (vertical) b.getPixel(at, i) else b.getPixel(i, at)
            if (Math.abs(((c shr 16) and 0xFF) - fr) > 10) return false
            if (Math.abs(((c shr 8) and 0xFF) - fg) > 10) return false
            if (Math.abs((c and 0xFF) - fb) > 10) return false
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

    /** 사람이 읽고 그대로 보내 줄 수 있는 한 줄. 새 기기에서 무엇이 문제인지 이걸로 갈린다. */
    fun summary(): String {
        val r = if (cw > 0) ch.toDouble() / cw else 0.0
        return "화면 " + screenW + "x" + screenH +
            " · 게임 영역 " + cw + "x" + ch + " @(" + ox + "," + oy + ")" +
            " · 비율 " + String.format("%.3f", r) + " (설계 " + String.format("%.3f", RATIO) + ")" +
            " · " + (if (ratioOk) "맞음" else "안 맞음")
    }

    fun mismatchReason(): String =
        "이 기기는 게임 화면 비율이 달라요 (" + cw + "x" + ch + " · 설계는 " + W + "x" + H + ").\n\n" +
        "게임이 검은 띠를 두르지 않고 넓은 화면에 맞춰 UI 를 다시 배치했습니다.\n" +
        "버튼 위치가 폰과 아예 다르므로, 배율을 곱해서는 맞출 수 없어요.\n\n" +
        "게임을 폰 비율로 고정할 수 있으면(삼성: 설정 → 디스플레이 → 전체 화면 앱, 또는 게임 부스터의 화면 비율) 그대로 동작합니다.\n" +
        "안 되면 이 기기 전용 좌표를 새로 재야 해요 — 도구 → [화면 보내기] 로 화면을 보내 주세요."
}
