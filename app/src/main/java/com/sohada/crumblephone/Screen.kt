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

    private val DOCK = intArrayOf(300, 1150, 2820, 2980)
    private val CLOSE_PTS = arrayOf(
        intArrayOf(711, 3030), intArrayOf(680, 3010), intArrayOf(740, 3050), intArrayOf(711, 2990),
        intArrayOf(711, 3070), intArrayOf(650, 3030), intArrayOf(772, 3030)
    )
    private val DLG_L = intArrayOf(330, 540, 2780, 2845)
    private val DLG_R = intArrayOf(890, 1100, 2780, 2845)

    private fun px(b: Bitmap, x: Int, y: Int): Int =
        if (x in 0 until b.width && y in 0 until b.height) b.getPixel(x, y) else Color.BLACK

    /** 하단 '플레이트 강화' 독의 나무색 비율. 밝기와 무관하다. */
    fun dockRatio(b: Bitmap): Double {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        var x = DOCK[0]
        while (x < DOCK[1]) {
            var y = DOCK[2]
            while (y < DOCK[3]) {
                val c = px(b, x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
                y += 6
            }
            x += 6
        }
        if (n == 0) return 0.0
        val mean = (rs + gs + bs).toDouble() / (3.0 * n)
        return if (mean < 0.5) 0.0 else ((rs - bs).toDouble() / n) / mean
    }

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
