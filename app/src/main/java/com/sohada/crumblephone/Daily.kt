package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 일일 던전 — PC 봇 `daily.ps1` 을 옮겼다.
 *
 * 던전 하나씩 돌며 **기회(별티켓)를 다 쓰고 → 달성 보상을 받고 → 다음 던전(▶)** 으로 넘어간다.
 * 첫 던전으로 되돌아오면 한 바퀴 돈 것이므로 끝낸다.
 *
 * ⚠️ **입장권(기회)을 쓴다.** 진입만 시험하고 싶으면 `maxDungeons = 0` 으로 부르면 된다
 *    (PC 봇의 `-MaxDungeons 0` 과 같은 안전한 시험 방법이다).
 *
 * ★ **소탕(SKIP 티켓)은 하지 않는다.** 소탕은 '지금 스테이지' 기준으로 보상을 주는데,
 *   스테이지는 계속 올라가므로 지금 태워 버리면 나중에 받을 것보다 손해다(사용자 판단).
 *   기능을 끄는 설정으로 두지 않고 **코드에서 아예 뺐다** — 켤 수 있게 두면 언젠가 켜진다.
 */
object Daily {

    /** 일일 던전 목록은 위→아래 순서가 고정이라, 방문한 순서로 이름을 붙인다. */
    private val NAMES = arrayOf("경험치", "코인", "반죽", "연구석", "룬결정", "던전6", "던전7", "던전8")
    private fun nameOf(i: Int) = if (i in 1..NAMES.size) NAMES[i - 1] else "던전$i"

