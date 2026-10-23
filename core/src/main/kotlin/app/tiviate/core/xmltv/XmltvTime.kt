package app.tiviate.core.xmltv

/**
 * Parses XMLTV timestamps (`YYYYMMDDhhmm[ss] [±hhmm]`) to epoch milliseconds.
 * Implemented without java.time so it runs on API 23 without desugaring.
 */
object XmltvTime {

    fun parse(value: String): Long? {
        val s = value.trim()
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        if (i != 12 && i != 14) return null
        val year = s.substring(0, 4).toInt()
        val month = s.substring(4, 6).toInt()
        val day = s.substring(6, 8).toInt()
        val hour = s.substring(8, 10).toInt()
        val minute = s.substring(10, 12).toInt()
        val second = if (i == 14) s.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        val rest = s.substring(i).trim()
        val offsetMinutes = when {
            rest.isEmpty() || rest.equals("UTC", true) || rest.equals("GMT", true) || rest == "Z" -> 0
            (rest[0] == '+' || rest[0] == '-') && rest.length >= 5 && rest.substring(1, 5).all { it.isDigit() } -> {
                val sign = if (rest[0] == '-') -1 else 1
                sign * (rest.substring(1, 3).toInt() * 60 + rest.substring(3, 5).toInt())
            }
            else -> return null
        }
        val days = daysFromCivil(year, month, day)
        val localSeconds = days * 86_400L + hour * 3_600L + minute * 60L + second
        return (localSeconds - offsetMinutes * 60L) * 1_000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
