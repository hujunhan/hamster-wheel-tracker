package com.hujunhan.hamsterwheeltracker.persistence

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReportingNightTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `before 18 belongs to previous reporting date`() {
        val now = ZonedDateTime.of(2026, 9, 2, 17, 59, 0, 0, zone).toInstant().toEpochMilli()
        val window = ReportingNight.containing(now, zone)

        assertEquals(epoch(2026, 9, 1, 18), window.startEpochMs)
        assertEquals(epoch(2026, 9, 2, 18), window.endEpochMs)
    }

    @Test
    fun `after 18 starts a new reporting night`() {
        val now = ZonedDateTime.of(2026, 9, 2, 18, 1, 0, 0, zone).toInstant().toEpochMilli()
        val window = ReportingNight.containing(now, zone)

        assertEquals(epoch(2026, 9, 2, 18), window.startEpochMs)
        assertEquals(epoch(2026, 9, 3, 18), window.endEpochMs)
    }

    @Test
    fun `window follows local DST instead of assuming 24 epoch hours`() {
        val now = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val window = ReportingNight.containing(now, zone)

        assertEquals(epoch(2026, 3, 7, 18), window.startEpochMs)
        assertEquals(epoch(2026, 3, 8, 18), window.endEpochMs)
        assertEquals(23L * 60L * 60L * 1000L, window.endEpochMs - window.startEpochMs)
    }

    private fun epoch(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
}
