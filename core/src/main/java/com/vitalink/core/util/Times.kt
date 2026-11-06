package com.vitalink.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Times {
    private val hourFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("es-MX"))
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-MX"))

    fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

    fun isoIn(minutes: Long): String = Instant.now().plus(Duration.ofMinutes(minutes)).truncatedTo(ChronoUnit.SECONDS).toString()

    /** Acepta los formatos de PostgREST y de Realtime (con o sin zona, con espacio o con 'T'). */
    fun parse(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        val text = iso.trim().replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(text).toInstant() }
            .recoverCatching { Instant.parse(text) }
            .recoverCatching { OffsetDateTime.parse(text.replace(Regex("([+-]\\d{2})$"), "$1:00")).toInstant() }
            .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }
            .getOrNull()
    }

    fun millis(iso: String?): Long = parse(iso)?.toEpochMilli() ?: 0L

    fun zone(timezone: String?): ZoneId = runCatching { ZoneId.of(timezone ?: "") }.getOrDefault(ZoneId.systemDefault())

    fun hour(iso: String?, timezone: String?): String =
        parse(iso)?.atZone(zone(timezone))?.format(hourFormat) ?: "--:--"

    fun hourNow(timezone: String?): String = Instant.now().atZone(zone(timezone)).format(hourFormat)

    fun today(timezone: String?): String =
        Instant.now().atZone(zone(timezone)).format(dayFormat).replaceFirstChar { it.uppercase() }

    fun isToday(iso: String?, timezone: String?): Boolean {
        val instant = parse(iso) ?: return false
        val z = zone(timezone)
        return instant.atZone(z).toLocalDate() == Instant.now().atZone(z).toLocalDate()
    }

    fun relative(iso: String?, now: Long = System.currentTimeMillis()): String {
        val t = parse(iso)?.toEpochMilli() ?: return ""
        return relativeMillis(now - t)
    }

    fun relativeMillis(diff: Long): String {
        val s = diff / 1000
        return when {
            s < 0 -> "en ${-s / 60 + 1} min"
            s < 10 -> "ahora"
            s < 60 -> "hace $s s"
            s < 3600 -> "hace ${s / 60} min"
            s < 86_400 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86_400} d"
        }
    }

    fun untilLabel(iso: String?, now: Long = System.currentTimeMillis()): String {
        val t = parse(iso)?.toEpochMilli() ?: return ""
        val min = (t - now) / 60_000
        return when {
            min <= 0 -> "ahora"
            min < 60 -> "en $min min"
            else -> "en ${min / 60} h ${min % 60} min"
        }
    }
}
