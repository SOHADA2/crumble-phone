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
    val TOBOL_ENTRY = intArrayOf(1340, 1500)         // 메인 우측 토벌전 바로가기(폴백 경로)
    val NAV_GUILD   = intArrayOf(905, 3000)          // 하단 네비 '길드' 탭 — 토벌전으로 가는 안전한 길
    val GUILD_TOBOL = intArrayOf(280, 1880)          // 길드 화면의 '길드 토벌전' 타일(피냐타 그림 한가운데)
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
    val IDLE_CLAIM    = intArrayOf(715, 2788)        // '자동 사냥 보상' 팝업의 [보상 받기]
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

    /** 설계 좌표로 한 점을 읽는다. 기기 해상도 환산은 `Coords` 가 여기서 한 번만 한다. */
    private fun px(b: Bitmap, dx: Int, dy: Int): Int {
        val x = Coords.x(dx); val y = Coords.y(dy)
        return if (x in 0 until b.width && y in 0 until b.height) b.getPixel(x, y) else Color.BLACK
    }

    /**
     * 기기끼리 대 볼 기준점들. **폰에서 같은 걸 찍어 나란히 놓으면 좌표가 맞는지 바로 보인다.**
     * 네 귀퉁이를 넣은 이유: 매핑이 틀리면 게임 밖 검은 띠가 잡혀 (0,0,0) 이 나온다.
     */
    private val LAND = arrayOf(
        Triple("좌상", 40, 60), Triple("우상", 1400, 60),
        Triple("좌하", 40, 3060), Triple("우하", 1400, 3060),
        Triple("미션아이콘", 1336, 1130), Triple("달력아이콘", 1336, 1300),
        Triple("퀘스트띠", 1140, 2020),
        Triple("독좌", 320, 2900), Triple("독중", 720, 2900), Triple("독우", 1130, 2900),
        Triple("뽑기1", 266, 2640), Triple("뽑기2", 712, 2640), Triple("뽑기3", 1158, 2640),
        Triple("하단X", 711, 3030)
    )

    /** 기준점들의 색을 한 줄로. 기기 간 비교용이라 값만 그대로 낸다. */
    fun landmarks(b: Bitmap): String {
        val sb = StringBuilder()
        for (t in LAND) {
            val c = px(b, t.second, t.third)
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(t.first).append("=")
                .append(Color.red(c)).append(",").append(Color.green(c)).append(",").append(Color.blue(c))
        }
        return sb.toString()
    }

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
     * **'자동 사냥 보상'** 팝업인가? (게임을 오래 켜 두지 않다가 들어가면 거의 항상 뜬다)
     *
     * 이게 첫 진입을 막고 있었다 — `waitGameReady` 가 아는 화면 넷 중 아무것도 못 찾아
     * 45초를 기다리다 포기했다. 무료 보상이니 닫지 말고 **받는다**.
     *
     * 판정: 아래쪽의 **넓은 주황 [보상 받기] 띠**. 실측(폰 1440x3120, y=2788)
     * x=430·570·715·860·1000 다섯 자리가 모두 `(255,173,1)` 이었다.
     * 폭이 넓다는 게 요점이다 — 확인창의 주황 버튼은 오른쪽 절반에만 있어서 왼쪽 두 점이 안 걸린다.
     *
     * ⚠️ 왼쪽 아래 '행운의 보상'(주사위 x1/x2/x5)은 건드리지 않는다. 광고를 보는 쪽일 수 있다.
     */
    private val IDLE_PTS = intArrayOf(430, 570, 715, 860, 1000)

    fun isIdleReward(b: Bitmap): Boolean {
        if (!hasCloseButton(b)) return false        // 서브 화면일 때만
        var hit = 0
        for (x in IDLE_PTS) {
            val c = px(b, x, 2788)
            if (Color.red(c) > 210 && Color.green(c) in 110..205 && Color.blue(c) < 95) hit++
        }
        return hit >= 4
    }

    /**
     * 게임 화면이 **떠 있나**(로딩이 아니라). 아는 화면이 아니어도 게임 UI 가 보이면 참이다.
     * 하단 한가운데 주황 ✕ 는 게임이 다 뜬 서브 화면에만 있고 로딩 화면에는 없다.
     */
    fun gameIsUp(b: Bitmap): Boolean =
        atMain(b) || atTobolLobby(b) || isBattleOver(b) || isConfirmDialog(b) || hasCloseButton(b)

    val OVEN_CLEANUP = intArrayOf(990, 2812)         // '자동 열기 결과'의 오른쪽 [정리하기]

    // 그 창의 **넓은 청록 패널** 두 줄. 가운데 안내문 판과 버튼 바로 위 띠다.
    // 실측(폰 1440x3120): x 100~1340 중 청록 비율 y2450 = 86~90% · y2730 = 95%.
    private val OVEN_CLEAN_BANDS = intArrayOf(2450, 2730)

    /**
     * **오븐 '자동 열기 결과 → 정리하기'** 창인가?
     *
     * ⚠️ 이 창은 위험한 확인창(전투 종료·게임 종료·크리스탈 새로고침)과 **버튼 자리가 똑같다** —
     *    둘 다 y2780~2845 에 왼쪽 청록 / 오른쪽 주황이다. 그래서 `isConfirmDialog` 가 그대로 걸리고,
     *    안전 규칙대로 왼쪽([그만두기])을 눌러 왔다. 그러면 오븐이 꽉 찬 채로 막힌다(장비 30개쯤에서).
     *
     * 가르는 신호는 **창의 넓이**다. 위험한 확인창은 가운데 작은 모달이라 화면 폭을 채우지 않는다.
     * 이 창은 오븐 패널 위에 떠서 **가로로 거의 꽉 찬 청록 판**을 두 줄 갖는다.
     *
     * 그리고 이 판정을 통과해도 **오븐이 도는 중에만** 오른쪽을 누른다(`Oven.kt`).
     * 신호 둘 + 문맥 하나가 다 맞을 때만 여는 문이다.
     */
    fun isOvenCleanupDialog(b: Bitmap): Boolean {
        if (!isConfirmDialog(b)) return false
        for (y in OVEN_CLEAN_BANDS) {
            var hit = 0; var n = 0
            var x = 100
            while (x <= 1340) {
                val c = px(b, x, y); n++
                if (Color.red(c) < 120 && Color.green(c) > 130 && Color.blue(c) > 140) hit++
                x += 10
            }
            if (n == 0 || hit * 100 / n < 70) return false
        }
        return true
    }

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

    /**
     * 토벌전 로비: '도전하기' 버튼이 주황(700,2700).
     *
     * ⚠️ `G < 200` 이 꼭 필요하다. 메인 화면의 같은 자리는 골드 숫자라 **노랑 (255,255,66)** 인데,
     *    이게 `B < 60` 을 **6 차이로** 겨우 비켜가고 있었다(실측). 화면이 조금만 밝아져도 메인을
     *    로비로 오판했을 것이다. 주황은 G≈149, 노랑은 G=255 라 이 조건이 둘을 크게 벌려 준다.
     */
    fun atTobolLobby(b: Bitmap): Boolean {
        val c = px(b, 700, 2700)
        return Color.red(c) > 230 && Color.green(c) in 111..199 && Color.blue(c) < 60
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

    /**
     * 퀘스트 띠가 **'퀘스트 NNNN 까지 한번에 클리어 하기'** 상태인가?
     * (밀린 퀘스트를 한 번에 받는 새 버튼. 열쇠 그림 + 파란·보라 띠 + 금색 글자)
     *
     * 색이 다른 상태와 정반대라 기존 판정이 전부 놓쳤다 — 실측(폰 1440x3120, BAR 영역 평균):
     * | 상태 | 평균 RGB | ratio |
     * |---|---|---|
     * | 완료(금색) | R>B 크게 | +1.11~1.28 |
     * | 미완료 | | +0.16~0.58 |
     * | **한번에 클리어(파랑)** | **(140,105,174)** | **-0.25** |
     * | 팝업이 덮음 | | < -0.6 |
     *
     * 파랑이 확실히 우세하고(B가 R·G보다 큼) 어둡지 않을 때만 참으로 본다.
     * 덮인 팝업은 ratio 가 더 낮게 내려가므로 아래쪽 경계로 갈린다.
     */
    fun isBulkClear(b: Bitmap): Boolean {
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
        if (n == 0) return false
        val r = rs.toDouble() / n; val g = gs.toDouble() / n; val bl = bs.toDouble() / n
        val mean = (r + g + bl) / 3.0
        if (mean < 90 || mean > 210) return false        // 너무 어둡거나(팝업) 너무 밝으면 아니다
        if (bl < r + 15) return false                    // 파랑이 빨강보다 확실히 커야 한다
        if (bl < g + 25) return false                    // 초록도 눌려 있어야 한다(보라 계열)
        return true
    }

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

    /**
     * 지금 화면에서 주요 판정값을 한 줄로 뽑는다.
     *
     * 좌표 매핑이 큰 것(독·닫기 버튼)으로는 맞아도 **띠 비율 같은 세밀한 값은 틀어질 수 있다.**
     * 그러면 판정이 조용히 어긋난다 — 퀘스트가 완료인데 미완료로 읽혀 엉뚱한 걸 돌리는 식이다.
     * 새 기기에서 그걸 확인하려면 값을 직접 재서 폰과 비교하는 수밖에 없다.
     *
     * 폰(설계 그대로) 기준값:
     *   독 ratio 메인 +0.32 / 가방 -0.45 / 뽑기 -0.60,  cv 정상 0.89 · 절전 0.11
     *   퀘스트 띠 완료 1.11~1.28 / 미완료 0.16~0.58 (임계 0.85)
     *   하단 ✕ 메인 0/7 · 서브 4~7/7
     */
    fun debugLine(b: Bitmap): String {
        val d = dockStats(b)
        return "독 ratio=" + f(d.ratio) + " cv=" + f(d.cv) + " 밝기=" + f(d.mean) +
            " · 퀘스트띠=" + f(questBarRatio(b)) +
            " · 뽑기버튼=" + gachaHits(b) + "/3" +
            " · 하단X=" + (if (hasCloseButton(b)) "있음" else "없음") +
            " · 메인=" + atMain(b) +
            " · 토벌로비=" + atTobolLobby(b) +
            " · 일일진입=" + atDailyEntry(b)
    }

    private fun f(v: Double) = String.format("%.2f", v)

    /**
     * 지금 화면이 **우리가 아는 게임 화면 중 하나로 보이나?**
     * 좌표 매핑이 맞는지 검증하는 데 쓴다 — 매핑이 맞으면 알아보이고, 틀리면 아무것도 안 걸린다.
     */
    fun looksLikeGame(b: Bitmap): Boolean =
        atMain(b) || atTobolLobby(b) || isBattleOver(b) || atDailyEntry(b) || atGuild(b)

    // ── 길드 화면 ──
    // 바탕이 청록인지 볼 네 점. 길드·로비 둘 다 4/4, 메인은 0/4 였다(실측).
    private val TEAL_PTS = arrayOf(
        intArrayOf(60, 1500), intArrayOf(1380, 1500), intArrayOf(60, 2700), intArrayOf(1390, 2750)
    )
    // '길드 토벌전' 타일(피냐타)이 있어야 할 자리. 평균 밝기 실측: 메인 42.6 / 길드 162.5 / 로비 94.9
    private val GUILD_TILE = intArrayOf(200, 400, 1800, 1980)

    /** 바탕이 청록인가? 길드·던전 같은 서브 화면이 여기 걸린다(메인은 갈색이라 안 걸린다). */
    fun isTealScreen(b: Bitmap): Boolean {
        var hit = 0
        for (p in TEAL_PTS) {
            val c = px(b, p[0], p[1])
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            if (bl > r * 2 && bl > g && bl > 40) hit++
        }
        return hit >= 3
    }

    /**
     * 길드 화면인가? 청록 바탕 **그리고** '길드 토벌전' 타일이 제자리에 밝게 보여야 한다.
     * 청록만 보면 던전·로비도 걸린다. 타일은 피냐타라 화려해서 바탕과 밝기가 크게 벌어진다.
     */
    fun atGuild(b: Bitmap): Boolean = isTealScreen(b) && tileBrightness(b) >= 130

    private fun tileBrightness(b: Bitmap): Double {
        var sum = 0.0; var n = 0
        var x = GUILD_TILE[0]
        while (x < GUILD_TILE[1]) {
            var y = GUILD_TILE[2]
            while (y < GUILD_TILE[3]) {
                val c = px(b, x, y)
                sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0; n++
                y += 4
            }
            x += 4
        }
        return if (n == 0) 0.0 else sum / n
    }

    // ── 일일 던전(daily.ps1) ──
    // ⚠️ PC 봇의 '던전' 탭은 (530,3054) 인데 **폰에서는 거기가 아이콘 아래 빈 배경**이다
    //    (실측 41,31,29). PC 는 터치 영역이 넓어 우연히 먹었던 것으로 보인다. 아이콘 한가운데로 옮겼다.
    val NAV_DUNGEON       = intArrayOf(505, 2990)    // 하단 네비 '던전'(교차한 검)
    val DAILY_TAB         = intArrayOf(850, 2835)    // '일일 던전' 서브탭 (눈대중보다 아래다)
    // ⚠️ 옛 `DAILY_FIRST (700,830)` 는 지웠다 — 목록이 스크롤되면 배너 사이 검은 띠를 누른다.
    //    지금은 `findDailyBanner` 로 화면을 보고 배너를 찾아 누른다.
    // ── 던전 화면 (2026-09-06 실기 스크린샷에서 전부 다시 잼) ──
    val DAILY_CHALLENGE   = intArrayOf(715, 2600)    // 도전하기!
    val DAILY_CONT_CHK    = intArrayOf(1078, 2645)   // 연속 도전 체크박스 (옛 값은 71px 위였다)
    val DAILY_PREV        = intArrayOf(64, 1400)     // ◀ 이전 던전 (쓰지 않는다, 기록용)
    // 자동 편성 (1325,2600) · 편성하기 — **누르지 않는다.** 쿠키 편성 화면으로 빠진다.
    // ▶ 다음 던전 — **던전 화면 안**의 화살표다(목록 화면이 아니다).
    // ⚠️ 목록 화면에서 이 자리는 배너 한가운데라, 목록에 있는 동안 누르면 엉뚱한 던전이 열린다.
    //    반드시 `atDailyEntry` 가 참일 때만 누를 것.
    val DAILY_NEXT        = intArrayOf(1375, 1400)   // ▶ 다음 던전 (실측 x1359~1392, y1378~1423)
    val DAILY_ACHIEVE     = intArrayOf(1339, 1610)   // 달성 보상 상자 (실측 x1240~1438, y1531~1690)
    // ⚠️ 옛 `DAILY_CLAIM_ALL (710,2840)` · `DAILY_MODAL_CLOSE (720,430)` 는 지웠다.
    //    실측해 보니 (710,2840) 은 **하단 '일일 던전' 서브탭**이었다 — 달성 보상 창은 안 닫히고
    //    탭만 건드렸다. 달성 보상 창의 좌표는 아직 못 쟀다(창 스크린샷이 필요하다).
    // ⚠️ 아래 소탕 좌표 셋은 **쓰지 않는다.** 소탕은 '지금 스테이지' 기준으로 보상을 주는데
    //    스테이지가 계속 올라가므로 지금 태우면 손해다(사용자 판단, 2026-09-06).
    //    실측값이라 지우지 않고 남겨 둔다 — 다시 재지 말 것. 다만 **부르는 곳이 있으면 안 된다.**
    val DAILY_SWEEP       = intArrayOf(150, 2600)    // 소탕 (SKIP 티켓을 쓴다)
    val DAILY_SWEEP_MAX   = intArrayOf(1095, 2515)   // 소탕 다이얼로그 '최대'
    val DAILY_SWEEP_GO    = intArrayOf(715, 2815)    // 소탕 다이얼로그 '소탕하기'

    /**
     * 일일 던전 **목록** 화면인가? (던전 배너가 세로로 쭉 늘어선 그 화면)
     * 하단 서브탭 '일일 던전' 자리가 밝은 청록이고, 그 오른쪽 '쟁탈전' 자리는 까맣다.
     * 실측: (720,2840) = (3,167,179) · (1150,2840) = (0,0,0).
     */
    fun atDailyList(b: Bitmap): Boolean {
        val tab = px(b, 720, 2840)
        if (!(Color.red(tab) < 60 && Color.green(tab) > 140 && Color.blue(tab) > 150)) return false
        val right = px(b, 1150, 2840)
        if (!(Color.red(right) < 45 && Color.green(right) < 45 && Color.blue(right) < 45)) return false
        // ⚠️ 여기까지는 던전 화면도 똑같이 통과한다(하단 서브탭 줄이 공용이다).
        //    던전 화면이 아닐 때만 목록이다 — 이걸 빠뜨려서 던전 화면을 목록으로 오인하고
        //    **쿠키 카드를 눌러 편성 화면으로 빠지는** 사고가 났다.
        return !atDailyEntry(b)
    }

    /**
     * 목록에 보이는 **모든** 던전 배너의 세로 중심. 위에서 아래 순서다.
     *
     * ⚠️ 고정 좌표로 첫 배너를 누르면 안 된다. 목록이 조금만 스크롤돼 있어도 **배너 사이 검은 띠**를
     *    누르게 되고, 탭이 헛돌아 '진입 실패'로 빠진다(실측: 옛 `DAILY_FIRST (700,830)` 자리가
     *    `(34,29,26)` — 딱 그 검은 띠였다).
     *
     * 그래서 세로로 훑어 **밝은 구간(배너)** 을 찾는다. 배경 청록은 밝기 57~59, 배너 사이 검은 띠는
     * 29~43, 배너는 90~223 이라 78 로 자르면 깨끗하게 갈린다.
     */
    fun findDailyBanners(b: Bitmap): IntArray {
        val out = ArrayList<Int>()
        var start = -1
        var y = 560
        while (y <= 2560) {
            val c = px(b, 700, y)
            val on = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 > 78
            if (on && start < 0) start = y
            if (!on && start >= 0) {
                if (y - start >= 100) out.add((start + y) / 2)
                start = -1
            }
            y += 6
        }
        if (start >= 0 && 2560 - start >= 100) out.add((start + 2560) / 2)
        return out.toIntArray()
    }

    private val ROW_SIG_X = intArrayOf(260, 420, 580, 740, 900, 1060, 1220, 1330)

    /**
     * 목록의 **한 줄**을 알아보는 지문. 던전마다 그림 색이 뚜렷이 달라 이걸로 충분하다.
     * (제목 글자는 게임 폰트라 OCR 이 안 된다 — 여러 번 확인된 사실이다.)
     */
    fun dailyRowSig(b: Bitmap, cy: Int): IntArray {
        val out = IntArray(ROW_SIG_X.size * 3)
        for ((i, x) in ROW_SIG_X.withIndex()) {
            val c = px(b, x, cy)
            out[i * 3] = Color.red(c); out[i * 3 + 1] = Color.green(c); out[i * 3 + 2] = Color.blue(c)
        }
        return out
    }

    /** 같은 줄인가? 애니메이션·반짝임이 있어 넉넉히 본다. */
    fun dailyRowMatch(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff += Math.abs(a[i] - b[i])
        return diff < a.size * 26
    }

    /**
     * 던전 **안** 화면인가? (도전하기·소탕·자동 편성이 있는 화면)
     *
     * ⚠️ 하단 서브탭 줄(도전 던전 / 일일 던전 / 쟁탈전)은 **목록 화면과 던전 화면에 똑같이 있다.**
     *    실측으로 확인했다 — 두 화면 모두 (720,2840)=(3,167,179), (1150,2840)=(0,0,0).
     *    그래서 그 줄만으로는 둘을 절대 못 가른다. 던전 화면에만 있는 **하단 버튼 줄**을 본다:
     *    소탕(밝은 청록) + 자동 편성(초록). 실측 던전 (82,193,204)/(6,199,142) · 목록 (18,54,66)/(53,149,163).
     */
    fun atDailyEntry(b: Bitmap): Boolean {
        val sweep = px(b, 210, 2600)
        if (!(Color.red(sweep) < 130 && Color.green(sweep) > 150 && Color.blue(sweep) > 160)) return false
        val auto = px(b, 1325, 2600)
        return Color.red(auto) < 90 && Color.green(auto) > 160 && Color.blue(auto) in 100..190
    }

    // 도전하기 버튼의 **바탕색**을 보는 자리. 글자(흰색)를 피해 왼쪽 안쪽을 고른다.
    private val DAILY_CH_PT = intArrayOf(560, 2600)

    /** 도전하기가 주황 = 아직 남은 열쇠가 있다. */
    fun dailyChallengeOpen(b: Bitmap): Boolean {
        val c = px(b, DAILY_CH_PT[0], DAILY_CH_PT[1])
        return Color.red(c) > 200 && Color.green(c) in 90..190 && Color.blue(c) < 80
    }

    /** 도전하기가 청록 = 열쇠를 다 썼다. 이 던전은 끝. */
    fun dailyChallengeDone(b: Bitmap): Boolean {
        val c = px(b, DAILY_CH_PT[0], DAILY_CH_PT[1])
        return Color.red(c) < 80 && Color.green(c) > 140 && Color.blue(c) > 150
    }

    /** '연속 도전' 이 켜져 있나(노란 체크)? 켜져 있으면 꺼야 한다 — 아래 Daily 주석 참고. */
    fun dailyContChecked(b: Bitmap): Boolean {
        val c = px(b, DAILY_CONT_CHK[0], DAILY_CONT_CHK[1])
        return Color.red(c) > 230 && Color.green(c) > 200 && Color.blue(c) < 120
    }

    private val DAILY_SIG_PTS = arrayOf(
        intArrayOf(160, 600), intArrayOf(280, 600), intArrayOf(400, 600),
        intArrayOf(160, 700), intArrayOf(280, 700), intArrayOf(400, 700),
        intArrayOf(160, 800), intArrayOf(280, 800), intArrayOf(400, 800)
    )

    /**
     * 어느 던전인지 알아보는 지문 — 좌상단 '보상 아이콘' 박스를 뜬다.
     * 던전마다 색이 매우 뚜렷하고(XP별·코인·반죽·젬·크리스탈) 애니메이션이 없어 안정적이다.
     * 제목 글자로 하면 폰트를 못 읽어서(OCR 불가) 안 된다.
     */
    fun dailySig(b: Bitmap): IntArray {
        val out = IntArray(DAILY_SIG_PTS.size * 3)
        for ((i, p) in DAILY_SIG_PTS.withIndex()) {
            val c = px(b, p[0], p[1])
            out[i * 3] = Color.red(c); out[i * 3 + 1] = Color.green(c); out[i * 3 + 2] = Color.blue(c)
        }
        return out
    }

    /** 같은 던전인가? 실측: 같은 던전 ~225 / 다른 던전 1400+ → 700 이 안전한 경계. */
    fun dailySigMatch(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff += Math.abs(a[i] - b[i])
        return diff < 700
    }

    // ── 아레나(arena.ps1) ──  좌표는 시즌2 기준 실측
    val ARENA_BANNER   = intArrayOf(710, 1295)       // 던전 화면의 아레나 배너
    val ARENA_GO       = intArrayOf(1092, 2519)      // '도전하러 가기' → 로비
    val ARENA_CHALLENGE = intArrayOf(710, 2480)      // 도전하기
    val ARENA_NEXT     = intArrayOf(1225, 2480)      // 다음 상대
    val ARENA_CONTINUE = intArrayOf(720, 2790)       // 결과/승급 '화면을 탭하세요'
    val ARENA_CENTER   = intArrayOf(720, 1560)       // 승급·강등 축하 화면(아무 곳이나 탭)

    // OCR 로 읽을 자리 (x, y, w, h)
    val A_MY   = intArrayOf(305, 1415, 165, 58)      // 내 전투력(흰 글씨)
    val A_OPP  = intArrayOf(750, 565, 200, 60)       // 상대 전투력(금 글씨)
    val A_CUR  = intArrayOf(600, 40, 150, 52)        // 아레나 재화(상단)
    val A_PTS  = intArrayOf(590, 1415, 140, 58)      // 내 아레나 점수

    /**
     * '300 크리스탈로 상대 새로고침 하시겠습니까?' 팝업이 떠 있나?
     * 오른쪽 [확인]이 주황이면 참. 정상 로비는 그 자리가 청록이라 확실히 갈린다.
     * 이게 뜨면 점수도 못 읽고 갇히므로, 만나면 **왼쪽 [취소]** 를 누르고 지금 상대와 그냥 도전한다.
     * (오른쪽 주황은 크리스탈 300 을 쓴다 — 어느 창에서든 절대 누르지 않는다.)
     */
    fun isArenaRefreshDialog(b: Bitmap): Boolean {
        for (p in arrayOf(intArrayOf(1040, 2750), intArrayOf(1120, 2830))) {
            val c = px(b, p[0], p[1])
            if (!(Color.red(c) > 220 && Color.blue(c) < 90)) return false
        }
        return true
    }

    // ── 보스 소환 / 쿠키 조합(프리셋) ──
    val BOSS_SUMMON = intArrayOf(710, 406)           // 상단 '보스 소환' 빨간 배너 중앙

    /**
     * 상단 '보스 소환' 빨간 배너가 있나? = **아직 이 보스를 못 깼다**.
     * 깨서 스테이지가 밀리면 이 자리는 하늘·배경이 되어 빨강이 아니다 — 승패를 이걸로 가른다.
     * PC 봇 실측: (562,380) R245 G78 B34 / (620,410) R233 G108 B34.
     *
     * ⚠️ 전투 **중**에도 배너는 사라진다(PC 봇이 여기서 세 번 틀렸다). 그래서 소환 직후에 보면
     *    안 되고, 전투가 끝날 만큼 기다렸다가 봐야 한다.
     */
    private val BOSS_PTS = arrayOf(intArrayOf(562, 380), intArrayOf(620, 410))

    fun hasBossBanner(b: Bitmap): Boolean {
        for (p in BOSS_PTS) {
            val c = px(b, p[0], p[1])
            if (!(Color.red(c) > 200 && Color.green(c) < 130 && Color.blue(c) < 90)) return false
        }
        return true
    }
    val NAV_COOKIE  = intArrayOf(70, 2972)           // 하단 좌측 '쿠키' 탭 → 편성 화면
    val NAV_BATTLE  = intArrayOf(718, 2972)          // 하단 '전투/홈' 탭 → 메인 전투로 복귀
    val PRESET_TABS = arrayOf(                       // 쿠키 편성 화면의 프리셋 1~5 탭
        intArrayOf(74, 698), intArrayOf(194, 698), intArrayOf(312, 698),
        intArrayOf(428, 698), intArrayOf(542, 698)
    )

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
        // 설계 좌표를 이 기기 화면으로 환산해서 창을 잡는다.
        val x1 = Coords.x(X1); val y1 = Coords.y(Y1)
        val w = Coords.x(X2) - x1
        val h = Coords.y(Y2) - y1
        val half = Coords.y(HALF)
        // 넓이에 비례하는 임계값이라 배율의 제곱으로 줄어든다(Coords.area 가 그 계산이다).
        val need = Coords.area(NEED)
        if (w <= 0 || h <= 0 || b.width < x1 + w || b.height < y1 + h) return null
        val buf = IntArray(w * h)
        b.getPixels(buf, 0, w, x1, y1, w, h)

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
        var y = half
        while (y < h - half) {
            var s = 0
            for (k in y - half until y + half) s += rows[k]
            if (s > best) { best = s; bestY = y }
            y += 10
        }
        if (best < need) return null
        // 돌려주는 값은 다시 **설계 좌표**다. 탭은 TapService 가 또 환산한다.
        val cy = if (Coords.sy == 0.0) Y1 + bestY else Y1 + Math.round(bestY / Coords.sy).toInt()
        return intArrayOf((X1 + X2) / 2, cy)
    }
}
