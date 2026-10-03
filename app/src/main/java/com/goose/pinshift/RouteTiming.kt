package com.goose.pinshift

import kotlin.math.cos
import kotlin.math.PI

/** Trip length is governed by average speed and requested duration. */
object RouteTiming {
    fun targetMetres(kmh: Double, minutes: Double): Double {
        require(kmh > 0 && kmh.isFinite() && minutes > 0 && minutes.isFinite())
        return kmh * 1000.0 * minutes / 60.0
    }
    fun minutesFor(metres: Double, kmh: Double): Double {
        require(metres >= 0 && metres.isFinite() && kmh > 0 && kmh.isFinite())
        return metres / (kmh * 1000.0 / 60.0)
    }
    /** Starts and ends at the correct position and preserves the overall mean speed. */
    fun distanceFraction(elapsedFraction: Double): Double {
        val x = elapsedFraction.coerceIn(0.0,1.0)
        return (x + 0.18 / (2.0 * PI) * (1 - cos(2.0 * PI * x))).coerceIn(0.0,1.0)
    }
    /** Derivative of distanceFraction with respect to elapsed time. */
    fun speedMultiplier(elapsedFraction: Double): Double {
        val x = elapsedFraction.coerceIn(0.0, 1.0)
        return 1.0 + 0.18 * kotlin.math.sin(2.0 * PI * x)
    }
}
