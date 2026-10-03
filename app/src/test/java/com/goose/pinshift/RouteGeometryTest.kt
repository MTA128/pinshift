package com.goose.pinshift

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RouteGeometryTest {
    @Test fun coordinateValidationIncludesBoundaries() {
        assertTrue(RouteGeometry.valid(-90.0, -180.0))
        assertTrue(RouteGeometry.valid(90.0, 180.0))
        assertFalse(RouteGeometry.valid(90.00001, 0.0))
        assertFalse(RouteGeometry.valid(0.0, -180.001))
        assertFalse(RouteGeometry.valid(Double.NaN, 0.0))
        assertFalse(RouteGeometry.valid(0.0, Double.POSITIVE_INFINITY))
    }

    @Test fun distanceForKnownEquatorialSegment() {
        val a = Pair(0.0, 0.0)
        val b = Pair(0.0, 1.0)
        assertEquals(111195.0, RouteGeometry.segmentMetres(a, b), 150.0)
        assertEquals(0.0, RouteGeometry.segmentMetres(a, a), 1e-8)
    }

    @Test fun midpointFollowsMetresNotWaypointIndices() {
        val points = listOf(Pair(51.0, 0.0), Pair(51.0, 0.001), Pair(51.0, 0.004))
        val position = RouteGeometry.atFraction(points, 0.5)
        assertEquals(51.0, position.latitude, 0.0002)
        assertTrue(position.longitude > 0.0015 && position.longitude < 0.0025)
    }

    @Test fun roadProgressStartsAndEndsExactlyAtWaypoints() {
        val points = listOf(Pair(51.5074, -0.1278), Pair(51.508, -0.123), Pair(51.509, -0.120))
        val first = RouteGeometry.atFraction(points, 0.0)
        val last = RouteGeometry.atFraction(points, 1.0)
        assertEquals(points.first().first, first.latitude, 0.0)
        assertEquals(points.first().second, first.longitude, 0.0)
        assertEquals(points.last().first, last.latitude, 0.0)
        assertEquals(points.last().second, last.longitude, 0.0)
    }

    @Test fun datelineUsesShorterPathAndAvoidsWorldCrossing() {
        val points = listOf(Pair(0.0, 179.9), Pair(0.0, -179.9))
        assertEquals(22239.0, RouteGeometry.pathMetres(points), 150.0)
        val midpoint = RouteGeometry.atFraction(points, 0.5)
        assertTrue("Should stay near date line", abs(midpoint.longitude) > 179.8)
        assertTrue(midpoint.latitude.isFinite())
    }

    @Test fun duplicatePointsDoNotDivideByZero() {
        val points = listOf(Pair(51.0, 0.0), Pair(51.0, 0.0), Pair(51.0, 0.004))
        val midpoint = RouteGeometry.atFraction(points, 0.5)
        assertTrue(midpoint.longitude > 0.0015 && midpoint.longitude < 0.0025)
        assertTrue(midpoint.bearing.isFinite())
    }

    @Test fun emptyAndNonFinitePathsAreRejected() {
        try { RouteGeometry.atFraction(emptyList(), 0.5); fail("Accepted empty path") }
        catch (_: IllegalArgumentException) { }
        try { RouteGeometry.atFraction(listOf(Pair(0.0, 0.0), Pair(1.0, 1.0)), Double.NaN); fail("Accepted NaN progress") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun speedMultiplierDoesNotReverseAndHasCorrectAverage() {
        val speeds = (0..1000).map { RouteTiming.speedMultiplier(it / 1000.0) }
        assertTrue(speeds.all { it in 0.82 - 0.00001..1.18 + 0.00001 })
        assertEquals(1.0, speeds.average(), 1e-5)
    }
}
