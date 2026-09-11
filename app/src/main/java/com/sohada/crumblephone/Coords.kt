package com.sohada.crumblephone

/**
 * 설계 좌표 ↔ 이 기기의 실제 화면 좌표.
 *
 * 코드의 모든 좌표는 **1440x3120 설계 좌표**로 적는다(PC 봇과 같은 기준).
 * 실제 기기가 다르면 여기서 한 번만 환산한다. 좌표가 바깥으로 나가는 곳은 네 군데뿐이다:
 *   1. `Screen.px` — 화면 한 점 읽기
 *   2. `TapService.tap` / `swipe` — 탭 넣기
 *   3. `Ocr` — 숫자 읽을 영역 자르기
 *   4. `Shortcut.findTobol` — 아이콘 찾는 창
 *
 * ## 세로가 남을 때 — 가장자리 기준 환산
 *
 * 예전엔 가로·세로를 각각 늘렸다(`sx`, `sy`). 비율이 같으면 맞지만 **조금만 달라도 어긋난다.**
 * 게다가 허용치가 ±2% 라, 1080x2400 · 720x1600 · 픽셀처럼 **흔한 폰이 통째로 막혔다**
 * (2.6~3.1% 어긋난다).
 *
 * 모바일 게임 UI 는 늘어나지 않는다 — 위쪽 HUD 는 **위 끝에**, 아래 독·버튼은 **아래 끝에**
 * 붙고, 남는 세로는 **가운데(게임 화면)만** 차지한다. 그래서 가로 기준 균일 배율 [s] 하나만
 * 쓰고, 남는 세로 [extra] 는 가운데에서만 흡수한다:
 *
 * ```
 *   위 30%   : y = v·s                                 (위 끝에 고정)
 *   가운데   : y = v·s + extra·(v-t)/(b-t)              (남는 만큼 벌어짐)
 *   아래 30% : y = v·s + extra   ( = 화면높이 - (H-v)·s ) (아래 끝에 고정)
 * ```
 *
 * 경계에서 값이 이어지고(연속) 단조증가라, `Coords.y(a+b) - Coords.y(a)` 로 **길이를 재던
 * 기존 코드가 그대로 성립한다.** 비율이 정확히 같으면 `extra = 0` 이라 예전(`v·s`)과 똑같다.
 *
 * ⚠️ **그래도 '지어내지 않는다'는 정책은 그대로다.** 이 환산은 UI 가 가장자리에 붙어 있다는
 *    가정이고, 가정이 틀릴 수 있다. 그래서 [EXACT_OFF] 넘게 어긋난 기기에서는 **환산을 걸어 보고
 *    `looksLikeGame` 이 아는 화면을 알아볼 때만** 채택한다. 못 알아보면 시작을 막는다 —
 *    엉뚱한 자리를 누르면 재화가 나간다. 태블릿(4:3·16:10)은 게임이 UI 를 통째로 재배치하므로
 *    어떤 환산으로도 못 맞춘다. 그건 [MAX_OFF] 밖이라 아예 시도하지 않는다.
 */
object Coords {

    const val W = 1440
    const val H = 3120
    private const val RATIO = H.toDouble() / W          // 2.1666…

    /** 위/아래 '고정 띠'의 비율. 이 바깥은 가장자리에 붙고, 사이만 늘어난다. */
    private const val TOP_ZONE = 0.30
    private const val BOT_ZONE = 0.70

    /** 이 안이면 검증 없이 쓴다(예전 기준 — 1440x3120 · 1080x2340 · 720x1560 이 여기 든다). */
    private const val EXACT_OFF = 0.02

    /** 여기까지는 **검증에 통과하면** 쓴다. 넘으면 게임이 UI 를 재배치한 것이라 시도하지 않는다. */
    private const val MAX_OFF = 0.08

    @Volatile var screenW = W; private set
    @Volatile var screenH = H; private set

    /** 게임이 실제로 그려진 영역(레터박스를 뺀 자리). 화면 전체일 수도 있다. */
    @Volatile var ox = 0; private set
    @Volatile var oy = 0; private set
    @Volatile var cw = W; private set
    @Volatile var ch = H; private set

