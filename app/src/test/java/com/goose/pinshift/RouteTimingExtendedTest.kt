package com.goose.pinshift

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RouteTimingExtendedTest {
    @Test fun roundTripsForRealisticJourneys() {
        for (speed in listOf(1.0, 5.0, 16.0, 40.0, 110.0))
            for (minutes in listOf(5.0, 15.0, 40.0, 120.0, 240.0)) {
                val metres = RouteTiming.targetMetres(speed, minutes)
                assertEquals(minutes, RouteTiming.minutesFor(metres, speed), 1e-9)
                assertEquals(speed * minutes / 60.0, metres / 1000.0, 1e-9)
            }
    }

    @Test fun fractionIsMonotonicAcrossFullRoute() {
        val fractions = (0..10000).map { RouteTiming.distanceFraction(it / 10000.0) }
        assertEquals(0.0, fractions.first(), 1e-12)
        assertEquals(1.0, fractions.last(), 1e-12)
        assertTrue(fractions.all { it.isFinite() && it in 0.0..1.0 })
        assertTrue(fractions.zipWithNext().all { (a,b) -> b >= a })
    }

    @Test fun progressClampsBeforeAndAfterRoute() {
        assertEquals(0.0, RouteTiming.distanceFraction(-42.0), 0.0)
        assertEquals(1.0, RouteTiming.distanceFraction(42.0), 0.0)
    }

    @Test fun speedVariationPreservesTotalDistanceButVariesInstantaneousSpeed() {
        val fractions = (0..500).map { RouteTiming.distanceFraction(it / 500.0) }
        val multipliers = fractions.zipWithNext().map { (a,b) -> (b-a)*500 }
        assertEquals(1.0, multipliers.average(), 1e-6)
        assertTrue("Should vary speed", multipliers.maxOrNull()!! > 1.15)
        assertTrue("Should vary speed", multipliers.minOrNull()!! < 0.85)
        assertTrue("Must never travel backwards", multipliers.minOrNull()!! >= 0.0)
    }

    @Test fun invalidSpeedAndTimeAreRejected() {
        for (speed in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try { RouteTiming.targetMetres(speed, 10.0); fail("Accepted speed: $speed") }
            catch (_: IllegalArgumentException) { }
        }
        for (minutes in listOf(-1.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try { RouteTiming.targetMetres(5.0, minutes); fail("Accepted minutes: $minutes") }
            catch (_: IllegalArgumentException) { }
        }
        try { RouteTiming.minutesFor(-1.0, 5.0); fail("Accepted negative distance") }
        catch (_: IllegalArgumentException) { }
    }
}
