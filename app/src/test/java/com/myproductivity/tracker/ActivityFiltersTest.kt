package com.myproductivity.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityFiltersTest {
    private val activities = listOf(
        Activity("1", "Study", "Career & Learning", "2026-11-06", 60, 30, "High", "Completed"),
        Activity("2", "Walk", "Fitness & Health", "2026-11-06", 30, null, "Low", "Planned"),
        Activity("3", "Project", "Work", "2026-11-05", 90, 45, "High", "In Progress"),
        Activity("4", "Break", "Personal Tasks", "2026-11-05", null, null, "Medium", "Planned")
    )

    @Test fun combinesCategoryStatusPriorityAndPlannedDurationFilters() {
        val result = filterActivities(
            activities,
            ActivityFilters(
                categories = setOf("Career & Learning", "Work"),
                statuses = setOf("Completed", "In Progress"),
                priorities = setOf("High"),
                minPlannedMinutes = 45,
                maxPlannedMinutes = 90
            )
        )
        assertEquals(listOf("1", "3"), result.map { it.clientId })
    }

    @Test fun plannedDurationRangeExcludesActivitiesWithoutPlannedMinutes() {
        val result = filterActivities(
            activities,
            ActivityFilters(minPlannedMinutes = 30, maxPlannedMinutes = 60)
        )
        assertEquals(listOf("1", "2"), result.map { it.clientId })
    }
}
