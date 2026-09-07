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


    /**
     * 봇이 도는 동안 화면을 아주 어둡게 할지.
     * **화면 읽기는 백라이트가 아니라 프레임버퍼를 읽으므로 봇에는 아무 영향이 없다.**
     * (검은 판을 덮어씌우는 방식은 안 된다 — 그건 캡처에 같이 찍혀 게임을 못 읽는다.)
     */
    var dimScreen: Boolean
        get() = sp?.getBoolean("dimScreen", false) ?: false
        set(v) { sp?.edit()?.putBoolean("dimScreen", v)?.apply() }

    /**
     * 고른 게임 앱의 패키지. 비어 있으면 `GameApp` 이 자동으로 찾아 채운다.
     * 같은 게임이라도 스토어마다 패키지가 달라서(구글 플레이 / 갤럭시 스토어) 기억해 둬야 한다.
     */
    var gamePackage: String
        get() = sp?.getString("gamePackage", "") ?: ""
        set(v) { sp?.edit()?.putString("gamePackage", v)?.apply() }

    /**
     * 오븐 '자동 열기'의 **1회에 여는 개수**. 게임 쪽 설정값을 여기에 그대로 적어 둔다.
     * 봇이 이 값을 바꾸지는 않는다 — 한 싸이클이 얼마나 큰지 알아야 상한을 제대로 잡을 수 있어서다.
     * 오븐 레벨에 따라 고를 수 있는 값이 달라서 사용자가 직접 맞춘다.
     */
    /**
     * **광고 제거를 가지고 있나.** 켜면 일일 던전에서 `[▶ SKIP]` 을 눌러 도전 횟수를 더 받는다.
     *
     * ⚠️ 기본은 **꺼짐**이다. 광고 제거가 없는데 켜면 **진짜 광고가 재생된다** —
     *    봇이 광고를 보고 앉아 있게 되므로, 켜는 건 사용자가 직접 정한다.
     *    (봇은 눌러 본 뒤 던전 화면을 벗어나면 광고로 보고 빠져나온 다음, 그 실행에서는 다시 안 쓴다.)
     */
    var adFree: Boolean
        get() = sp?.getBoolean("adFree", false) ?: false
        set(v) { sp?.edit()?.putBoolean("adFree", v)?.apply() }

    val OVEN_CHOICES = intArrayOf(5, 10, 20, 30, 50)
    var ovenPerRun: Int
        get() = sp?.getInt("ovenPerRun", 20) ?: 20
        set(v) { sp?.edit()?.putInt("ovenPerRun", v)?.apply() }

    val ARENA_CHOICES = intArrayOf(5, 10, 20, 30)
}
