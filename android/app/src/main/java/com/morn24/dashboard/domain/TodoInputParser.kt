package com.morn24.dashboard.domain

import java.time.LocalDateTime

data class ParsedTodoInput(val title: String, val date: String?, val time: String?)

/**
 * Kotlin port of web/rules.js parseTodoInput: reads a date and a start time out of a sentence.
 * "내일 오후 3시 병원" gives title "병원", date tomorrow, time "15:00". Verified against the
 * sentences in rule-vectors.json (generated from the JavaScript original).
 */
object TodoInputParser {
    private const val PARTICLE = "(?:에|부터|까지)?"
    private const val AMPM = "(?:(오전|오후|아침|낮|저녁|밤|새벽)\\s*)?"
    private val KDAY = mapOf('일' to 0, '월' to 1, '화' to 2, '수' to 3, '목' to 4, '금' to 5, '토' to 6)

    private val RELATIVE_DAY = Regex("(?<=\\s)(오늘|내일|모레|글피)$PARTICLE(?=\\s)")
    private val WEEKDAY = Regex("(?<=\\s)(?:(이번\\s*주|다음\\s*주|담주)\\s*)?([월화수목금토일])요일$PARTICLE(?=\\s)")
    private val MONTH_DAY_KOREAN = Regex("(?<=\\s)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일$PARTICLE(?=\\s)")
    private val MONTH_DAY_SLASH = Regex("(?<=\\s)(\\d{1,2})/(\\d{1,2})$PARTICLE(?=\\s)")
    private val CLOCK = Regex("(?<=\\s)$AMPM(\\d{1,2}):(\\d{2})$PARTICLE(?=\\s)")
    private val HOUR = Regex("(?<=\\s)$AMPM(\\d{1,2})\\s*시(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?$PARTICLE(?=\\s)")
    private val NOON = Regex("(?<=\\s)정오$PARTICLE(?=\\s)")
    private val SPACES = Regex("\\s+")
    private val MORNING = Regex("오전|아침|새벽")
    private val AFTERNOON = Regex("오후|저녁|밤|낮")

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): ParsedTodoInput {
        val today = now.toLocalDate().toString()
        var rest = " $text "
        var date: String? = null
        var time: String? = null

        fun take(re: Regex, fn: (MatchResult) -> Boolean) {
            if (date != null && time != null) return
            val m = re.find(rest) ?: return
            if (!fn(m)) return
            rest = rest.substring(0, m.range.first) + " " + rest.substring(m.range.last + 1)
        }

        // 날짜
        take(RELATIVE_DAY) { m ->
            date = TodoRules.addDays(today, mapOf("오늘" to 0, "내일" to 1, "모레" to 2, "글피" to 3).getValue(m.groupValues[1]))
            true
        }
        if (date == null) take(WEEKDAY) { m ->
            val prefix = m.groups[1]?.value
            val target = KDAY.getValue(m.groupValues[2][0])
            val cur = TodoRules.dow(today)
            if (prefix == null) {
                date = TodoRules.addDays(today, (target - cur + 7) % 7)
            } else {
                val monday = TodoRules.addDays(today, -((cur + 6) % 7))
                date = TodoRules.addDays(monday, ((target + 6) % 7) + (if (prefix.startsWith("이번")) 0 else 7))
            }
            true
        }
        fun monthDay(month: Int, day: Int): Boolean {
            if (month < 1 || month > 12 || day < 1 || day > 31) return false
            val year = now.year
            var value = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
            if (value < TodoRules.addDays(today, -180)) value = "${year + 1}${value.substring(4)}" // 반년 넘게 지났으면 내년
            date = value
            return true
        }
        if (date == null) take(MONTH_DAY_KOREAN) { m -> monthDay(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        if (date == null) take(MONTH_DAY_SLASH) { m -> monthDay(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }

        // 시간
        fun clock(ampm: String, hour: Int, minute: Int): Boolean {
            var h = hour
            if (h > 24 || minute > 59) return false
            if (MORNING.containsMatchIn(ampm)) {
                if (h == 12) h = 0
            } else if (AFTERNOON.containsMatchIn(ampm)) {
                if (h < 12) h += 12
            } else if (h in 1..6) {
                h += 12 // "3시"처럼 오전/오후가 없으면 1~6시는 오후로 본다
            }
            if (h == 24) h = 0
            time = "${h.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
            return true
        }
        take(CLOCK) { m -> clock(m.groups[1]?.value.orEmpty(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        if (time == null) take(HOUR) { m ->
            val half = m.groups[4] != null
            val minutes = m.groups[3]?.value?.toInt() ?: 0
            clock(m.groups[1]?.value.orEmpty(), m.groupValues[2].toInt(), if (half) 30 else minutes)
        }
        if (time == null) take(NOON) {
            time = "12:00"
            true
        }

        val title = rest.replace(SPACES, " ").trim()
        if (title.isEmpty()) return ParsedTodoInput(text.trim(), null, null)
        if (time != null && date == null) date = today
        return ParsedTodoInput(title, date, time)
    }
}
