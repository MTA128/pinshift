package com.goose.pinshift

import org.junit.Assert.*
import org.junit.Test

class RouteTimingTest {
    @Test fun fortyMinutesWalkingNeedsLongerPath() {
        assertEquals(3333.3333,RouteTiming.targetMetres(5.0,40.0),0.01)
        assertEquals(40.0,RouteTiming.minutesFor(3333.3333,5.0),0.001)
    }
    @Test fun variationKeepsEndpointsAndDoesNotMoveBackwards() {
        assertEquals(0.0,RouteTiming.distanceFraction(0.0),1e-9)
        assertEquals(1.0,RouteTiming.distanceFraction(1.0),1e-9)
        val values=(0..100).map { RouteTiming.distanceFraction(it/100.0) }
        assertTrue(values.zipWithNext().all { (a,b) -> b>=a })
    }
    @Test fun longerDurationDoesNotIncreaseSpeed() {
        assertEquals(2*RouteTiming.targetMetres(16.0,40.0),
            RouteTiming.targetMetres(16.0,80.0),0.001)
    }
}
