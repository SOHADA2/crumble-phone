package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 쿠키 **조합(덱) 구성** — 1~5번 덱을 이름으로 채우기 위한 것.
 *
 * ## 왜 '사전'이 필요한가
 * 편성 목록에는 **쿠키 이름이 안 적혀 있다**(실측: `Lv.61` · `편성중` · `3/7` 뿐).
 * 초상화만 나열돼 있어서, 이름으로 찾으려면 **어느 칸이 어느 쿠키인지**를 한 번은 알아 둬야 한다.
 *
 * ## 어떻게 만드나 — 상세 화면의 ▶ 를 쓴다
 * 목록 첫 칸을 열면 상세 화면이 뜨고, **좌우에 ◀ ▶ 넘김 버튼**이 있다(실측 (63,1792)/(1375,1793)).
 * 그래서 `목록 → 상세 → 뒤로` 를 73번 반복할 필요가 없다:
 *
 * ```
 * 첫 칸 열기 → 이름 읽기 → ▶ → 이름 읽기 → ▶ → …
 * ```
 *
 * PC 봇은 이걸 **도감 45그룹 순회**로 하려 했는데(`deck_dict.ps1`), 폰에서는 편성 화면 안에서 끝난다.
 *
 * ## 끝을 어떻게 아나
 * 이름이 **이미 본 것**으로 돌아오면 한 바퀴 돈 것으로 본다.
 * 개수를 미리 못 믿는 이유는 목록이 `골라보기`(정렬·필터)에 따라 달라지기 때문이다.
 */
object Deck {

    /** 한 바퀴 상한. 실제로는 이름이 겹치는 순간 끝난다 — 무한히 돌지 않게 두는 안전선. */
    private const val MAX_COOKIES = 400

    /** ▶ 를 누르고 화면이 바뀔 때까지 얼마나 기다릴지. */
    private const val STEP_MS = 900L

    fun buildDict(ctx: Context) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "쿠키 사전"
        thread(name = "deckdict") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                run()
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun run() {
        // ── 편성 화면으로 ──
        Runner.set("쿠키 사전 만드는 중", "편성 화면으로 가는 중")
        Runner.tap(Screen.NAV_COOKIE, 2500)
        var b = Runner.shot()
        if (b == null || !Screen.atCookieRoster(b)) {
            Runner.set("편성 화면을 못 열었어요", "쿠키 탭이 안 눌렸어요")
            Bot.log("편성 화면이 아닙니다 - 사전 만들기를 멈춥니다")
            return
        }

        // ── 목록 맨 위로 ──
        // 지금 어디까지 내려와 있는지 모르니 위로 넉넉히 끈다. 이미 위면 아무 일도 안 일어난다.
        Runner.set("쿠키 사전 만드는 중", "목록 맨 위로")
        for (k in 1..6) {
            if (!Runner.running) return
            TapService.swipe(Screen.GRID_SWIPE_X, Screen.GRID_TOP, Screen.GRID_SWIPE_X, Screen.GRID_BOTTOM, 320)
            Runner.sleep(450)
        }
        Runner.sleep(800)

        // ── 첫 칸을 열어 상세로 ──
        Runner.tap(Screen.cardAt(0, 0), 1800)
        b = Runner.shot()
        if (b == null || !Screen.atCookieDetail(b)) {
            Runner.set("상세를 못 열었어요", "첫 칸이 안 눌렸어요")
            Bot.log("쿠키 상세 화면이 아닙니다 (◀ ▶ 를 못 찾음) - 멈춥니다")
            return
        }

        // ── ▶ 로 한 바퀴 ──
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        var blanks = 0
        for (i in 1..MAX_COOKIES) {
            if (!Runner.running) break
            val shot = Runner.shot() ?: break
            if (!Screen.atCookieDetail(shot)) {
                Bot.log("상세 화면을 벗어났어요 (" + i + "번째) - 여기서 멈춥니다")
                break
            }
            val nm = readName(shot)
            if (nm == null) {
                // 한두 번은 연출 때문일 수 있다. 연달아 못 읽으면 그만둔다.
                blanks++
                Bot.log("  " + i + ": 이름을 못 읽었어요")
                if (blanks >= 3) { Bot.log("연달아 세 번 못 읽어 멈춥니다"); break }
            } else {
                blanks = 0
                if (!seen.add(nm)) {
                    Bot.log("'" + nm + "' 이 다시 나왔어요 - 한 바퀴 돈 것으로 봅니다")
                    break
                }
                names.add(nm)
                Bot.log("  " + names.size + ": " + nm)
            }
            Runner.set("쿠키 사전 만드는 중", names.size.toString() + "마리 읽음")
            Runner.setProgress(names.size, 80)
            Runner.tap(Screen.DETAIL_NEXT, STEP_MS)
        }

        // ── 목록으로 돌아가기 ──
        TapService.back(); Runner.sleep(1200)

        if (names.isEmpty()) {
            Runner.set("한 마리도 못 읽었어요", "글자 읽기 점검부터 해 보세요")
            Runner.lastResult = "쿠키 사전: 실패 (이름을 못 읽음)"
            return
        }
        Prefs.deckDict = names.joinToString("\n")
        Runner.set("쿠키 사전 완성", names.size.toString() + "마리를 기억했어요")
        Runner.lastResult = "쿠키 사전 " + names.size + "마리"
        Bot.log("── 쿠키 사전 " + names.size + "마리 저장 ──")
    }

    /**
     * 상세 화면의 이름을 읽는다. 여러 줄로 잡히면 **'쿠키'로 끝나는 줄**을 고른다
     * (같은 띠에 속성·등급 글자가 같이 있을 수 있다).
     *
     * ⚠️ `쿠키` 가 `쿠귀`·`구기` 로 자주 오독된다(PC 봇도 같은 계열을 겪었다) → 끝말을 바로잡는다.
     */
    private fun readName(b: android.graphics.Bitmap): String? {
        val c = Screen.NAME_CROP
        val raw = Ocr.readKorean(b, c[0], c[1], c[2], c[3]) ?: return null
        val lines = raw.split("\n").map { clean(it) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.firstOrNull { it.endsWith("쿠키") } ?: lines.maxByOrNull { it.length }
    }

    private val TAIL = Regex("[구무부쿠][기귀키]$")

    private fun clean(s: String): String {
        var t = s.replace(Regex("\\s+"), " ").trim()
        t = TAIL.replace(t, "쿠키")
        return t
    }
}
