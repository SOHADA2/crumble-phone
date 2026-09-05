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
 * 아직 안 옮긴 것: **소탕(SKIP 티켓)**. PC 는 `-UseSkip` 스위치로 켜야만 하는데,
 * 폰에는 그 스위치를 둘 자리가 없어 뺐다. 넣으려면 SKIP 티켓을 쓴다는 걸 화면에 밝혀야 한다.
 */
object Daily {

    /** 일일 던전 목록은 위→아래 순서가 고정이라, 방문한 순서로 이름을 붙인다. */
    private val NAMES = arrayOf("경험치", "코인", "반죽", "연구석", "룬결정", "던전6", "던전7", "던전8")
    private fun nameOf(i: Int) = if (i in 1..NAMES.size) NAMES[i - 1] else "던전$i"

    fun start(ctx: Context, maxDungeons: Int = 8) {
        if (Runner.running) { Bot.log("이미 무언가 돌고 있어요"); return }
        if (!TapService.isReady) { Runner.set("시작 못 함", "접근성 서비스를 켜 주세요"); return }
        if (CaptureService.instance == null) { Runner.set("시작 못 함", "화면 읽기를 허용해 주세요"); return }
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
            val deadline = System.currentTimeMillis() + 260_000   // 던전당 안전 상한
            while (System.currentTimeMillis() < deadline && Runner.running) {
                val s = Runner.shot()
                if (s == null) { Runner.sleep(900); continue }
                if (Screen.atDailyEntry(s)) {
                    if (Screen.dailyChallengeDone(s)) break
                    if (Screen.dailyChallengeOpen(s)) {
                        Runner.set("일일 던전 " + idx + "번째", name + " · 도전")
                        Runner.setProgress(2, 4)
                        Runner.tap(Screen.DAILY_CHALLENGE, 3000)
                        fought = true
                        continue
                    }
                    Runner.sleep(1200); continue
                }
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
    private fun enter(): Boolean {
        for (t in 1..2) {
            if (!Runner.running) return false
            Runner.set("일일 던전으로 이동 중", t.toString() + "/2")
            Runner.tap(Screen.NAV_DUNGEON, 3000)
            Runner.tap(Screen.DAILY_TAB, 2500)
            Runner.tap(Screen.DAILY_FIRST, 2800)
            for (w in 1..4) {
                if (Runner.shot()?.let { Screen.atDailyEntry(it) } == true) return true
                Runner.sleep(900)
            }
        }
        return false
    }
}
