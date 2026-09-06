package com.sohada.crumblephone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import kotlin.concurrent.thread

/**
 * 자동 진행 엔진. PC 봇의 tobol.ps1 을 그대로 옮긴 것이 첫 콘텐츠다.
 * 화면 읽기(CaptureService)와 탭(TapService)만 쓰고, 좌표·판정은 Screen 에 모아 뒀다.
 */
object Runner {

    @Volatile var running = false          // 지금 무언가 돌고 있나
    @Volatile var task = ""                // 무슨 콘텐츠인지 (예: 토벌전)
    @Volatile var status = "쉬는 중"        // 큰 글씨로 보일 한 줄
    @Volatile var detail = ""              // 작은 글씨 상세
    @Volatile var lastResult = ""          // 끝난 뒤에도 남길 결과
    /**
     * 진행률 0~100. **-1 이면 막대를 숨긴다.**
     * 진짜 분모가 있을 때만 채운다 — 없는 걸 지어내면 막대가 거짓말을 한다.
     * `set()` 으로 새 상태를 쓰면 자동으로 -1 이 된다(다음 단계로 넘어갔으니 앞 막대는 의미가 없다).
     */
    @Volatile var progress = -1
    @Volatile var lastScore = 0L           // 이번 판 점수
    @Volatile var bestScore = 0L           // 이번 세션 최고 점수

    /** 화면 한 장.
     *  ⚠️ 다음에 shot() 을 부르면 앞 장은 버려진다(한 장이 18MB 라 들고 있을 수 없다).
     *     받은 그 자리에서 판정까지 끝낼 것. */
    private var last: Bitmap? = null
    fun shot(): Bitmap? {
        val cap = CaptureService.instance ?: return null
        repeat(8) {
            val b = cap.grab()
            if (b != null) {
                val old = last
                last = b
                if (old != null && old != b) old.recycle()
                return b
            }
            Thread.sleep(150)
        }
        // 화면이 멈춰 있으면 새 프레임이 안 온다. 그때는 내용이 그대로이므로 마지막 장을 그대로 쓴다.
        return last
    }

    internal fun sleep(ms: Long) = Thread.sleep(ms)

    internal fun set(s: String, d: String = "") {
        status = s; detail = d; progress = -1
        Bot.log("$s ${if (d.isEmpty()) "" else "· $d"}")
    }

    /** 진행률을 '한 일 / 전체'로 담는다. 전체가 0 이하면 막대를 숨긴다. */
    fun setProgress(done: Int, total: Int) {
        progress = if (total <= 0) -1 else (100L * done / total).toInt().coerceIn(0, 100)
    }

    fun tap(p: IntArray, waitMs: Long = 1800) { TapService.tap(p[0], p[1]); sleep(waitMs) }

    /** 절전이면 왕관을 씌워 깨운다(절전이 아니면 빈 바닥 드래그라 무해). */
    internal fun wake() {
        val w = Screen.WAKE
        TapService.swipe(w[0], w[1], w[2], w[3], 900)
        sleep(1800)
    }

    /**
     * 어느 화면에 있든 뒤로가기만으로 메인까지 올라간다. 블라인드 좌표 탭은 쓰지 않는다.
     * 반환: 성공여부 + 이유("" / "battle" / "stuck" / "capture")
     */
    fun resetToMain(maxBack: Int = 8): Pair<Boolean, String> {
        wake()
        var dlg = 0
        for (i in 0..maxBack) {
            if (!running) return false to "stop"
            val b = shot() ?: return false to "capture"
            if (Screen.atMain(b)) return true to ""
            if (Screen.isConfirmDialog(b)) {
                dlg++
                // 확인창은 왼쪽(계속하기/취소)만 누른다. 또 뜨면 전투가 계속 도는 것이라 포기한다.
                tap(Screen.DLG_SAFE)
                if (dlg >= 2) return false to "battle"
                continue
            }
            if (i == maxBack) break
            TapService.back()
            sleep(1800)
        }
        return false to "stuck"
    }

