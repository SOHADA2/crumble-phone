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

    /** 71227167 → 71,227,167 */
    fun comma(n: Long): String = String.format("%,d", n)
}
