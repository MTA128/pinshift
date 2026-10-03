package com.goose.pinshift

import kotlin.math.*

/** Testable, platform-independent geographic interpolation used by playback. */
object RouteGeometry {
    data class Position(val latitude: Double, val longitude: Double, val bearing: Float)

    fun valid(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun segmentMetres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        require(valid(a.first, a.second) && valid(b.first, b.second))
        val phi1 = Math.toRadians(a.first)
        val phi2 = Math.toRadians(b.first)
        val dLat = phi2 - phi1
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).pow(2.0) + cos(phi1) * cos(phi2) * sin(dLon / 2).pow(2.0)
        return 6371000.0 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }

    fun pathMetres(points: List<Pair<Double, Double>>): Double {
        require(points.size >= 2)
        return points.zipWithNext().sumOf { (a, b) -> segmentMetres(a, b) }
    }

    fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
        val phi1 = Math.toRadians(a.first)
        val phi2 = Math.toRadians(b.first)
        val delta = Math.toRadians(b.second - a.second)
        val y = sin(delta) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(delta)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun interpolate(a: Pair<Double, Double>, b: Pair<Double, Double>, t: Double): Pair<Double, Double> {
        if (t <= 0.0) return a
        if (t >= 1.0) return b
        val lat1 = Math.toRadians(a.first)
        val lon1 = Math.toRadians(a.second)
        val lat2 = Math.toRadians(b.first)
        val lon2 = Math.toRadians(b.second)
        val delta = segmentMetres(a, b) / 6371000.0
        val sinDelta = sin(delta)
        if (abs(sinDelta) < 1e-10) return a
        val c1 = sin((1.0 - t) * delta) / sinDelta
        val c2 = sin(t * delta) / sinDelta
        val x = c1 * cos(lat1) * cos(lon1) + c2 * cos(lat2) * cos(lon2)
        val y = c1 * cos(lat1) * sin(lon1) + c2 * cos(lat2) * sin(lon2)
        val z = c1 * sin(lat1) + c2 * sin(lat2)
        return Pair(Math.toDegrees(atan2(z, sqrt(x * x + y * y))),
            Math.toDegrees(atan2(y, x)))
    }

    /** Interpolates by travelled metres, not by index, including across the date line. */
    fun atFraction(points: List<Pair<Double, Double>>, fraction: Double): Position {
        require(points.size >= 2 && fraction.isFinite())
        val total = pathMetres(points)
        if (total < 0.001) {
            val p = points.last()
            return Position(p.first, p.second, 0f)
        }
        if (fraction <= 0.0) {
            val p = points.first()
            return Position(p.first, p.second, bearing(points.first(), points[1]))
        }
        if (fraction >= 1.0) {
            val p = points.last()
            return Position(p.first, p.second, bearing(points[points.lastIndex - 1], p))
        }
        var remaining = total * fraction
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val segment = segmentMetres(a, b)
            if (segment < 0.001) continue
            if (remaining <= segment) {
                val next = interpolate(a, b, (remaining / segment).coerceIn(0.0, 1.0))
                return Position(next.first, next.second, bearing(a, b))
            }
            remaining -= segment
        }
        val p = points.last()
        return Position(p.first, p.second, bearing(points[points.lastIndex - 1], p))
    }
}
