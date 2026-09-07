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

    /** 이름을 나누는 기준 — 줄바꿈 · 쉼표 · 가운뎃점 · 슬래시 · 세미콜론 */
    val SEP = Regex("[\n,、·/;]+")

    private const val MAX_COOKIES = 400
    private const val STEP_MS = 900L
    /** 한 쪽에서 연달아 몇 번까지 눌러 볼지. 무한히 두드리지 않게. */
    private const val PAGE_TAPS = 10
    /** 전체를 몇 바퀴까지 다시 훑을지(누를 때마다 순서가 바뀔 수 있으므로). */
    private const val ROUNDS = 3

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
        // 첫 칸 자리도 **찾아서** 누른다. 맨 위로 올린 뒤라도 몇 px 어긋나 있을 수 있다.
        val top = Runner.shot()?.let { Screen.findStarRows(it) } ?: IntArray(0)
        if (top.isEmpty()) { fail("카드 줄을 못 찾았어요"); return }
        Runner.tap(Screen.cardAt(0, top[0]), 1800)
        val b = Runner.shot()
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

        // ★ 스크롤이 **정확히 몇 행씩 안 움직인다**(스와이프 1480px ≈ 4.67행).
        //   그래서 '한 쪽에 25장' 으로 세면 두 번째 쪽부터 어긋난다 — 처음에 이걸로 틀렸다.
        //   지금은 ① 보이는 별줄을 **찾아서** 행을 잡고 ② **겹치는 행을 알아봐서** 이어 붙인다.
        //   조금씩(2행) 밀어 겹침을 넉넉히 남긴다.
        val rowsOut = ArrayList<Array<FloatArray>>()   // 한 칸이 5장인 '행'들
        var pages = 0
        while (rowsOut.size * Screen.CARD_COLS < names.size && Runner.running && pages < 40) {
            val shot = Runner.shot()
            if (shot == null || !Screen.atDeckEdit(shot)) { fail("편집 모드를 벗어났어요"); return }
            val stars = Screen.findStarRows(shot)
            if (stars.isEmpty()) { fail("카드 줄을 못 찾았어요"); return }

            var appended = 0
            for (sy in stars) {
                val row = Array(Screen.CARD_COLS) { Screen.cardThumb(shot, it, sy) }
                // 이미 담은 행인가? 뒤쪽 8행만 견줘 보면 충분하다.
                var dup = false
                var k = rowsOut.size - 1
                var checked = 0
                while (k >= 0 && checked < 8) {
                    if (sameRow(rowsOut[k], row)) { dup = true; break }
                    k--; checked++
                }
                if (!dup) { rowsOut.add(row); appended++ }
            }
            Runner.set("쿠키 사전 만드는 중",
                "그림 " + (rowsOut.size * Screen.CARD_COLS).coerceAtMost(names.size) + "/" + names.size)
            Runner.setProgress(rowsOut.size * Screen.CARD_COLS, names.size)
            // ⚠️ **겹치는 행이 하나는 있어야** 이어 붙인 게 맞다고 할 수 있다.
            //   한 쪽이 통째로 새것이면 사이가 빈 것일 수 있다 — 그러면 이름과 그림의 짝이
            //   조용히 밀린다. 그 상태로 만드느니 멈추고 알린다.
            if (pages > 0 && appended >= stars.size && stars.size >= Screen.CARD_ROWS_VISIBLE) {
                Bot.log("겹치는 줄이 없어요 (" + appended + "줄 전부 새것) - 사이가 빌 수 있어 멈춥니다")
                Runner.tap(Screen.DECK_CANCEL, 2000)
                Runner.set("사전을 못 끝냈어요", "스크롤이 너무 많이 내려갔어요 · 다시 해 주세요")
                return
            }
            if (appended == 0 && pages > 0) { Bot.log("더 내려갈 데가 없어요"); break }
            Bot.log("  한 쪽: 새 줄 " + appended + "개 (누적 " + rowsOut.size * Screen.CARD_COLS + ")")
            pageDown(); pages++
        }

        val thumbs = ArrayList<FloatArray>()
        for (row in rowsOut) for (t in row) { if (thumbs.size < names.size) thumbs.add(t) }
        Runner.tap(Screen.DECK_CANCEL, 2000)

        if (thumbs.size < names.size) {
            Bot.log("그림을 " + thumbs.size + "장밖에 못 모았어요 (이름 " + names.size + ")")
            Runner.set("사전을 못 끝냈어요", "그림 " + thumbs.size + " / 이름 " + names.size); return
        }
        Prefs.deckDict = names.joinToString("\n")
        DeckDict.saveThumbs(ctx, thumbs)
        // 사전이 바뀌면 지난 점검 결과는 낡은 것이다. 지워서 '아직 점검 안 함'으로 되돌린다.
        Prefs.deckUnseen = ""; Prefs.deckChecked = false
        Runner.set("쿠키 사전 완성", names.size.toString() + "마리 (이름+그림)")
        Runner.lastResult = "쿠키 사전 " + names.size + "마리"
        Bot.log("── 쿠키 사전 " + names.size + "마리 (이름+그림) 저장 ──")
    }

    // ══════════════════════════════════════════════════════════
    //  ①-2 사전 점검 — **어떤 쿠키가 지금 안 되는지** 알려 준다
    // ══════════════════════════════════════════════════════════

    /**
     * 편성 목록을 한 바퀴 훑으며 **사전의 어느 쿠키를 화면에서 알아보는지** 센다.
     *
     * 배치는 그림으로 찾으므로, 못 알아보는 쿠키는 **덱에 적어도 안 들어간다.**
     * 그걸 [시험] 을 돌려 로그로만 알 수 있으면 불편하다 — 여기서 미리 확인하고
     * 사전 화면에 표시를 달아 **눈으로 보이게** 한다.
     *
     * 카드를 **하나도 누르지 않는다.** 편집 모드에 들어갔다 주황 ✕ 로 나오기만 한다.
     */
    fun checkDict(ctx: Context) {
        if (!Runner.guard()) return
        Runner.running = true; Runner.task = "사전 점검"
        thread(name = "deckcheck") {
            try {
                if (!Runner.bringGameToFront(ctx)) { Runner.set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                val (ok, why) = Runner.resetToMain()
                if (!ok) { Runner.failByReason(why); return@thread }
                check(ctx)
            }
            catch (e: Exception) { Runner.set("오류", e.message ?: "알 수 없음") }
            finally { Runner.running = false; Runner.task = "" }
        }
    }

    private fun check(ctx: Context) {
        if (!DeckDict.ready(ctx)) { Runner.set("사전이 아직이에요", "[쿠키 사전 만들기] 를 먼저 해 주세요"); return }
        Runner.set("사전 점검 중", "편성 화면으로")
        Runner.tap(Screen.NAV_COOKIE, 2500)
        if (!atRoster()) { fail("편성 화면을 못 열었어요"); return }
        Runner.tap(Screen.ROSTER_EQUIP, 2200)
        if (!atEdit()) { fail("편집 모드로 못 들어갔어요"); return }
        scrollTop()

        val seen = HashSet<Int>()
        var cards = 0
        var pages = 0
        var quiet = 0
        while (Runner.running && pages < 40) {
            val shot = settle()
            if (shot == null || !Screen.atDeckEdit(shot)) { fail("편집 모드를 벗어났어요"); return }
            val stars = Screen.findStarRows(shot)
            if (stars.isEmpty()) { fail("카드 줄을 못 찾았어요"); return }
            var newHere = 0
            for (sy in stars) for (c in 0 until Screen.CARD_COLS) {
                cards++
                val who = DeckDict.identify(ctx, listOf(Screen.cardThumb(shot, c, sy)))
                if (who >= 0 && seen.add(who)) newHere++
            }
            Runner.set("사전 점검 중", "알아본 쿠키 " + seen.size + "/" + DeckDict.names.size)
            Runner.setProgress(seen.size, DeckDict.names.size)
            if (newHere == 0) { quiet++; if (quiet >= 2) break } else quiet = 0
            pageDown(); pages++
        }
        Runner.tap(Screen.DECK_CANCEL, 2000)

        val miss = DeckDict.names.indices.filter { !seen.contains(it) }
        Prefs.deckUnseen = miss.joinToString(",")
        Prefs.deckChecked = true
        Bot.log("── 사전 점검: " + seen.size + "/" + DeckDict.names.size + "마리 확인 ──")
        if (miss.isEmpty()) Bot.log("전부 알아봐요 - 어떤 쿠키든 덱에 넣을 수 있어요")
        else Bot.log("못 알아본 " + miss.size + "마리: " +
            miss.joinToString(", ") { DeckDict.names.getOrElse(it) { "?" } })
        Runner.set("사전 점검 끝",
            if (miss.isEmpty()) "전부 알아봐요 (" + seen.size + "마리)"
            else seen.size.toString() + "마리 알아봄 · " + miss.size + "마리 못 알아봄")
        Runner.lastResult = "사전 점검: " + seen.size + "/" + DeckDict.names.size + "마리"
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
        // 줄바꿈뿐 아니라 **쉼표·가운뎃점·슬래시**로도 나눈다.
        // 실기에서 사용자가 `다크초코쿠키, 허브쿠키` 처럼 한 줄에 쉼표로 적었는데
        // 줄바꿈으로만 나누는 바람에 그 전체를 이름 하나로 보고 못 찾았다.
        val wanted = Prefs.deckNames(n).split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) { Runner.set("덱 " + n + "번이 비어 있어요", "쿠키 이름을 적어 주세요"); return }

        val want = HashSet<Int>()
        val missing = ArrayList<String>()
        for (nm in wanted) {
            val (i, why) = find(nm)
            if (i < 0) { missing.add(nm); Bot.log("'" + nm + "' 을 못 찾았어요 - " + why) }
            else { want.add(i); Bot.log("'" + nm + "' → " + DeckDict.names[i] + " (" + why + ")") }
        }
        if (want.isEmpty()) { Runner.set("이름을 하나도 못 찾았어요", "사전을 다시 만들어 보세요"); return }

        // 점검에서 이미 '못 알아본다'고 나온 쿠키는 **시작 전에** 알려 준다.
        // 한참 돌린 뒤에야 아는 것보다 낫다.
        val known = Prefs.deckUnseen.split(",").mapNotNull { it.trim().toIntOrNull() }.toHashSet()
        val risky = want.filter { known.contains(it) }
        if (risky.isNotEmpty())
            Bot.log("⚠ 지난 점검에서 못 알아본 쿠키가 섞여 있어요: " +
                risky.joinToString(", ") { DeckDict.names.getOrElse(it) { "?" } })

        Runner.set("덱 " + n + "번 맞추는 중", "편성 화면으로")
        Runner.tap(Screen.NAV_COOKIE, 2500)
        if (!atRoster()) { fail("편성 화면을 못 열었어요"); return }
        Runner.tap(Screen.PRESET_TABS[n - 1], 1500)
        Runner.tap(Screen.ROSTER_EQUIP, 2200)
        if (!atEdit()) { fail("편집 모드로 못 들어갔어요"); return }

        var added = 0; var removed = 0; var unknown = 0
        var logged = false
        val seenIdx = HashSet<Int>()

        for (round in 1..ROUNDS) {
            if (!Runner.running) break
            scrollTop()
            var changes = 0
            var pages = 0
            var dry2 = 0                       // 새로 본 카드가 없는 쪽이 연속 몇 번인지
            while (Runner.running && pages < 40) {
                var taps = 0
                var again = true
                var newHere = 0
                // 이 쪽에서 **방금 바꿔 확인까지 끝낸** 쿠키. 다시 건드리지 않는다.
                // 목록이 다시 정렬되는 동안 '아직 안 들어갔다'로 잘못 읽고 되돌려 버린 적이 있다.
                val justDone = HashSet<Int>()
                while (again && taps < PAGE_TAPS && Runner.running) {
                    again = false
                    // 방금 무언가 눌렀다면 목록이 다시 정렬되며 움직인다. 멈출 때까지 기다린다.
                    val shot = settle()
                    if (shot == null || !Screen.atDeckEdit(shot)) { fail("편집 모드를 벗어났어요"); return }
                    // ★ 별줄을 **찾아서** 행을 잡는다. 스크롤이 정확히 안 멈추므로 고정값을 쓰면 안 된다.
                    val stars = Screen.findStarRows(shot)
                    if (stars.isEmpty()) { fail("카드 줄을 못 찾았어요"); return }
                    // 첫 쪽에서 한 번만, 왜 알아보고 못 알아보는지를 로그에 남긴다.
                    if (round == 1 && pages == 0 && taps == 0 && !logged) {
                        logged = true
                        Bot.log("별줄 " + stars.size + "개: " + stars.joinToString(","))
                        for (c in 0 until Screen.CARD_COLS)
                            Bot.log("  1행 " + (c + 1) + "칸: " + DeckDict.describe(ctx, Screen.cardThumb(shot, c, stars[0])))
                    }
                    outer@ for (sy in stars) {
                        for (c in 0 until Screen.CARD_COLS) {
                            // 이 칸이 **누구인지** 그림으로 알아낸다. 자리는 안 믿는다.
                            val who = DeckDict.identify(ctx, listOf(Screen.cardThumb(shot, c, sy)))
                            if (who < 0) { unknown++; continue }
                            if (seenIdx.add(who)) newHere++
                            if (justDone.contains(who)) continue
                            val inTeam = Screen.cardInTeam(shot, c, sy)
                            val shouldBe = want.contains(who)
                            if (inTeam == shouldBe) continue
                            val label = DeckDict.names.getOrElse(who) { "?" }
                            Runner.tap(Screen.cardAt(c, sy), 500)
                            val after = settle() ?: return
                            // **누른 자리가 정말 바뀌었나.** 안 바뀌면 더 두드리지 않는다.
                            if (Screen.cardInTeam(after, c, sy) == inTeam) {
                                Bot.log("'" + label + "' 을 눌렀는데 편성이 안 바뀌었어요")
                                Bot.log("  → 저장하지 않고 멈춥니다")
                                Runner.tap(Screen.DECK_CANCEL, 2000)
                                Runner.set("덱을 못 바꿨어요", "칸을 눌러도 편성이 안 바뀌어요")
                                Runner.lastResult = "덱 " + n + "번: 탭이 편성을 안 바꿔요"
                                return
                            }
                            if (shouldBe) { added++; Bot.log("  + " + label) } else { removed++; Bot.log("  − " + label) }
                            justDone.add(who)
                            changes++; taps++
                            Runner.set("덱 " + n + "번 맞추는 중", "넣음 " + added + " · 뺌 " + removed)
                            // 한 번 누르면 **순서가 다시 바뀔 수 있으니** 이 쪽을 처음부터 다시 본다.
                            again = true
                            break@outer
                        }
                    }
                }
                // 새로 본 카드가 없는 쪽이 두 번 이어지면 끝까지 내려온 것이다.
                if (newHere == 0) { dry2++; if (dry2 >= 2) break } else dry2 = 0
                pageDown(); pages++
            }
            Bot.log("── " + round + "바퀴: 알아본 쿠키 " + seenIdx.size + "마리 · 바꾼 것 " + changes + "개")
            if (changes == 0) break
            if (round < ROUNDS) Bot.log("  순서가 바뀌었을 수 있어 한 바퀴 더 봅니다")
        }
        // ★ **원하는 쿠키를 화면에서 못 찾았으면 반드시 알린다.**
        //   조용히 안 넣고 끝내면 사용자는 덱이 맞춰진 줄 안다.
        val notSeen = want.filter { !seenIdx.contains(it) }
        if (notSeen.isNotEmpty()) {
            Bot.log("⚠ 넣으려던 쿠키를 화면에서 못 찾았어요: " +
                notSeen.joinToString(", ") { DeckDict.names.getOrElse(it) { "?" } })
            Bot.log("  사전의 그림과 지금 화면이 다를 수 있어요 - 사전을 다시 만들어 보세요")
        }
        if (seenIdx.size < DeckDict.names.size) {
            val miss = DeckDict.names.indices.filter { !seenIdx.contains(it) }
            Bot.log("못 알아본 쿠키 " + miss.size + "마리: " +
                miss.take(12).joinToString(", ") { DeckDict.names.getOrElse(it) { "?" } } +
                (if (miss.size > 12) " 외 " + (miss.size - 12) + "마리" else ""))
        }

        Runner.tap(if (dry) Screen.DECK_CANCEL else Screen.DECK_SAVE, 2200)
        val short = want.count { !seenIdx.contains(it) }
        val tail = (if (short > 0) " · 못 넣음 " + short else "") + (if (dry) " (저장 안 함)" else "")
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

    /**
     * 한 쪽 내린다. **두 행 남짓만** 민다 — 다섯 행이 보이므로 최소 두세 행이 겹쳐서,
     * 겹침으로 이어 붙일 수 있다. 크게 밀면 겹침이 사라져 사이가 빈다.
     * 느리게(700ms) 미는 것도 일부러다. 빨리 밀면 관성으로 더 내려간다.
     */
    private fun pageDown() {
        val d = Screen.STAR_PITCH * 2
        TapService.swipe(Screen.GRID_SWIPE_X, Screen.GRID_TOP + d, Screen.GRID_SWIPE_X, Screen.GRID_TOP, 700)
        Runner.sleep(900)
    }

    /** 두 행이 같은 카드들인가. 다섯 칸 지문의 평균이 충분히 닮았으면 같다고 본다. */
    private fun sameRow(a: Array<FloatArray>, b: Array<FloatArray>): Boolean {
        if (a.size != b.size) return false
        var sum = 0f
        for (i in a.indices) sum += Screen.thumbScore(a[i], b[i])
        return sum / a.size > 0.90f
    }

    /**
     * **화면이 잠잠해질 때까지** 기다렸다가 찍는다.
     *
     * 카드를 넣고 빼면 목록이 **다시 정렬되며 미끄러진다.** 그 도중에 찍으면 카드가 반쯤 움직인
     * 자리에 있어서, 그림은 맞게 읽혀도 **편성중 배지 자리는 빗나간다.**
     * 실기에서 `+ 다크초코 쿠키` 로 잘 넣어 놓고 바로 다음 바퀴에 '안 들어가 있다'고 읽어
     * 다시 눌러 빼 버린 게 이것이었다.
     */
    private fun settle(maxMs: Long = 3000): android.graphics.Bitmap? {
        var prev: FloatArray? = null
        var waited = 0L
        var shot = Runner.shot()
        while (waited < maxMs && Runner.running) {
            val cur = if (shot == null) return shot
                      else Screen.regionThumb(shot, 60, 1100, 1320, 1500)
            if (prev != null && Screen.thumbScore(prev, cur) > 0.995f) return shot
            prev = cur
            Runner.sleep(350); waited += 350
            shot = Runner.shot()
        }
        return shot
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
    fun findIndex(want: String): Int = find(want).first

    /** 찾은 순번과 **왜 그렇게 됐는지**. 못 찾았을 때 로그에 남기려고 같이 낸다. */
    fun find(want: String): Pair<Int, String> {
        val names = DeckDict.names
        if (names.isEmpty()) return -1 to "사전이 비어 있음"
        val w = norm(want)
        if (w.isEmpty()) return -1 to "글자를 못 알아봄('" + want + "')"
        names.forEachIndexed { i, n -> if (norm(n) == w) return i to "정확히" }
        var best = -1; var bd = Int.MAX_VALUE
        names.forEachIndexed { i, n ->
            val d = lev(w, norm(n))
            if (d < bd) { bd = d; best = i }
        }
        val limit = Math.max(1, (w.length * 0.34).toInt())
        val near = names.getOrElse(best) { "?" }
        return if (bd <= limit) best to ("비슷함(거리 " + bd + ")")
               else -1 to ("가장 가까운 게 '" + near + "' 인데 거리 " + bd + " > " + limit)
    }

    /**
     * 견줄 모양으로 다듬는다.
     *
     * ⚠️ **먼저 완성형(NFC)으로 합친다.** 안 그러면 자판에 따라 `다크초코쿠키` 가 자모로 분리된
     *    형태(`ᄃ`+`ᅡ`+…)로 들어오는데, 그건 `가-힣` 범위가 아니라서 **글자가 통째로 사라진다.**
     *    실기에서 사전에 있는 이름을 둘 다 '못 찾았다'고 한 게 이것이었다.
     *
     * ⚠️ 남기는 기준도 '한글만'이 아니라 **글자·숫자면 남긴다**로 넓혔다.
     *    영어·숫자가 섞인 이름(`TSSR` 같은)도 통째로 지워지지 않게.
     */
    private fun norm(s: String): String {
        val t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
        val sb = StringBuilder()
        for (c in t) if (Character.isLetterOrDigit(c)) sb.append(c)
        return sb.toString()
    }

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
