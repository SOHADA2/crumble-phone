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
    internal const val PRESETS = 5        // 쿠키 조합 1~5

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
            if (!challengeOnce(n)) {
                // 소환 배너가 사라졌다 = 그 사이에 깼거나 화면이 바뀐 것. 기다릴 이유가 없다.
                Runner.set("도전할 보스가 없어요", "소환 배너가 사라졌어요")
                Runner.lastResult = "소환 배너가 사라져 중단했어요"
                return
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

    /**
     * 보스 한 번 도전 — **팝업 치우기 → 조합 변경 → 소환 → (지연) → 우측 돌진**.
     *
     * [보스전] 버튼과 **퀘스트(`Chores`)가 같은 함수를 쓴다.** 보스에 가는 길이 두 곳에 생기면
     * 한쪽만 고쳐 놓고 잊는다 — PC 봇도 두 경로(배너·탐색 실패)가 같은 함수를 부른다.
     */
    internal fun challengeOnce(n: Int, loose: Boolean = false): Boolean {
        // ── ① 아무것도 건드리기 전에 배너부터 확인한다 ──
        // 이게 있어서 부르는 쪽이 **한 번 보자마자 바로** 불러도 된다.
        // 여기서 걸러지면 조합도 안 바꾸고 팝업도 안 건드리니 **오탐 비용이 0** 이다.
        // (예전엔 부르는 쪽이 두 바퀴 연속 보고 나서야 불렀는데, 그 기다리는 사이에
        //  퀘스트 수령이 카운터를 지워 영영 못 들어가는 사고가 났다)
        val pre = Runner.shot()
        if (pre == null || !Screen.hasBossBanner(pre, loose)) {
            Bot.log("  '보스 소환' 배너가 안 보여요 - 아무것도 안 하고 돌아갑니다")
            return false
        }

        // 오븐 퀘스트 직후엔 장비 '장착/판매' 비교 팝업이 남아 있을 수 있다.
        // 그대로 두면 아래 탭이 전부 '팝업 바깥 누르기'로 먹혀 조합 변경도 보스 소환도 안 되는데,
        // 조합은 한 칸 써 버려서 싸우지도 않고 '1~5 모두 실패'까지 간다. 먼저 치운다.
        clearEquipPopup()
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
        //
        // ── ② 누르기 직전에 **다시** 찾는다 ──
        // 조합 변경으로 화면을 한 바퀴 돌고 왔으니 그 사이에 상황이 바뀌었을 수 있다.
        // 소환 자리는 **고정 좌표가 아니라 찾아서** 누른다. 예전엔 PC 좌표 `(710,406)` 을 그대로
        // 눌렀는데 폰에서 그 자리는 배너 위 허공이라, 조합만 한 칸씩 까먹고 한 번도 안 싸웠다.
        val shot = Runner.shot()
        val at = if (shot == null) null else Screen.bossSummonPoint(shot, loose)
        if (at == null) {
            Bot.log("  '보스 소환' 배너를 못 찾았어요 - 아무것도 누르지 않고 넘어갑니다")
            return false
        }
        val chargeMs = Prefs.bossChargeMs
        Runner.tap(at, Prefs.bossDelayMs.toLong())
        if (chargeMs > 0 && Runner.running) {
            Bot.log("  오른쪽으로 " + (chargeMs / 1000.0) + "초 밀어붙임")
            val ms = (chargeMs * 1.10).toLong()
            TapService.swipe(Screen.CHARGE_FROM[0], Screen.CHARGE_FROM[1],
                Screen.CHARGE_TO[0], Screen.CHARGE_TO[1], ms)
            Runner.sleep(ms + 300L)
        }
        return true
    }

    /**
     * 오븐에서 뽑은 장비의 '장착 / 판매' 비교 팝업이 남아 있으면 치운다.
     * 이 팝업은 닫기 X 가 없어 **바깥을 누르면 닫히는** 종류라, 떠 있는 동안의 탭은 전부 '팝업 닫기'로 먹힌다
     * → 조합 변경도 보스 소환도 안 되고 돌진 드래그도 팝업 위에서 일어나 조이스틱에 안 닿는다.
     * 그런데도 조합은 한 칸 써 버려서, 싸우지도 않고 '1~5 모두 실패'까지 갈 수 있다.
     */
    private fun clearEquipPopup() {
        for (k in 1..3) {
            val s = Runner.shot() ?: return
            if (!Screen.isOvenPopup(s)) { if (k > 1) Bot.log("  장비 비교 팝업 치움"); return }
            Runner.tap(Screen.OUTSIDE, 1200)
        }
        Bot.log("  장비 비교 팝업이 안 닫혀요 - 그래도 진행합니다")
    }

    /** 쿠키 조합(프리셋) n 번으로 바꾼다. 쿠키 화면 → 프리셋 탭 → 전투 화면으로 복귀. */
    private fun switchPreset(n: Int) {
        if (n < 1 || n > Screen.PRESET_TABS.size) return
        Runner.tap(Screen.NAV_COOKIE, 2500)

        // ⚠️ 편성 화면이 열린 걸 **확인하고** 누른다. 옛 좌표가 팀 진열대 배경을 가리키는 바람에
        //    쿠키 상세 화면이 열려 봇이 거기서 길을 잃은 적이 있다.
        //    화면이 아니면 조합 변경만 건너뛰고 전투로 돌아간다 — 헤매느니 조합 하나를 포기한다.
        val b = Runner.shot()
        if (b != null && Screen.atCookieRoster(b)) {
            Runner.tap(Screen.PRESET_TABS[n - 1], 1500)
        } else {
            Bot.log("  편성 화면이 안 열렸어요 - 조합 " + n + " 변경을 건너뜁니다")
        }
        Runner.tap(Screen.NAV_BATTLE, 2500)

        // 전투 화면으로 못 돌아왔으면(쿠키 상세 같은 데 있으면) 뒤로가기로 빠져나온다.
        for (k in 1..3) {
            val s = Runner.shot() ?: break
            if (Screen.atMain(s)) break
            TapService.back(); Runner.sleep(1500)
        }
    }
}
