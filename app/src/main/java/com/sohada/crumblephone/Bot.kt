package com.sohada.crumblephone

/** 화면(액티비티)과 서비스가 주고받는 아주 작은 공용 창구. */
object Bot {
    /** 로그 한 줄을 화면에 붙인다. 액티비티가 살아 있을 때만 보인다. */
    @Volatile
    var logger: ((String) -> Unit)? = null

    /** 최근 기록. 화면을 캡처해 보내는 대신 **글로 복사해 보낼 수 있게** 조금 들고 있는다. */
    private val recent = ArrayDeque<String>()

    fun log(s: String) {
        android.util.Log.i("CrumblePhone", s)
        synchronized(recent) {
            recent.addLast(s)
            while (recent.size > 60) recent.removeFirst()
        }
        logger?.invoke(s)
    }

    fun recentText(): String = synchronized(recent) { recent.joinToString("\n") }
}
