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
import android.os.IBinder
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

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra("code", code)
                .putExtra("data", data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    var width = 0
    var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) 먼저 포그라운드로 (14+ 필수)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "크럼블 폰봇", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val noti = Notification.Builder(this, CH)
            .setContentTitle("크럼블 폰봇")
            .setContentText("화면을 읽는 중")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, noti, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, noti)
        }

        // 2) 그 다음에 화면 읽기 시작
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code != 0 && data != null && projection == null) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(code, data)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { Bot.log("화면 읽기가 중지됐습니다") }
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
            } catch (e: Exception) {
                Bot.log("화면 읽기 실패: ${e.message}")
            }
        }
        return START_STICKY
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

    override fun onDestroy() {
        display?.release(); reader?.close(); projection?.stop()
        instance = null
        super.onDestroy()
    }
}
