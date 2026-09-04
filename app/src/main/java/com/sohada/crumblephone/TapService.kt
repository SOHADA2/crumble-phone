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

        /** 실좌표(1440x3120 기준 그대로) 한 점을 누른다. */
        fun tap(x: Int, y: Int, ms: Long = 60): Boolean {
            val s = instance ?: return false
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(p, 0, ms)
            return s.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        /** 끌기. 게임의 '절전 해제(왕관 씌우기)' 처럼 드래그가 필요한 곳에 쓴다. */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 900): Boolean {
            val s = instance ?: return false
            val p = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
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
