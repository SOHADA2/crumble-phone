package com.sohada.crumblephone

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 화면 판정. PC 봇(dev.ps1 · tobol.ps1)의 상수와 규칙을 그대로 옮겼다.
 * 폰이 PC 봇 기준과 같은 1440x3120 이라 좌표를 변환하지 않는다.
 */
object Screen {

    // ── 좌표(1440x3120 실좌표) ──
    val WAKE = intArrayOf(721, 1864, 725, 2520)      // 절전 해제: 왕관 씌우기 드래그
    val DLG_SAFE = intArrayOf(441, 2797)             // 확인창 왼쪽 버튼(계속하기/취소) — 오른쪽 주황은 절대 금지
    val TOBOL_ENTRY = intArrayOf(1340, 1500)         // 메인 우측 토벌전 바로가기
    val TOBOL_CHALLENGE = intArrayOf(700, 2715)      // 도전하기!
    val TOBOL_CLOSE = intArrayOf(715, 2990)          // BATTLE OVER 결과창 ✕

    // ── 봇 본체(퀘스트 순환) — PC 봇 bot.ps1 의 720 축소본 좌표를 ×2 한 실좌표 ──
    val QUEST_BAR   = intArrayOf(1140, 2020)         // 전투 화면 오른쪽 퀘스트 띠
    val NAV_CLOSE   = intArrayOf(720, 3016)          // 하단 네비 한가운데 X (열린 화면 닫기)
    val SUB_CLOSE   = intArrayOf(718, 3054)          // 미션·출석처럼 전체를 덮는 창의 하단 X
    val OUTSIDE     = intArrayOf(720, 1100)          // 팝업 바깥(전장 빈 곳) — 닫기 버튼이 없는 팝업용
    val GACHA_10    = intArrayOf(712, 2640)          // 뽑기 '10회'
    val GACHA_CLOSE = intArrayOf(712, 2976)          // 뽑기 결과창 X

    // 가방 상자 사용(box.ps1) — 퀘스트가 상자를 맨 앞 칸에 올려 준다는 전제
    val BAG_SLOT1   = intArrayOf(228, 2116)          // 가방 첫 번째 칸
    val BAG_USE     = intArrayOf(712, 1692)          // 사용하기
    val BAG_DISMISS = intArrayOf(712, 1600)          // 보상 화면 빈 곳

    // 보상 자동 받기(bot.ps1 의 Claim-Missions / Claim-Attendance)
    val ICON_MISSION  = intArrayOf(1336, 1130)       // 우측 미션(클립보드) 아이콘
    val ICON_CALENDAR = intArrayOf(1336, 1300)       // 우측 달력(이벤트 허브) 아이콘
    val MISSION_TABS  = arrayOf(intArrayOf(274, 2700), intArrayOf(710, 2700), intArrayOf(1138, 2700)) // 일일·주간·도전
    val CLAIM_ALL     = intArrayOf(716, 2490)        // 모두 받기
    val ATT_MIRACLE   = intArrayOf(390, 2840)        // 기적의 출석 탭
    val ATT_NEW       = intArrayOf(196, 2840)        // 신규 출석 탭(기적을 고른 뒤에야 보인다)
    val ATT_CLAIM     = intArrayOf(720, 2570)        // 받기 / 모두 받기

    private val DOCK = intArrayOf(300, 1150, 2820, 2980)
    private val CLOSE_PTS = arrayOf(
        intArrayOf(711, 3030), intArrayOf(680, 3010), intArrayOf(740, 3050), intArrayOf(711, 2990),
        intArrayOf(711, 3070), intArrayOf(650, 3030), intArrayOf(772, 3030)
    )
    private val DLG_L = intArrayOf(330, 540, 2780, 2845)
    private val DLG_R = intArrayOf(890, 1100, 2780, 2845)

    private fun px(b: Bitmap, x: Int, y: Int): Int =
        if (x in 0 until b.width && y in 0 until b.height) b.getPixel(x, y) else Color.BLACK

    /** 독을 한 번 훑어 세 값을 같이 낸다. 셋 다 밝기와 무관하다. */
    class Dock(val mean: Double, val ratio: Double, val cv: Double)

