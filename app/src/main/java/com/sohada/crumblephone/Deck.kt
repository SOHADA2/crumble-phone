package com.sohada.crumblephone

import android.content.Context
import kotlin.concurrent.thread

/**
 * 쿠키 **조합(덱) 구성** — 1~5번 덱을 이름으로 채운다.
 *
 * ## 두 번 틀리고 정착한 방식
 * 1. 편성 목록에는 **이름이 안 적혀 있다**(`Lv.61`·`편성중`·`3/7` 뿐) → 이름을 알려면 상세를 열어야 한다.
 * 2. 그래서 '목록 몇 번째가 누구'인지를 기억했는데 **그것도 틀렸다.**
 *    목록은 **편성중인 쿠키가 맨 앞으로 올라오는** 정렬이라 프리셋을 바꾸면 순서가 통째로 달라진다.
 *    실측: 프리셋5 에서 1·2·5번이던 쿠키가 프리셋2 에서는 3·4·1번.
 *    쿠키가 늘거나 레벨이 올라도 바뀐다.
 *
 * **그래서 순번을 안 쓴다.** 사전에 **이름과 초상화 지문**을 같이 담아 두고,
 * 배치할 때는 **화면에 보이는 카드의 그림을 대 봐서** 누른다. 순서가 어떻게 바뀌든 상관없다.
 */
object Deck {

    private const val MAX_COOKIES = 400
    private const val STEP_MS = 900L
    /** 한 쪽에서 연달아 몇 번까지 눌러 볼지. 무한히 두드리지 않게. */
    private const val PAGE_TAPS = 10
    /** 전체를 몇 바퀴까지 다시 훑을지(누를 때마다 순서가 바뀔 수 있으므로). */
    private const val ROUNDS = 3
    /** 화면 세로 어긋남 보정 — 모드에 따라 30px쯤 밀린다(실측 34). */
    private val SHIFTS = intArrayOf(-12, 0, 12)

    // ══════════════════════════════════════════════════════════
    //  ① 사전 만들기 — 이름 한 바퀴 + 지문 한 바퀴
    // ══════════════════════════════════════════════════════════

