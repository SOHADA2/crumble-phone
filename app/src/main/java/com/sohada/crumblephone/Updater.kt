package com.sohada.crumblephone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 앱 안에서 새 판을 받아 설치한다.
 *
 * ⏳ **아직 안 산다.** 저장소가 비공개라 아래 주소가 로그인을 요구해서 앱이 못 받는다.
 *    지금은 확인이 조용히 실패하고 넘어간다. 끝내는 길은 둘이고 CLAUDE.md 3장에 적어 뒀다 —
 *    이 저장소를 공개로 돌리거나(그러면 이 주소 그대로 바로 동작한다),
 *    공개 릴리스 저장소를 따로 두고 `BASE` 를 그쪽으로 돌리거나(그쪽으로 갈 예정).
 *
 * ⚠️ **앱에 토큰을 넣어 해결하려 하지 말 것.** APK 를 뜯으면 누구나 저장소에 접근하게 된다.
 *
 * 마지막 '설치' 확인은 안드로이드가 반드시 사용자에게 묻는다(사이드로드 앱은 예외가 없다).
 * 그 앞 — 새 판이 있는지 확인 · 내려받기 — 까지가 자동이다.
 */
object Updater {

    // 태그를 직접 가리킨다. `/releases/latest` 는 프리릴리스를 건너뛰어서 404 가 난다.
    private const val BASE = "https://github.com/SOHADA2/crumble-phone/releases/download/latest"
    private const val MANIFEST = "$BASE/latest.json"
    private const val APK = "$BASE/app-debug.apk"

    /** 아직 확인 안 함 / 최신 / 새 판 있음 / 받는 중 … 관제 화면이 이걸 그대로 보여 준다. */
    @Volatile var state = ""
    @Volatile var latestCode = 0
    @Volatile var latestName = ""
    @Volatile var notes = ""
    @Volatile var progress = -1          // 0~100, -1 이면 막대 숨김
    @Volatile var busy = false

    /** 지금 깔려 있는 판의 번호. */
    fun currentCode(ctx: Context): Int {
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        } catch (e: Exception) { 0 }
    }

    fun currentName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    /** 새 판이 있나? 결과는 state/latestCode 에 담긴다. */
    fun check(ctx: Context, quiet: Boolean = false) {
        if (busy) return
        busy = true
        state = "확인 중"
        thread(name = "update-check") {
            try {
                val txt = get(MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
                val j = JSONObject(txt)
                latestCode = j.optInt("versionCode", 0)
                latestName = j.optString("versionName", "")
                notes = j.optString("notes", "")
                val cur = currentCode(ctx)
                state = if (latestCode > cur) "새 버전 " + latestName + " 있음" else "최신이에요"
                if (!quiet) Bot.log("업데이트 확인: 지금 " + currentName(ctx) + " · 서버 " + latestName)
            }
            catch (e: Exception) {
                // 릴리스 저장소가 아직 없거나 인터넷이 안 될 때 여기로 온다. 조용히 지나간다.
                // 저장소가 아직 비공개면 여기로 온다(주소가 로그인을 요구한다). 조용히 지나간다.
                state = if (quiet) "" else "확인 실패 (인터넷·저장소 공개 여부를 확인해 주세요)"
                if (!quiet) Bot.log("업데이트 확인 실패: " + (e.message ?: "알 수 없음"))
            }
            finally { busy = false }
        }
    }

    /** 새 판을 받아 설치 화면까지 띄운다. */
    fun update(ctx: Context) {
        if (busy) return
        busy = true
        progress = 0
        state = "받는 중"
        thread(name = "update-download") {
            try {
                val conn = openConn(APK)
                val total = conn.contentLength
                val apk = ctx.cacheDir.resolve("update.apk")
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); got += n
                            progress = if (total > 0) (100L * got / total).toInt().coerceIn(0, 100) else -1
                        }
                    }
                }
                state = "설치 준비 중"
                progress = -1
                install(ctx, apk.readBytes())
                state = "설치 화면을 띄웠어요"
            }
            catch (e: Exception) {
                state = "받기 실패 (" + (e.message ?: "알 수 없음") + ")"
                progress = -1
                Bot.log("업데이트 실패: " + (e.message ?: "알 수 없음"))
            }
            finally { busy = false }
        }
    }

    /**
     * PackageInstaller 로 넘긴다. FileProvider 를 따로 두지 않아도 되고,
     * 설치 확인 창은 시스템이 띄운다.
     */
    private fun install(ctx: Context, bytes: ByteArray) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("crumble", 0, bytes.size.toLong()).use { out ->
                out.write(bytes); session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    private fun openConn(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) throw Exception("HTTP " + c.responseCode)
        return c
    }

    private fun get(url: String): InputStream = openConn(url).inputStream
}

/**
 * 설치 결과를 받는다. 시스템이 '사용자 확인이 필요하다'고 하면 그 창을 띄운다 —
 * 사이드로드 앱은 이 확인을 건너뛸 수 없다(기기 관리자 앱만 예외다).
 */
class InstallReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "com.sohada.crumblephone.INSTALL_RESULT" }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.state = "설치 완료"
                Bot.log("업데이트 설치 완료")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없음"
                Updater.state = "설치 실패 (" + msg + ")"
                Bot.log("업데이트 설치 실패: " + msg)
            }
        }
    }
}
