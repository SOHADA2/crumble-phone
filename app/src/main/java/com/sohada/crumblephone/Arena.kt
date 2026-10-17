package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 아레나 — PC 봇 `arena.ps1` 을 옮겼다. **⚙ 에서 고른 조건(여러 개 같이)에 맞는 상대만 골라 싸운다.**
 *
 * 흐름: 로비에서 내 전투력·상대 전투력을 읽고 → 조건에 안 맞으면 [다음 상대] 로 넘기고
 *       → 맞는 상대를 만나면 [도전하기] → 전투 → 결과·승급 화면을 탭으로 넘겨 로비 복귀 → 반복.
 *       다 넘겨도 없으면 '더 넘길 수 없으면 지금 상대' 를 켰을 때만 지금 상대와, 아니면 멈춘다.
 *       크리스탈 새로고침은 어떤 조합이든 절대 안 쓴다(그 창이 뜨면 취소).
 *
 * 조건(Prefs · 2026-10-17 · PC `Get-ArenaRule` 과 같다): 낮은 상대 · 높아도 N% 안쪽 · 못 넘기면 지금 상대.
 * 고른 것 중 **하나라도** 맞으면 도전. 판마다 다시 읽는다.
 *
 * ⚠️ **아레나 재화를 쓴다**(1판 3). 재화가 모자라면 스스로 끝낸다.
 *    진입만 시험하고 싶으면 `maxFights = 0` 으로 부르면 된다.
 *
 * 승패는 화면으로 못 읽는다 → **아레나 점수의 변화**로 가른다(오르면 승·내리면 패, 무승부 없음).
 */
object Arena {

    private const val COST = 1            // 1판당 아레나 재화 1개 (도전 버튼 '258/6' 의 6 은 하루 지급량 — 사장님 확인 2026-10-17)
    private const val SCAN = 8            // 상대를 몇 명까지 넘겨 볼지

    /** 조건 한 줄 설명(로그·정산 머리) — PC `Format-ArenaRule` 과 같은 글. */
    private fun ruleText(): String {
        val p = ArrayList<String>()
        if (Prefs.arenaSpend > 0) p.add("재화 " + Prefs.arenaSpend + "개까지")
        if (Prefs.arenaLower) p.add("나보다 낮으면")
        if (Prefs.arenaHigher) p.add("" + Prefs.arenaMargin + "% 높은 데까지")
        if (Prefs.arenaFallback) p.add("못 넘기면 지금 상대")
        return if (p.isEmpty()) "(고른 조건 없음)" else p.joinToString(" · ")
    }
    /** 상대가 조건에 맞으면 그 이유, 아니면 null — PC `Test-ArenaPick` 과 같다. */
    private fun pickWhy(opp: Long, me: Long): String? {
        if (Prefs.arenaLower && opp <= me) return "나보다 낮음"
        if (Prefs.arenaHigher && opp > me && opp <= me * (1.0 + Prefs.arenaMargin / 100.0))
            return String.format("%.1f%% 높지만 %d%% 안쪽", 100.0 * (opp - me) / me, Prefs.arenaMargin)
        return null
    }

