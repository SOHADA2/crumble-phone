package com.sohada.crumblephone

/**
 * 일일 던전 — **던전별 컨트롤 타임라인** (2026-10-01).
 *
 * 사장님: *"던전 레벨이 올라가면서 컨트롤 없이 진행하면 깨기가 어려운 지경에 이르렀어.
 * 각 던전별로 몇 초에 움직이고 … 상세 조정이 필요할 것 같아. (PC 모바일 둘 다)"*
 *
 * 예전엔 경험치 던전 하나에 동작 하나(N초 기다렸다 아래로 M초)뿐이었다. 이제 던전마다 적는다.
 * ```
 * 1   아래    2        ← 1초에 아래로 2초
 * 6   왼쪽    1.5
 * 9.5 오른위  1
 * 반복 12               ← 12초마다 처음부터
 * ```
 * ★ **PC `env.ps1` 의 `ConvertFrom-DailyPlan` 과 같은 규칙·같은 문구**로 읽는다.
 *   한쪽에서 다듬은 글을 다른 쪽에 그대로 붙여 넣어도 같게 움직여야 한다.
 * ★ 시간 기준 = [도전하기] 를 누르고 3초 뒤가 0초(예전 '돌진 시작 지연' 과 같은 기준).
 * ★ 좌표는 설계 좌표(1440x3120)다 — `TapService.swipe` 가 `Coords` 로 기기에 맞춘다.
 */
object DailyPlan {
    val DUNGEONS = arrayOf("경험치", "코인", "반죽", "연구석", "룬결정")

    /** 조이스틱: 전장 (720,1200) 을 누른 채 끝점으로 끈다. 경험치 '아래' 로 쓰던 시작점 그대로. */
    val JOY_FROM = intArrayOf(720, 1200)
    val DIRS: LinkedHashMap<String, IntArray> = linkedMapOf(
        "위" to intArrayOf(720, 600),
        "아래" to intArrayOf(720, 2000),
        "왼쪽" to intArrayOf(80, 1200),
        "오른쪽" to intArrayOf(1360, 1200),
        "왼위" to intArrayOf(270, 750),
        "오른위" to intArrayOf(1170, 750),
        "왼아래" to intArrayOf(270, 1650),
        "오른아래" to intArrayOf(1170, 1650)
    )
    private val ALIAS = mapOf(
        "상" to "위", "하" to "아래", "좌" to "왼쪽", "우" to "오른쪽", "왼" to "왼쪽", "오른" to "오른쪽", "오" to "오른쪽",
        "왼쪽위" to "왼위", "오른쪽위" to "오른위", "왼쪽아래" to "왼아래", "오른쪽아래" to "오른아래",
        "위왼쪽" to "왼위", "위오른쪽" to "오른위", "아래왼쪽" to "왼아래", "아래오른쪽" to "오른아래"
    )
    const val MIN_DUR = 0.3     // 이보다 짧으면 끌기가 '탭' 으로 읽힐 수 있다
    const val MAX_DUR = 10.0
    const val MAX_AT = 170.0    // 한 판은 길어야 1분 남짓(실측 22~55초)

    /** `swipe` 는 선형으로 끌어 처음 얼마간이 데드존에 묻힌다 → 그만큼 더 준다(예전과 같은 보정). */
    const val PAD = 1.10

    class Step(val at: Double, val dir: String, val dur: Double)
    class Plan(val steps: List<Step>, val repeat: Double?, val errors: List<String>)

    private fun num(t: String): Double? =
        t.trim().removeSuffix("초에").removeSuffix("초").removeSuffix("에").toDoubleOrNull()

    private fun dir(raw: String): String? {
        var t = raw.trim()
        for (suf in arrayOf("으로", "로")) {
            if (t.length > suf.length && t.endsWith(suf)) { t = t.removeSuffix(suf); break }
        }
        if (DIRS.containsKey(t)) return t
        return ALIAS[t]
    }

