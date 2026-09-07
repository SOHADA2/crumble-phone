package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 보스 도전 — PC 봇 `boss.ps1` 을 그대로 옮긴 것.
 *
 * 스테이지가 보스에 막히면 쿠키 조합(프리셋)을 1~5 로 바꿔 가며 한 번씩 도전한다.
 * 조합 하나가 통하면 스테이지가 밀리고 끝난다.
 *
 * **왜 퀘스트에서 떼어냈나** — 보스는 '퀘스트를 받는 일'이 아니라 별개의 콘텐츠다.
 * 퀘스트를 돌렸을 뿐인데 보스전이 시작되면 사용자가 무슨 일이 벌어지는지 알 수 없다.
 * 지금은 [보스전]을 눌렀을 때만 보스를 소환한다.
 *
 * ### 승패 판정 — 상단 빨간 배너 하나만 본다
 * 배너가 남아 있으면 아직 못 깬 것, 사라졌으면 이겨서 스테이지가 밀린 것이다.
 * ⚠️ **전투 중에도 배너는 사라진다.** PC 봇이 이걸로 세 번 틀렸다(29회 '클리어'했다는데 실제는 1-5).
 *    그래서 소환하고 [WAIT_SEC] 를 다 기다린 뒤에만 본다.
 */
object Boss {

    private const val WAIT_SEC = 35L      // 소환 후 전투가 끝날 때까지 (실측: 전투 ~30초)
    private const val PRESETS = 5         // 쿠키 조합 1~5

    fun start(ctx: Context) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "보스전"
        thread(name = "boss") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                loop()
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun loop() {
        // 막힌 보스가 없으면 아무것도 안 한다. 없는데 소환 자리를 누르면 엉뚱한 걸 누르게 된다.
        val first = Runner.shot()
        if (first == null) { Runner.set("화면을 못 읽었어요", "다시 시도해 주세요"); return }
        if (!Screen.hasBossBanner(first)) {
            Runner.set("도전할 보스가 없어요", "지금은 보스에 막혀 있지 않아요")
            Runner.lastResult = "막힌 보스가 없었어요 (도전 안 함)"
            Bot.log("상단 '보스 소환' 배너가 없습니다 - 도전할 보스가 없어요")
            return
        }

        for (n in 1..PRESETS) {
            if (!Runner.running) break
            Runner.set("보스전 준비 중", "쿠키 조합 " + n + "/" + PRESETS)
            switchPreset(n)
            // ── 보스 소환 → 잠깐 오른쪽으로 밀어붙인다 ──
            // 전장을 누른 채 끌면 **조이스틱**이라 그 방향으로 캐릭터가 달려든다.
            // 대부분의 보스가 달려들었을 때 효과가 좋다. 시간은 ⚙ 에서 고른다(0 이면 안 함).
            //
            // ⚠️ **소환 직후엔 아직 조이스틱이 안 먹는다**(전투 시작 연출) — 그때 밀면 그냥 버려진다.
            //    그래서 '먹기 시작하는 시점'까지 기다렸다가 민다(⚙ 의 '돌진 시작 지연').
            // ⚠️ 미는 거리는 속도에 영향이 없다(조이스틱). 다만 선형으로 끌기 때문에 처음 얼마간은
            //    데드존 안이라 버려진다 → 거리를 800px 로 크게 잡고 시간도 10% 더 준다.
            // ⚠️ `dispatchGesture` 는 비동기다 — **끝날 때까지 기다려 줘야** 다음 동작과 안 겹친다.
            val chargeMs = Prefs.bossChargeMs
            Runner.tap(Screen.BOSS_SUMMON, Prefs.bossDelayMs.toLong())
            if (chargeMs > 0 && Runner.running) {
                Bot.log("  오른쪽으로 " + (chargeMs / 1000.0) + "초 밀어붙임")
                val ms = (chargeMs * 1.10).toLong()
                TapService.swipe(Screen.CHARGE_FROM[0], Screen.CHARGE_FROM[1],
                    Screen.CHARGE_TO[0], Screen.CHARGE_TO[1], ms)
                Runner.sleep(ms + 300L)
            }

            // 기다리는 동안 진행률을 채워 준다(멈춘 것처럼 보이지 않게).
            var s = 0L
            while (s < WAIT_SEC && Runner.running) {
                Runner.status = "보스전 진행 중"
                Runner.detail = "쿠키 조합 " + n + "/" + PRESETS + " · " + (WAIT_SEC - s) + "초 남음"
                Runner.setProgress(s.toInt(), WAIT_SEC.toInt())
                Runner.sleep(3000); s += 3
            }
            if (!Runner.running) break

            Runner.set("승패 확인 중", "쿠키 조합 " + n + "/" + PRESETS)
            val b = Runner.shot()
            if (b != null && !Screen.hasBossBanner(b)) {
                Runner.set("보스 클리어!", "쿠키 조합 " + n + "번으로 깼어요")
                Runner.lastResult = "✔ 쿠키 조합 " + n + "번으로 클리어"
                Bot.log("✔ 쿠키 조합 " + n + " 으로 보스 클리어 (스테이지 밀림)")
                return
            }
            Bot.log("  조합 " + n + " 실패 (보스 아직 안 깨짐) - 다음 조합")
        }

        if (Runner.running) {
            Runner.set("보스를 못 깼어요", "쿠키 조합 1~" + PRESETS + " 모두 도전했어요")
            Runner.lastResult = "✗ 쿠키 조합 1~" + PRESETS + " 모두 실패"
        } else {
            Runner.set("멈췄어요", "보스전을 중간에 멈췄어요")
        }
    }

    /** 쿠키 조합(프리셋) n 번으로 바꾼다. 쿠키 화면 → 프리셋 탭 → 전투 화면으로 복귀. */
    private fun switchPreset(n: Int) {
        if (n < 1 || n > Screen.PRESET_TABS.size) return
        Runner.tap(Screen.NAV_COOKIE, 2500)
        Runner.tap(Screen.PRESET_TABS[n - 1], 1500)
        Runner.tap(Screen.NAV_BATTLE, 2500)
    }
}