    fun buildDict(ctx: Context) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "쿠키 사전"
        thread(name = "deckdict") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                build(ctx)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun build(ctx: Context) {
        Runner.set("쿠키 사전 만드는 중", "편성 화면으로")
        Runner.tap(Screen.NAV_COOKIE, 2500)
        if (!atRoster()) { fail("편성 화면을 못 열었어요"); return }

        // ── 0) 몇 마리인지 먼저 읽는다 ──
        // 왼쪽 아래 `73/73`. 이게 있으면 '몇 마리에서 끝나야 하는지'를 알아서,
        // ▶ 가 한 번 안 먹어 중간에 끊기는 사고를 잡을 수 있다.
        var target = 0
        Runner.shot()?.let { sh ->
            val c = Screen.OWNED_CROP
            val txt = Ocr.readText(sh, c[0], c[1], c[2], c[3]) ?: ""
            val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(txt.replace(",", ""))
            if (m != null) target = m.groupValues[1].toIntOrNull() ?: 0
            Bot.log("소유 쿠키 수: " + (if (target > 0) target.toString() + "마리" else "못 읽음 (" + txt.trim() + ")"))
        }

        // ── 1) 이름 ──
        // 상세 화면에는 좌우 넘김(▶)이 있어서 목록으로 돌아올 필요가 없다.
        scrollTop()
        Runner.tap(Screen.cardAt(0, 0, false), 1800)
        var b = Runner.shot()
        if (b == null || !Screen.atCookieDetail(b)) { fail("첫 칸의 상세를 못 열었어요"); return }

        // ★ 여기서 **한 칸도 건너뛰면 안 된다.**
        //   이름과 그림은 '몇 번째'로만 짝지어지므로, 하나를 안 담고 넘어가면 그 뒤가 통째로 밀려
        //   엉뚱한 그림과 붙는다. 실기에서 71/73 이 됐을 때가 이 사고였다.
        //   그래서 **못 읽어도 자리는 채우고**(`?N`), 넘어갔는지는 **이름이 아니라 그림으로** 본다.
        val names = ArrayList<String>()
        var first: FloatArray? = null
        for (i in 1..MAX_COOKIES) {
            if (!Runner.running) return
            val shot = Runner.shot() ?: break
            if (!Screen.atCookieDetail(shot)) { Bot.log("상세를 벗어났어요 (" + i + "번째)"); break }

            val cur = Screen.detailSig(shot)
            // 첫 쿠키 그림으로 돌아왔으면 한 바퀴 돈 것이다. 이름보다 이게 확실하다.
            if (first != null && names.size > 1 && Screen.thumbScore(cur, first) > 0.97f) {
                Bot.log("첫 쿠키로 돌아왔어요 - 한 바퀴 돌았습니다"); break
            }
            if (first == null) first = cur

            val nm = readName(shot) ?: ("?" + (names.size + 1))
            names.add(nm)
            Bot.log("  " + names.size + ": " + nm)
            Runner.set("쿠키 사전 만드는 중", "이름 " + names.size + (if (target > 0) "/" + target else "") + "마리")
            Runner.setProgress(names.size, if (target > 0) target else 80)
            if (target > 0 && names.size >= target) { Bot.log("소유 수 " + target + "마리를 다 읽었습니다"); break }

            // ── ▶ 로 넘긴다. **정말 넘어갔는지 그림으로 확인**한다 ──
            // 탭이 씹히는 일이 실제로 있었다(56/73 사고). 안 넘어갔으면 다시 누른다.
            var moved = false
            for (k in 1..3) {
                Runner.tap(Screen.DETAIL_NEXT, if (k == 1) STEP_MS else STEP_MS + 700)
                val nx = Runner.shot() ?: break
                if (!Screen.atCookieDetail(nx)) { moved = true; break }
                if (Screen.thumbScore(Screen.detailSig(nx), cur) < 0.97f) { moved = true; break }
                Bot.log("  ▶ 가 안 먹었어요 - 다시 누릅니다 (" + k + ")")
            }
            if (!moved) { Bot.log("▶ 를 세 번 눌러도 안 넘어가요 - 여기서 끝냅니다"); break }
        }
        TapService.back(); Runner.sleep(1500)
        if (names.isEmpty()) {
            Runner.set("한 마리도 못 읽었어요", "글자 읽기 점검부터 해 보세요"); return
        }
        if (target > 0 && names.size != target) {
            // 수가 안 맞으면 이름과 그림의 짝이 밀렸을 수 있다. 만들긴 하되 반드시 알린다.
            Bot.log("⚠ 소유 " + target + "마리인데 " + names.size + "마리를 읽었어요")
            Bot.log("  이름과 그림의 짝이 밀렸을 수 있어요 - 사전을 다시 만들어 주세요")
        }

        // ── 2) 지문 ──
        // **편집 모드에서** 뜬다. 나중에 배치도 편집 모드에서 하므로 화면이 같아야 잘 맞는다.
        // 아무것도 안 바꾸고 주황 ✕ 로 나오니 덱은 그대로다.
        if (!atRoster()) { fail("편성 화면이 아니에요"); return }
        Runner.tap(Screen.ROSTER_EQUIP, 2200)
        if (!atEdit()) { fail("편집 모드로 못 들어갔어요"); return }
        scrollTop()

        val thumbs = ArrayList<FloatArray>()
        var page = 0
        while (thumbs.size < names.size && Runner.running && page < 30) {
            val shot = Runner.shot()
            if (shot == null || !Screen.atDeckEdit(shot)) { fail("편집 모드를 벗어났어요"); return }
            for (r in 0 until Screen.CARD_ROWS_VISIBLE) {
                for (c in 0 until Screen.CARD_COLS) {
                    if (thumbs.size >= names.size) break
                    thumbs.add(Screen.cardThumb(shot, c, r, true))
                }
            }
            Runner.set("쿠키 사전 만드는 중", "그림 " + thumbs.size + "/" + names.size)
            Runner.setProgress(thumbs.size, names.size)
            if (thumbs.size < names.size) { pageDown(); page++ }
        }
        Runner.tap(Screen.DECK_CANCEL, 2000)

        if (thumbs.size < names.size) {
            Bot.log("그림을 " + thumbs.size + "장밖에 못 모았어요 (이름 " + names.size + ")")
            Runner.set("사전을 못 끝냈어요", "그림 " + thumbs.size + " / 이름 " + names.size); return
        }
        Prefs.deckDict = names.joinToString("\n")
        DeckDict.saveThumbs(ctx, thumbs)
        Runner.set("쿠키 사전 완성", names.size.toString() + "마리 (이름+그림)")
        Runner.lastResult = "쿠키 사전 " + names.size + "마리"
        Bot.log("── 쿠키 사전 " + names.size + "마리 (이름+그림) 저장 ──")
    }

