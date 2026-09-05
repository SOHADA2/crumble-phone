package com.sohada.crumblephone

import android.content.Context

/**
 * 사용자가 고른 설정. 아주 작아서 SharedPreferences 하나면 충분하다.
 *
 * 처음 한 번 `init(ctx)` 해 두면 어디서든 값만 읽어 쓴다(콘텐츠 코드가 Context 를 들고 다니지 않게).
 */
object Prefs {
    private const val FILE = "crumble"

    // 아직 init 이 안 됐을 때 읽어도 죽지 않고 기본값이 나오게 nullable 로 둔다.
    // (콘텐츠는 서비스 스레드에서 도는데, 관제 화면을 거치지 않고 불릴 길이 생길 수 있다.)
    private var sp: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /**
     * 시험 모드 — **재화·입장권을 하나도 쓰지 않고 진입까지만** 하고 끝낸다.
     * 좌표가 맞는지 공짜로 확인하는 방법이다(PC 봇의 `-MaxDungeons 0` · `-MaxFights 0` 과 같다).
     */
    var testMode: Boolean
        get() = sp?.getBoolean("testMode", false) ?: false
        set(v) { sp?.edit()?.putBoolean("testMode", v)?.apply() }

    /** 아레나를 몇 판까지 할지. 재화가 먼저 떨어지면 거기서 스스로 끝난다. */
    var arenaFights: Int
        get() = sp?.getInt("arenaFights", 10) ?: 10
        set(v) { sp?.edit()?.putInt("arenaFights", v)?.apply() }

    /** 일일 던전에서 기회를 다 쓴 뒤 소탕으로 **SKIP 티켓까지** 쓸지. 기본은 끔. */
    var dailySweep: Boolean
        get() = sp?.getBoolean("dailySweep", false) ?: false
        set(v) { sp?.edit()?.putBoolean("dailySweep", v)?.apply() }

    val ARENA_CHOICES = intArrayOf(5, 10, 20, 30)
}
