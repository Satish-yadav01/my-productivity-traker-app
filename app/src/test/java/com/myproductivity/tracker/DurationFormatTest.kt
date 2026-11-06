package com.myproductivity.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test fun formatsMinutesAndHoursWithReadableUnits() {
        assertEquals("35 min", formatMinutes(35))
        assertEquals("2 hr", formatMinutes(120))
        assertEquals("2 hr 15 min", formatMinutes(135))
    }
}
