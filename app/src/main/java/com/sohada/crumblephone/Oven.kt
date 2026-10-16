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
    private const val IDLE_QUIT_MS = 5_000L    // 한 싸이클은 끊김 없이 이어지므로 5초면 '끝났다'로 충분(예전 10초)

    /**
     * Auto 한 번이 얼마나 큰지는 **오븐 레벨마다 다르다**(1회에 5~50개).
     * 게임 쪽 '자동 열기 → 1회에 여는 개수'를 설정(⚙ → 오븐 1회 개수)에 맞춰 두면
     * 그 값으로 상한을 잡는다. 20개짜리면 **한 싸이클로 퀘스트가 끝난다**(사용자 확인).
     */
    private fun perRun() = Prefs.ovenPerRun.coerceIn(1, 50)

    /**
     * ★ 2026-10-16: 봇은 **정리하지 않는다**(사장님 결정). 장비 프리셋이 생긴 뒤로 [정리 하기] 와
     * '자동 정리' 의 [정리하기] 는 전투력 높은 순으로 장착해 버려서 손으로 고른 옵션을 망친다.
     * 결과 패널은 ✕ 로 닫고, 장비가 쌓여 더 못 뽑으면 이 깃발을 세워 사람에게 넘긴다.
     */
    @Volatile var needTidy = false
        private set

    /** 퀘스트를 채웠으면 true. 시작조차 못 했으면 false. */
    fun run(count: Int = 15): Boolean {
        needTidy = false
        // 오븐이 꽉 차면 '자동 정리' 확인창이 뜬다. 그게 떠 있으면 Auto 시작이 막힌다.
        if (!clearDialog()) {
            Bot.log(if (needTidy) "  오븐: 장비가 꽉 찼어요 - 정리는 직접 해 주세요" else "  오븐: 확인창을 못 치웠어요 - 오븐은 건너뜁니다")
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
            if (!clearDialog()) { if (needTidy) return false; break }
            Runner.shot()?.let { if (Screen.isOvenPopup(it)) Runner.tap(Screen.OUTSIDE, 1500) }

            Runner.set("오븐에서 장비 뽑는 중", "시작 " + attempt + "/2")
            Runner.tap(Screen.OVEN_AUTO, 1500)
            // ★ 어느 패널이 열렸는지 보고 나서 누른다.
            //   좌표가 같아서 눈 감고 누르면 [시작]/[정리 하기] 로 의미가 정반대일 수 있다.
            var kind = Runner.shot()?.let { Screen.ovenPanelKind(it) } ?: "none"
            if (kind == "result") {
                // 지난 결과가 남아 있으면 Auto 를 새로 못 연다. 정리는 사람이 한다 → ✕ 로 닫고 넘긴다.
                Bot.log("  오븐: 지난 장비가 남아 있어 새로 뽑을 수 없어요 - 정리는 직접 해 주세요")
                Runner.tap(Screen.OVEN_PANEL_X, 1500)
                needTidy = true
                return false
            }
            if (kind != "open") { Bot.log("  오븐: '자동 열기' 패널이 안 열렸어요 (판정 " + kind + ")"); continue }
            Runner.tap(Screen.OVEN_GO, 1500)          // [시작]

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

                // 장비가 꽉 차면 '정리하기' 창이 뜨고 그때부터 아무것도 안 뽑힌다(장비 30개쯤).
                // 여기서 안 풀어 주면 남은 시간 내내 헛돈다.
                if (Screen.isOvenCleanupDialog(cur)) {
                    Bot.log("  오븐: '자동 정리' 창 (장비가 꽉 찼어요) - [그만두기], 정리는 직접 해 주세요")
                    Runner.tap(Screen.DLG_SAFE, 2000)
                    needTidy = true
                    break
                }

                // ★ 싸이클이 끝나면 게임이 '자동 열기 결과' 패널을 스스로 띄운다 — 가장 확실한 종료 신호다.
                //   (뱃지 유휴보다 훨씬 낫다. PC 실측: 뱃지는 2590~2744 로 흔들리기만 해 가동/정지를 못 가른다.)
                if (Screen.ovenPanelKind(cur) == "result") {
                    Bot.log("  오븐: 한 싸이클 끝 ('자동 열기 결과' 패널, 넘김 " + dismissed + "회)")
                    break
                }

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
     *
     * ⚠️ `OVEN_GO` 는 '자동 열기' 패널의 [시작] 과 '자동 열기 결과' 패널의 [정리 하기] 가 **같은 좌표**다.
     *    어느 패널이 열리는지는 **지금 Auto 가 켜져 있느냐**에 달렸다:
     *      Auto 켜짐 → '결과' 패널 → [정리 하기] → 꺼진다 (원하는 것)
     *      Auto 꺼짐 → '자동 열기' 패널 → **[시작]** → **켜진다** (정반대!)
     *    그래서 Auto 가 스스로 한 싸이클을 끝낸 뒤에 부르면 **오히려 다시 켜고 나간다.**
     *    PC 봇에서 이 탓에 '오븐에서 장비 뽑기 50개' 가 **123/50** 까지 초과 진행됐다(사용자 제보).
     *    → **누르기 전에 `Screen.ovenPanelKind` 로 어느 패널인지 가린다.**
     *      'result' 면 [정리 하기], 'open' 이면 Auto 가 안 도는 것이니 안 누르고 X 로 닫는다.
     */
    private fun stopAuto() {
        for (t in 1..3) {
            if (!Runner.running) return
            clearDialog()
            clearPopups(3, 1500)
            var kind = Runner.shot()?.let { Screen.ovenPanelKind(it) } ?: "none"
            if (kind == "none") {
                Runner.tap(Screen.OVEN_AUTO, 2000)   // 패널이 안 보이면 열어서 어느 쪽인지 본다
                kind = Runner.shot()?.let { Screen.ovenPanelKind(it) } ?: "none"
            }

            if (kind == "result") {
                // [정리 하기] 는 누르지 않는다(위 needTidy 주석). ✕ 로 닫기만 — Auto 가 아직 돌고 있었다면
                // '1회 개수' 만큼 뽑거나 30개가 차면 게임이 알아서 멈춘다.
                Bot.log("  오븐: '자동 열기 결과' - 정리하지 않고 ✕ 로 닫습니다 (장비는 오븐에 쌓여 있어요)")
                Runner.tap(Screen.OVEN_PANEL_X, 1500)
                clearPopups(2, 1000)
                return
            }
            if (kind == "open") {
                // [시작] 버튼이 보인다 = Auto 는 돌고 있지 않다. **절대 누르지 말고** 닫는다.
                // 여기를 눌렀던 것이 '끄려다 켜는' 사고의 정체였다.
                Bot.log("  오븐: Auto 는 꺼져 있어요 ('자동 열기' 패널) - 누르지 않고 닫습니다")
                Runner.tap(Screen.OVEN_PANEL_X, 1500)
                return
            }
            Bot.log("  오븐: 패널이 안 열려요 - 다시 시도 (" + t + "/3)")
        }
        Bot.log("  오븐: Auto 상태를 확인하지 못했어요")
    }

    private fun clearPopups(n: Int, waitMs: Long) {
        for (k in 1..n) {
            val s = Runner.shot() ?: return
            if (!Screen.isOvenPopup(s)) return
            Runner.tap(Screen.OUTSIDE, waitMs)
        }
    }

    /**
     * 확인창이 떠 있으면 **왼쪽(안전한 쪽)** 으로 닫는다. 치웠으면 true.
     *
     * ★ 2026-10-16 부터 이 파일은 **오른쪽(주황)을 한 번도 누르지 않는다.** 오븐 '자동 정리' 창도
     *   왼쪽 [그만두기] 로 닫고 needTidy 를 세운다 — 정리는 사람이 한다(장비 프리셋 때문).
     *   판정(`isOvenCleanupDialog`)은 '자동 정리' 창인지 알아보려고 그대로 쓴다.
     */
    private fun clearDialog(): Boolean {
        for (k in 1..4) {
            val s = Runner.shot() ?: return false
            if (Screen.isOvenCleanupDialog(s)) {
                // 오븐이 꽉 찼다. 예전엔 오른쪽 [정리하기] 를 눌렀지만 이제 정리는 사람이 한다 → 왼쪽 [그만두기].
                Bot.log("  오븐: '자동 정리' 창 (장비가 꽉 찼어요) - [그만두기], 정리는 직접 해 주세요")
                Runner.tap(Screen.DLG_SAFE, 2000)
                needTidy = true
                return false
            }
            if (!Screen.isConfirmDialog(s)) return true
            Bot.log("  오븐: 확인창 - 왼쪽(안전한 쪽)으로 닫습니다")
            Runner.tap(Screen.DLG_SAFE, 2500)
        }
        return false
    }
}
