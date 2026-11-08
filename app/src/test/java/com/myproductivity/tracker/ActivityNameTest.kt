package com.myproductivity.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityNameTest {
    @Test
    fun capitalizesOnlyTheFirstLetterWithoutChangingOtherLetters() {
        assertEquals("Read a Book / learn Kotlin", "read a Book / learn Kotlin".capitalizeWords())
    }

    @Test
    fun preservesWhitespaceAndPunctuation() {
        assertEquals("  Morning walk! ", "  morning walk! ".capitalizeWords())
    }
}
