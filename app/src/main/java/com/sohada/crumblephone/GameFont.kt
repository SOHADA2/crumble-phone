package com.sohada.crumblephone

import android.content.Context
import android.graphics.Typeface

/**
 * 게임 글꼴(CookieRun Black).
 *
 * ⚠️ 이 앱은 원래 **커스텀 글꼴을 안 쓴다** — APK 를 가볍게 유지하려고 그렇게 정했다([Theme]).
 *    여기서 예외를 뒀다. 사장님이 원하는 그림을 직접 그려서 보내 주셨는데, 그 느낌의 절반이
 *    **글꼴**이었다("폰트도 그렇고, 획 그렇고, 뒷부분의 그림자도"). 시스템 고딕으로는
 *    아무리 테두리를 둘러도 그 통통한 게임 글씨가 안 나온다.
 *    대신 쓰는 자리를 **조합 이름표 한 곳**으로 묶었다. 나머지 화면은 예전처럼 시스템 글꼴이다.
 *
 * 2MB 다(APK 4.2MB → 6.2MB). PC 봇이 관제창에 쓰는 것과 같은 파일이다.
 *
 * 못 읽어도 죽지 않는다 — 그때는 굵은 시스템 글꼴로 떨어진다(모양만 덜 닮는다).
 */
object GameFont {

    private var cached: Typeface? = null
    private var tried = false

    fun black(ctx: Context): Typeface {
        if (!tried) {
            tried = true
            cached = try {
                Typeface.createFromAsset(ctx.assets, "fonts/cookierun_black.ttf")
            } catch (e: Exception) {
                Bot.log("게임 글꼴을 못 읽었어요 - 기본 글꼴로 갑니다: " + (e.message ?: ""))
                null
            }
        }
        return cached ?: Typeface.create("sans-serif-black", Typeface.BOLD)
    }
}
