package com.sohada.crumblephone

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics

/**
 * 화면을 읽는 포그라운드 서비스(MediaProjection).
 * PC 봇의 `adb exec-out screencap` 을 대신한다.
 *
 * ※ 안드로이드 14+ 규칙: getMediaProjection() 을 부르기 '전에' foregroundServiceType=mediaProjection 으로
 *   포그라운드 전환을 끝내 놔야 한다. 순서를 바꾸면 SecurityException 이 난다.
 */
class CaptureService : Service() {

    companion object {
        @Volatile
        var instance: CaptureService? = null
        const val CH = "crumble_capture"
        const val ACTION_STOP = "com.sohada.crumblephone.STOP_CAPTURE"

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra("code", code)
                .putExtra("data", data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** 화면 읽기를 끈다. 안 켜져 있으면 아무 일도 안 한다. */
        fun stop(ctx: Context) {
            if (instance == null) return
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var lastNoti = ""          // 알림 글이 바뀔 때만 다시 그린다(같은 글로 계속 부르면 눌린다)

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    var width = 0
    var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 알림의 [끄기] 나 관제 화면의 [화면 읽기 끄기] 로 들어온다.
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }

        // 1) 먼저 포그라운드로 (14+ 필수)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "크럼블 폰봇", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNoti(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, buildNoti())
        }
        startTicker()

        // 2) 그 다음에 화면 읽기 시작
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code != 0 && data != null && projection == null) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(code, data)
                p.registerCallback(object : MediaProjection.Callback() {
                    // 사용자가 상태바/빠른설정에서 공유를 끊으면 여기로 온다.
                    // 정리를 안 하면 instance 가 남아 앱은 '읽는 중'인 줄 알고 영영 빈 화면을 붙든다.
                    override fun onStop() { Bot.log("화면 읽기가 중지됐습니다"); shutdown() }
                }, null)
                projection = p

                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.getRealMetrics(dm)
                width = dm.widthPixels
                height = dm.heightPixels

                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                display = p.createVirtualDisplay(
                    "crumble-cap", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, null
                )
                instance = this
                Bot.log("화면 읽기 준비됨: ${width}x${height}")
                refreshNoti()
            } catch (e: Exception) {
                Bot.log("화면 읽기 실패: ${e.message}")
            }
        }
        // START_STICKY 로 두면 안 된다 — 시스템이 되살려도 화면 읽기 동의(1회용 토큰)는 없어서
        // 캡처는 안 되는데 '화면을 읽는 중' 알림만 남는다. 그게 못 끄는 것처럼 보였던 원인이다.
        return START_NOT_STICKY
    }

    // ══════════════════════════════════════════════════════════
    //  알림 = 살아 있는 상태판
    // ══════════════════════════════════════════════════════════
    //  봇이 도는 동안 사용자는 **게임 화면**을 본다. 앱을 다시 열어야만 진행을 알 수 있으면
    //  아무 소용이 없다. 알림줄은 게임 위에서도 늘 볼 수 있는 유일한 자리다
    //  (오버레이 알약은 권한이 있어야 하지만 알림은 항상 뜬다).

    /** 지금 보여 줄 한 줄. 이게 바뀔 때만 알림을 다시 그린다. */
    private fun notiText(): String {
        if (!Runner.running) return "쉬는 중 · 화면 읽기 켜짐"
        val d = Runner.detail
        return Runner.status + (if (d.isEmpty()) "" else " · " + d)
    }

    private fun buildNoti(): Notification {
        // [끄기] 가 없으면 한 번 켠 화면 읽기를 끌 방법이 없다
        // (포그라운드 서비스라 앱을 최근목록에서 밀어내도 안 죽는다).
        val stopPi = PendingIntent.getForegroundService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 알림을 누르면 관제 화면이 열린다.
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH)
            .setContentTitle(if (Runner.running && Runner.task.isNotEmpty()) "크럼블 폰봇 · " + Runner.task else "크럼블 폰봇")
            .setContentText(notiText())
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)          // 글이 바뀔 때마다 소리·진동이 나면 안 된다
            .addAction(R.drawable.ic_stat_bot, "끄기", stopPi)
        val p = Runner.progress
        if (Runner.running && p >= 0) b.setProgress(100, p, false)
        return b.build()
    }

    private fun refreshNoti() {
        val t = (if (Runner.running) Runner.task else "") + "|" + notiText() + "|" + Runner.progress
        if (t == lastNoti) return
        lastNoti = t
        try {
            getSystemService(NotificationManager::class.java).notify(1, buildNoti())
        } catch (e: Exception) { /* 알림 권한이 없으면 조용히 넘어간다 */ }
    }

    private fun startTicker() {
        ui.removeCallbacksAndMessages(null)
        ui.post(object : Runnable {
            override fun run() {
                refreshNoti()
                // 봇이 도는 동안에는 알약이 떠 있어야 한다. 관제 화면에서만 띄우면
                // 앱을 최근목록에서 밀어냈을 때 봇은 도는데 알약만 사라진다.
                // 여기는 서비스라 앱이 닫혀도 살아 있고, 이 핸들러는 메인 스레드다.
                Overlay.ensure(applicationContext)
                ui.postDelayed(this, 1000)
            }
        })
    }

    /** 지금 화면 한 장. 아직 프레임이 안 왔으면 null. */
    fun grab(): Bitmap? {
        val r = reader ?: return null
        val img = r.acquireLatestImage() ?: return null
        return try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val tmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(tmp, 0, 0, width, height)
        } catch (e: Exception) {
            Bot.log("한 장 만들기 실패: ${e.message}"); null
        } finally {
            img.close()
        }
    }

    /** 돌던 것을 멈추고, 화면 읽기를 놓고, 알림까지 걷어낸다. */
    private fun shutdown() {
        ui.removeCallbacksAndMessages(null)
        Runner.stop()
        Overlay.hide()
        release()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Bot.log("화면 읽기를 껐습니다")
    }

    private fun release() {
        ui.removeCallbacksAndMessages(null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        instance = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
