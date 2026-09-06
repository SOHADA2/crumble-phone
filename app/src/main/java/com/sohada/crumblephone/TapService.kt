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
            return s.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onServiceConnected() {
        instance = this
        Bot.log("접근성 서비스 연결됨 - 탭을 넣을 수 있습니다")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