    // ══════════════════════════════════════════════════════════
    //  ② 배치 — 그림을 보고 누른다
    // ══════════════════════════════════════════════════════════

    fun applyPreset(ctx: Context, n: Int, dry: Boolean) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "덱 " + n + "번"
        thread(name = "deckapply") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                apply(ctx, n, dry)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun apply(ctx: Context, n: Int, dry: Boolean) {
        if (!DeckDict.ready(ctx)) {
            Runner.set("사전이 아직이에요", "[쿠키 사전 만들기] 를 먼저 해 주세요"); return
        }
        val wanted = Prefs.deckNames(n).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) { Runner.set("덱 " + n + "번이 비어 있어요", "쿠키 이름을 적어 주세요"); return }

        val want = HashSet<Int>()
        val missing = ArrayList<String>()
        for (nm in wanted) {
            val i = findIndex(nm)
            if (i < 0) missing.add(nm) else want.add(i)
        }
        if (missing.isNotEmpty()) Bot.log("사전에서 못 찾은 이름: " + missing.joinToString(", "))
        if (want.isEmpty()) { Runner.set("이름을 하나도 못 찾았어요", "사전을 다시 만들어 보세요"); return }

        Runner.set("덱 " + n + "번 맞추는 중", "편성 화면으로")
        Runner.tap(Screen.NAV_COOKIE, 2500)
        if (!atRoster()) { fail("편성 화면을 못 열었어요"); return }
        Runner.tap(Screen.PRESET_TABS[n - 1], 1500)
        Runner.tap(Screen.ROSTER_EQUIP, 2200)
        if (!atEdit()) { fail("편집 모드로 못 들어갔어요"); return }

        var added = 0; var removed = 0; var unknown = 0
        for (round in 1..ROUNDS) {
            if (!Runner.running) break
            scrollTop()
            var changes = 0
            var page = 0
            while (Runner.running && page < 30) {
                var taps = 0
                var again = true
                var lastSeen = 0
                while (again && taps < PAGE_TAPS && Runner.running) {
                    again = false
                    val shot = Runner.shot()
                    if (shot == null || !Screen.atDeckEdit(shot)) { fail("편집 모드를 벗어났어요"); return }
                    lastSeen = 0
                    outer@ for (r in 0 until Screen.CARD_ROWS_VISIBLE) {
                        for (c in 0 until Screen.CARD_COLS) {
                            // 이 칸이 **누구인지** 그림으로 알아낸다. 순번은 안 믿는다.
                            val shots = SHIFTS.map { Screen.cardThumb(shot, c, r, true, it) }
                            val who = DeckDict.identify(ctx, shots)
                            if (who < 0) { unknown++; continue }
                            lastSeen++
                            val inTeam = Screen.cardInTeam(shot, c, r)
                            val shouldBe = want.contains(who)
                            if (inTeam == shouldBe) continue
                            val label = DeckDict.names.getOrElse(who) { "?" }
                            Runner.tap(Screen.cardAt(c, r, true), 800)
                            val after = Runner.shot() ?: return
                            // **누른 자리가 정말 바뀌었나**를 본다. 안 바뀌면 더 두드리지 않는다.
                            if (Screen.cardInTeam(after, c, r) == inTeam) {
                                Bot.log("'" + label + "' 을 눌렀는데 편성이 안 바뀌었어요")
                                Bot.log("  → 저장하지 않고 멈춥니다")
                                Runner.tap(Screen.DECK_CANCEL, 2000)
                                Runner.set("덱을 못 바꿨어요", "칸을 눌러도 편성이 안 바뀌어요")
                                Runner.lastResult = "덱 " + n + "번: 탭이 편성을 안 바꿔요"
                                return
                            }
                            if (shouldBe) { added++; Bot.log("  + " + label) } else { removed++; Bot.log("  − " + label) }
                            changes++; taps++
                            Runner.set("덱 " + n + "번 맞추는 중", "넣음 " + added + " · 뺌 " + removed)
                            // 한 번 누르면 **순서가 다시 바뀔 수 있으니** 이 쪽을 처음부터 다시 본다.
                            again = true
                            break@outer
                        }
                    }
                }
                if (lastSeen == 0) break      // 아는 카드가 하나도 없다 = 목록 끝
                pageDown(); page++
            }
            Bot.log("── " + round + "바퀴: 바꾼 것 " + changes + "개")
            if (changes == 0) break           // 더 바꿀 게 없다
            if (round < ROUNDS) Bot.log("  순서가 바뀌었을 수 있어 한 바퀴 더 봅니다")
        }

