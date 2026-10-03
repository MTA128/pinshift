package com.goose.pinshift

import org.junit.Assert.*
import org.junit.Test

class RouteFitTest {
    private val a = RouteFit.Point(51.5074, -0.1278)
    private val b = RouteFit.Point(51.5120, -0.1110)
    private val c = RouteFit.Point(51.5230, -0.1020)

    @Test fun targetLengthKeepsAverageSpeedFixed() {
        assertEquals(3333.3333333, RouteFit.targetDistanceMetres(5.0, 40), 0.001)
        assertEquals(10000.0, RouteFit.targetDistanceMetres(30.0, 20), 0.0001)
    }

    @Test fun accurateAndInaccurateDistancesHaveExplicitTolerance() {
        assertTrue(RouteFit.accepted(3350.0, 3333.333))
        assertFalse(RouteFit.accepted(4200.0, 3333.333))
        assertFalse(RouteFit.accepted(Double.NaN, 3333.333))
        assertFalse(RouteFit.accepted(500.0, 0.0))
    }

    @Test fun insertedDetourPreservesAllManualStops() {
        val points = listOf(a, b, c)
        val result = RouteFit.detour(points, 650.0, clockwise = true)
        assertEquals(4, result.size)
        assertEquals(0, result.indexOf(a))
        assertEquals(3, result.indexOf(c))
        assertTrue(result.indexOf(b) > result.indexOf(a))
        assertTrue(result.indexOf(b) < result.indexOf(c))
        assertTrue(result.all { RouteGeometry.valid(it.lat, it.lon) })
    }

    @Test fun oppositeSidesAreNotTheSameRoadRequest() {
        val stops = listOf(a, c)
        val left = RouteFit.detour(stops, 750.0, clockwise = false)
        val right = RouteFit.detour(stops, 750.0, clockwise = true)
        assertEquals(3, left.size)
        assertEquals(3, right.size)
        assertNotEquals(left[1], right[1])
    }

    @Test fun aReturnToStartCreatesLoopCandidates() {
        val stops = listOf(a, a)
        val loop = RouteFit.detour(stops, 500.0, clockwise = true)
        assertEquals(4, loop.size)
        assertEquals(a, loop.first())
        assertEquals(a, loop.last())
        assertTrue(RouteFit.legMetres(loop[1], a) > 400)
        assertTrue(RouteFit.legMetres(loop[2], a) > 400)
    }

    @Test fun candidateRadiusIncreasesWithRequestedLength() {
        val stops = listOf(a, c)
        val baseline = RouteFit.legMetres(a, c) * 1.15
        val near = RouteFit.startingRadius(stops, baseline, baseline + 350)
        val far = RouteFit.startingRadius(stops, baseline, baseline + 1500)
        assertTrue(far > near)
        assertTrue(near >= 80)
    }

    @Test fun invalidStopsOrRadiusFailFast() {
        try { RouteFit.detour(listOf(a), 100.0, clockwise = true); fail() }
        catch (_: IllegalArgumentException) {}
        try { RouteFit.detour(listOf(a, c), -1.0, clockwise = true); fail() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun projectingAcrossDateLineKeepsLongitudeNormal() {
        val start = RouteFit.Point(0.0, 179.999)
        val across = RouteFit.destination(start, 90.0, 5000.0)
        assertTrue(across.lon in -180.0..180.0)
        assertTrue(across.lon < -179.0)
    }
}
