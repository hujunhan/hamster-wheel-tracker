package com.hujunhan.hamsterwheeltracker.persistence

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class ReportingWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
)

object ReportingNight {
    private val boundary = LocalTime.of(18, 0)

    fun containing(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): ReportingWindow {
        val current = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val date = if (current.toLocalTime() >= boundary) current.toLocalDate() else current.toLocalDate().minusDays(1)
        return forDate(date, zoneId)
    }

    fun previous(window: ReportingWindow, daysBack: Long, zoneId: ZoneId = ZoneId.systemDefault()): ReportingWindow {
        require(daysBack >= 0)
        val date = Instant.ofEpochMilli(window.startEpochMs).atZone(zoneId).toLocalDate().minusDays(daysBack)
        return forDate(date, zoneId)
    }

    private fun forDate(date: java.time.LocalDate, zoneId: ZoneId): ReportingWindow {
        val start = date.atTime(boundary).atZone(zoneId).toInstant().toEpochMilli()
        val end = date.plusDays(1).atTime(boundary).atZone(zoneId).toInstant().toEpochMilli()
        return ReportingWindow(start, end)
    }
}
