package com.sohada.crumblephone

import android.content.Context
import android.content.Intent

/**
 * 게임 앱을 찾는다.
 *
 * ⚠️ **패키지 이름을 하나로 박으면 안 된다.** 같은 게임이라도 **스토어마다 패키지가 다르다** —
 *    구글 플레이로 깔았다가 갤럭시 스토어로 다시 깔면 앱이 게임을 못 알아본다(실제로 겪음).
 *    그래서 `com.devsisters.` 로 시작하는 설치된 앱을 **스스로 찾고**, 고른 것을 기억한다.
 *
 * 화면 판정·좌표는 스토어와 무관하게 같으므로, 패키지만 맞으면 나머지는 그대로 돈다.
 */
object GameApp {

    private const val PREFIX = "com.devsisters."
    /** 예전 판이 쓰던 이름. 후보가 여럿일 때 이걸 먼저 고른다. */
    private const val PREFERRED = "com.devsisters.cc"

    /** (패키지, 앱 이름) 목록. 런처에 뜨는 앱만 본다. */
    fun candidates(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(main, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName.startsWith(PREFIX) }
                .distinctBy { it.packageName }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
        } catch (e: Exception) { emptyList() }
    }

    private fun installed(ctx: Context, pkg: String): Boolean =
        pkg.isNotEmpty() && ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /** 지금 쓸 게임 패키지. 없으면 null. 자동으로 찾으면 기억해 둔다. */
    fun pkg(ctx: Context): String? {
        Prefs.init(ctx)
        val saved = Prefs.gamePackage
        if (installed(ctx, saved)) return saved

        val found = candidates(ctx)
        val pick = found.firstOrNull { it.first == PREFERRED } ?: found.firstOrNull() ?: return null
        Prefs.gamePackage = pick.first
        Bot.log("게임을 찾았어요: " + pick.second + " (" + pick.first + ")")
        return pick.first
    }

    fun set(ctx: Context, pkg: String) {
        Prefs.init(ctx)
        Prefs.gamePackage = pkg
        Bot.log("게임 앱을 " + pkg + " 로 바꿨어요")
    }

    /** 화면에 보여 줄 이름. 못 찾았으면 null. */
    fun label(ctx: Context): String? {
        val p = pkg(ctx) ?: return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
        } catch (e: Exception) { p }
    }

    fun launchIntent(ctx: Context): Intent? {
        val p = pkg(ctx) ?: return null
        return ctx.packageManager.getLaunchIntentForPackage(p)
    }
}
