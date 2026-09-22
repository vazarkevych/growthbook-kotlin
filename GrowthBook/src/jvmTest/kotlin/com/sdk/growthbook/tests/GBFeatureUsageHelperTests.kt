package com.sdk.growthbook.tests

import com.sdk.growthbook.evaluators.GBFeatureUsageHelper
import com.sdk.growthbook.model.GBNull
import com.sdk.growthbook.model.GBString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GBFeatureUsageHelperTests {

    @Test
    fun reportsOncePerValue() {
        val helper = GBFeatureUsageHelper()

        assertTrue(helper.shouldReport("flag", GBString("a")))
        assertFalse(helper.shouldReport("flag", GBString("a")))
        assertTrue(helper.shouldReport("flag", GBString("b")))
        assertFalse(helper.shouldReport("flag", GBString("b")))
    }

    @Test
    fun returningToAPreviousValueReportsAgain() {
        // The helper remembers the last value, not every value ever seen: a flag flipping back is
        // a change like any other.
        val helper = GBFeatureUsageHelper()

        assertTrue(helper.shouldReport("flag", GBString("a")))
        assertTrue(helper.shouldReport("flag", GBString("b")))
        assertTrue(helper.shouldReport("flag", GBString("a")))
    }

    @Test
    fun keysAreIndependent() {
        val helper = GBFeatureUsageHelper()

        assertTrue(helper.shouldReport("flag-a", GBString("v")))
        assertTrue(helper.shouldReport("flag-b", GBString("v")))
        assertFalse(helper.shouldReport("flag-a", GBString("v")))
    }

    @Test
    fun nullAndAbsentAreDifferentStates() {
        // A feature that resolves to no value at all must still report the first time: without the
        // membership check, an unrecorded key and a recorded null would both compare equal to null.
        val helper = GBFeatureUsageHelper()

        assertTrue(helper.shouldReport("missing", null))
        assertFalse(helper.shouldReport("missing", null))
        assertTrue(helper.shouldReport("missing", GBNull))
        assertFalse(helper.shouldReport("missing", GBNull))
    }

    @Test
    fun resetMakesEveryKeyReportAgain() {
        val helper = GBFeatureUsageHelper()

        assertTrue(helper.shouldReport("flag", GBString("a")))
        assertFalse(helper.shouldReport("flag", GBString("a")))

        helper.reset()

        assertTrue(helper.shouldReport("flag", GBString("a")))
    }
}