    /**
     * 하단 '플레이트 강화' 독의 통계.
     *   ratio = (R-B)/평균밝기 — 메인 +0.23~0.33 / 가방 -0.45 / 뽑기 -0.60
     *   cv    = 표준편차/평균  — 정상 0.89(밝든 어둡든) / 절전 0.11
     *
     * 절전을 절대 밝기로 가르면 안 된다. 게임이 무터치 시 화면을 어둡게 만드는데
     * 그게 절전 화면보다 더 어두울 때가 있어(정상 6.7 vs 절전 12.6) 어떤 임계값도 안전하지 않다.
     */
    fun dockStats(b: Bitmap): Dock {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        val vals = ArrayList<Double>()
        var x = DOCK[0]
        while (x < DOCK[1]) {
            var y = DOCK[2]
            while (y < DOCK[3]) {
                val c = px(b, x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                rs += r; gs += g; bs += bl; n++
                vals.add((r + g + bl) / 3.0)
                y += 6
            }
            x += 6
        }
        if (n == 0) return Dock(0.0, 0.0, 0.0)
        val mean = (rs + gs + bs).toDouble() / (3.0 * n)
        if (mean < 0.5) return Dock(mean, 0.0, 0.0)
        var sq = 0.0
        for (v in vals) sq += (v - mean) * (v - mean)
        return Dock(mean, ((rs - bs).toDouble() / n) / mean, Math.sqrt(sq / vals.size) / mean)
    }

    /** 하단 '플레이트 강화' 독의 나무색 비율. 밝기와 무관하다. */
    fun dockRatio(b: Bitmap): Double = dockStats(b).ratio

    /** 하단 한가운데 주황 닫기(✕)/뒤로(↩) 버튼이 있나? 있으면 서브 화면이다. */
    fun hasCloseButton(b: Bitmap): Boolean {
        var hit = 0
        for (p in CLOSE_PTS) {
            val c = px(b, p[0], p[1])
            if (Color.red(c) > 170 && Color.blue(c) < 80) hit++
        }
        return hit >= 4
    }

    /**
     * 메인 화면인가? 독이 나무색 **그리고** 닫기 버튼이 없어야 한다.
     * 독만 보면 안 된다 — 토벌전 BATTLE OVER 는 배경이 어두운 갈색이라 독 비율이 1.67 로 나와 메인으로 오판한다.
     */
    fun atMain(b: Bitmap): Boolean = dockRatio(b) >= 0.1 && !hasCloseButton(b)

    /**
     * 게임의 확인창(예/아니오)이 떠 있나?
     * 전투 종료 · 게임 종료 · 다이아 새로고침 창이 모두 같은 배치라, 왼쪽이 언제나 안전한 쪽이다.
     * 왼쪽 청록과 오른쪽 주황이 '동시에' 잡힐 때만 참으로 본다.
     */
    fun isConfirmDialog(b: Bitmap): Boolean {
        var teal = 0; var orange = 0; var n = 0
        var x = DLG_L[0]
        while (x < DLG_L[1]) {
            var y = DLG_L[2]
            while (y < DLG_L[3]) {
                val c = px(b, x, y); n++
                if (Color.red(c) < 90 && Color.green(c) > 150 && Color.blue(c) > 160) teal++
                y += 10
            }
            x += 10
        }
        x = DLG_R[0]
        while (x < DLG_R[1]) {
            var y = DLG_R[2]
            while (y < DLG_R[3]) {
                val c = px(b, x, y)
                if (Color.red(c) > 230 && Color.green(c) in 111..199 && Color.blue(c) < 70) orange++
                y += 10
            }
            x += 10
        }
        val need = (n * 0.2).toInt()
        return teal >= need && orange >= need
    }

    /** 토벌전 로비: '도전하기' 버튼이 주황(700,2700). */
    fun atTobolLobby(b: Bitmap): Boolean {
        val c = px(b, 700, 2700)
        return Color.red(c) > 230 && Color.green(c) > 110 && Color.blue(c) < 60
    }

    /**
     * BATTLE OVER 결과창: 배경이 어두운 갈색(100,1600)&(1340,1600) **그리고** 하단에 주황 닫기(✕).
     * 어둡기만 보면 게임 로딩 화면을 결과창으로 오인한다(폰에서 실제로 겪음) → 닫기 버튼까지 같이 본다.
     */
    fun isBattleOver(b: Bitmap): Boolean {
        for (p in arrayOf(intArrayOf(100, 1600), intArrayOf(1340, 1600))) {
            val c = px(b, p[0], p[1])
            if (!(Color.red(c) < 75 && Color.green(c) < 55 && Color.blue(c) < 45)) return false
        }
        return hasCloseButton(b)
    }

    // ══════════════════════════════════════════════════════════
    //  봇 본체(퀘스트 순환) 판정 — bot.ps1 / box.ps1 에서 옮겼다
    // ══════════════════════════════════════════════════════════

    private val BAR = intArrayOf(940, 1430, 1905, 2075)

    /**
     * 전투 화면 오른쪽 퀘스트 띠의 (R-B)/평균밝기.
     * 게임이 화면을 어둡게 만들어도 비율은 유지된다.
     *   실측: 완료 1.11~1.28 / 미완료 0.16~0.58 (띠가 반투명이라 뒤 배경에 따라 흔들린다)
     * -0.6 아래로 크게 벗어나면 무언가가 띠를 덮고 있다는 뜻이다.
     */
    fun questBarRatio(b: Bitmap): Double {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        var x = BAR[0]
        while (x < BAR[1]) {
            var y = BAR[2]
            while (y < BAR[3]) {
                val c = px(b, x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
                y += 6
            }
            x += 6
        }
        if (n == 0) return 0.0
        val mean = (rs + gs + bs).toDouble() / (3.0 * n)
        return if (mean < 1) 0.0 else ((rs - bs).toDouble() / n) / mean
    }

    /** 퀘스트가 완료돼 받을 수 있나? 미완료를 잘못 누르는 쪽이 훨씬 위험해서 넉넉히 잡는다. */
    fun isQuestDone(b: Bitmap): Boolean = questBarRatio(b) >= 0.85

    private val GACHA_BTNS = arrayOf(intArrayOf(266, 2640), intArrayOf(712, 2640), intArrayOf(1158, 2640))

    /**
     * 뽑기 화면의 1회/10회/30회 주황 버튼 3자리 중 몇 개가 주황인가? (0~3)
     * 3이면 뽑기 화면. 1~2 는 '뽑기 화면 같은데 뭔가 다른' 상태라 건드리지 않는다.
     */
    fun gachaHits(b: Bitmap): Int {
        var hit = 0
        for (p in GACHA_BTNS) {
            val c = px(b, p[0], p[1])
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            // 화면이 어두워져도(절전 직전) 유지되도록 비율로 본다. 15는 완전 검정 배제용.
            if (r >= 15 && r > bl * 2 && g > bl && r > g) hit++
        }
        return hit
    }

    fun isGachaScreen(b: Bitmap): Boolean = gachaHits(b) == 3

    /**
     * 가방·뽑기 같은 청록 패널이 하단을 덮고 있나?
     * 절대 R-B 로 보면 패널 경계에 걸려 가방 내용물에 따라 흔들린다(실제로 가방을 통째로 놓쳤다).
     *   실측 ratio: 메인 +0.23~0.33 / 가방 -0.45 / 뽑기 -0.60 → 두 분포 사이인 -0.2 를 임계로.
     */
    fun isBottomPanel(b: Bitmap): Boolean = dockRatio(b) <= -0.2

    // ── 오븐(oven.ps1) ──
    val OVEN_AUTO   = intArrayOf(516, 2840)          // 오븐 왼쪽 'Auto' 버튼
    val OVEN_GO     = intArrayOf(713, 2815)          // '자동 열기'의 [시작] 과 '자동 열기 결과'의 [정리 하기] 가 같은 자리다
    private val OVEN_EQUIP = intArrayOf(826, 2300)   // 장착 버튼 자리 — 팝업이 떴는지 보는 데만 쓰고 누르지 않는다
    private val OVEN_BADGE = intArrayOf(770, 845, 2675, 2750)   // 오븐에 쌓인 장비 뱃지(빨간 원)

    /**
     * 장착/판매 비교 팝업이 떠 있나? '장착' 버튼 자리가 청록이면 참.
     * 절대값을 쓰면 게임이 화면을 어둡게 만들 때 깨진다 — 청록은 G·B 가 R 보다 훨씬 크다는 비율로 본다.
     */
    fun isOvenPopup(b: Bitmap): Boolean {
        val c = px(b, OVEN_EQUIP[0], OVEN_EQUIP[1])
        val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
        return g > r * 2 && bl > r * 2 && (g + bl) > 20
    }

    /**
     * 오븐 뱃지(쌓인 장비 표시)의 진한 빨강 픽셀 수. 늘어나면 Auto 가 실제로 돌고 있다는 뜻이다.
     * 실측: 뱃지 있음 ~2700-3100 / 없음 ~600-900
     */
    fun ovenBadge(b: Bitmap): Int {
        var d = 0
        var x = OVEN_BADGE[0]
        while (x < OVEN_BADGE[1]) {
            var y = OVEN_BADGE[2]
            while (y < OVEN_BADGE[3]) {
                val c = px(b, x, y)
                val r = Color.red(c)
                if (r in 101..199 && Color.green(c) < 60 && Color.blue(c) < 60) d++
                y++
            }
            x++
        }
        return d
    }

    /**
     * 가방에서 칸을 고른 뒤 '사용하기' 주황 버튼이 떠 있나?
     * 없으면 그 칸이 상자가 아니므로 아무것도 누르지 않고 가방을 닫아야 한다.
     */
    fun hasUseButton(b: Bitmap): Boolean {
        var hit = 0; var n = 0
        var x = 620
        while (x < 810) {
            var y = 1670
            while (y < 1715) {
                val c = px(b, x, y); n++
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (r > 30 && r > bl * 2 && g > bl) hit++
                y += 6
            }
            x += 6
        }
        return n > 0 && hit.toDouble() / n > 0.3
    }
}

/**
 * 메인 화면 우측 아이콘 열에서 토벌전(피냐타) 바로가기를 찾는다.
 *
 * 좌표를 고정하면 안 된다 — 이벤트에 따라 아이콘 개수가 달라져 열이 통째로 밀린다.
 * (실제로 PC 기준 (1340,1500) 이 폰에서는 '크럼블 패스' 였다.)
 * 피냐타만 청록/분홍이 화려하고 나머지(퀘스트·달력·쿠폰·더보기)는 흑백이라, 청록 픽셀 수로 고른다.
 *   실측: 피냐타 슬롯 121 / 나머지 0~33 (같은 크기 창)
 */
object Shortcut {
    private const val X1 = 1285
    private const val X2 = 1405
    private const val Y1 = 1100
    private const val Y2 = 2000
    private const val HALF = 60          // 슬롯 반높이
    // 실측(4칸 간격, 1/16 표본): 피냐타 121 · 나머지 0~33 → 전 픽셀 기준으론 약 1936 대 528. 그 사이인 1000 을 기준으로.
    private const val NEED = 1000

    fun findTobol(b: android.graphics.Bitmap): IntArray? {
        val w = X2 - X1
        val h = Y2 - Y1
        if (b.width < X2 || b.height < Y2) return null
        val buf = IntArray(w * h)
        b.getPixels(buf, 0, w, X1, Y1, w, h)

        // 줄마다 청록 픽셀 수를 세어 두고, 슬롯 높이만큼 훑어 가장 진한 곳을 고른다.
        val rows = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            val off = y * w
            for (x in 0 until w) {
                val p = buf[off + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
                if (bl > 150 && g > 140 && r < 140) c++
            }
            rows[y] = c
        }
        var best = -1; var bestY = -1
        var y = HALF
        while (y < h - HALF) {
            var s = 0
            for (k in y - HALF until y + HALF) s += rows[k]
            if (s > best) { best = s; bestY = y }
            y += 10
        }
        if (best < NEED) return null
        return intArrayOf((X1 + X2) / 2, Y1 + bestY)
    }
}
