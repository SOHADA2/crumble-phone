package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 봇 본체 — PC 봇 `bot.ps1`(+ `box.ps1`)의 퀘스트 순환을 옮긴 것.
 *
 * 하는 일은 넷이다.
 *   ① 보상 자동 받기 — 미션 '모두 받기' + 출석 '받기' (20분마다)
 *   ② 완료된 퀘스트 수령 — 퀘스트 띠가 완료 색이면 눌러서 받는다
 *   ③ 다음 퀘스트가 행동형이면 대신 해 준다 — 쿠키 뽑기 10회 / 가방 상자 / 오븐 장비 뽑기
 *   ④ 스테이지가 막히면 보스를 깬다 — 쿠키 조합 1~5 를 바꿔 가며 최대 5번
 *
 * ★ 이 고리를 모르면 초반에 영영 못 나간다(PC 봇에서 몇 시간을 헛돌았다):
 *     방치 전투로 몹 처치 → 퀘스트 완료 → **보상 수령** → 레벨업 재료·골드
 *       → 편성 팀 레벨업 → 전투력 상승 → 보스 클리어 → 다음 스테이지
 *   보상을 안 받으면 재료가 0 이라 레벨업을 눌러도 아무 일이 없다.
 */
object Chores {

    private const val POWERSAVE_CV = 0.4          // 독의 변동계수가 이보다 낮으면 절전(평평한 화면)
    private const val COMPLETE_RATIO = 0.85       // 퀘스트 띠가 이 이상이면 완료
    private const val REWARD_MINUTES = 20L        // 보상 자동 받기 주기
    private const val BOSS_WAIT_SEC = 35L         // 보스 소환 후 결과 대기 (실측: 전투 ~30초에 종료)
    private const val BOSS_PRESETS = 5            // 쿠키 조합 1~5 를 하나씩 바꿔 가며 도전한다
    private const val BACKOFF_MINUTES = 15L       // 조합을 다 써도 못 깼을 때 쉬는 시간
    private const val REWARD_STEPS = 6            // 보상 받기 걸음 수(미션 3탭 + 출석 3단계) — 진행률용

    /**
     * 이 퀘스트에서 오븐을 이미 돌려 봤나.
     * 스테이지 클리어형 퀘스트도 화면이 안 바뀌어서 오븐과 구분이 안 된다. 한 번 돌려 보고
     * 완료가 안 되면 오븐 퀘스트가 아닌 것으로 보고 넘긴다 — 안 그러면 계속 오븐만 돌린다.
     */
    private var ovenTried = false

