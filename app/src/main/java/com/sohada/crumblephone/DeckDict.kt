package com.sohada.crumblephone

import android.content.Context
import java.io.File

/**
 * 쿠키 **사전** — 이름 + 초상화 지문.
 *
 * ## 왜 지문까지 들고 있나
 * 처음엔 '목록 몇 번째 칸'만 기억했는데 **그게 틀렸다.**
 * 목록은 **편성중인 쿠키가 맨 앞으로 올라오는** 정렬이라 프리셋을 바꾸면 순서가 통째로 달라진다.
 * 실측: 프리셋5 에서 1·2·5번이던 쿠키가 프리셋2 에서는 3·4·1번이었다.
 * 사용자 지적대로 **쿠키가 늘거나 레벨이 오르면 또 바뀐다.**
 *
 * 그래서 순번은 버리고 **그림으로 찾는다.** 이름은 사용자가 적기 위한 것이고,
 * 실제로 어느 칸을 누를지는 매번 화면을 보고 정한다.
 *
 * 이름은 설정에, 지문은 파일에 둔다(73마리 × 832바이트 ≈ 59KB).
 */
object DeckDict {

    private const val FILE = "deck_thumbs.bin"

    /** 목록 순서대로의 이름. 지문과 같은 순서다. */
    val names: List<String>
        get() = Prefs.deckDict.split("\n").filter { it.isNotBlank() }

    private var cache: Array<FloatArray>? = null

    fun thumbs(ctx: Context): Array<FloatArray> {
        cache?.let { return it }
        val f = File(ctx.filesDir, FILE)
        val n = Screen.THUMB_W * Screen.THUMB_H
        if (!f.exists()) return emptyArray()
        val raw = try { f.readBytes() } catch (e: Exception) { return emptyArray() }
        val count = raw.size / n
        val out = Array(count) { FloatArray(n) }
        var i = 0
        for (k in 0 until count) for (j in 0 until n) out[k][j] = (raw[i++].toInt() and 0xFF).toFloat()
        cache = out
        return out
    }

    /** 지문을 저장한다. 0~255 로 눌러 담는다 — 비교는 정규화해서 하므로 정밀도가 필요 없다. */
    fun saveThumbs(ctx: Context, list: List<FloatArray>) {
        val n = Screen.THUMB_W * Screen.THUMB_H
        val raw = ByteArray(list.size * n)
        var i = 0
        for (t in list) for (j in 0 until n) {
            val v = t[j].toInt().coerceIn(0, 255)
            raw[i++] = v.toByte()
        }
        try { File(ctx.filesDir, FILE).writeBytes(raw) } catch (e: Exception) { Bot.log("지문 저장 실패: " + e.message) }
        cache = null
    }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (e: Exception) { }
        cache = null
    }

    /** 이름과 지문 수가 맞나. 하나만 있으면 못 쓴다. */
    fun ready(ctx: Context): Boolean {
        val t = thumbs(ctx)
        return t.isNotEmpty() && t.size == names.size
    }

    /**
     * 화면에서 뽑은 지문이 **몇 번 쿠키인가**. 못 알아보면 -1.
     *
     * ★ 찾는 쿠키들하고만 대 보면 안 된다 — 엉뚱한 쿠키가 0.66 까지 나온 적이 있다.
     *   **사전 전체와 대 보고 1등을 고른 뒤**, 그 1등이 충분히 높고 2등과 벌어져 있을 때만 인정한다.
     *   실측 여유: 맞는 짝 0.75~0.96 / 다른 쿠키 최대 0.67.
     */
    fun identify(ctx: Context, shots: List<FloatArray>): Int {
        val dict = thumbs(ctx)
        if (dict.isEmpty()) return -1
        var b1 = -2f; var b2 = -2f; var best = -1
        for (k in dict.indices) {
            var s = -2f
            for (sh in shots) { val v = Screen.thumbScore(sh, dict[k]); if (v > s) s = v }
            if (s > b1) { b2 = b1; b1 = s; best = k } else if (s > b2) b2 = s
        }
        return if (b1 >= MIN_SCORE && b1 - b2 >= MIN_GAP) best else -1
    }

    const val MIN_SCORE = 0.55f   // 이보다 낮으면 그림을 못 알아본 것으로 본다
    const val MIN_GAP = 0.06f     // 2등과 이만큼은 벌어져야 확신한다
}
