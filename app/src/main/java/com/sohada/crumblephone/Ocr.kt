package com.sohada.crumblephone

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 화면의 숫자 읽기. PC 봇의 ocr.ps1(Windows 내장 OCR) 자리를 대신한다.
 * ML Kit 온디바이스 인식이라 인터넷이 필요 없고, 모델은 Play 서비스가 들고 있어 앱이 커지지 않는다.
 */
object Ocr {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * 한글 인식기. **기본 인식기(`DEFAULT_OPTIONS`)는 라틴 전용이라 한글을 아예 못 읽는다** —
     * 쿠키 이름을 읽으려다 이걸 모르면 "OCR이 또 안 되네"로 잘못 결론 내리기 쉽다.
     * 모델은 Play 서비스가 들고 있어 APK 가 커지지 않는다(숫자용 라틴 인식기와 같은 방식).
     */
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /**
     * 설계 좌표로 자른 조각을 만든다. 못 자르면 `null`.
     * `scale` 을 주면 그만큼 확대한다 — 작은 글자는 확대하면 인식률이 크게 오른다
     * (PC 사전 빌더도 4배 확대해서야 이름을 읽어 냈다).
     */
    private fun crop(bmp: Bitmap, dx: Int, dy: Int, dw: Int, dh: Int, scale: Int = 1): Bitmap? {
        val x = Coords.x(dx); val y = Coords.y(dy)
        val w = Coords.x(dx + dw) - x; val h = Coords.y(dy + dh) - y
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > bmp.width || y + h > bmp.height) return null
        val c = Bitmap.createBitmap(bmp, x, y, w, h)
        if (scale <= 1) return c
        val big = Bitmap.createScaledBitmap(c, w * scale, h * scale, true)
        if (big !== c) c.recycle()
        return big
    }

    /**
     * 잘라낸 영역의 **한글**을 그대로 읽는다(쿠키 이름 등). 못 읽으면 `null`.
     * ⚠️ 결과를 기다리므로 반드시 작업 스레드에서 부를 것.
     */
    fun readKorean(bmp: Bitmap, dx: Int, dy: Int, dw: Int, dh: Int, scale: Int = 3): String? {
        val c = crop(bmp, dx, dy, dw, dh, scale) ?: return null
        return try {
            Tasks.await(korean.process(InputImage.fromBitmap(c, 0)), 8, TimeUnit.SECONDS).text
        } catch (e: Exception) {
            null
        } finally {
            c.recycle()
        }
    }

    /**
     * 잘라낸 영역에서 숫자만 뽑는다. 자릿수가 모자라면 오독으로 보고 버린다.
     * ⚠️ 결과를 기다리므로 반드시 작업 스레드에서 부를 것(메인 스레드에서 부르면 멈춘다).
     */
    fun readNumber(bmp: Bitmap, dx: Int, dy: Int, dw: Int, dh: Int, minDigits: Int = 4): Long? {
        val x = Coords.x(dx); val y = Coords.y(dy)
        val w = Coords.x(dx + dw) - x; val h = Coords.y(dy + dh) - y
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > bmp.width || y + h > bmp.height) return null
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
    fun readText(bmp: Bitmap, dx: Int, dy: Int, dw: Int, dh: Int): String? {
        val x = Coords.x(dx); val y = Coords.y(dy)
        val w = Coords.x(dx + dw) - x; val h = Coords.y(dy + dh) - y
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > bmp.width || y + h > bmp.height) return null
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