    /**
     * 시작해도 되나? 안 되면 이유를 상태에 적고 false 를 준다.
     * 콘텐츠마다 같은 검사를 베껴 두면 새 검사를 넣을 때 한 군데를 빠뜨린다 — 여기 하나로 모은다.
     */
    internal fun guard(): Boolean {
        if (running) { Bot.log("이미 무언가 돌고 있어요"); return false }
        if (!TapService.isReady) { set("시작 못 함", "접근성 서비스를 켜 주세요"); return false }
        if (CaptureService.instance == null) { set("시작 못 함", "화면 읽기를 허용해 주세요"); return false }
        // 화면 비율은 여기서 안 본다. 게임을 띄워 봐야 레터박스인지 재배치인지 알 수 있고,
        // 그 판단은 bringGameToFront 가 게임 화면을 실제로 보고 한다.
        return true
    }

    fun stop() {
        running = false
        set("멈추는 중")
    }

    // ══════════════════════════════════════════════════════════
    //  토벌전
    // ══════════════════════════════════════════════════════════
    /**
     * 게임을 앞으로 띄운다. 앱이 앞에 있으면 앱 자신을 읽게 되므로 반드시 먼저 해야 한다.
     * 패키지는 `GameApp` 이 찾아 준다 — 스토어마다 다르므로 하나로 박으면 안 된다.
     */
    internal fun bringGameToFront(ctx: Context): Boolean {
        val i = GameApp.launchIntent(ctx) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        set("게임 여는 중", "잠시만요")
        ctx.startActivity(i)
        sleep(6000)
        if (!waitGameReady()) return false

        // ── 이제서야 좌표를 확정한다 ──
        // 화면 크기만으로는 부족하다. 태블릿처럼 넓은 화면이면 게임이 검은 띠를 두르고
        // 원래 비율을 지킬 수도(그러면 그대로 돈다), 넓은 화면에 맞춰 UI 를 재배치할 수도 있다.
        // 그건 게임 화면을 실제로 봐야만 갈린다.
        Coords.redetect()
        shot()?.let { Coords.detect(it) }
        if (!Coords.ratioOk) {
            set("시작 못 함", Coords.mismatchReason())
            lastResult = "이 기기에서는 좌표가 맞지 않아요"
            return false
        }
        return true
    }

    /**
     * 게임이 화면을 다 그릴 때까지 기다린다.
     * 로딩 중에 판정하면 엉뚱한 결론이 난다(실제로 로딩 화면에서 뒤로가기를 눌러 '전투 중'으로 잘못 끝났다).
     * 우리가 아는 화면(메인·토벌 로비·결과창·확인창) 중 하나가 보이면 준비된 것으로 본다.
     */
    internal fun waitGameReady(maxSec: Int = 45): Boolean {
        val deadline = System.currentTimeMillis() + maxSec * 1000L
        while (System.currentTimeMillis() < deadline && running) {
            val b = shot()
            if (b != null && (Screen.atMain(b) || Screen.atTobolLobby(b) || Screen.isBattleOver(b) || Screen.isConfirmDialog(b))) return true
            set("게임 여는 중", "로딩을 기다리는 중")
            sleep(2000)
        }
        return false
    }

    fun startTobol(ctx: Context, maxAttempts: Int = if (Prefs.testMode) 0 else 300) {
        if (!guard()) return
        running = true; task = "토벌전"; lastScore = 0L; bestScore = 0L
        thread(name = "tobol") {
            try {
                if (!bringGameToFront(ctx)) { set("시작 못 함", "게임을 찾지 못했어요"); return@thread }
                tobolLoop(maxAttempts)
            }
            catch (e: Exception) { set("오류", e.message ?: "알 수 없음") }
            finally { running = false; task = "" }
        }
    }