        Runner.tap(if (dry) Screen.DECK_CANCEL else Screen.DECK_SAVE, 2200)
        val tail = if (dry) " (저장 안 함)" else ""
        Runner.set("덱 " + n + "번 " + (if (dry) "시험 끝" else "저장했어요"),
            "넣음 " + added + " · 뺌 " + removed + tail)
        Runner.lastResult = "덱 " + n + "번: 넣음 " + added + " · 뺌 " + removed + tail
        if (unknown > 0) Bot.log("그림을 못 알아본 칸 " + unknown + "번 (건드리지 않았어요)")
        if (missing.isNotEmpty()) Bot.log("못 찾은 이름 " + missing.size + "개는 건너뛰었어요")
    }

    // ══════════════════════════════════════════════════════════
    //  화면 다루기
    // ══════════════════════════════════════════════════════════

    private fun atRoster(): Boolean {
        val b = Runner.shot() ?: return false
        return Screen.atCookieRoster(b)
    }

    private fun atEdit(): Boolean {
        val b = Runner.shot() ?: return false
        return Screen.atDeckEdit(b)
    }

    /** 목록 맨 위로. 이미 위면 아무 일도 안 일어난다. */
    private fun scrollTop() {
        for (k in 1..6) {
            if (!Runner.running) return
            TapService.swipe(Screen.GRID_SWIPE_X, Screen.GRID_TOP, Screen.GRID_SWIPE_X, Screen.GRID_BOTTOM, 320)
            Runner.sleep(400)
        }
        Runner.sleep(700)
    }

    private fun pageDown() {
        TapService.swipe(Screen.GRID_SWIPE_X, Screen.GRID_BOTTOM, Screen.GRID_SWIPE_X, Screen.GRID_TOP, 400)
        Runner.sleep(900)
    }

    private fun fail(msg: String) {
        Bot.log(msg + " - 멈춥니다")
        Runner.set(msg, "화면이 예상과 달라요")
    }

    // ══════════════════════════════════════════════════════════
    //  이름 읽기·찾기
    // ══════════════════════════════════════════════════════════

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
        if (t.endsWith(" 쿠") || (t.endsWith("쿠") && t.length > 2)) t += "키"
        return t
    }

    /**
     * 사전에서 `want` 와 가장 가까운 쿠키의 순번. 못 찾으면 -1.
     *
     * ★ **OCR 오독을 고치는 대신 찾을 때 너그럽게 본다.**
     * 73마리 실측에서 `ㅋ` 이 `ㄱ` 으로 새는 오독이 규칙적이었다 —
     * `밀키웨이`→`밀귀웨이` · `팬케이크`→`팬궤이크` · `커피`→`귀피`. 전부 편집거리 1이라 그냥 붙는다.
     * 임계값은 PC 봇 `Match-Canon` 과 같은 **이름 길이의 34%**.
     */
    fun findIndex(want: String): Int {
        val names = DeckDict.names
        if (names.isEmpty()) return -1
        val w = norm(want)
        if (w.isEmpty()) return -1
        names.forEachIndexed { i, n -> if (norm(n) == w) return i }
        var best = -1; var bd = Int.MAX_VALUE
        names.forEachIndexed { i, n ->
            val d = lev(w, norm(n))
            if (d < bd) { bd = d; best = i }
        }
        return if (bd <= Math.max(1, (w.length * 0.34).toInt())) best else -1
    }

    private fun norm(s: String) = s.replace(Regex("[^가-힣]"), "")

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
