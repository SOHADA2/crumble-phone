package com.sohada.crumblephone

/** 화면(액티비티)과 서비스가 주고받는 아주 작은 공용 창구. */
object Bot {
    /** 로그 한 줄을 화면에 붙인다. 액티비티가 살아 있을 때만 보인다. */
    @Volatile
    var logger: ((String) -> Unit)? = null

    fun log(s: String) {
        android.util.Log.i("CrumblePhone", s)
        logger?.invoke(s)
    }
}