    fun start(ctx: Context, maxDungeons: Int = if (Prefs.testMode) 0 else 8) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "일일 던전"
        thread(name = "daily") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                loop(maxDungeons)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun loop(maxDungeons: Int) {
        Runner.set("일일 던전 준비 중", "메인 화면으로 이동 중")
        val (ok, why) = Runner.resetToMain()
        if (!ok) { Runner.failByReason(why); return }

        if (!enter()) {
            Runner.set("시작 못 함", "일일 던전 화면을 찾지 못했어요")
            Runner.lastResult = "일일 던전 진입 실패"
            return
        }
        if (maxDungeons <= 0) {          // 진입만 시험하는 안전한 방법(입장권을 안 쓴다)
            Runner.set("일일 던전", "진입까지만 확인했어요")
            Runner.lastResult = "일일 던전 진입 확인"
            return
        }

        val visited = ArrayList<IntArray>()   // 목록에서 이미 눌러 본 줄의 지문
        val sigs = ArrayList<IntArray>()      // 실제로 들어가 본 던전의 지문
        val results = ArrayList<String>()
        var battled = 0
        var scrolls = 0

        for (d in 1..maxDungeons) {
            if (!Runner.running) break

            var b = Runner.shot() ?: break

            // ── 던전 화면이 아니면 목록에서 하나 골라 들어간다 ──
            if (!Screen.atDailyEntry(b)) {
                if (!Screen.atDailyList(b) && !backToList()) {
                    Bot.log("일일 던전 화면을 못 찾았어요 - 종료"); break
                }
                b = Runner.shot() ?: break
                var pick = -1
                for (y in Screen.findDailyBanners(b)) {
                    val sig = Screen.dailyRowSig(b, y)
                    if (visited.none { Screen.dailyRowMatch(it, sig) }) { pick = y; break }
                }
                if (pick < 0) {
                    if (scrolls >= 3) { Bot.log("더 볼 던전이 없어요 - 끝"); break }
                    scrolls++
                    Runner.set("일일 던전", "목록을 밀어 내리는 중 (" + scrolls + ")")
                    TapService.swipe(720, 2200, 720, 1100, 450)
                    Runner.sleep(1200)
                    continue
                }
                visited.add(Screen.dailyRowSig(b, pick))
                Bot.log("일일 던전 목록 - 배너(y=" + pick + ")로 들어갑니다")
                Runner.tap(intArrayOf(700, pick), 2200)
                var opened = false
                for (w in 1..5) {
                    if (Runner.shot()?.let { Screen.atDailyEntry(it) } == true) { opened = true; break }
                    Runner.sleep(700)
                }
                if (!opened) { Bot.log("  들어가지 못했어요 - 다시 시도"); continue }
                b = Runner.shot() ?: break
            }

            // ── 한 바퀴 돌았나 ──
            // 던전 안에서는 ▶ 로 오른쪽으로 넘어간다. 첫 던전으로 되돌아오면 한 바퀴다.
            // 중간 던전끼리는 서로 비슷해서 전부와 대조하면 일찍 멈춘다 — 첫 던전하고만 본다.
            val sig = Screen.dailySig(b)
            if (sigs.isNotEmpty() && Screen.dailySigMatch(sigs[0], sig)) {
                Bot.log("첫 던전으로 되돌아옴 - 한 바퀴 완료"); break
            }
            sigs.add(sig)
            val idx = sigs.size
            val name = nameOf(idx)
            Runner.set("일일 던전 " + idx + "번째", name + " · 준비 중")
            Runner.setProgress(1, 4)

            // ── '연속 도전'은 끈다 ──
            // 켜면 게임이 봇과 상관없이 자동으로 다음 판을 이어가서 화면이 어긋난다
            // (엉뚱한 팝업 위에 탭이 들어간다). 봇이 매 판 직접 눌러
            // '한 판 끝 → 던전 화면 복귀'를 확인하며 동기적으로 나아간다.
            for (k in 1..2) {
                val s = Runner.shot() ?: break
                if (!Screen.dailyContChecked(s)) break
                Runner.tap(Screen.DAILY_CONT_CHK, 1200)
            }

            // ── 남은 열쇠를 **하나씩** 다 쓴다 ──
            // 열쇠는 낱개로 남는다(상자·퀘스트로도 들어온다). 그래서 '4번'처럼 횟수를 박지 않고
            // **도전하기 버튼 색**만 본다: 주황이면 아직 남았고, 청록이 되면 그 던전은 끝이다.
            var fought = 0
            var idle = 0
            val deadline = System.currentTimeMillis() + 260_000
            while (System.currentTimeMillis() < deadline && Runner.running) {
                val s = Runner.shot()
                if (s == null) { Runner.sleep(900); continue }
                if (Screen.atDailyEntry(s)) {
                    if (Screen.dailyChallengeDone(s)) break        // 청록 = 남은 열쇠 없음
                    if (Screen.dailyChallengeOpen(s)) {            // 주황 = 아직 남음
                        idle = 0
                        fought++
                        Runner.set("일일 던전 " + idx + "번째", name + " · " + fought + "번째 도전")
                        Runner.setProgress(2, 4)
                        Runner.tap(Screen.DAILY_CHALLENGE, 3000)
                        continue
                    }
                    // 주황도 청록도 아니다 = 열쇠가 아예 없거나 화면이 넘어가는 중.
                    idle++
                    if (idle >= 5) {
                        Bot.log("던전 " + idx + ": 도전할 수 없어요(열쇠 없음) - 다음 던전으로")
                        break
                    }
                    Runner.sleep(1200); continue
                }
                idle = 0
                Runner.status = "일일 던전 " + idx + "번째"
                Runner.detail = name + " · 자동 전투 중"
                Runner.setProgress(3, 4)
                Runner.sleep(5000)
            }
            if (fought > 0) battled++
            if (!Runner.running) break

            // ── 수령 전에 던전 화면이 '안정'됐는지 본다 ──
            var stable = 0
            var w = 0
            while (w < 8 && stable < 2 && Runner.running) {
                stable = if (Runner.shot()?.let { Screen.atDailyEntry(it) } == true) stable + 1 else 0
                Runner.sleep(1000); w++
            }
            b = Runner.shot() ?: break
            if (!Screen.atDailyEntry(b)) {
                Bot.log("던전 " + idx + ": 화면이 안정되지 않아 수령을 건너뜁니다(안전)")
                results.add(name + " 확인불가")
                continue
            }

            // ── 달성 보상 수령 ──
            // 상자 → 창 → [모두 받기] → 뒤로가기로 닫기. 세 자리 모두 실측값이다.
            // 받을 게 없으면 [모두 받기] 가 회색이라 눌러도 아무 일이 없다(무해).
            Runner.set("일일 던전 " + idx + "번째", name + " · 달성 보상")
            Runner.setProgress(4, 4)
            Runner.tap(Screen.DAILY_ACHIEVE, 1500)
            var opened2 = false
            for (k in 1..4) {
                if (Runner.shot()?.let { Screen.isDailyAchieve(it) } == true) { opened2 = true; break }
                Runner.sleep(600)
            }
            if (opened2) {
                Runner.tap(Screen.DAILY_CLAIM_ALL, 1600)
                // 창은 뒤로가기로 닫는다 — 바깥을 눌러 닫으려다 엉뚱한 걸 누른 이력이 있다.
                for (k in 1..3) {
                    TapService.back(); Runner.sleep(1100)
                    if (Runner.shot()?.let { Screen.atDailyEntry(it) } == true) break
                }
            } else {
                Bot.log("던전 " + idx + ": 달성 보상 창이 안 열렸어요 - 건너뜁니다")
            }

            results.add(name + " " + (if (fought > 0) fought.toString() + "판" else "열쇠 없음"))
            Runner.lastResult = results.joinToString(" · ")

            // ── ▶ 다음 던전 ──
            // ⚠️ **던전 화면일 때만** 누른다. 목록 화면에서 이 자리는 배너 한가운데다.
            Runner.shot()?.let {
                if (Screen.atDailyEntry(it)) Runner.tap(Screen.DAILY_NEXT, 2200)
                else Bot.log("  던전 화면이 아니라 ▶ 를 누르지 않았어요(목록에서 다시 고릅니다)")
            }
        }

        val head = if (battled == 0) "돌 수 있는 던전이 없었어요" else battled.toString() + "개 던전 진행함"
        if (Runner.running) Runner.set("일일 던전 끝", head) else Runner.set("멈췄어요", head)
        Runner.lastResult = if (results.isEmpty()) head else head + " — " + results.joinToString(" · ")
    }

