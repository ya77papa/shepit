package io.github.ya77papa.shepit.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Статус співрозмовника: on — в мережі; at — востаннє (мс); hidden — ховає свій час. */
data class PresenceInfo(val on: Boolean = false, val at: Long? = null, val hidden: Boolean = false)

object PresenceText {
    /** «в мережі», «востаннє 15 хв тому», «востаннє вчора о 21:40»… — як у веб-версії. */
    fun of(p: PresenceInfo?, now: Long = System.currentTimeMillis()): String? {
        p ?: return null
        if (p.on) return "в мережі"
        val at = p.at
        if (p.hidden || at == null) return "востаннє нещодавно"
        val min = (now - at) / 60_000
        if (min < 1) return "востаннє щойно"
        if (min < 60) return "востаннє $min хв тому"
        val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
        fun day(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val days = Math.round((day(now) - day(at)) / 86_400_000.0)
        if (days == 0L) return "востаннє сьогодні о $hm"
        if (days == 1L) return "востаннє вчора о $hm"
        val sameYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.YEAR)
        val uk = Locale.forLanguageTag("uk")
        return "востаннє " + SimpleDateFormat(if (sameYear) "d MMMM" else "d MMMM yyyy", uk).format(Date(at))
    }
}
