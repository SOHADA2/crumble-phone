package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 퀘스트 자동(관제 화면의 '퀘스트') — PC 봇 `bot.ps1`(+ `box.ps1`)의 퀘스트 순환을 옮긴 것.
 * PC 봇에서는 이걸 '봇 본체' 라고 불렀다. 화면에서는 토벌전·아레나와 같은 결로 '퀘스트' 다.
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
    private const val COMPLETE_FLOOR = 0.45       // 학습으로도 이 밑으로는 안 내린다
    private const val BAR_CHANGED = 0.35          // 띠 비율이 이만큼 달라지면 '다른 퀘스트로 바뀐 것'

    /**
     * 이 기기에서 '완료'로 볼 띠 비율. 폰 실측은 완료 1.11~1.28 / 미완료 0.16~0.58 이라
     * 0.85 가 한가운데지만, 화면이 작거나 색이 다른 기기에서는 완료가 0.85 밑으로 읽힐 수 있다.
     * 탐색이 실제로 보상을 받아 낸 것을 확인하면(띠가 바뀜) 그때 본 값에 맞춰 낮춘다.
     */
    private var doneRatio = COMPLETE_RATIO

    /**
     * 이 실행에서 **내 탭이 게임에 실제로 먹었다는 증거**를 한 번이라도 봤나.
     * (퀘스트 띠가 눌러서 갈렸다 / 뽑기·가방이 열렸다 / 미션 창이 열렸다)
     *
     * 증거가 없으면 오븐을 돌리지 않는다. 오븐은 재화를 쓰는 유일한 동작인데, 판정이 "화면이
     * 안 바뀜"이라 **탭이 아예 안 먹는 기기에서도 똑같이 참**이 된다 — 갤럭시 탭에서 아무것도
     * 진행되지 않는 채로 오븐만 돌던 게 이것이다.
     */
    private var tapsProven = false

    private const val PROBE_NONE = 0      // 아무것도 못 함
    private const val PROBE_DID = 1       // 뽑기·상자·오븐을 대신 해 줬다
    private const val PROBE_CLAIMED = 2   // 누르고 보니 완료 퀘스트였다(보상을 받았다)
    private const val BACKOFF_MINUTES = 15L       // 보스가 필요한 퀘스트라 잠시 쉬는 시간
    private const val REWARD_STEPS = 6            // 보상 받기 걸음 수(미션 3탭 + 출석 3단계) — 진행률용

    /**
     * 이 퀘스트에서 오븐을 이미 돌려 봤나.
     * 스테이지 클리어형 퀘스트도 화면이 안 바뀌어서 오븐과 구분이 안 된다. 한 번 돌려 보고
     * 완료가 안 되면 오븐 퀘스트가 아닌 것으로 보고 넘긴다 — 안 그러면 계속 오븐만 돌린다.
     */
    private var ovenTried = false

    fun start(ctx: Context, maxQuests: Int = 0) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "퀘스트"
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
        if (!Runner.guard()) return
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
        var probeAllowedAt = 0L   // 보스가 필요한 퀘스트라 쉬는 중이면 이 시각까지 탐색을 미룬다
        var bossNoticed = false   // '보스가 필요하다'는 안내를 한 번만 남기려고
        var bulkTries = 0         // '한번에 클리어'를 몇 번 눌러 봤는지(안 먹으면 그만 두려고)
        var loggedFirst = false   // 시작 화면 판정값을 한 번만 남기려고
        tapsProven = false        // 증거는 실행마다 새로 모은다

        while (Runner.running) {
            if (maxQuests > 0 && quests >= maxQuests) { Runner.set("퀘스트 끝", "퀘스트 " + quests + "개를 받았어요"); break }

            val b = Runner.shot()
            if (b == null) { Runner.set("화면을 못 읽었어요", "다시 시도 중"); Runner.sleep(5000); continue }

            // 시작 화면의 판정값을 한 번 남긴다 — 새 기기에서 무엇이 어긋났는지 스크린샷 없이 보려고.
            if (!loggedFirst) {
                loggedFirst = true
                Bot.log("시작 화면: " + Screen.debugLine(b))
                Bot.log("  기준점: " + Screen.landmarks(b))
            }

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

            val atMain = dock.ratio >= 0.1 && !Screen.hasCloseButton(b)

            // ── 메인이 아니면 퀘스트 띠 좌표가 의미 없다 ──
            // 쿠키런 공지는 닫기 X 자리가 제각각이라 좌표를 고정하면 위험하다.
            // 뒤로가기가 대부분을 닫아 주므로 그걸 주력으로, 단계별로 수단을 바꾼다.
            if (!atMain) {
                // 아는 팝업이면 좌표를 더듬지 말고 바로 처리한다.
                if (Screen.isIdleReward(b)) {
                    Runner.set("자동 사냥 보상 받는 중")
                    Bot.log("자동 사냥 보상 팝업 - [보상 받기]")
                    Runner.tap(Screen.IDLE_CLAIM, 2500)
                    offMain = 0; Runner.sleep(2000); continue
                }
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

            // ── '퀘스트 NNNN 까지 한번에 클리어 하기' ──
            // 밀린 퀘스트를 한 번에 받는 새 버튼. 띠가 파랗게 바뀌어 기존 판정이 전부 놓쳤다
            // (완료도 미완료도 아니라 계속 탐색만 돌았다). 보이면 눌러서 받고 이어간다.
            if (Screen.isBulkClear(b)) {
                bulkTries++
                if (bulkTries > 3) {
                    // 세 번 눌러도 안 바뀐다 = 누를 수 없는 상태다. 더 두드리지 않고 넘어간다.
                    if (bulkTries == 4) Bot.log("'한번에 클리어'를 눌러도 안 바뀌어요 - 그냥 지나갑니다")
                } else {
                    Runner.set("한번에 클리어 받는 중", "밀린 퀘스트를 한 번에 받아요")
                    Bot.log("'한번에 클리어 하기' 발견 (" + fmt(ratio) + ") - 누릅니다")
                    Runner.tap(Screen.QUEST_BAR, 350)
                    if (waitBarChanged(ratio)) {
                        tapsProven = true; quests++; bulkTries = 0
                        ovenTried = false; bossNoticed = false; probeAllowedAt = 0L
                        Bot.log("  받았어요 - 띠가 바뀌었습니다")
                        Runner.lastResult = "퀘스트 " + quests + "개 수령 · 대신 해 준 일 " + handled + "번"
                    }
                    continue
                }
            } else bulkTries = 0

            if (ratio >= doneRatio) {
                quests++
                Runner.set("퀘스트 보상 받는 중", "지금까지 " + quests + "개")
                Bot.log("퀘스트 수령 (" + fmt(ratio) + ")")
                Runner.tap(Screen.QUEST_BAR, 350)
                // 정말 받아졌나 — 띠가 새 퀘스트로 갈렸으면 받은 것이다.
                // 이게 '내 탭이 게임에 먹혔다'는 증거이기도 하다(오븐 잠금을 푸는 열쇠).
                if (waitBarChanged(ratio)) tapsProven = true
                else Bot.log("  띠가 그대로예요 - 수령이 안 됐을 수 있어요")
                // 새 퀘스트가 올라왔으니 오븐·보스 기회를 되돌린다.
                // 백오프도 같이 푼다 — 안 그러면 앞 퀘스트 때문에 걸린 15분이
                // 이미 교체된 새 퀘스트의 탐색까지 막아 버린다.
                ovenTried = false
                bossNoticed = false
                probeAllowedAt = 0L
                Runner.lastResult = "퀘스트 " + quests + "개 수령 · 대신 해 준 일 " + handled + "번"
                continue
            }

            // ── 미완료 퀘스트 ──
            Runner.status = "퀘스트 기다리는 중"
            Runner.detail = "받은 퀘스트 " + quests + "개 · 대신 해 준 일 " + handled + "번"

            if (System.currentTimeMillis() < probeAllowedAt) { Runner.sleep(8000); continue }

            // 행동형(뽑기·상자·오븐)이면 대신 해 준다.
            // ⚠️ 비트맵을 probe 안까지 들고 가면 안 된다 - Runner.shot() 이 이전 장을 recycle 한다.
            //    필요한 값(뽑기 버튼 수)만 여기서 미리 재서 숫자로 넘긴다.
            val r = probe(Screen.gachaHits(b), ratio)
            if (r == PROBE_DID) { handled++; Runner.sleep(800); continue }
            if (r == PROBE_CLAIMED) {
                // 탐색으로 누른 게 사실은 '보상 받기'였다 = 이 기기에서는 완료가 0.85 밑으로 읽힌다.
                // 다음부터는 탐색을 거치지 않도록 기준을 이 값에 맞춰 낮춘다.
                val lowered = Math.max(COMPLETE_FLOOR, ratio - 0.05)
                if (lowered < doneRatio) {
                    doneRatio = lowered
                    Bot.log("완료 기준을 " + fmt(doneRatio) + " 로 낮췄어요 (이 기기 화면에 맞춤)")
                }
                quests++
                Runner.set("퀘스트 보상 받는 중", "지금까지 " + quests + "개")
                ovenTried = false
                bossNoticed = false
                probeAllowedAt = 0L
                Runner.lastResult = "퀘스트 " + quests + "개 수령 · 대신 해 준 일 " + handled + "번"
                Runner.sleep(3000); continue
            }

            // 탐색으로도 안 풀렸다 = 스테이지 클리어형이다. 보스를 깨야 넘어간다.
            //
            // ⚠️ **여기서 보스를 소환하지 않는다.** 예전엔 퀘스트가 알아서 조합을 1~5 로 바꿔 가며
            //    보스를 불렀는데, 사용자가 [퀘스트]만 눌렀는데 보스전이 시작돼 무슨 일이 벌어지는지
            //    알 수 없었다. 보스는 별개의 콘텐츠라 [보스전] 버튼으로 뺐다.
            //    여기서는 그 사실만 알리고, 다른 퀘스트가 완료되기를 기다린다
            //    (방치 전투가 도는 동안 다른 퀘스트는 계속 찬다).
            if (!bossNoticed) {
                bossNoticed = true
                Bot.log("이 퀘스트는 보스를 깨야 넘어가요 - [보스전] 을 눌러 주세요")
            }
            Runner.set("보스가 필요한 퀘스트예요", "[보스전] 을 눌러 주세요 · 그 사이 다른 퀘스트를 기다립니다")
            probeAllowedAt = System.currentTimeMillis() + BACKOFF_MINUTES * 60_000
            Runner.sleep(8000)
        }
        if (Runner.running) Runner.set("퀘스트 끝", "퀘스트 " + quests + "개를 받았어요")
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
    private fun probe(preHits: Int, before: Double): Int {
        Runner.set("다음 할 일 찾는 중")
        Runner.tap(Screen.QUEST_BAR, 1200)
        val b = Runner.shot() ?: return PROBE_NONE
        Bot.log("  누른 뒤 화면: " + Screen.debugLine(b))

        // ⚠️ '뽑기 화면인가'는 **탭 전후를 비교해서** 본다.
        // 절대 판정으로 하면, 다른 기기에서 메인 화면이 우연히 3점을 다 통과할 때(색·좌표가
        // 조금만 달라도 생긴다) 탐색이 매번 "뽑기 화면"으로 새고 아무것도 진행되지 않는다.
        // 실제로 갤럭시 탭에서 '뽑기 화면 -> 10회' 뒤 "화면이 그대로"로 끝났다.
        val hits = Screen.gachaHits(b)
        if (hits == 3) {
            if (preHits == 3) {
                Bot.log("  누르기 전에도 뽑기 버튼 3/3 - 뽑기 화면이 아닙니다(오탐)")
            } else {
                Bot.log("  뽑기 화면 -> 10회 수행")
                tapsProven = true
                return if (gacha10()) PROBE_DID else PROBE_NONE
            }
        }

        // 가방은 하단에만 열려서 '보스 소환'이 그대로 보인다 → 메인 판정보다 먼저 봐야 한다.
        if (hits < 3 && Screen.isBottomPanel(b)) {
            // 뽑기 화면도 하단 청록 패널이라 이 판정을 통과한다. 버튼이 2개만 주황이면
            // 뽑기 화면이 거의 확실하니(하나가 가려졌거나 UI 가 바뀐 것) 손대지 않는다.
            if (hits >= 2) {
                Bot.log("  하단 패널인데 뽑기 버튼이 " + hits + "/3 만 주황 - 건드리지 않음")
                Runner.tap(Screen.NAV_CLOSE, 2000)
                return PROBE_NONE
            }
            Bot.log("  가방 열림 -> 상자 사용")
            tapsProven = true
            return if (useBox()) PROBE_DID else PROBE_NONE
        }

        if (Screen.dockRatio(b) >= 0.1) {
            // ⚠️ 여기서 바로 오븐을 돌리면 안 된다.
            // 탐색은 '완료 퀘스트를 받는 것'과 똑같은 자리를 누른다. 그래서 완료였는데 띠 비율이
            // 기준보다 낮게 읽힌 기기에서는, 이 탭이 보상을 받아 버리고 화면은 메인 그대로다
            // → 옛 코드는 그걸 '안 바뀜'으로 보고 오븐(재화 소모)을 돌렸다. 실제로 태블릿에서
            //   '보상 받을 때인데 딴 걸 한다'로 나타난 증상이 이것이다.
            // 띠 비율을 다시 재서 크게 달라졌으면 새 퀘스트로 넘어간 것 = 보상을 받은 것이다.
            val now = Screen.questBarRatio(b)
            if (Math.abs(now - before) >= BAR_CHANGED) {
                Bot.log("  띠가 바뀜 (" + fmt(before) + " -> " + fmt(now) + ") = 보상을 받은 것")
                tapsProven = true
                return PROBE_CLAIMED
            }

            // 화면이 안 바뀌는 유형 = 메인의 무언가를 가리키는 손가락 힌트(오븐) 또는 스테이지 클리어형.
            // 퀘스트 순환에서 재화를 쓰는 건 오븐뿐이다. 시험 모드면 그것만 건너뛴다.
            if (!tapsProven) {
                // 아직 한 번도 '내 탭이 먹혔다'를 못 봤다 → 이 "안 바뀜"은 오븐 퀘스트가 아니라
                // 탭 자체가 안 들어가는 것일 수 있다. 재화를 쓰기 전에 증거를 먼저 본다.
                Bot.log("  화면이 안 바뀜 - 아직 탭이 먹힌 증거가 없어 오븐은 미룹니다")
                Bot.log("    점검 → [탭 점검] 을 한 번 눌러 주세요")
                return PROBE_NONE
            }
            if (Prefs.testMode) { Bot.log("  화면이 안 바뀜 - 시험 모드라 오븐은 건너뜁니다"); return PROBE_NONE }
            if (ovenTried) { Bot.log("  화면이 안 바뀜 - 오븐은 이미 해 봤어요(오븐 퀘스트가 아닙니다)"); return PROBE_NONE }
            ovenTried = true
            Bot.log("  화면이 안 바뀜 -> 오븐 처리 (띠 " + fmt(before) + " -> " + fmt(now) + ")")
            return if (Oven.run()) PROBE_DID else PROBE_NONE
        }

        Bot.log("  모르는 화면 - 되돌아 나감")
        Runner.tap(Screen.NAV_CLOSE, 2000)
        val after = Runner.shot()
        if (after != null && !Screen.atMain(after)) { TapService.back(); Runner.sleep(3000) }
        return PROBE_NONE
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
    /**
     * 아이콘을 눌렀는데 창이 열렸나. 느린 기기에서 2.5초로 모자랄 수 있어 한 번 더 기다려 준다.
     * (여기서 아이콘을 다시 누르면 안 된다 — 이미 열려 있으면 창 **안**을 누르는 꼴이 된다.)
     * 끝내 안 열리면 그 화면의 판정값을 남긴다. 좌표가 어긋난 기기를 스크린샷 없이 가려내려는 것.
     */
    private fun waitPanel(name: String): Boolean {
        var chk = Runner.shot()
        if (chk != null && Screen.atMain(chk)) {
            Runner.sleep(2500)
            chk = Runner.shot()
        }
        if (chk != null && Screen.atMain(chk)) {
            Bot.log("  [보상] " + name + " 창이 안 열렸어요 - 건너뜁니다")
            Bot.log("    화면: " + Screen.debugLine(chk))
            Bot.log("    기준점: " + Screen.landmarks(chk))
            return false
        }
        tapsProven = true
        return true
    }

    private fun claimMissions() {
        Runner.set("보상 받기", "미션")
        Runner.setProgress(0, REWARD_STEPS)
        Runner.tap(Screen.ICON_MISSION, 2500)
        if (!waitPanel("미션")) return
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
        if (!waitPanel("이벤트")) return
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

    /**
     * 띠가 바뀔 때까지 **짧게 지켜본다**. 바뀌면 그 즉시 돌아온다.
     *
     * 예전엔 누르고 나서 2.5초 + 3초를 **무조건** 기다렸다(퀘스트 하나에 6초).
     * 게임은 대개 0.5초 안에 반응하므로 그 대부분이 버리는 시간이었다.
     * 고정 대기 대신 결과를 보고 판단하면 **빠를 땐 빠르고 느릴 땐 기다린다.**
     */
    private fun waitBarChanged(before: Double, tries: Int = 6): Boolean {
        for (i in 1..tries) {
            if (!Runner.running) return false
            val b = Runner.shot() ?: return false
            if (Math.abs(Screen.questBarRatio(b) - before) >= BAR_CHANGED) return true
            Runner.sleep(250)
        }
        return false
    }

    private fun fmt(v: Double): String = String.format("%.2f", v)

    /** 지금 쓰고 있는 완료 기준(진단 표시용). 기기에 맞춰 낮아졌으면 그 값이 나온다. */
    fun doneRatioNow(): Double = doneRatio
}