    /** 가로 기준 균일 배율. 세로에도 이걸 쓴다(세로만 따로 늘이지 않는다). */
    @Volatile var s = 1.0; private set

    /** 설계보다 남는 세로 픽셀. 양수면 화면이 더 길고 음수면 더 짧다. 가운데에서만 흡수한다. */
    @Volatile var extra = 0.0; private set

    /** 이 기기에서 쓸 수 있는 환산을 갖고 있나. 아니면 시작을 막는다. */
    @Volatile var ratioOk = true; private set

    /** 게임 화면을 실제로 보고 판단했나. */
    @Volatile var detected = false; private set

    /** 가장자리 기준으로 맞춘 상태인가(진단 문구에 쓴다). */
    @Volatile var anchored = false; private set

    val exact: Boolean get() = ox == 0 && oy == 0 && cw == W && ch == H

    /** 화면 크기만 먼저 잡아 둔다(캡처 전에도 대략 알 수 있게). */
    fun set(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w == screenW && h == screenH && !detected) return
        screenW = w; screenH = h
        applyRect(0, 0, w, h, "화면 " + w + "x" + h)
        detected = false
    }

    private fun offOf(w: Int, h: Int): Double {
        if (w <= 0) return 1.0
        return Math.abs(h.toDouble() / w - RATIO) / RATIO
    }

    private fun applyRect(x: Int, y: Int, w: Int, h: Int, why: String?) {
        ox = x; oy = y; cw = w; ch = h
        s = w.toDouble() / W
        extra = h - H * s
        val off = offOf(w, h)
        ratioOk = off <= EXACT_OFF
        anchored = off > EXACT_OFF
        if (why != null) {
            Bot.log(why + (if (exact) " (설계 그대로)" else " · 배율 " + String.format("%.3f", s)) +
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
     *   ① 화면 전체 — 비율이 좀 달라도 가장자리 기준 환산이 흡수한다([MAX_OFF] 까지)
     *   ② 균일한 띠를 걷어낸 영역 — 검정이든 무슨 색이든 한 색으로 채워진 가장자리
     *
     * ⚠️ **"레터박스일 것이다"라고 지어낸 후보를 넣지 말 것.** 예전엔 높이/너비에 맞춘 가운데
     *    정렬을 후보로 넣었는데, 갤럭시 탭 S7+ 는 띠가 **아예 없는데도**(양쪽 끝까지 게임 그림)
     *    그 지어낸 매핑이 채택돼 봇이 엉뚱한 자리를 눌렀다.
     *    못 찾으면 지어내지 말고 **비율 불일치로 두어 시작을 막는다** — 그게 이 파일의 정책이다.
     */
    fun detect(b: android.graphics.Bitmap) {
        if (detected) return
        detected = true
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) return
        screenW = w; screenH = h

        val cands = ArrayList<IntArray>()
        cands.add(intArrayOf(0, 0, w, h))
        trimUniform(b)?.let { cands.add(it) }

        for (c in cands) {
            if (offOf(c[2], c[3]) > MAX_OFF) continue
            applyRect(c[0], c[1], c[2], c[3], null)          // 조용히 걸어 보고
            if (Screen.looksLikeGame(b)) {                    // 아는 화면이 보이면 이게 정답이다
                ratioOk = true                                // 검증을 통과했으니 비율이 좀 달라도 쓴다
                Bot.log("게임 영역 " + c[2] + "x" + c[3] + " @(" + c[0] + "," + c[1] + ")" +
                        (if (anchored) " — 가장자리 기준으로 맞췄어요 (남는 세로 " +
                            Math.round(extra).toInt() + "px)" else " — 아는 화면이 보여요"))
                return
            }
        }
        // 아무것도 못 알아봤다. 비율이 정확한 기기는 그대로 쓰고(게임이 화면에 없었을 뿐일 수 있다),
        // 어긋난 기기는 여기서 막힌다 — 지어낸 환산으로는 누르지 않는다.
        applyRect(0, 0, w, h, "화면 " + w + "x" + h + " — 아는 화면을 못 찾았어요")
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

    // ── 환산 ──────────────────────────────────────────────────────────

    private val zoneTop: Double get() = H * TOP_ZONE
    private val zoneBot: Double get() = H * BOT_ZONE

    /** 설계 y → (원점을 뺀) 기기 y. 위/아래는 가장자리에 붙고 가운데만 늘어난다. */
    private fun mapY(v: Double): Double {
        if (extra == 0.0) return v * s
        val t = zoneTop; val b = zoneBot
        return when {
            v <= t -> v * s
            v >= b -> v * s + extra
            else -> v * s + extra * (v - t) / (b - t)
        }
    }

    fun x(v: Int): Int = ox + Math.round(v * s).toInt()
    fun y(v: Int): Int = oy + Math.round(mapY(v.toDouble())).toInt()

    /**
     * **길이**를 환산한다(점이 아니라 폭·높이). 원점을 더하면 안 되는 자리에 쓴다.
     * 반높이·여백처럼 짧은 값은 위치와 무관하게 배율이면 충분하다.
     */
    fun len(v: Int): Int = Math.round(v * s).toInt()

    /** 넓이에 비례하는 값(픽셀 개수 임계 등)을 환산한다. */
    fun area(v: Int): Int = Math.round(v * s * s).toInt()

    /**
     * 기기 y → 설계 y. [mapY] 의 역이다.
     * 화면에서 찾아낸 자리를 **설계 좌표로 돌려줄 때** 쓴다(탭은 다시 [y] 가 환산한다).
     */
    fun invY(deviceY: Int): Int {
        if (s <= 0.0) return deviceY
        val u = (deviceY - oy).toDouble()
        if (extra == 0.0) return Math.round(u / s).toInt()
        val t = zoneTop; val b = zoneBot
        val ut = t * s                    // 위 고정 띠의 끝
        val ub = b * s + extra            // 아래 고정 띠의 시작
        val v = when {
            u <= ut -> u / s
            u >= ub -> (u - extra) / s
            // u = v·s + extra·(v-t)/(b-t) 를 v 에 대해 푼 것
            else -> (u + extra * t / (b - t)) / (s + extra / (b - t))
        }
        return Math.round(v).toInt()
    }

    /** 사람이 읽고 그대로 보내 줄 수 있는 한 줄. 새 기기에서 무엇이 문제인지 이걸로 갈린다. */
    fun summary(): String {
        val r = if (cw > 0) ch.toDouble() / cw else 0.0
        return "화면 " + screenW + "x" + screenH +
            " · 게임 영역 " + cw + "x" + ch + " @(" + ox + "," + oy + ")" +
            " · 비율 " + String.format("%.3f", r) + " (설계 " + String.format("%.3f", RATIO) + ")" +
            " · 배율 " + String.format("%.3f", s) +
            (if (Math.abs(extra) >= 1.0) " · 남는 세로 " + Math.round(extra).toInt() + "px" else "") +
            " · " + (if (ratioOk) (if (anchored) "가장자리 기준으로 맞춤" else "맞음") else "안 맞음")
    }

    fun mismatchReason(): String =
        "이 기기는 게임 화면 비율이 달라요 (" + cw + "x" + ch + " · 설계는 " + W + "x" + H + ").\n\n" +
        "세로가 조금 남는 정도면 가장자리 기준으로 알아서 맞추는데, 그렇게 맞춰 봐도 아는 화면이\n" +
        "안 보였습니다. 게임이 넓은 화면에 맞춰 UI 를 통째로 다시 배치한 것 같아요.\n" +
        "버튼 위치가 폰과 아예 다르므로 환산으로는 맞출 수 없습니다.\n\n" +
        "게임을 폰 비율로 고정할 수 있으면(삼성: 설정 → 디스플레이 → 전체 화면 앱, 또는 게임 부스터의 화면 비율) 그대로 동작합니다.\n" +
        "안 되면 이 기기 전용 좌표를 새로 재야 해요 — 도구 → [화면 보내기] 로 화면을 보내 주세요.\n\n" +
        "참고: " + summary()
}