    /**
     * 던전 안에 있으면 **목록으로 돌아온다**. 이미 목록이면 아무것도 안 한다.
     * 뒤로가기만 쓴다 — 무엇도 시작시키지 않는 유일하게 안전한 조작이다.
     */
    private fun backToList(): Boolean {
        for (k in 1..4) {
            if (!Runner.running) return false
            val b = Runner.shot() ?: return false
            if (Screen.atDailyList(b)) return true
            TapService.back()
            Runner.sleep(1400)
        }
        return Runner.shot()?.let { Screen.atDailyList(it) } == true
    }

    /**
     * 던전 → '일일 던전' 탭 → 첫 던전 배너 → 진입 화면.
     * 진입 화면이 보일 때까지 확인하고, 안 되면 통째로 한 번 더 시도한다.
     * 그 뒤의 모든 동작은 `atDailyEntry` 가 참일 때만 하므로, 여기서 어긋나도 헛탭으로 끝난다.
     */
    /**
     * 일일 던전 진입 화면까지 간다.
     *
     * ⚠️ **이미 목록 화면에 있으면 되돌아 나가지 않는다.** 예전엔 고정 좌표로 첫 배너를 누르고,
     *    안 들어가지면 무조건 `clearPopups` + `resetToMain` 으로 메인까지 되돌아갔다.
     *    그런데 그 고정 좌표가 배너 사이 **검은 띠**여서 탭이 늘 헛돌았고, 목록 앞에 서 있으면서도
     *    "가림막 치우는 중"으로 되돌아가기를 반복했다. 화면을 보고 배너를 찾아 누른다.
     */
    private fun enter(): Boolean {
        for (t in 1..3) {
            if (!Runner.running) return false
            Runner.set("일일 던전으로 이동 중", t.toString() + "/3")

            var b = Runner.shot()
            if (b != null && Screen.atDailyList(b)) return true   // 목록까지만 가면 된다
            if (b != null && Screen.atDailyEntry(b)) return true

            // 목록이 아니면 그때만 던전 탭으로 이동한다.
            if (b == null || !Screen.atDailyList(b)) {
                Runner.tap(Screen.NAV_DUNGEON, 3000)
                Runner.tap(Screen.DAILY_TAB, 2500)
                b = Runner.shot()
            }

            if (b != null && Screen.atDailyList(b)) return true

            // 목록도 아니고 진입도 안 됐다. 그때만 팝업을 치우고 메인부터 다시 간다.
            if (t < 3) { Runner.clearPopups(); Runner.resetToMain() }
        }
        return false
    }
}