    // 판 수 상한은 두지 않는다(2026-10-17 사장님) — 끝내는 기준은 ⚙ '아레나 재화 사용량' 하나. 1000 은 안전 상한일 뿐.
    fun start(ctx: Context, maxFights: Int = if (Prefs.testMode) 0 else 1000) {
        if (!Runner.guard()) return
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
        // 정산 창(2026-10-16 · PC 와 같이) — 판마다 '승/패 · 점수 전→후 · 상대 전투력'.
        val rows = ArrayList<String>()
        val sumStart = System.currentTimeMillis()
        var curStart: Long? = null; var curNow: Long? = null; var ptsNow: Long? = null
        var endWhy = ""
        var blind = 0                     // 상대 전투력을 한 바퀴 내내 못 읽은 횟수(연속)
        Bot.log("  도전 조건: " + ruleText())

        while (fights < maxFights && Runner.running) {
            if (!Runner.awaitGame()) break
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
            if (cur != null) { if (curStart == null) curStart = cur; curNow = cur }
            if (cur != null && cur < COST) {
                Runner.set("아레나 끝", "재화가 모자라요 (" + cur + " < " + COST + ")")
                endWhy = "재화 부족"
                break
            }
            if (startPts == null) startPts = readPts(b)

            // 정한 재화 사용량(⚙ · 판마다 다시 읽는다). 다음 한 판을 하면 넘치면 그 전에 멈춘다.
            val lim = Prefs.arenaSpend
            var spent = fights.toLong() * COST
            val c0 = curStart; val c1 = curNow
            if (c0 != null && c1 != null) spent = maxOf(spent, c0 - c1)
            if (lim > 0 && spent + COST > lim) {
                Bot.log("  정한 재화 사용량 " + lim + " 개에 닿았어요 (쓴 재화 " + spent + " · 1판 " + COST + ") - 종료")
                Runner.set("아레나 끝", "정한 재화를 다 씀")
                endWhy = "정한 재화 " + lim + " 개를 다 씀 (쓴 재화 " + spent + ")"
                break
            }

            // ── 조건에 맞는 상대 찾기 ──
            if (!Prefs.arenaLower && !Prefs.arenaHigher && !Prefs.arenaFallback) {
                Bot.log("  도전 조건이 하나도 없어요 - 멈춥니다 (⚙ 설정 → 아레나 도전 조건)")
                endWhy = "도전 조건을 하나도 안 골랐어요"
                break
            }
            var chosen = false
            var oppPick: Long? = null
            var lastOpp: Long? = null
            var readAny = false
            var why = ""
            for (i in 1..SCAN) {
                if (!Runner.running) break
                Runner.set("아레나", "상대 물색 중 (" + i + "/" + SCAN + ")")
                val s = Runner.shot() ?: break
                if (Screen.isArenaRefreshDialog(s)) {
                    // 여기까지 왔으면 상대를 다 넘긴 것이다. 크리스탈은 절대 안 쓴다 → 취소. 그다음은 아래에서 고른다.
                    Bot.log("  더 넘기려면 크리스탈 새로고침이라 취소 (크리스탈은 안 써요)")
                    Runner.tap(Screen.DLG_SAFE, 1600)
                    break
                }
                val opp = readOpp(s)
                if (opp == null) Bot.log("  상대 전투력을 못 읽음 (" + i + ") - 넘김")
                else {
                    readAny = true; lastOpp = opp
                    val w = pickWhy(opp, mine)
                    if (w != null) { Bot.log("  상대 " + Ocr.comma(opp) + " · 나 " + Ocr.comma(mine) + " → 도전 (" + w + ")"); chosen = true; oppPick = opp; why = w }
                    else Bot.log("  상대 " + Ocr.comma(opp) + " · 나 " + Ocr.comma(mine) + " → 조건에 안 맞아 다음 상대")
                }
                if (chosen) break
                Runner.tap(Screen.ARENA_NEXT, 1600)
            }
            if (!Runner.running) break
            if (!chosen) {
                if (!readAny) {
                    // 상대를 한 번도 못 읽었다 = 규칙을 적용할 재료가 없다. 눈 감고 싸우지 않는다(PC 와 같음).
                    blind++
                    Bot.log("  ⚠️ 상대 전투력을 다 못 읽었어요 (" + blind + "/3) - 화면 좌표가 바뀌었을 수 있어요")
                    if (blind >= 3) { endWhy = "상대 전투력을 못 읽음"; break }
                    Runner.sleep(2000); continue
                }
                blind = 0
                Runner.shot()?.let { if (Screen.isArenaRefreshDialog(it)) Runner.tap(Screen.DLG_SAFE, 1600) }
                if (!Prefs.arenaFallback) {
                    Bot.log("  조건에 맞는 상대가 없어요 - 크리스탈 새로고침은 안 하므로 멈춥니다 (" + ruleText() + ")")
                    endWhy = "조건에 맞는 상대가 없음 (크리스탈 새로고침은 안 함)"
                    break
                }
                Bot.log("  조건에 맞는 상대가 없어 더 못 넘김 → 지금 상대와 도전")
                oppPick = lastOpp; why = "못 넘겨서"
            } else blind = 0

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
            if (!waitLobby()) { Bot.log("  결과 뒤 로비로 못 돌아옴 - 종료"); endWhy = "결과 뒤 로비로 못 돌아옴"; break }

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
            if (after != null) ptsNow = after
            val ptTxt = if (before != null && after != null) {
                val dd = after - before
                before.toString() + " → " + after + " (" + (if (dd >= 0) "+$dd" else "$dd") + ")"
            } else "점수 못 읽음"
            val op = oppPick
            var oppTxt = "상대 ?"
            if (op != null) {
                val pct = if (mine > 0) 100.0 * (op - mine) / mine else 0.0
                oppTxt = "상대 " + Runner.fmtShort(op) + " (" + (if (pct >= 0) "+" else "") + String.format("%.0f", pct) + "%)"
            }
            if (why == "못 넘겨서") oppTxt += " · 못 넘겨서"
            rows.add(String.format("%3d판  %-4s %s   %s", fights, res, ptTxt, oppTxt))
        }

        val tail = "" + fights + "판 · " + wins + "승 " + losses + "패"
        if (Runner.running) Runner.set("아레나 끝", tail) else Runner.set("멈췄어요", tail)
        Runner.lastResult = "아레나 " + tail
        val L = ArrayList<String>()
        if (endWhy.isEmpty() && fights >= maxFights) endWhy = "정한 판 수(" + maxFights + ") 다 함"
        if (endWhy.isNotEmpty()) L.add("끝난 이유: " + endWhy)
        L.add("도전 조건: " + ruleText()); L.add("")
        L.add(tail)
        val p0 = startPts
        val p1 = ptsNow
        if (p0 != null && p1 != null) { val dd = p1 - p0; L.add("점수   " + p0 + " → " + p1 + "  (" + (if (dd >= 0) "+$dd" else "$dd") + ")") }
        if (curStart != null) L.add("재화   " + curStart + " → " + (curNow ?: curStart) + "  (1판 " + COST + ")")
        val cs = curStart; val cn = curNow
        val usedAll = maxOf(fights.toLong() * COST, if (cs != null && cn != null) cs - cn else 0L)
        L.add("쓴 재화 " + usedAll + (if (Prefs.arenaSpend > 0) " / " + Prefs.arenaSpend else ""))
        L.add("")
        if (rows.isEmpty()) L.add("아직 싸운 판이 없어요.") else { L.add("── 판별 기록 ──"); L.addAll(rows) }
        Runner.putSummary("아레나 정산", sumStart, L)
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
        if (myPower() != null) return true
        // 안 들어가졌다. 메인으로 보여도 보상 팝업 같은 게 덮고 있을 수 있으니 치우고 한 번 더.
        Runner.clearPopups()
        val (ok2, _) = Runner.resetToMain()
        if (!ok2) return false
        Runner.tap(Screen.NAV_DUNGEON, 2500)
        Runner.tap(Screen.ARENA_BANNER, 2500)
        Runner.tap(Screen.ARENA_GO, 2500)
        return myPower() != null
    }
}
