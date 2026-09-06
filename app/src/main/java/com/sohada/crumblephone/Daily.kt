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

        val sigs = ArrayList<IntArray>()
        val results = ArrayList<String>()
        var battled = 0

        for (d in 1..maxDungeons) {
            if (!Runner.running) break
            var b = Runner.shot() ?: break
            if (!Screen.atDailyEntry(b)) { Runner.sleep(1200); continue }

            // 한 바퀴 종료 판정은 **첫 던전으로 되돌아왔을 때만** 한다.
            // 중간 던전끼리는 서로 비슷해서 그걸로 끊으면 일찍 멈춘다.
            val sig = Screen.dailySig(b)
            if (sigs.isNotEmpty() && Screen.dailySigMatch(sigs[0], sig)) {
                Bot.log("첫 던전으로 되돌아옴 - 한 바퀴 완료"); break
            }
            sigs.add(sig)
            val idx = sigs.size
            val name = nameOf(idx)
            Runner.set("일일 던전 " + idx + "번째", name + " · 준비 중")
            Runner.setProgress(1, 4)      // 이 던전 안에서의 단계다(전체 진행률이 아니다)

            // ── '연속 도전'은 끈다 ──
            // 켜면 게임이 봇과 상관없이 자동으로 다음 판을 이어가서 화면이 어긋난다
            // (엉뚱한 팝업 위에 탭이 들어간다). 봇이 매 판 직접 눌러
            // '한 판 끝 → 진입 화면 복귀'를 확인하며 동기적으로 나아간다.
            for (k in 1..2) {
                val s = Runner.shot() ?: break
                if (!Screen.dailyContChecked(s)) break
                Runner.tap(Screen.DAILY_CONT_CHK, 1200)
            }

            // ── 전투: 도전하기가 주황이면 누르고, 청록(0/3)이 되면 이 던전 끝 ──
            var fought = false
            var idle = 0     // 진입 화면인데 도전도 완료도 아닌 상태가 연속 몇 번인지
            val deadline = System.currentTimeMillis() + 260_000   // 던전당 안전 상한
            while (System.currentTimeMillis() < deadline && Runner.running) {
                val s = Runner.shot()
                if (s == null) { Runner.sleep(900); continue }
                if (Screen.atDailyEntry(s)) {
                    if (Screen.dailyChallengeDone(s)) break
                    if (Screen.dailyChallengeOpen(s)) {
                        idle = 0
                        Runner.set("일일 던전 " + idx + "번째", name + " · 도전")
                        Runner.setProgress(2, 4)
                        Runner.tap(Screen.DAILY_CHALLENGE, 3000)
                        fought = true
                        continue
                    }
                    // 도전도 안 되고 완료도 아니다 = **열쇠(기회)가 없다.**
                    // 예전엔 여기서 260초 상한까지 그냥 기다렸다. 기다려도 열쇠는 안 생기니
                    // 6초만 지켜보고(전환 중일 수 있다) 바로 다음 던전을 보러 간다.
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
            if (fought) battled++
            if (!Runner.running) break

            // ── 수령 전에 진입 화면이 '안정'됐는지 본다 ──
            // 비동기 결과 화면이 남아 있을 수 있다. 진입 화면이 두 번 연속 보일 때까지 기다린다.
            var stable = 0
            var w = 0
            while (w < 8 && stable < 2 && Runner.running) {
                stable = if (Runner.shot()?.let { Screen.atDailyEntry(it) } == true) stable + 1 else 0
                Runner.sleep(1200); w++
            }
            b = Runner.shot() ?: break
            if (!Screen.atDailyEntry(b)) {
                // 엉뚱한 화면에서 마구 누르지 않는다. 수령은 건너뛰고 순환만 이어간다.
                Bot.log("던전 " + idx + ": 진입 화면이 안정되지 않아 수령을 건너뜁니다(안전)")
                results.add(name + " 확인불가")
                Runner.tap(Screen.DAILY_NEXT, 2200)
                continue
            }

            // ── 달성 보상 수령 ──
            Runner.set("일일 던전 " + idx + "번째", name + " · 달성 보상 수령")
            Runner.setProgress(4, 4)
            Runner.tap(Screen.DAILY_ACHIEVE, 2000)
            Runner.tap(Screen.DAILY_CLAIM_ALL, 2000)
            Runner.tap(Screen.DAILY_MODAL_CLOSE, 1500)

            results.add(name + " " + (if (fought) "기회 소진" else "이미 완료"))
            Runner.lastResult = results.joinToString(" · ")
            Runner.tap(Screen.DAILY_NEXT, 2200)
        }

        val head = if (battled == 0) "모두 완료 (더 누를 필요 없어요)" else battled.toString() + "개 던전 진행함"
        if (Runner.running) Runner.set("일일 던전 끝", head) else Runner.set("멈췄어요", head)
        Runner.lastResult = if (results.isEmpty()) head else head + " — " + results.joinToString(" · ")
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
            if (b != null && Screen.atDailyEntry(b)) return true

            // 목록이 아니면 그때만 던전 탭으로 이동한다.
            if (b == null || !Screen.atDailyList(b)) {
                Runner.tap(Screen.NAV_DUNGEON, 3000)
                Runner.tap(Screen.DAILY_TAB, 2500)
                b = Runner.shot()
            }

            if (b != null && Screen.atDailyList(b)) {
                val y = Screen.findDailyBanner(b)
                if (y > 0) {
                    Bot.log("일일 던전 목록 - 배너(y=" + y + ")를 눌러 들어갑니다")
                    Runner.tap(intArrayOf(700, y), 2800)
                    for (w in 1..5) {
                        if (Runner.shot()?.let { Screen.atDailyEntry(it) } == true) return true
                        Runner.sleep(900)
                    }
                    Runner.shot()?.let { Bot.log("  들어가지 못했어요: " + Screen.debugLine(it)) }
                } else {
                    Bot.log("일일 던전 목록인데 배너를 못 찾았어요")
                }
            }

            // 목록도 아니고 진입도 안 됐다. 그때만 팝업을 치우고 메인부터 다시 간다.
            if (t < 3) { Runner.clearPopups(); Runner.resetToMain() }
        }
        return false
    }
}
