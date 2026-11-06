package com.myproductivity.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ActivityParserTest {
    private val today = LocalDate.of(2026, 11, 1) // a Sunday

    private fun one(text: String): ParsedActivity {
        val r = ActivityParser.parse(text, today)
        assertEquals("expected exactly one activity for: $text", 1, r.size)
        return r[0]
    }

    @Test fun hinglishGym() {
        val p = one("Aaj 45 min gym kiya")
        assertEquals("Fitness & Health", p.category)
        assertEquals(45, p.actualMin)
        assertEquals("Completed", p.status)
        assertEquals(today, p.date)
    }

    @Test fun hourAndMinutes() {
        assertEquals(90, one("Studied Kotlin for 1 hour and 30 minutes").actualMin)
    }

    @Test fun hinglishFractions() {
        assertEquals(90, one("dedh ghanta padhai kiya").actualMin)
    }

    @Test fun plannedAndActual() {
        val p = one("Planned 2 hours coding, actual 90 minutes")
        assertEquals(120, p.plannedMin)
        assertEquals(90, p.actualMin)
    }

    @Test fun futureHinglishIsPlannedTomorrow() {
        val p = one("kal 30 min yoga karunga")
        assertEquals("Planned", p.status)
        assertEquals(today.plusDays(1), p.date)
        assertEquals(30, p.plannedMin)
    }

    @Test fun multipleActivities() {
        val r = ActivityParser.parse("Morning walk 30 min, then 1 hour Kotlin course, then team meeting 45 min", today)
        assertEquals(3, r.size)
        assertEquals(listOf("Fitness & Health", "Career & Learning", "Work"), r.map { it.category })
    }

    @Test fun inProgressAndPriority() {
        val p = one("Working on project report high priority")
        assertEquals("In Progress", p.status)
        assertEquals("High", p.priority)
    }

    @Test fun yesterday() {
        assertEquals(today.minusDays(1), one("Meditation 20 minutes yesterday").date)
    }

    @Test fun ambiguousKalAsksForCorrection() {
        val p = one("kal gym 30 min")
        assertTrue(p.issues.any { it.field == "date" && it.blocking })
    }

    @Test fun ambiguousCategoryAsksForCorrection() {
        val p = one("gym and Kotlin 40 min")
        assertNull(p.category)
        assertTrue(p.issues.any { it.field == "category" && it.blocking })
    }

    @Test fun unknownCategoryAsksForCorrection() {
        val p = one("something 20 min")
        assertNull(p.category)
        assertTrue(p.issues.any { it.field == "category" && it.blocking })
    }

    @Test fun emptyInput() {
        assertTrue(ActivityParser.parse("   ", today).isEmpty())
    }
}
