package com.sohada.crumblephone

/**
 * 오븐 장비 뽑기 — PC 봇 `oven.ps1` 의 Auto(자동 열기) 방식을 옮겼다.
 * '오븐에서 장비 뽑기 N번' 퀘스트를 대신 채워 준다. 전투력도 같이 오른다.
 *
 * 흐름:
 *   Auto → '자동 열기' 패널 → [시작] → 알아서 여러 개씩 뽑힌다.
 *   교체 후보가 나오면 장착/판매 팝업이 뜨는데 **버튼 말고 팝업 바깥**을 눌러 넘긴다.
 *   (Auto 가동 중에는 이렇게 넘겨도 퀘스트 수치가 오르고 장비는 오븐에 쌓인다.
 *    Auto 없이 수동으로 뽑을 때는 바깥 탭이 오답이었다 — 상황이 다르다.)
 *
 * ★ **Auto 는 어떤 경로로 끝나든 반드시 꺼야 한다.** 켜 둔 채 나가면 재화를 계속 소모한다.
 *   그래서 시작에 성공한 뒤로는 전부 try/finally 안에서 돈다. 사용자가 [멈추기] 를 눌러도
 *   끄는 것까지는 하고 나간다 — 여기서 일찍 빠지면 돈이 샌다.
 *
 * 한 번에 몇 개가 나오는지는 오븐 레벨마다 다르다. 그래서 개수에 의존하지 않고
 * '넘긴 팝업 수'와 '퀘스트 완료'로만 판단하며, 상한을 둬서 폭주를 막는다.
 */
object Oven {

    private const val DONE_STRICT = 1.0    // 가동 중 완료 판정 문턱
    private const val IDLE_QUIT_MS = 10_000L   // 이만큼 아무 변화가 없으면 한 싸이클이 끝난 것

    /**
     * Auto 한 번이 얼마나 큰지는 **오븐 레벨마다 다르다**(1회에 5~50개).
     * 게임 쪽 '자동 열기 → 1회에 여는 개수'를 설정(⚙ → 오븐 1회 개수)에 맞춰 두면
     * 그 값으로 상한을 잡는다. 20개짜리면 **한 싸이클로 퀘스트가 끝난다**(사용자 확인).
     */
    private fun perRun() = Prefs.ovenPerRun.coerceIn(1, 50)

    /** 퀘스트를 채웠으면 true. 시작조차 못 했으면 false. */
    fun run(count: Int = 15): Boolean {
        // 오븐이 꽉 차면 '자동 정리' 확인창이 뜬다. 그게 떠 있으면 Auto 시작이 막힌다.
        if (!clearDialog()) {
            Bot.log("  오븐: 확인창을 못 치웠어요 - 오븐은 건너뜁니다")
            return false
        }

        val first = Runner.shot() ?: return false
        if (Screen.questBarRatio(first) >= 0.85) { Bot.log("  오븐: 퀘스트가 이미 완료 - 돌릴 필요 없음"); return false }

        val baseBadge = Screen.ovenBadge(first)
        var started = false

        // Auto 를 눌렀을 때 열리는 패널이 두 가지다.
        //   '자동 열기'      → [시작] 을 누르면 가동
        //   '자동 열기 결과' → (이전 잔여물) [정리 하기]만 되고 Auto 는 여전히 꺼짐 → 한 번 더 시도
        for (attempt in 1..2) {
            if (started || !Runner.running) break
            if (!clearDialog()) break
            Runner.shot()?.let { if (Screen.isOvenPopup(it)) Runner.tap(Screen.OUTSIDE, 1500) }

            Runner.set("오븐에서 장비 뽑는 중", "시작 " + attempt + "/2")
            Runner.tap(Screen.OVEN_AUTO, 1500)
            Runner.tap(Screen.OVEN_GO, 1500)

            // 가동 확인: 14초 안에 (a)비교 팝업 (b)뱃지 증가 (c)퀘스트 완료 중 하나면 돌고 있는 것.
            val until = System.currentTimeMillis() + 14_000
            while (System.currentTimeMillis() < until && Runner.running) {
                Runner.sleep(1500)
                val s = Runner.shot() ?: continue
                if (Screen.isOvenPopup(s)) { started = true; break }
                if (Screen.ovenBadge(s) > baseBadge + 800) { started = true; break }
                // 여기서는 엄격한 문턱을 쓴다 — '자동 열기' 패널이 뜨는 순간 띠 영역이 0.85~0.88 로
                // 읽혀 '0회 뽑고 헛완료' 로 오판하던 사고가 있었다. 진짜 금색은 1.1+ 다.
                if (Screen.questBarRatio(s) >= DONE_STRICT) { started = true; break }
            }
        }

        if (!started) {
            Bot.log("  오븐: Auto 가동을 확인하지 못했어요")
            stopAuto()      // 눌러는 봤으니 켜졌을 수 있다 - 안전하게 꺼 둔다
            return false
        }

        var dismissed = 0
        var done = false
        val per = perRun()
        // 상한 셋 — 시간·넘긴 수·'조용해지면 끝'. 예전엔 시간만 있어서 완료 감지가 빗나가면
        // 140초 동안 팝업 55개(≈장비 110개)를 뽑아 재화를 크게 낭비했다.
        // 이제는 **한 싸이클 분량**에 맞춘다: 1회 개수보다 조금 넉넉한 만큼만 넘기고,
        // 팝업도 뱃지 변화도 10초 없으면 싸이클이 끝난 것으로 보고 나간다.
        val maxDismiss = (per + 5).coerceIn(8, 40)
        try {
            val deadline = System.currentTimeMillis() + maxOf(60L, per * 5L) * 1000L
            var lastMove = System.currentTimeMillis()
            var lastBadge = baseBadge
            Bot.log("  오븐: 1회 " + per + "개 기준 · 최대 넘김 " + maxDismiss + "회 · 최대 " +
                    maxOf(60L, per * 5L) + "초")
            Runner.set("오븐에서 장비 뽑는 중", "0/" + maxDismiss)
            Runner.setProgress(0, maxDismiss)
            while (System.currentTimeMillis() < deadline && dismissed < maxDismiss && Runner.running) {
                val cur = Runner.shot()
                if (cur == null) { Runner.sleep(2000); continue }

                // 뱃지가 오르고 있으면 아직 도는 중이다.
                val badge = Screen.ovenBadge(cur)
                if (badge != lastBadge) { lastBadge = badge; lastMove = System.currentTimeMillis() }
                if (System.currentTimeMillis() - lastMove > IDLE_QUIT_MS && !Screen.isOvenPopup(cur)) {
                    Bot.log("  오븐: 한 싸이클이 끝난 것 같아요 (넘김 " + dismissed + "회) - 나갑니다")
                    break
                }

                // 완료 확인을 먼저 한다. 팝업이 띠를 가려 못 읽을 때가 많다.
                if (Screen.questBarRatio(cur) >= DONE_STRICT) {
                    Bot.log("  오븐: 퀘스트 완료 (넘김 " + dismissed + "회)"); done = true; break
                }

                if (Screen.isOvenPopup(cur)) {
                    Runner.tap(Screen.OUTSIDE, 1000)   // 넘기면 오븐에 쌓인다(퀘스트 수치도 오른다)
                    dismissed++
                    lastMove = System.currentTimeMillis()
                    Runner.set("오븐에서 장비 뽑는 중", dismissed.toString() + "/" + maxDismiss)
                    Runner.setProgress(dismissed, maxDismiss)
                    // 팝업이 사라진 순간에 띠가 드러난다. 여기서 다시 봐야 완료를 안 놓친다.
                    val after = Runner.shot()
                    if (after != null && Screen.questBarRatio(after) >= DONE_STRICT) {
                        Bot.log("  오븐: 퀘스트 완료 (넘김 " + dismissed + "회)"); done = true; break
                    }
                    continue
                }
                Runner.sleep(1000)
            }
            if (dismissed >= maxDismiss) Bot.log("  오븐: 최대 " + maxDismiss + "회 도달 - Auto 로 못 끝내는 퀘스트로 보고 나갑니다")
            else if (!done && Runner.running) Bot.log("  오븐: 시간 상한 도달 (넘김 " + dismissed + "회)")
        }
        finally {
            stopAuto()      // ★ 어떤 경로로 왔든 Auto 는 반드시 끈다
        }
        return done
    }

