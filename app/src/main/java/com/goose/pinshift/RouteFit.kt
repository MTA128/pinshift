package com.goose.pinshift

import kotlin.math.*

enum class DetourSide(val label: String) {
    EITHER("Either side"),
    LEFT("Left detours"),
    RIGHT("Right detours")
}

/**
 * Produces candidate via-points. Coordinates are deliberately approximate;
 * every candidate must be snapped to real roads by the routing backend.
 */
object RouteFit {
    data class Point(val lat: Double, val lon: Double)

    fun targetDistanceMetres(kmh: Double, minutes: Int): Double =
        RouteTiming.targetMetres(kmh, minutes.toDouble())

    fun accepted(distanceMetres: Double, targetMetres: Double, tolerance: Double = 0.05): Boolean =
        targetMetres > 0.0 && distanceMetres.isFinite() &&
            abs(distanceMetres - targetMetres) / targetMetres <= tolerance

    fun legMetres(a: Point, b: Point): Double =
        RouteGeometry.segmentMetres(Pair(a.lat, a.lon), Pair(b.lat, b.lon))

    private fun heading(a: Point, b: Point): Double =
        RouteGeometry.bearing(Pair(a.lat, a.lon), Pair(b.lat, b.lon)).toDouble()

    fun destination(from: Point, bearingDeg: Double, metres: Double): Point {
        require(metres >= 0 && metres.isFinite())
        val lat = Math.toRadians(from.lat)
        val lon = Math.toRadians(from.lon)
        val theta = Math.toRadians(bearingDeg)
        val delta = metres / 6371000.0
        val projectedLat = asin(sin(lat) * cos(delta) + cos(lat) * sin(delta) * cos(theta))
        val projectedLon = lon + atan2(sin(theta) * sin(delta) * cos(lat),
            cos(delta) - sin(lat) * sin(projectedLat))
        val normalizedLon = ((Math.toDegrees(projectedLon) + 540.0) % 360.0) - 180.0
        return Point(Math.toDegrees(projectedLat), normalizedLon)
    }

    fun mainLeg(stops: List<Point>): Int {
        require(stops.size >= 2)
        return (0 until stops.lastIndex).maxByOrNull {
            legMetres(stops[it], stops[it + 1])
        } ?: 0
    }

    /**
     * Return a candidate stop sequence retaining user stops in their original order.
     * If start==end, create a closed route via two geographically separate stops.
     */
    fun detour(stops: List<Point>, radius: Double, clockwise: Boolean,
               legIndex: Int = mainLeg(stops)): List<Point> {
        require(stops.size >= 2)
        require(radius > 0 && radius.isFinite())
        require(legIndex in 0 until stops.lastIndex)
        val from = stops[legIndex]
        val to = stops[legIndex + 1]
        val length = legMetres(from, to)
        if (length < 10.0) {
            val turn = if (clockwise) 1.0 else -1.0
            val first = destination(from, 40.0 * turn, radius)
            val second = destination(from, 125.0 * turn, radius)
            return stops.take(legIndex + 1) + listOf(first, second) + stops.drop(legIndex + 1)
        }
        val midpoint = destination(from, heading(from, to), length / 2.0)
        val side = if (clockwise) 90.0 else -90.0
        val away = destination(midpoint, heading(from, to) + side, radius)
        return stops.take(legIndex + 1) + away + stops.drop(legIndex + 1)
    }

    fun startingRadius(stops: List<Point>, baselineRoadMetres: Double, targetMetres: Double): Double {
        val longest = legMetres(stops[mainLeg(stops)], stops[mainLeg(stops) + 1])
        val extra = (targetMetres - baselineRoadMetres).coerceAtLeast(0.0)
        if (longest < 10.0) return (targetMetres / 4.8).coerceIn(100.0, 120000.0)
        val halfLeg = longest / 2.0
        val halfWanted = (longest + extra) / 2.0
        return sqrt((halfWanted * halfWanted - halfLeg * halfLeg).coerceAtLeast(0.0))
            .coerceIn(80.0, 120000.0)
    }
}
