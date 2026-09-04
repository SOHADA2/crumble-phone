package com.sohada.crumblephone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * 스파이크(가능성 확인용) 화면. 딱 세 가지를 실기로 확인한다.
 *   1) MediaProjection 으로 게임 화면이 '검게 안 나오고' 실제로 찍히는가
 *   2) 접근성 탭이 게임에 실제로 먹히는가
 *   3) 접근성 서비스를 켠 채로 게임이 정상 실행되는가(막지 않는가)
 *
 * 좌표는 PC 봇과 같은 1440x3120 실좌표를 그대로 쓴다(이 폰이 정확히 그 해상도라 변환이 필요 없다).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val REQ_CAP = 1001

    // ── PC 봇(dev.ps1)과 똑같은 판정 상수 ──
    private val DOCK = intArrayOf(300, 1150, 2820, 2980)   // x1,x2,y1,y2
    private val CLOSE_PTS = arrayOf(
        intArrayOf(711, 3030), intArrayOf(680, 3010), intArrayOf(740, 3050), intArrayOf(711, 2990),
        intArrayOf(711, 3070), intArrayOf(650, 3030), intArrayOf(772, 3030)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#231A18"))
        }
        fun title(t: String) = TextView(this).apply {
            text = t; textSize = 20f; setTextColor(Color.parseColor("#FFC94A")); setPadding(0, 0, 0, 20)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        root.addView(title("크럼블 폰봇 — 가능성 확인"))
        root.addView(btn("1. 접근성 서비스 켜기(설정 열기)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. 화면 읽기 허용") { askProjection() })
        root.addView(btn("3. 게임 켜고 14초 뒤 화면 검사") { launchGameAndCheck() })
        root.addView(btn("4. 지금 화면 검사") { checkScreen("지금") })
        root.addView(btn("5. 게임 켜고 탭 시험 (던전 열었다 되돌아오기)") { tapTest() })

        logView = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#E8D9C8")); setPadding(0, 24, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)

        Bot.logger = { s -> ui.post { logView.append(s + "\n") } }
        Bot.log("준비됨. 1 → 2 → 3 순서로 눌러 주세요.")
        Bot.log("접근성: " + if (TapService.isReady) "켜짐" else "아직 안 켜짐")

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAP) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.start(this, resultCode, data)
                Bot.log("화면 읽기 허용됨")
            } else Bot.log("화면 읽기를 거부했습니다")
        }
    }

    private fun launchGameAndCheck() {
        val i = packageManager.getLaunchIntentForPackage("com.devsisters.cc")
        if (i == null) { Bot.log("게임이 설치돼 있지 않습니다"); return }
        Bot.log("게임 실행 → 6초 뒤 화면을 읽습니다")
        startActivity(i)
        ui.postDelayed({ checkScreen("게임") }, 14000)
    }

    /**
     * 탭 시험: 게임을 앞으로 띄우고 충분히 기다린 뒤 하단 던전 탭을 누른다.
     *   앱이 앞에 있을 때 누르면 앱 자신을 누르게 되므로, 반드시 게임을 먼저 띄워야 한다.
     *   던전 화면으로 바뀌면(=메인 아님) 탭이 게임에 먹혔다는 뜻이다. 확인 후 뒤로가기로 되돌린다.
     */
    private fun tapTest() {
        if (!TapService.isReady) { Bot.log("접근성 서비스를 먼저 켜 주세요"); return }
        val gi = packageManager.getLaunchIntentForPackage("com.devsisters.cc")
        if (gi == null) { Bot.log("게임을 찾지 못했습니다"); return }
        Bot.log("게임을 띄우고 14초 기다립니다...")
        startActivity(gi)
        ui.postDelayed({
            checkScreen("탭 전")
            val ok = TapService.tap(530, 3054)
            Bot.log("던전(530,3054) 탭 넣음 → dispatchGesture=" + ok)
            ui.postDelayed({
                checkScreen("탭 후")
                ui.postDelayed({
                    Bot.log("뒤로가기로 되돌립니다")
                    TapService.back()
                    ui.postDelayed({ checkScreen("되돌린 뒤") }, 3000)
                }, 1200)
            }, 3500)
        }, 14000)
    }

    /** 화면 한 장을 읽어 PC 봇과 같은 방식으로 판정하고, PNG 로도 남긴다. */
    private fun checkScreen(tag: String) {
        val cap = CaptureService.instance
        if (cap == null) { Bot.log("[$tag] 화면 읽기가 아직 준비 안 됨 (2번을 먼저)"); return }
        val bmp = cap.grab()
        if (bmp == null) { Bot.log("[$tag] 프레임을 못 얻었습니다"); return }

        var black = 0; var n = 0
        var rs = 0L; var gs = 0L; var bs = 0L
        var x = DOCK[0]
        while (x < DOCK[1]) {
            var y = DOCK[2]
            while (y < DOCK[3]) {
                val c = bmp.getPixel(x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
                if (Color.red(c) + Color.green(c) + Color.blue(c) < 12) black++
                y += 6
            }
            x += 6
        }
        val mean = (rs + gs + bs).toDouble() / (3.0 * n)
        val ratio = if (mean < 0.5) 0.0 else ((rs - bs).toDouble() / n) / mean

        var hit = 0
        for (p in CLOSE_PTS) {
            val c = bmp.getPixel(p[0], p[1])
            if (Color.red(c) > 170 && Color.blue(c) < 80) hit++
        }
        val atMain = ratio >= 0.1 && hit < 4

        val f = File(getExternalFilesDir("shots"), "shot_${System.currentTimeMillis()}.png")
        try { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } } catch (_: Exception) {}

        Bot.log("[$tag] ${bmp.width}x${bmp.height}  독 밝기=${"%.1f".format(mean)} 비율=${"%.2f".format(ratio)}" +
                "  검은점=${black}/${n}  닫기버튼=${hit}/7  → " + if (atMain) "메인" else "메인 아님")
        Bot.log("   저장: ${f.name}")
        bmp.recycle()
    }
}
