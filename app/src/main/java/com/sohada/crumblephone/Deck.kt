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

    // ══════════════════════════════════════════════════════════
    //  덱 배치 — 이름 목록대로 프리셋 하나를 채운다
    // ══════════════════════════════════════════════════════════

    /**
     * 프리셋 `n`(1~5)을 `wanted` 이름들로 채운다.
     *
     * ## 짐작으로 누르지 않는다
     * 편집 모드에서 칸을 누르면 팀에 들어가고 다시 누르면 빠질 **것으로 보이지만**, 그건 아직
     * 실기로 못 본 동작이다. 그래서 **누를 때마다 배지를 다시 읽어 정말 바뀌었는지 확인**한다
     * (`Screen.cardInTeam`, 실측 25칸 전수 정답). 안 바뀌면 더 두드리지 않고 **바로 멈춘다** —
     * 이 프로젝트가 여러 번 당한 '안 먹는 좌표를 계속 누르며 헤매기'를 여기서 막는다.
     *
     * ## 저장은 마지막에 한 번
     * `dry` 면 [편성 저장] 대신 **주황 ✕ 로 취소**하고 나온다. 좌표가 맞는지 공짜로 보는 방법이다
     * (PC 봇의 `-MaxFights 0` 과 같은 자리).
     *
     * @return 실제로 팀에 넣은 쿠키 수
     */
    fun applyPreset(ctx: Context, n: Int, dry: Boolean) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "덱 " + n + "번"
        thread(name = "deckapply") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                apply(n, dry)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun apply(n: Int, dry: Boolean) {
        val wanted = Prefs.deckNames(n).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) {
            Runner.set("덱 " + n + "번이 비어 있어요", "쿠키 이름을 먼저 적어 주세요"); return
        }

        // 이름 → 목록 순번. 못 찾은 이름은 여기서 걸러 사용자에게 알린다.
        val want = LinkedHashMap<Int, String>()
        val missing = ArrayList<String>()
        for (nm in wanted) {
            val idx = findIndex(nm)
            if (idx < 0) missing.add(nm) else want[idx] = nm
        }
        if (missing.isNotEmpty()) Bot.log("사전에서 못 찾은 이름: " + missing.joinToString(", "))
        if (want.isEmpty()) {
            Runner.set("이름을 하나도 못 찾았어요", "쿠키 사전을 먼저 만들어 주세요"); return
        }

        // ── 편성 화면 → 프리셋 n → 편집 모드 ──
        Runner.set("덱 " + n + "번 맞추는 중", "편성 화면으로")
        Runner.tap(Screen.NAV_COOKIE, 2500)
        var b = Runner.shot()
        if (b == null || !Screen.atCookieRoster(b)) { fail("편성 화면을 못 열었어요"); return }

        Runner.tap(Screen.PRESET_TABS[n - 1], 1500)
        Runner.tap(Screen.ROSTER_EQUIP, 2000)
        b = Runner.shot()
        if (b == null || !Screen.atDeckEdit(b)) { fail("편집 모드로 못 들어갔어요"); return }

        // ── 맨 위로 ──
        for (k in 1..6) {
            if (!Runner.running) return
            TapService.swipe(Screen.GRID_SWIPE_X, Screen.GRID_TOP, Screen.GRID_SWIPE_X, Screen.GRID_BOTTOM, 320)
            Runner.sleep(400)
        }
        Runner.sleep(800)

        // ── 한 행씩 내려가며 맞춘다 ──
        // 목록 순번 = 행*5 + 열 이므로, 지금 화면 맨 위 행이 몇 번째인지만 알면 칸을 계산할 수 있다.
        var topRow = 0
        var added = 0
        var removed = 0
        val totalRows = (Prefs.deckDictSize + Screen.CARD_COLS - 1) / Screen.CARD_COLS
        while (topRow < totalRows && Runner.running) {
            val shot = Runner.shot()
            if (shot == null || !Screen.atDeckEdit(shot)) { fail("편집 모드를 벗어났어요"); return }
            // 한 장 찍어 **보이는 25칸을 한 번에** 읽는다. 칸마다 찍으면 한 페이지에 25장이라 너무 느리다.
            val todo = ArrayList<IntArray>()   // {열, 화면행, 순번, 넣을까(1/0)}
            for (r in 0 until Screen.CARD_ROWS_VISIBLE) {
                val row = topRow + r
                if (row >= totalRows) break
                for (c in 0 until Screen.CARD_COLS) {
                    val idx = row * Screen.CARD_COLS + c
                    if (idx >= Prefs.deckDictSize) break
                    val shouldBe = want.containsKey(idx)
                    if (Screen.cardInTeam(shot, c, r) != shouldBe)
                        todo.add(intArrayOf(c, r, idx, if (shouldBe) 1 else 0))
                }
            }
            run {
                for (job in todo) {
                    if (!Runner.running) return
                    val c = job[0]; val r = job[1]; val idx = job[2]; val shouldBe = job[3] == 1
                    val row = topRow + r
                    val label = want[idx] ?: ("순번 " + (idx + 1))
                    Runner.tap(Screen.cardAt(c, r, true), 700)
                    val after = Runner.shot()
                    if (after == null) { fail("화면을 못 읽었어요"); return }
                    if (Screen.cardInTeam(after, c, r) != shouldBe) {
                        // 눌렀는데 안 바뀌었다 = 이 화면에서 탭이 팀을 바꾸는 동작이 아니거나
                        // 자리 계산이 틀렸다. 계속 두드리지 않고 여기서 끝낸다.
                        Bot.log("'" + label + "' 칸을 눌렀는데 편성이 안 바뀌었어요 (행" + row + " 열" + c + ")")
                        Bot.log("  → 여기서 멈춥니다. 저장하지 않아요")
                        leave(true)
                        Runner.set("덱을 못 바꿨어요", "칸을 눌러도 편성이 안 바뀌어요")
                        Runner.lastResult = "덱 " + n + "번: 탭이 편성을 안 바꿔요"
                        return
                    }
                    if (shouldBe) { added++; Bot.log("  + " + label) } else { removed++; Bot.log("  − 뺐어요 (행" + row + " 열" + c + ")") }
                    Runner.set("덱 " + n + "번 맞추는 중", "넣음 " + added + " · 뺌 " + removed)
                }
            }
            topRow += Screen.CARD_ROWS_VISIBLE
            if (topRow < totalRows) {
                TapService.swipe(Screen.GRID_SWIPE_X, Screen.GRID_BOTTOM, Screen.GRID_SWIPE_X, Screen.GRID_TOP, 400)
                Runner.sleep(900)
            }
        }

        leave(dry)
        if (dry) {
            Runner.set("덱 " + n + "번 시험 끝", "넣음 " + added + " · 뺌 " + removed + " (저장 안 함)")
            Runner.lastResult = "덱 " + n + "번 시험: 넣음 " + added + " · 뺌 " + removed
        } else {
            Runner.set("덱 " + n + "번 저장했어요", "넣음 " + added + " · 뺌 " + removed)
            Runner.lastResult = "덱 " + n + "번: 넣음 " + added + " · 뺌 " + removed
        }
        if (missing.isNotEmpty()) Bot.log("못 찾은 이름 " + missing.size + "개는 건너뛰었어요")
    }

    /** 편집 모드에서 나간다. `cancel` 이면 저장하지 않는다. */
    private fun leave(cancel: Boolean) {
        Runner.tap(if (cancel) Screen.DECK_CANCEL else Screen.DECK_SAVE, 2000)
    }

    private fun fail(msg: String) {
        Bot.log(msg + " - 멈춥니다")
        Runner.set(msg, "화면이 예상과 달라요")
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
        // '…맛 쿠' 처럼 **끝 글자가 통째로 빠진** 것을 되살린다.
        // 73마리 실측에서 `귀피맛 쿠`·`보더맛 쿠`·`명랑한 쿠` 셋이 이랬다.
        if (t.endsWith(" 쿠") || (t.endsWith("쿠") && t.length > 2)) t += "키"
        return t
    }

    // ══════════════════════════════════════════════════════════
    //  이름으로 찾기 — **OCR 을 완벽하게 만들려 하지 않는다**
    // ══════════════════════════════════════════════════════════

    /**
     * 사전에서 `want` 와 가장 가까운 쿠키의 **순번(0부터)**. 못 찾으면 -1.
     *
     * ★ 요점: **OCR 오독을 고치는 대신, 찾을 때 너그럽게 본다.**
     * 73마리 실측에서 `ㅋ` 이 `ㄱ` 으로 새는 오독이 규칙적으로 나왔다 —
     * `밀키웨이`→`밀귀웨이` · `팬케이크`→`팬궤이크` · `치즈케이크`→`치즈궤이크` · `커피`→`귀피`.
     * 이걸 공식 이름표로 되돌리려면 크럼블 전체 이름 목록이 있어야 하는데 그건 없다.
     * 그런데 **편집거리로 보면 전부 1~2 차이**라, 사용자가 제대로 친 이름과 그냥 붙는다.
     *
     * 임계값은 PC 봇(`deck_dict.ps1` 의 `Match-Canon`)이 쓰던 **이름 길이의 34%** 를 그대로 쓴다.
     */
    fun findIndex(want: String): Int {
        val names = Prefs.deckDict.split("\n").filter { it.isNotBlank() }
        if (names.isEmpty()) return -1
        val w = norm(want)
        if (w.isEmpty()) return -1
        names.forEachIndexed { i, n -> if (norm(n) == w) return i }   // 똑같으면 바로
        var best = -1
        var bd = Int.MAX_VALUE
        names.forEachIndexed { i, n ->
            val d = lev(w, norm(n))
            if (d < bd) { bd = d; best = i }
        }
        val limit = Math.max(1, (w.length * 0.34).toInt())
        return if (bd <= limit) best else -1
    }

    /** 띄어쓰기와 한글 아닌 글자를 걷어낸다. 비교는 이 모양끼리 한다. */
    private fun norm(s: String) = s.replace(Regex("[^가-힣]"), "")

    /** 편집거리. 두 줄만 들고 도는 표준 방식이라 73개쯤은 순식간이다. */
    private fun lev(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur.copyOf()
        }
        return prev[b.length]
    }
}
