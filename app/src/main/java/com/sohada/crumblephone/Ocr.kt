package com.sohada.crumblephone

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 화면의 숫자 읽기. PC 봇의 ocr.ps1(Windows 내장 OCR) 자리를 대신한다.
 * ML Kit 온디바이스 인식이라 인터넷이 필요 없고, 모델은 Play 서비스가 들고 있어 앱이 커지지 않는다.
 */
object Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * 잘라낸 영역에서 숫자만 뽑는다. 자릿수가 모자라면 오독으로 보고 버린다.
     * ⚠️ 결과를 기다리므로 반드시 작업 스레드에서 부를 것(메인 스레드에서 부르면 멈춘다).
     */
    fun readNumber(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, minDigits: Int = 4): Long? {
        if (x < 0 || y < 0 || x + w > bmp.width || y + h > bmp.height) return null
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bmp, x, y, w, h)
            val r = Tasks.await(client.process(InputImage.fromBitmap(crop, 0)), 6, TimeUnit.SECONDS)
            val digits = r.text.replace(Regex("[^0-9]"), "")
            if (digits.length >= minDigits) digits.toLongOrNull() else null
        } catch (e: Exception) {
            null
        } finally {
            crop?.recycle()
        }
    }

    /** 잘라낸 영역의 글자를 그대로. 단위(K·M·G)까지 봐야 할 때 쓴다. */
    fun readText(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): String? {
        if (x < 0 || y < 0 || x + w > bmp.width || y + h > bmp.height) return null
        var crop: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(bmp, x, y, w, h)
            Tasks.await(client.process(InputImage.fromBitmap(crop, 0)), 6, TimeUnit.SECONDS).text
        } catch (e: Exception) {
            null
        } finally {
            crop?.recycle()
        }
    }

    private val POWER = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGB])?", RegexOption.IGNORE_CASE)

    /**
     * 전투력처럼 **단위가 붙는 숫자**를 실제 크기로 읽는다.
     *   `59.64M` → 59,640,000 · `1.21G` → 1,210,000,000 · `59M 70K` → 59,070,000 · `1,234` → 1,234
     *
     * ⚠️ 숫자만 뽑아서 비교하면 안 된다. PC 봇(`arena.ps1`)은 비숫자를 버려서
     *    `59.64M` 을 `5964`, `1.21G` 를 `121` 로 읽는다 — 단위가 섞이는 순간
     *    **더 센 상대를 약하다고 오판한다.** 아레나에서 그건 곧 패배다.
     */
    fun readPower(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): Long? {
        val t = (readText(bmp, x, y, w, h) ?: return null).replace(",", "")
        var sum = 0.0
        var found = false
        for (m in POWER.findAll(t)) {
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            val mul = when (m.groupValues[2].uppercase()) {
                "K" -> 1_000.0
                "M" -> 1_000_000.0
                "G", "B" -> 1_000_000_000.0
                else -> 1.0
            }
            sum += v * mul
            found = true
        }
        return if (found) sum.toLong() else null
    }

    /** 71227167 → 71,227,167 */
    fun comma(n: Long): String = String.format("%,d", n)
}
