package com.sohada.crumblephone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * 화면을 대신 눌러 주는 접근성 서비스.
 * PC 봇의 `adb shell input tap/swipe` 를 대신한다. 루트도 adb 도 필요 없고,
 * 사용자가 설정에서 한 번 켜 주기만 하면 된다.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null

        val isReady: Boolean get() = instance != null

        /** **설계 좌표(1440x3120)** 한 점을 누른다. 기기 해상도 환산은 `Coords` 가 한다. */
        fun tap(x: Int, y: Int, ms: Long = 60): Boolean {
            val s = instance ?: return false
            if (blocked("탭")) return false
            val p = Path().apply { moveTo(Coords.x(x).toFloat(), Coords.y(y).toFloat()) }
            val stroke = GestureDescription.StrokeDescription(p, 0, ms)
            return s.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        /**
         * 한 점을 누르고 **실제로 들어갔는지 결과를 받아 온다**(진단용).
         * 보통 `tap` 은 결과를 안 본다 — 여기서만 콜백을 달아 완료/취소를 가린다.
         * 화면이 안 바뀔 때 '탭이 안 들어간 것'과 '좌표가 틀린 것'을 가르는 유일한 방법이다.
         */
        fun tapChecked(x: Int, y: Int, ms: Long = 60): String {
            val s = instance ?: return "접근성 꺼짐"
            val dx = Coords.x(x); val dy = Coords.y(y)
            val where = " · 기기좌표(" + dx + "," + dy + ") " + ms + "ms"
            val p = Path().apply { moveTo(dx.toFloat(), dy.toFloat()) }
            val latch = java.util.concurrent.CountDownLatch(1)
            var res = "응답 없음"
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { res = "완료"; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { res = "취소됨"; latch.countDown() }
            }
            val sent = try {
                val stroke = GestureDescription.StrokeDescription(p, 0, ms)
                s.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, null)
            } catch (e: Throwable) { return "예외 " + e + where }
            if (!sent) return "거부됨(dispatch=false)" + where
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            return res + where
        }

        /** 끌기. 게임의 '절전 해제(왕관 씌우기)' 처럼 드래그가 필요한 곳에 쓴다. */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 900): Boolean {
            val s = instance ?: return false
            if (blocked("끌기")) return false
            val p = Path().apply {
                moveTo(Coords.x(x1).toFloat(), Coords.y(y1).toFloat())
                lineTo(Coords.x(x2).toFloat(), Coords.y(y2).toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(p, 0, ms)
            return s.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        /** 뒤로가기. PC 봇의 keyevent 4 와 같다(무엇도 시작시키지 않는 안전한 조작). */
        fun back(): Boolean {
            val s = instance ?: return false
            if (blocked("뒤로가기")) return false
            return s.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // ══════════════════════════════════════════════════════════
        //  지금 맨 앞에 선 앱
        // ══════════════════════════════════════════════════════════
        /**
         * 마지막으로 확인된 **맨 앞 앱**의 패키지. 아직 아무 창 소식도 못 받았으면 빈 값.
         *
         * 왜 이걸 보나 — 화면 읽기(MediaProjection)도 제스처(접근성)도 **지금 보이는 화면**에만
         * 작동한다. 사장님이 도중에 카톡을 열면 봇은 그걸 모르고 **그 앱을 누른다.**
         * PC 봇은 `Test-GameFocused` 로 예전부터 막고 있었는데 **폰에만 없었다.**
         *
         * `typeWindowStateChanged` 는 접근성 설정(`res/xml`)에서 이미 받고 있어서 공짜다.
         */
        @Volatile
        var topPkg = ""
            private set

        /** 런처에 뜨는 앱인가? 창 소식마다 PackageManager 를 부르지 않으려고 기억해 둔다. */
        private val launchable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /**
         * 창 소식을 믿어도 되나. 화면으로는 게임이 보이는데 '맨 앞'이 딴 앱이라면
         * 이 기기에서는 소식이 안 오는 것이므로 판정을 통째로 끈다([calibrateFront]).
         */
        @Volatile
        var trustFront = true
            private set

        /**
         * 게임이 지금 맨 앞인가?
         *
         * ⚠️ **모르면 '맞다'로 본다.** 창 소식을 한 번도 못 받았거나 게임 앱을 못 찾았을 때
         *    '아니다'로 기울이면 봇이 **영영 멈춰 선다** — 조용히 아무것도 안 하는 게 제일 나쁜 고장이다.
         *    (누를까 말까는 반대로 **안 누르는 쪽**이 안전하다. 두 판단의 기울기가 다른 게 요점이다.)
         */
        val gameIsFront: Boolean
            get() {
                if (!trustFront) return true
                val top = topPkg
                if (top.isEmpty()) return true
                val s = instance ?: return true
                val g = gamePkgOf(s)
                if (g.isEmpty()) return true
                return top == g
            }

        // 게임 패키지는 자주 안 바뀌는데 `GameApp.pkg` 는 매번 PackageManager 를 부른다.
        // 이 판정은 탭마다·바퀴마다 도는 자리라 1분만 기억해 둔다(게임 앱을 바꿔도 1분 안에 반영된다).
        @Volatile private var gamePkgCache = ""
        @Volatile private var gamePkgAt = 0L

        private fun gamePkgOf(ctx: android.content.Context): String {
            val now = System.currentTimeMillis()
            if (gamePkgCache.isNotEmpty() && now - gamePkgAt < 60_000) return gamePkgCache
            val g = try { GameApp.pkg(ctx) } catch (e: Exception) { null } ?: return ""
            gamePkgCache = g; gamePkgAt = now
            return g
        }

        /**
         * **눈으로 본 것과 대조한다.** 게임을 띄운 직후, 화면에 게임이 실제로 보이는 그 순간에 부른다.
         * 그런데도 '맨 앞'이 게임이 아니라면 이 기기에서는 창 소식이 안 오는 것이다 →
         * 판정을 끄지 않으면 봇이 **영영 기다린다**(제일 나쁜 고장).
         */
        fun calibrateFront(ctx: android.content.Context) {
            val g = try { GameApp.pkg(ctx) } catch (e: Exception) { null } ?: return
            val top = topPkg
            if (top.isEmpty()) return              // 아직 소식이 없을 뿐 - gameIsFront 가 이미 '맞다'로 본다
            if (top == g) {
                if (!trustFront) { trustFront = true; Bot.log("'맨 앞 앱' 판정을 다시 켭니다") }
                return
            }
            trustFront = false
            Bot.log("이 기기에서는 '맨 앞 앱' 소식이 실제와 안 맞아요 (게임이 보이는데 앞은 " + top + ") - 앞뒤 판정을 끕니다")
        }

        private var blockedAt = 0L

        /** 게임이 앞에 없으면 제스처를 넣지 않는다 — 넣으면 **사장님이 보고 있는 앱**이 눌린다. */
        private fun blocked(what: String): Boolean {
            if (gameIsFront) return false
            val now = System.currentTimeMillis()
            if (now - blockedAt > 5000) {          // 로그가 도배되지 않게 5초에 한 줄만
                blockedAt = now
                Bot.log("게임이 앞에 없어서 " + what + "을 넣지 않았어요 (지금 앞: " + topPkg + ")")
            }
            return true
        }
    }

    override fun onServiceConnected() {
        instance = this
        Bot.log("접근성 서비스 연결됨 - 탭을 넣을 수 있습니다")
    }

    /**
     * 창이 바뀔 때마다 **맨 앞 앱**을 적어 둔다. 화면 내용은 읽지 않는다(패키지 이름만 본다).
     *
     * ⚠️ **런처에 뜨는 앱만** 적는다. 키보드·토스트·상태바 내림창 같은 스쳐 지나가는 창까지
     *    적으면, 그게 닫힐 때 소식이 안 와서 봇이 '다른 앱을 쓰는 중' 으로 **굳어 버린다.**
     *    (그런 창은 잠깐 덮을 뿐이고, 그 사이 탭 몇 번을 흘리는 건 감수한다.)
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p.isEmpty()) return
        val ok = launchable.getOrPut(p) {
            try { packageManager.getLaunchIntentForPackage(p) != null } catch (ex: Exception) { false }
        }
        if (ok) topPkg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
