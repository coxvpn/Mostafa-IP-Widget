package app.myipwidget

import java.util.Calendar

/** Today's date in the Persian (Jalali) calendar, written with Persian digits. */
object PersianDate {
    private val MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )
    private val DAYS = arrayOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه")

    /** (year, month, day) in the Persian calendar for a Gregorian date. */
    fun fromGregorian(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val offsets = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + offsets[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }

    private fun digits(value: Int): String =
        value.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    /** For the widget: "دوشنبه ۲۵ آبان". */
    fun short(now: Calendar = Calendar.getInstance()): String {
        val (_, month, day) = fromGregorian(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val weekday = DAYS[now.get(Calendar.DAY_OF_WEEK) % 7]   // Saturday = 7 -> 0
        return "$weekday ${digits(day)} ${MONTHS[month - 1]}"
    }

    /** For the app: "دوشنبه ۲۵ آبان ۱۴۰۵  -  2026-11-16". */
    fun full(now: Calendar = Calendar.getInstance()): String {
        val (year, _, _) = fromGregorian(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val gregorian = "%04d-%02d-%02d".format(
            java.util.Locale.US, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
        )
        return short(now) + " " + digits(year) + "  -  " + gregorian
    }
}