    fun start(ctx: Context, maxQuests: Int = 0) {
        if (Runner.running) { Bot.log("이미 무언가 돌고 있어요"); return }
        if (!TapService.isReady) { Runner.set("시작 못 함", "접근성 서비스를 켜 주세요"); return }
        if (CaptureService.instance == null) { Runner.set("시작 못 함", "화면 읽기를 허용해 주세요"); return }
        Runner.running = true; Runner.task = "봇 본체"
        ovenTried = false
        thread(name = "chores") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                loop(maxQuests)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    /** 보상만 한 번 받고 끝낸다(제일 안전해서 처음 시험할 때 쓴다). */
    fun startRewardsOnly(ctx: Context) {
        if (Runner.running) { Bot.log("이미 무언가 돌고 있어요"); return }
        if (!TapService.isReady) { Runner.set("시작 못 함", "접근성 서비스를 켜 주세요"); return }
        if (CaptureService.instance == null) { Runner.set("시작 못 함", "화면 읽기를 허용해 주세요"); return }
        Runner.running = true; Runner.task = "보상 받기"
        thread(name = "rewards") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                claimMissions()
                claimAttendance()
                Runner.set("보상 받기 끝", "미션·출석을 받아 왔어요")
                Runner.lastResult = "미션·출석 보상 받음"
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  본 고리
    // ══════════════════════════════════════════════════════════

    private fun loop(maxQuests: Int) {
        var quests = 0            // 받아 낸 퀘스트 수
        var handled = 0           // 뽑기·상자처럼 직접 해 준 횟수
        var offMain = 0           // 메인이 아닌 상태가 연속 몇 번인지
        var covered = 0           // 퀘스트 띠 가림이 연속 몇 번째인지
        var powersave = 0         // 절전 해제를 몇 번 시도했는지
        var bossTries = 0         // 이 퀘스트에서 보스를 몇 번 도전했는지(조합 번호이기도 하다)
        var bossWaitUntil = 0L    // 내가 소환한 보스전이 끝날 때까지
        var probeAllowedAt = 0L   // 조합을 다 써서 쉬는 중이면 이 시각까지 탐색을 미룬다
        var nextRewardAt = System.currentTimeMillis()      // 시작하자마자 한 번 받는다

        while (Runner.running) {
            if (maxQuests > 0 && quests >= maxQuests) { Runner.set("봇 본체 끝", "퀘스트 " + quests + "개를 받았어요"); break }

            val b = Runner.shot()
            if (b == null) { Runner.set("화면을 못 읽었어요", "다시 시도 중"); Runner.sleep(5000); continue }

            // ── 화면이 '평평'하면 절전이거나 공지 팝업이 독을 덮은 것이다 ──
            // 두 값이 너무 붙어 있어(절전 mean31·cv0.23 / 공지 mean44·cv0.37) 하나로 못 가른다.
            // 먼저 왕관을 씌워 보고(진짜 절전이면 풀린다), 안 되면 닫기 수단을 단계적으로 바꾼다.
            val dock = Screen.dockStats(b)
            if (dock.cv < POWERSAVE_CV) {
                powersave++
                if (powersave == 1) Bot.log("화면이 평평함 (cv=" + fmt(dock.cv) + ", 밝기=" + fmt(dock.mean) + ") - 절전 해제/공지 닫기 시도")
                if (powersave <= 2) { Runner.set("절전 깨우는 중", "왕관 씌우기"); Runner.wake() }
                else {
                    Runner.set("공지·팝업 닫는 중")
                    when (powersave - 2) {
                        1 -> { Bot.log("  뒤로가기"); TapService.back(); Runner.sleep(2000) }
                        2 -> Runner.tap(Screen.SUB_CLOSE, 2000)
                        3 -> Runner.tap(Screen.NAV_CLOSE, 2000)
                        4 -> Runner.tap(Screen.OUTSIDE, 2000)
                        5 -> { Bot.log("  뒤로가기(재시도)"); TapService.back(); Runner.sleep(2000) }
                        else -> { Bot.log("  못 빠져나옴 - 다시 처음부터"); powersave = 0 }
                    }
                }
                Runner.sleep(4000); continue
            }
            if (powersave > 0) { powersave = 0; Bot.log("화면 정상 - 작업 재개") }

            // ── 내가 소환한 보스전이 도는 중이면 아무것도 누르지 않고 기다린다 ──
            // 보스전 중인지를 화면 색으로 알아내려던 시도는 두 번 다 실패했다(PC 봇 기록):
            //   상단 이름표 자리는 스테이지 배경에 좌우되고(사막 -13 / 초록 들판 +85),
            //   '보스 소환' 버튼 자리는 보스 HP 바도 같은 빨강이라 전투 중에도 값이 똑같다.
            // 그래서 화면을 안 읽고 '내가 소환한 시각'만 기억해서 그 동안 기다린다.
            //
            // ※ PC 봇은 이 검사를 팝업 닫기·보상 뒤에 뒀지만 여기서는 앞으로 당겼다.
            //   전투 중에 뒤로가기를 누르면 '전투를 바로 종료하시겠습니까?' 창이 뜨고,
            //   보상을 받으러 창을 여닫으면 전투 중에 화면을 헤집게 된다. 기다리는 게 맞다.
            if (System.currentTimeMillis() < bossWaitUntil) {
                val left = (bossWaitUntil - System.currentTimeMillis()) / 1000
                Runner.status = "보스전 진행 중"
                Runner.detail = "쿠키 조합 " + bossTries + "/" + BOSS_PRESETS + " · " + left + "초 남음"
                Runner.setProgress((BOSS_WAIT_SEC - left).toInt(), BOSS_WAIT_SEC.toInt())
                Runner.sleep(4000); continue
            }

            val atMain = dock.ratio >= 0.1 && !Screen.hasCloseButton(b)

            // ── 보상 자동 받기 ──
            if (atMain && System.currentTimeMillis() >= nextRewardAt) {
                claimMissions()
                claimAttendance()
                nextRewardAt = System.currentTimeMillis() + REWARD_MINUTES * 60_000
                Runner.sleep(3000); continue      // 창을 여닫았으니 이 바퀴는 마치고 다시 판정
            }

            // ── 메인이 아니면 퀘스트 띠 좌표가 의미 없다 ──
            // 쿠키런 공지는 닫기 X 자리가 제각각이라 좌표를 고정하면 위험하다.
            // 뒤로가기가 대부분을 닫아 주므로 그걸 주력으로, 단계별로 수단을 바꾼다.
            if (!atMain) {
                offMain++
                if (offMain == 1) Runner.set("팝업·공지 닫는 중")
                when (offMain) {
                    3 -> { Bot.log("  하단 X"); Runner.tap(Screen.NAV_CLOSE, 2000) }
                    5 -> { Bot.log("  뒤로가기"); TapService.back(); Runner.sleep(2000) }
                    7 -> { Bot.log("  팝업 바깥"); Runner.tap(Screen.OUTSIDE, 2000) }
                    9 -> { Bot.log("  뒤로가기(재시도)"); TapService.back(); Runner.sleep(2000) }
                    else -> if (offMain >= 12) { Bot.log("  못 닫음 - 다시 처음부터"); offMain = 0 }
                }
                Runner.sleep(4000); continue
            }
            offMain = 0

            // ── 퀘스트 띠 ──
            val ratio = Screen.questBarRatio(b)

            // 정상 범위는 대략 -0.5~1.3. -0.6 미만이면 오븐 비교 팝업 같은 게 띠를 덮고 있다.
            // 바깥 탭이 안 먹는 화면도 있어서(2분간 헛탭한 사고) 수단을 단계적으로 바꾼다.
            if (ratio < -0.6) {
                covered++
                if (covered == 1) Runner.set("가림막 치우는 중", "퀘스트 띠가 덮여 있어요 (" + fmt(ratio) + ")")
                when {
                    covered <= 2 -> Runner.tap(Screen.OUTSIDE, 2000)
                    covered <= 4 -> { Bot.log("  하단 X 시도"); Runner.tap(Screen.NAV_CLOSE, 2000) }
                    covered <= 6 -> { Bot.log("  결과창 X 시도"); Runner.tap(Screen.GACHA_CLOSE, 2000) }
                    covered <= 8 -> { Bot.log("  뒤로가기 시도"); TapService.back(); Runner.sleep(2000) }
                    else -> { Bot.log("  못 치움 - 다시 처음부터"); covered = 0 }
                }
                Runner.sleep(3000); continue
            }
            covered = 0

            if (ratio >= COMPLETE_RATIO) {
                quests++
                Runner.set("퀘스트 보상 받는 중", "지금까지 " + quests + "개")
                Bot.log("퀘스트 수령 (" + fmt(ratio) + ")")
                Runner.tap(Screen.QUEST_BAR, 2500)
                // 새 퀘스트가 올라왔으니 오븐·보스 기회를 되돌린다.
                // 백오프도 같이 푼다 — 안 그러면 앞 퀘스트 때문에 걸린 15분이
                // 이미 교체된 새 퀘스트의 탐색까지 막아 버린다.
                ovenTried = false
                bossTries = 0
                probeAllowedAt = 0L
                Runner.lastResult = "퀘스트 " + quests + "개 수령 · 대신 해 준 일 " + handled + "번"
                Runner.sleep(3000); continue
            }

            // ── 미완료 퀘스트 ──
            Runner.status = "퀘스트 기다리는 중"
            Runner.detail = "받은 퀘스트 " + quests + "개 · 대신 해 준 일 " + handled + "번"

            if (System.currentTimeMillis() < probeAllowedAt) { Runner.sleep(8000); continue }

            // 행동형(뽑기·상자·오븐)이면 대신 해 준다.
            if (probe()) { handled++; Runner.sleep(3000); continue }

            // 탐색으로도 안 풀렸다 = 스테이지 클리어형. 보스를 깨야 넘어간다.
            // 이때 게임이 '보스만 눌리는 락'을 건다(빈 곳이나 퀘스트를 눌러도 안 풀리고 보스 버튼만
            // 반응한다). 보스를 한 판 소환해 들어갔다 나오면 풀린다.
            //
            // ⚠️ 여기서 버튼이 있는지 색으로 확인하지 않는다. PC 봇은 빨강 판정을 썼다가
            //    배너 위로 전투 이펙트·데미지 숫자가 겹쳐 값이 흔들려(락 2.09 vs 다른 프레임 0.76)
            //    버튼을 놓치고 갇혔다. 이 자리는 탐색이 이미 실패한 뒤라 바로 눌러도 된다.
            if (bossTries < BOSS_PRESETS) {
                bossTries++
                Runner.set("보스전 준비 중", "쿠키 조합 " + bossTries + "/" + BOSS_PRESETS)
                switchPreset(bossTries)            // 보스가 세면 조합을 바꿔 가며 도전한다
                Runner.tap(Screen.BOSS_SUMMON, 6000)
                bossWaitUntil = System.currentTimeMillis() + BOSS_WAIT_SEC * 1000
                continue
            }

            // 조합 1~5 를 다 써도 못 깼다. 여기서 멈춰 버리면 그 뒤로 보상도 못 받으니
            // 잠시 쉬었다가 처음부터 다시 해 본다(그 사이 쿠키가 크면 저절로 넘어가기도 한다).
            Runner.set("보스를 못 깼어요", "쿠키 조합 1~5 모두 도전함 · " + BACKOFF_MINUTES + "분 뒤 다시")
            Bot.log("쿠키 조합 1~5 를 모두 도전했지만 실패했어요 (" + BACKOFF_MINUTES + "분 뒤 재시도)")
            probeAllowedAt = System.currentTimeMillis() + BACKOFF_MINUTES * 60_000
            bossTries = 0                          // 쉬고 나면 조합 1번부터 다시
            Runner.sleep(8000)
        }
        if (Runner.running) Runner.set("봇 본체 끝", "퀘스트 " + quests + "개를 받았어요")
        else Runner.set("멈췄어요", "퀘스트 " + quests + "개까지 받았어요")
        Runner.lastResult = "퀘스트 " + quests + "개 수령 · 대신 해 준 일 " + handled + "번"
    }

    /**
     * 퀘스트 띠를 눌러 '이 퀘스트가 무슨 유형인지'를 화면 변화로 알아낸다.
     *   뽑기 화면이 열리면  → 10회 뽑기
     *   가방이 열리면      → 첫 칸 상자 사용
     *   화면이 그대로면    → 오븐 장비 뽑기 (한 번 해 보고 아니면 다음부터 지나간다)
     * 성공적으로 대신 해 줬으면 true.
     */
    private fun probe(): Boolean {
        Runner.set("다음 할 일 찾는 중")
        Runner.tap(Screen.QUEST_BAR, 2500)
        val b = Runner.shot() ?: return false

        val hits = Screen.gachaHits(b)
        if (hits == 3) { Bot.log("  뽑기 화면 -> 10회 수행"); return gacha10() }

        // 가방은 하단에만 열려서 '보스 소환'이 그대로 보인다 → 메인 판정보다 먼저 봐야 한다.
        if (Screen.isBottomPanel(b)) {
            // 뽑기 화면도 하단 청록 패널이라 이 판정을 통과한다. 버튼이 2개만 주황이면
            // 뽑기 화면이 거의 확실하니(하나가 가려졌거나 UI 가 바뀐 것) 손대지 않는다.
            if (hits >= 2) {
                Bot.log("  하단 패널인데 뽑기 버튼이 " + hits + "/3 만 주황 - 건드리지 않음")
                Runner.tap(Screen.NAV_CLOSE, 2000)
                return false
            }
            Bot.log("  가방 열림 -> 상자 사용")
            return useBox()
        }

        if (Screen.dockRatio(b) >= 0.1) {
            // 화면이 안 바뀌는 유형 = 메인의 무언가를 가리키는 손가락 힌트(오븐) 또는 스테이지 클리어형.
            if (ovenTried) { Bot.log("  화면이 안 바뀜 - 오븐은 이미 해 봤어요(오븐 퀘스트가 아닙니다)"); return false }
            ovenTried = true
            Bot.log("  화면이 안 바뀜 -> 오븐 처리")
            return Oven.run()
        }

        Bot.log("  모르는 화면 - 되돌아 나감")
        Runner.tap(Screen.NAV_CLOSE, 2000)
        val after = Runner.shot()
        if (after != null && !Screen.atMain(after)) { TapService.back(); Runner.sleep(3000) }
        return false
    }

    /**
     * 쿠키 조합(프리셋) n 번으로 바꾼다. 보스가 세서 못 깰 때 1~5 를 하나씩 시험하는 데 쓴다.
     * 하단 '쿠키' 탭 → 프리셋 탭 → '전투' 탭으로 돌아온다.
     */
    private fun switchPreset(n: Int) {
        if (n < 1 || n > Screen.PRESET_TABS.size) return
        Runner.tap(Screen.NAV_COOKIE, 2000)
        Runner.tap(Screen.PRESET_TABS[n - 1], 1500)
        Runner.tap(Screen.NAV_BATTLE, 2000)
    }

    // ══════════════════════════════════════════════════════════
    //  대신 해 주는 일들
    // ══════════════════════════════════════════════════════════

    /**
     * 쿠키 뽑기 10회. 좋은 게 나오면 컷신이 끼어들어 연출이 길어지므로
     * 고정 대기로 좌표를 찍지 않고 목표 화면에 닿을 때까지 확인하면서 나아간다.
     */
    private fun gacha10(): Boolean {
        Runner.set("쿠키 뽑기 10회 하는 중")
        Runner.tap(Screen.GACHA_10, 3000)

        // ── 뽑기가 '실제로 시작됐는지'부터 확인한다 ──
        // 아래 정리 고리는 '뽑기 화면이면 성공'으로 본다. 10회 버튼이 안 눌렸는데 화면이 그대로면
        // '뽑고 돌아왔다'로 착각해 조용한 실패가 된다(퀘스트는 영영 안 끝난다).
        // ⚠ 여기서 10회를 다시 누르면 안 된다. 앞의 탭이 실은 먹었다면 두 번 뽑게 된다.
        var started = false
        for (i in 1..3) {
            if (!Runner.running) return false
            val b = Runner.shot() ?: return false
            if (!Screen.isGachaScreen(b)) { started = true; break }
            Runner.sleep(2000)
        }
        if (!started) { Bot.log("    10회를 눌렀는데 화면이 그대로 - 뽑기가 시작되지 않았어요"); return false }

        // 결과창 닫기: 뽑기 선택 화면으로 돌아올 때까지. 컷신 중의 탭은 대개 스킵으로 먹힌다.
        // 10연차는 쿠키를 한 장씩 넘기는 데다 새 쿠키·희귀 쿠키가 걸리면 연출이 더 붙는다 → 넉넉히 24번.
        var back = false
        for (i in 1..24) {
            if (!Runner.running) return false
            val b = Runner.shot() ?: return false
            if (Screen.isGachaScreen(b)) { back = true; break }
            // 연출이 끝나면서 메인까지 나가 버리는 경우도 있다. 이미 뽑았으니 성공.
            // '독이 밝다'만 보면 안 된다 — 결과 화면이 떠 있어도 독은 메인처럼 읽힌다.
            if (Screen.atMain(b) && !Screen.isBottomPanel(b) && Screen.questBarRatio(b) > -0.6) {
                Bot.log("    뽑기 끝나고 메인까지 나옴")
                return true
            }
            Runner.set("뽑기 결과 정리하는 중")
            Runner.setProgress(i, 24)
            Runner.tap(Screen.GACHA_CLOSE, 1200)
        }
        if (!back) { Bot.log("    뽑기 화면 복귀 실패"); return false }

        // 뽑기 화면을 닫고 메인으로. 퀘스트 띠가 제자리에 보여야 진짜 메인이다
        // (띠가 가려져 있으면 아직 무언가 덮고 있다는 뜻이라 바깥 고리가 그 가림막을 떠안는다).
        for (i in 1..8) {
            if (!Runner.running) return false
            val b = Runner.shot() ?: return false
            if (Screen.atMain(b) && !Screen.isBottomPanel(b) && !Screen.isGachaScreen(b) &&
                Screen.questBarRatio(b) > -0.6) return true
            Runner.tap(Screen.NAV_CLOSE, 2000)
        }
        Bot.log("    메인 복귀 실패")
        return false
    }

    /**
     * 가방 첫 칸의 상자를 쓴다('가방에서 상자 N개 사용하기' 퀘스트).
     * 첫 칸이 상자라는 전제다 — '사용하기'가 안 보이면 아무것도 누르지 않고 가방을 닫는다.
     */
    private fun useBox(): Boolean {
        Runner.set("가방에서 상자 여는 중")
        Runner.tap(Screen.BAG_SLOT1, 4000)
        val b = Runner.shot() ?: return false
        if (!Screen.hasUseButton(b)) {
            Bot.log("    '사용하기'가 안 보임 - 아무것도 하지 않고 가방을 닫습니다")
            Runner.tap(Screen.NAV_CLOSE, 3000)
            Runner.tap(Screen.NAV_CLOSE, 3000)
            return false
        }
        Bot.log("    사용하기 -> 보상 수령")
        Runner.tap(Screen.BAG_USE, 5000)
        Runner.tap(Screen.BAG_DISMISS, 3000)
        Runner.tap(Screen.NAV_CLOSE, 3000)
        Runner.tap(Screen.NAV_CLOSE, 3000)
        return true
    }

    // ══════════════════════════════════════════════════════════
    //  보상 자동 받기
    // ══════════════════════════════════════════════════════════

    /** 미션 창을 열어 일일·주간·도전 탭에서 각각 '모두 받기'. 이미 받았으면 회색이라 눌러도 무해하다. */
    private fun claimMissions() {
        Runner.set("보상 받기", "미션")
        Runner.setProgress(0, REWARD_STEPS)
        Runner.tap(Screen.ICON_MISSION, 2500)
        val chk = Runner.shot()
        if (chk != null && Screen.atMain(chk)) { Bot.log("  [보상] 미션 창이 안 열렸어요 - 건너뜁니다"); return }
        var step = 0
        for (tab in Screen.MISSION_TABS) {
            if (!Runner.running) return
            Runner.setProgress(++step, REWARD_STEPS)
            Runner.tap(tab, 1500)
            Runner.tap(Screen.CLAIM_ALL, 1800)
            Runner.tap(Screen.CLAIM_ALL, 1200)   // 보상 팝업이 떴으면 한 번 더(없으면 무해)
        }
        Runner.tap(Screen.SUB_CLOSE, 1500)
        Runner.tap(Screen.SUB_CLOSE, 2000)
        Bot.log("  [보상] 미션 수령 완료")
    }

    /**
     * 달력(이벤트 허브)에서 기적의 출석·신규 출석 '받기'.
     * 허브엔 룰렛(티켓 소모)이 섞여 있지만 여기서 누르는 자리는 전부 하단 탭/받기(y≥2570)라
     * 룰렛 스핀 버튼(y≈1870)과 절대 안 겹친다.
     */
    private fun claimAttendance() {
        Runner.set("보상 받기", "출석")
        Runner.setProgress(3, REWARD_STEPS)      // 미션 세 탭을 이미 지나왔다
        Runner.tap(Screen.ICON_CALENDAR, 2500)
        val chk = Runner.shot()
        if (chk != null && Screen.atMain(chk)) { Bot.log("  [보상] 이벤트 창이 안 열렸어요 - 건너뜁니다"); return }
        Runner.tap(Screen.ATT_MIRACLE, 1800)     // 기적의 출석 탭
        Runner.tap(Screen.ATT_CLAIM, 1800)
        Runner.tap(Screen.ATT_CLAIM, 1200)
        Runner.setProgress(4, REWARD_STEPS)
        Runner.tap(Screen.ATT_NEW, 1800)         // 신규 출석 탭(기적을 고른 뒤에야 보인다)
        Runner.tap(Screen.ATT_CLAIM, 1800)
        Runner.tap(Screen.ATT_CLAIM, 1200)
        Runner.setProgress(5, REWARD_STEPS)
        Runner.tap(Screen.SUB_CLOSE, 1500)
        Runner.tap(Screen.SUB_CLOSE, 2000)
        Bot.log("  [보상] 출석 수령 완료")
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