    private fun fmt(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty()) "0" else s
    }

    /** 글 → 계획. 못 알아들은 줄은 **건너뛰고 이유를 남긴다**(한 줄 틀렸다고 전부 버리지 않는다). */
    fun parse(text: String?): Plan {
        if (text.isNullOrBlank()) return Plan(emptyList(), null, emptyList())
        val steps = ArrayList<Step>()
        val errs = ArrayList<String>()
        var repeat: Double? = null
        val lines = text.split(Regex("\r?\n"))
        for ((i, raw) in lines.withIndex()) {
            var ln = raw
            val h = ln.indexOf('#'); if (h >= 0) ln = ln.substring(0, h)
            ln = ln.trim()
            if (ln.isEmpty()) continue
            val no = i + 1
            val tok = ln.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (tok[0].startsWith("반복")) {
                val r = if (tok.size >= 2) num(tok[1]) else if (tok[0].length > 2) num(tok[0].substring(2)) else null
                if (r == null || r < 1) { errs.add(no.toString() + "번째 줄: '반복' 뒤에 초를 적어 주세요 (예: 반복 12)"); continue }
                repeat = r; continue
            }
            if (tok.size != 3) { errs.add(no.toString() + "번째 줄: '시작초 방향 길이초' 세 가지를 적어 주세요 — '" + ln + "'"); continue }
            val at = num(tok[0])
            val d = dir(tok[1])
            val dur = num(tok[2])
            if (at == null) { errs.add(no.toString() + "번째 줄: 시작초 '" + tok[0] + "' 를 숫자로 읽지 못했어요"); continue }
            if (d == null) { errs.add(no.toString() + "번째 줄: 방향 '" + tok[1] + "' 를 모르겠어요"); continue }
            if (dur == null) { errs.add(no.toString() + "번째 줄: 길이 '" + tok[2] + "' 를 숫자로 읽지 못했어요"); continue }
            if (at < 0 || at > MAX_AT) { errs.add(no.toString() + "번째 줄: 시작초는 0~" + fmt(MAX_AT) + " 사이로 적어 주세요"); continue }
            if (dur < MIN_DUR || dur > MAX_DUR) { errs.add(no.toString() + "번째 줄: 길이는 " + fmt(MIN_DUR) + "~" + fmt(MAX_DUR) + "초 사이로 적어 주세요"); continue }
            steps.add(Step(at, d, dur))
        }
        val sorted = steps.sortedBy { it.at }
        // 반복 주기가 마지막 동작이 끝나기 전이면 다음 바퀴와 겹친다 → 알려 주고 반복은 끈다.
        if (repeat != null && sorted.isNotEmpty()) {
            val end = sorted.last().at + sorted.last().dur
            if (repeat < end) { errs.add("반복 " + fmt(repeat) + "초가 마지막 동작이 끝나는 " + fmt(end) + "초보다 짧아요 — 반복은 끕니다"); repeat = null }
        }
        if (sorted.isEmpty()) repeat = null
        return Plan(sorted, repeat, errs)
    }

    /**
     * 계획 → 글 (2026-10-01 · 방향키 편집 화면이 저장할 때). PC `ConvertTo-DailyPlanText` 와 같은 모양.
     * 편집은 칸으로 하고 저장은 **엔진이 이미 읽는 글 형식** 그대로 — 엔진·PC 형식은 하나도 안 바뀐다.
     */
    fun toText(steps: List<Step>, repeat: Double): String {
        val lines = steps.sortedBy { it.at }.map { fmt(it.at) + " " + it.dir + " " + fmt(it.dur) }.toMutableList()
        if (repeat > 0 && lines.isNotEmpty()) lines.add("반복 " + fmt(repeat))
        return lines.joinToString("\n")
    }

    /** 사람이 읽는 한 줄 요약(편집 창 미리보기·로그에 같이 쓴다). PC `Format-DailyPlan` 과 같은 모양. */
    fun format(p: Plan): String {
        if (p.steps.isEmpty()) return "조작 안 함 (자동 전투만)"
        var s = p.steps.joinToString(" → ") { fmt(it.at) + "초 " + it.dir + " " + fmt(it.dur) + "초" }
        if (p.repeat != null) s += " → (" + fmt(p.repeat) + "초마다 반복)"
        return s
    }

    /**
     * 던전 하나의 글. ★ 한 번도 저장한 적이 없으면 **옛 설정에서 옮겨 온다** —
     * 쓰던 '지연·돌진' 값이 업데이트하자마자 사라지면 안 된다. 경험치 = '{지연} 아래 {돌진}'.
     */
    fun load(name: String): String {
        val saved = Prefs.dailyPlan(name)
        if (saved != null) return saved
        if (name == "경험치" && Prefs.dailyChargeMs > 0) {
            return fmt(Prefs.dailyDelayMs / 1000.0) + " 아래 " + fmt(Prefs.dailyChargeMs / 1000.0)
        }
        return ""
    }

    fun save(name: String, text: String) = Prefs.setDailyPlan(name, text.trim())

    /** 설정 줄에 띄우는 짧은 요약 — 어느 던전에 몇 개가 들어 있나. */
    fun summary(): String = DUNGEONS.joinToString(" · ") {
        val n = parse(load(it)).steps.size
        if (n > 0) "$it $n" else "$it -"
    }
}