    /**
     * 토벌전 로비까지 들어간다. 최대 3번 시도한다.
     *
     * 길은 두 개고 **길드 탭이 먼저**다.
     *   1) 하단 '길드' 탭 -> 길드 화면 -> '길드 토벌전' 타일   <- 자리가 고정이라 안전하다
     *   2) 메인 우측 아이콘 열의 피냐타 바로가기               <- 폴백. 이벤트에 따라 한 칸씩 밀린다
     *
     * 2)를 주 경로로 쓰다가 실제로 사고가 났다 - 전환 중에 찾아서 한 칸 위의 '크럼블 패스'
     * (구매 화면)를 눌렀다. 하단 네비는 화면이 바뀌어도 자리가 그대로라 1)이 훨씬 믿을 만하다.
     * 그래도 2)는 남겨 둔다 - 이미 실기에서 되는 걸 확인한 길이라 1)이 막히면 받아 준다.
     */
    private fun enterTobolLobby(): Boolean {
        for (t in 1..3) {
            if (!running) return false
            var b = shot() ?: return false
            if (Screen.atTobolLobby(b)) return true
            // 결과창이 떠 있으면 먼저 닫는다(로비로 돌아간다). 시도마다 확인한다.
            if (Screen.isBattleOver(b)) {
                set("지난 결과창 닫는 중")
                tap(Screen.TOBOL_CLOSE, 2500)
                if (shot()?.let { Screen.atTobolLobby(it) } == true) return true
                b = shot() ?: return false
            }
            if (!Screen.atMain(b)) {
                set("메인 화면으로 이동 중")
                val (ok, why) = resetToMain()
                if (!ok) { failByReason(why); return false }
            }
            sleep(1500)          // 연출이 끝나 화면이 자리를 잡을 때까지
            if (shot()?.let { Screen.atMain(it) } != true) {
                Bot.log("메인이 아직 아니에요 - 다시 (" + t + "/3)"); sleep(1500); continue
            }

            if (enterViaGuild()) { Bot.log("길드 경로로 들어왔어요"); return true }
            if (!running) return false
            if (enterViaShortcut()) { Bot.log("바로가기 경로로 들어왔어요"); return true }

            Bot.log("토벌전이 안 열렸어요 - 되돌리고 다시 (" + t + "/3)")
            val (ok2, why2) = resetToMain()
            if (!ok2) { failByReason(why2); return false }
        }
        set("시작 못 함", "토벌전에 들어가지 못했어요 (시즌 중인지 확인)")
        lastResult = "토벌전 진입 실패"
        return false
    }

    /**
     * 1) 하단 '길드' 탭 -> '길드 토벌전' 타일.
     *
     * 길드 화면인지 **확인한 뒤에만** 타일을 누른다. 아니면 아무것도 누르지 않고 물러난다 —
     * 화면을 안 보고 좌표를 누르는 짓은 하지 않는다(그 자리는 화면마다 다른 것이 있다).
     * 판정 실측: 청록 바탕 4점이 길드 4/4 · 메인 0/4, 타일 밝기가 길드 162 · 로비 95 · 메인 43.
     */
    private fun enterViaGuild(): Boolean {
        set("길드로 이동 중")
        tap(Screen.NAV_GUILD, 2800)
        val g = shot() ?: return false
        if (!Screen.atGuild(g)) { Bot.log("  길드 화면이 아니에요 - 이 길은 건너뜁니다"); return false }
        set("길드 토벌전으로 이동 중")
        tap(Screen.GUILD_TOBOL, 3000)
        return shot()?.let { Screen.atTobolLobby(it) } == true
    }

    /**
     * 2) 메인 우측 아이콘 열의 피냐타 바로가기(폴백).
     * 좌표를 고정하면 안 된다 - 이벤트에 따라 아이콘 개수가 달라져 열이 통째로 밀린다.
     */
    private fun enterViaShortcut(): Boolean {
        val b = shot() ?: return false
        if (!Screen.atMain(b)) return false
        val sc = Shortcut.findTobol(b)
        if (sc == null) { Bot.log("  토벌전 아이콘을 못 찾았어요"); return false }
        Bot.log("  토벌전 아이콘 (" + sc[0] + "," + sc[1] + ")")
        set("토벌전으로 이동 중")
        tap(sc, 2800)
        return shot()?.let { Screen.atTobolLobby(it) } == true
    }



    internal fun failByReason(why: String) {
        when (why) {
            "battle" -> { set("시작 못 함", "게임이 전투 중이라 들어갈 수 없어요. 전투가 끝난 뒤 다시 눌러 주세요")
                          lastResult = "전투 중이라 시작하지 못했어요" }
            "stop"   -> set("멈췄어요")
            else     -> { set("시작 못 함", "게임 화면을 메인으로 되돌리지 못했어요")
                          lastResult = "진입 실패 - 게임 화면을 확인해 주세요" }
        }
    }

