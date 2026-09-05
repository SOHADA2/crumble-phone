package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 아레나 — PC 봇 `arena.ps1` 을 옮겼다. **나보다 약한 상대만 골라 싸운다.**
 *
 * 흐름: 로비에서 내 전투력·상대 전투력을 읽고 → 상대가 세면 [다음 상대] 로 넘기고
 *       → 약한 상대를 만나면 [도전하기] → 전투 → 결과·승급 화면을 탭으로 넘겨 로비 복귀 → 반복.
 *       상대를 8명 넘겨도 못 찾으면 지금 상대와 그냥 도전한다(크리스탈은 절대 안 쓴다).
 *
 * ⚠️ **아레나 재화를 쓴다**(1판 3). 재화가 모자라면 스스로 끝낸다.
 *    진입만 시험하고 싶으면 `maxFights = 0` 으로 부르면 된다.
 *
 * 승패는 화면으로 못 읽는다 → **아레나 점수의 변화**로 가른다(오르면 승·내리면 패, 무승부 없음).
 */
object Arena {

    private const val COST = 3            // 1판당 아레나 재화
    private const val SCAN = 8            // 상대를 몇 명까지 넘겨 볼지

    fun start(ctx: Context, maxFights: Int = if (Prefs.testMode) 0 else Prefs.arenaFights) {
        if (Runner.running) { Bot.log("이미 무언가 돌고 있어요"); return }
        if (!TapService.isReady) { Runner.set("시작 못 함", "접근성 서비스를 켜 주세요"); return }
        if (CaptureService.instance == null) { Runner.set("시작 못 함", "화면 읽기를 허용해 주세요"); return }
        Runner.running = true; Runner.task = "아레나"
        thread(name = "arena") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                loop(maxFights)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    // ── 읽기 ──
    private fun myPower(): Long? = Runner.shot()?.let { readMy(it) }
    private fun readMy(b: android.graphics.Bitmap) = Ocr.readPower(b, Screen.A_MY[0], Screen.A_MY[1], Screen.A_MY[2], Screen.A_MY[3])
    private fun readOpp(b: android.graphics.Bitmap) = Ocr.readPower(b, Screen.A_OPP[0], Screen.A_OPP[1], Screen.A_OPP[2], Screen.A_OPP[3])
    private fun readCur(b: android.graphics.Bitmap) = Ocr.readNumber(b, Screen.A_CUR[0], Screen.A_CUR[1], Screen.A_CUR[2], Screen.A_CUR[3], minDigits = 1)
    private fun readPts(b: android.graphics.Bitmap) = Ocr.readNumber(b, Screen.A_PTS[0], Screen.A_PTS[1], Screen.A_PTS[2], Screen.A_PTS[3], minDigits = 1)

    private fun loop(maxFights: Int) {
        if (!enter()) {
            Runner.set("시작 못 함", "아레나 로비에 들어가지 못했어요 (시즌 중인지 확인)")
            Runner.lastResult = "아레나 진입 실패"
            return
        }
        if (maxFights <= 0) {            // 진입만 시험하는 안전한 방법(재화를 안 쓴다)
            Runner.set("아레나", "진입까지만 확인했어요")
            Runner.lastResult = "아레나 진입 확인"
            return
        }

        var fights = 0; var wins = 0; var losses = 0
        var startPts: Long? = null

        while (fights < maxFights && Runner.running) {
            Runner.set("아레나", "상대 물색 중")
            var b = Runner.shot() ?: break
            if (Screen.isArenaRefreshDialog(b)) {
                Bot.log("  새로고침(크리스탈) 팝업 - 취소")
                Runner.tap(Screen.DLG_SAFE, 1600)
                b = Runner.shot() ?: break
            }
            val mine = readMy(b)
            if (mine == null) { Bot.log("내 전투력을 못 읽음(로비 아님?) - 화면 넘기고 재시도"); if (!waitLobby()) break; continue }

            // 재화가 한 판 값보다 적으면 더 못 한다.
            val cur = readCur(b)
            if (cur != null && cur < COST) {
                Runner.set("아레나 끝", "재화가 모자라요 (" + cur + " < " + COST + ")")
                break
            }
            if (startPts == null) startPts = readPts(b)

            // ── 나보다 약한 상대 찾기 ──
            var chosen = false
            for (i in 1..SCAN) {
                if (!Runner.running) break
                Runner.set("아레나", "상대 물색 중 (" + i + "/" + SCAN + ")")
                val s = Runner.shot() ?: break
                if (Screen.isArenaRefreshDialog(s)) {
                    // 여기까지 왔으면 상대를 다 넘긴 것이다. 크리스탈을 쓰지 않고 지금 상대와 도전한다.
                    Bot.log("  새로고침 팝업 - 취소하고 지금 상대와 그냥 도전")
                    Runner.tap(Screen.DLG_SAFE, 1600)
                    chosen = true; break
                }
                val opp = readOpp(s)
                when {
                    opp == null -> Bot.log("  상대 전투력을 못 읽음 (" + i + ") - 넘김")
                    opp <= mine -> { Bot.log("  상대 " + Ocr.comma(opp) + " ≤ 나 " + Ocr.comma(mine) + " → 도전"); chosen = true }
                    else -> Bot.log("  상대 " + Ocr.comma(opp) + " > 나 " + Ocr.comma(mine) + " → 다음 상대")
                }
                if (chosen) break
                Runner.tap(Screen.ARENA_NEXT, 1600)
            }
            if (!Runner.running) break
            if (!chosen) Bot.log("  약한 상대를 못 찾음 - 지금 상대와 그냥 도전")

            // ── 전투 ──
            val before = Runner.shot()?.let { readPts(it) }
            fights++
            Runner.set("아레나 " + fights + "판째", "전투 중")
            Runner.tap(Screen.ARENA_CHALLENGE, 3000)
            for (t in 1..20) {           // 전투는 대략 20초. 막대로 남은 시간을 보여 준다.
                if (!Runner.running) break
                Runner.setProgress(t, 20)
                Runner.sleep(1000)
            }
            if (!waitLobby()) { Bot.log("  결과 뒤 로비로 못 돌아옴 - 종료"); break }

            // ── 승패는 점수 변화로 가른다 ──
            // 오르면 승·내리면 패(무승부 없음). ±1~2 는 OCR 노이즈라 무시하고,
            // 300 을 넘는 점프는 팝업에 가려 0 으로 읽힌 오독이라 버린다.
            Runner.set("아레나 " + fights + "판째", "승패 확인 중")
            var after: Long? = null
            for (t in 1..8) {
                if (!Runner.running) break
                after = Runner.shot()?.let { readPts(it) }
                if (after != null && before != null) {
                    val d = Math.abs(after - before)
                    if (d in 3..300) break
                }
                Runner.sleep(700)
            }
            var res = "판정보류"
            if (before != null && after != null) {
                val diff = after - before
                if (diff >= 3) { wins++; res = "승" }
                else if (diff <= -3) { losses++; res = "패" }
            }
            Bot.log("  " + fights + "판 완료 · " + res + " (" + before + "→" + after + ") · 누적 " + wins + "승 " + losses + "패")
            Runner.lastResult = "아레나 " + fights + "판 · " + wins + "승 " + losses + "패"
        }

        val tail = "" + fights + "판 · " + wins + "승 " + losses + "패"
        if (Runner.running) Runner.set("아레나 끝", tail) else Runner.set("멈췄어요", tail)
        Runner.lastResult = "아레나 " + tail
    }

    /**
     * 결과·승급·강등 등 **어떤 화면이든** 넘겨 로비까지 간다. 로비 판정은 '내 전투력이 읽히면 로비'.
     * 아레나는 이기면 승급, 지면 강등 화면이 뜨는데 위치가 제각각이라 하단만 눌러서는 못 넘긴다
     * → 하단 '탭하세요' 와 화면 한가운데를 같이 누른다. 그래도 안 되면 뒤로가기 후 재진입한다.
     */
    private fun waitLobby(): Boolean {
        for (t in 1..14) {
            if (!Runner.running) return false
            Runner.status = "아레나"; Runner.detail = "승패 확인 중"
            if (myPower() != null) return true
            Runner.tap(Screen.ARENA_CONTINUE, 1100)
            Runner.tap(Screen.ARENA_CENTER, 1100)
        }
        Bot.log("  로비를 못 찾음 - 뒤로가기 후 재진입")
        for (k in 1..3) { TapService.back(); Runner.sleep(900) }
        return enter()
    }

    /** 이미 로비면 바로. 아니면 메인 → 던전 → 아레나 배너 → 도전하러 가기. */
    private fun enter(): Boolean {
        if (myPower() != null) return true
        Runner.set("아레나", "메인 화면으로 이동 중")
        val (ok, why) = Runner.resetToMain()
        if (!ok) { Runner.failByReason(why); return false }
        Runner.set("아레나로 이동 중")
        Runner.tap(Screen.NAV_DUNGEON, 2500)     // 던전 → '도전 던전' 탭이 기본으로 열린다
        Runner.tap(Screen.ARENA_BANNER, 2500)
        Runner.tap(Screen.ARENA_GO, 2500)
        return myPower() != null
    }
}