    /**
     * Auto 를 끄고 쌓인 장비를 정리한다(게임이 전투력 높은 것만 남기고 판다).
     * 시작에 성공했다면 어떤 경로로 끝나든 반드시 거쳐야 하는, 이 파일에서 제일 중요한 함수다.
     */
    private fun stopAuto() {
        Bot.log("  오븐: Auto 끄고 정리")
        clearDialog()
        // 비교 팝업이 떠 있으면 Auto 패널이 제대로 안 열린다. 먼저 넘긴다.
        for (k in 1..3) {
            val s = Runner.shot() ?: break
            if (!Screen.isOvenPopup(s)) break
            Runner.tap(Screen.OUTSIDE, 1500)
        }
        Runner.tap(Screen.OVEN_AUTO, 2000)     // '자동 열기 결과' 패널
        Runner.tap(Screen.OVEN_GO, 2000)       // [정리 하기]
        for (k in 1..2) {
            val s = Runner.shot() ?: break
            if (!Screen.isOvenPopup(s)) break
            Runner.tap(Screen.OUTSIDE, 1000)
        }
    }

    /**
     * 확인창이 떠 있으면 **왼쪽(안전한 쪽)** 으로 닫는다. 치웠으면 true.
     *
     * ⚠️ PC 봇(`oven.ps1`)은 여기서 오븐이 꽉 찼을 때의 '자동 정리' 확인창을 보고
     *    **오른쪽 주황 [정리하기]** 를 눌렀다. 폰에서는 그렇게 하지 않는다.
     *    - 확인창은 전투 종료·게임 종료·다이아 새로고침이 **전부 같은 배치**라 색만으로는 못 가른다.
     *      잘못 누르면 전투 강제 종료 / 게임 종료 / 크리스탈 300 소모다.
     *    - PC 쪽 [정리하기] 좌표부터가 스크린샷 기준 **추정치**라 실측된 적이 없다.
     *    오븐 퀘스트 하나를 건너뛰는 손해가 훨씬 싸다. 오븐이 꽉 차면 한 번 손으로 정리해 주면 된다.
     */
    private fun clearDialog(): Boolean {
        for (k in 1..3) {
            val s = Runner.shot() ?: return false
            if (!Screen.isConfirmDialog(s)) return true
            Bot.log("  오븐: 확인창 - 왼쪽(안전한 쪽)으로 닫습니다")
            Runner.tap(Screen.DLG_SAFE, 2500)
        }
        return false
    }
}