    private fun tobolLoop(maxAttempts: Int) {
        set("토벌전 준비 중")
        var b = shot()
        if (b == null) { set("시작 못 함", "화면을 읽지 못했어요"); return }
        // 지난 도전의 결과창이 그대로 떠 있으면 ✕ 로 닫아 로비로.
        if (!Screen.atTobolLobby(b) && Screen.isBattleOver(b)) {
            set("지난 결과창 닫는 중")
            tap(Screen.TOBOL_CLOSE, 2500)
        }
        if (!enterTobolLobby()) return

        var attempts = 0
        for (n in 1..maxAttempts) {
            if (!running) break
            var atLobby = shot()?.let { Screen.atTobolLobby(it) } ?: false
            if (!atLobby) {
                sleep(2000)
                atLobby = shot()?.let { Screen.atTobolLobby(it) } ?: false
                if (!atLobby) { set("토벌전 끝", "로비를 벗어났어요 (시즌 종료로 보임)"); break }
            }
            attempts++
            set("토벌전 " + attempts + "회차", "도전하는 중")
            tap(Screen.TOBOL_CHALLENGE, 3000)

            var over = false
            val WAIT_MS = 90_000L
            val started = System.currentTimeMillis()
            val deadline = started + WAIT_MS
            while (System.currentTimeMillis() < deadline && running) {
                val s = shot()
                if (s != null && Screen.isBattleOver(s)) { over = true; break }
                status = "토벌전 " + attempts + "회차"
                detail = if (bestScore > 0) "자동 전투 중 · 최고 " + Ocr.comma(bestScore) else "자동 전투 중"
                // 전투가 얼마나 걸릴지는 모른다. 이 막대는 '포기까지 얼마나 남았나'다 —
                // 전투가 끝나면 다음 회차에서 0 부터 다시 찬다.
                setProgress(((System.currentTimeMillis() - started) / 1000).toInt(), (WAIT_MS / 1000).toInt())
                sleep(3000)
            }
            if (!running) break
            if (!over) { Bot.log("전투 끝을 못 잡음 - 로비 복귀 시도"); closeResult(); continue }
            // 최종 점수 읽기(금색 큰 숫자). 막 떠서 안 잡힐 수 있으니 몇 번 다시 본다.
            set("토벌전 " + attempts + "회차", "점수 확인 중")
            var score: Long? = null
            for (t in 1..5) {
                if (!running) break
                val s2 = shot() ?: break
                // 실측: 최종 점수 숫자는 x400~1070 · y1400~1506. 여유를 둬 넉넉히 자른다.
                // (PC 봇의 340,1335,800,120 을 그대로 쓰면 숫자 아래쪽이 잘려 204,721 처럼 오독한다)
                score = Ocr.readNumber(s2, 330, 1380, 830, 150, minDigits = 6)
                if (score != null) break
                sleep(700)
            }
            if (score != null) {
                lastScore = score
                if (score > bestScore) bestScore = score
                Bot.log("이번 " + Ocr.comma(score) + " · 최고 " + Ocr.comma(bestScore))
            }
            set("토벌전 " + attempts + "회차", "결과창 닫는 중")
            if (!closeResult()) { set("토벌전 끝", "결과창을 닫지 못했어요"); break }
            lastResult = if (bestScore > 0) "토벌전 " + attempts + "회 · 최고 " + Ocr.comma(bestScore) else "토벌전 " + attempts + "회 도전함"
        }
        if (running) set("토벌전 끝", attempts.toString() + "회 도전했어요") else set("멈췄어요", "토벌전 " + attempts + "회까지 했어요")
        lastResult = "토벌전 " + attempts + "회 도전함"
    }

    private fun closeResult(): Boolean {
        for (i in 1..3) {
            if (!running) return false
            tap(Screen.TOBOL_CLOSE, 2500)
            if (shot()?.let { Screen.atTobolLobby(it) } == true) return true
            Bot.log("결과창이 안 닫힘 - 다시 시도 ($i/3)")
            TapService.back()
            sleep(1800)
            if (shot()?.let { Screen.atTobolLobby(it) } == true) return true
        }
        return false
    }
}
