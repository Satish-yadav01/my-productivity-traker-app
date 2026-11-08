package com.myproductivity.tracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class ReminderPolicyTest {
    @Test
    fun newActivitiesDefaultToTwoHourReminders() {
        assertEquals(120, ReminderScheduler.defaultDelayMinutes())
    }

    @Test
    fun seekBarSelectsOneMinuteStepsWithinTwelveHours() {
        assertEquals(1, ReminderScheduler.delayForSeekProgress(0))
        assertEquals(60, ReminderScheduler.delayForSeekProgress(59))
        assertEquals(120, ReminderScheduler.delayForSeekProgress(119))
        assertEquals(720, ReminderScheduler.delayForSeekProgress(719))
        assertEquals(1, ReminderScheduler.delayForSeekProgress(-1))
        assertEquals(720, ReminderScheduler.delayForSeekProgress(720))
    }

    @Test
    fun formatsReminderDelays() {
        assertEquals("1 min", ReminderScheduler.formatDelay(1))
        assertEquals("15 min", ReminderScheduler.formatDelay(15))
        assertEquals("1 hr", ReminderScheduler.formatDelay(60))
        assertEquals("1 hr 30 min", ReminderScheduler.formatDelay(90))
    }

    @Test
    fun formatsReminderTimestampForActivityCard() {
        assertEquals("8:15 PM", ReminderScheduler.formatReminderAt("2026-11-06 20:15"))
        assertEquals(null, ReminderScheduler.formatReminderAt("invalid"))
    }

    @Test
    fun reminderForTomorrowNeverFiresOnThePlanningDate() {
        val now = LocalDateTime.of(2026, 11, 8, 12, 0)
            .atZone(Config.ZONE).toInstant().toEpochMilli()
        val trigger = ReminderScheduler.activityReminderTriggerAt(now, "2026-11-09", 60)
        assertEquals(
            LocalDateTime.of(2026, 11, 9, 13, 0),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(trigger), Config.ZONE)
        )
    }

    @Test
    fun longReminderDelayStillUsesThePlannedDate() {
        val now = LocalDateTime.of(2026, 11, 8, 21, 0)
            .atZone(Config.ZONE).toInstant().toEpochMilli()
        val trigger = ReminderScheduler.activityReminderTriggerAt(now, "2026-11-09", 720)
        assertEquals(
            LocalDateTime.of(2026, 11, 9, 9, 0),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(trigger), Config.ZONE)
        )
    }

    @Test
    fun reminderForTodayStillUsesSelectedDelay() {
        val now = LocalDateTime.of(2026, 11, 8, 12, 0)
            .atZone(Config.ZONE).toInstant().toEpochMilli()
        val trigger = ReminderScheduler.activityReminderTriggerAt(now, "2026-11-08", 60)
        assertEquals(
            LocalDateTime.of(2026, 11, 8, 13, 0),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(trigger), Config.ZONE)
        )
    }

    @Test
    fun defaultTwoHourReminderIsFormattedForTheSpreadsheet() {
        val now = LocalDateTime.of(2026, 11, 8, 12, 0)
            .atZone(Config.ZONE).toInstant().toEpochMilli()
        assertEquals(
            "2026-11-08 14:00",
            ReminderScheduler.reminderAt("2026-11-08", ReminderScheduler.defaultDelayMinutes(), now)
        )
    }
}
