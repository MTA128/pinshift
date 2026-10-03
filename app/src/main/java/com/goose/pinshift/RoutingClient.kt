package com.goose.pinshift

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class TravelMode(val title: String, val profile: String, val suggestedKmh: Float) {
    WALK("Walk", "foot", 5f), CYCLE("Cycle", "bike", 16f), DRIVE("Drive", "car", 40f)
}

data class PlannedRoute(
    val mode: TravelMode,
    val points: List<GeoPoint>,
    val distanceMetres: Double,
    val averageKmh: Float,
    val targetMinutes: Int,
    val actualMinutes: Double,
    val from: GeoPoint,
    val to: GeoPoint,
    val candidatesTried: Int,
    val waypoints: List<GeoPoint> = emptyList(),
    val autoFit: Boolean = true,
    val preferredSide: DetourSide = DetourSide.EITHER
) {
    val targetDistanceMetres get() = RouteFit.targetDistanceMetres(averageKmh.toDouble(), targetMinutes)
    val errorPercent get() = 100 * abs(actualMinutes - targetMinutes) / targetMinutes
    val withinTolerance get() = errorPercent <= 5.0
}

class RoutingClient {
    private data class Response(val points: List<GeoPoint>, val metres: Double)

    private fun length(a: GeoPoint, b: GeoPoint): Double = RouteGeometry.segmentMetres(
        Pair(a.latitude, a.longitude), Pair(b.latitude, b.longitude)
    )

    private fun request(mode: TravelMode, stops: List<GeoPoint>): Response {
        val positions = stops.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
        }
        val url = "https://routing.openstreetmap.de/routed-" + mode.profile +
            "/route/v1/driving/" + positions +
            "?overview=full&geometries=geojson&steps=false&generate_hints=false"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            setRequestProperty("User-Agent", "GooseRoute/3.1 (github.com/MTA128/pinshift)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 429) error("Public route service is busy (rate limit). Try again shortly.")
            if (code !in 200..299) error("Routing service error (HTTP " + code + ")")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("code") != "Ok") error("No routable road or path connects these stops.")
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) error("The routing service found no matching road or path.")
            val item = routes.getJSONObject(0)
            val geometry = item.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until geometry.length()).map { index ->
                val coordinate = geometry.getJSONArray(index)
                GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
            }
            if (points.size < 2) error("No route shape was returned.")
            val distance = item.getDouble("distance")
            if (!distance.isFinite() || distance < 0) error("Routing service returned invalid distance.")
            return Response(points, distance)
        } finally { conn.disconnect() }
    }

    private fun asPlan(response: Response, start: GeoPoint, end: GeoPoint,
                       mode: TravelMode, speed: Float, minutes: Int, attempted: Int,
                       via: List<GeoPoint>, fit: Boolean, side: DetourSide): PlannedRoute {
        val points = response.points
        // Bound the payload sent to the Android foreground service.
        val shaped = if (points.size <= 600) points else (0 until 600).map { index ->
            points[(index.toDouble() * points.lastIndex / 599).roundToInt().coerceIn(0, points.lastIndex)]
        }
        // Use the actual playback polyline's length for an honest duration estimate.
        val playbackMetres = shaped.zipWithNext().sumOf { (a, b) -> length(a, b) }
        val actualMinutes = playbackMetres / (speed.toDouble() * 1000.0 / 60.0)
        return PlannedRoute(mode, shaped, playbackMetres, speed, minutes, actualMinutes,
            start, end, attempted, via.toList(), fit, side)
    }

    /**
     * Finds mapped routes while holding the requested average speed fixed.
     * Auto-fit varies *road-snapped* detour points, preserving manual waypoints in order.
     * Returns the closest route found; does not claim an exact fit if none exists.
     */
    suspend fun generate(start: GeoPoint, end: GeoPoint, mode: TravelMode,
                         speed: Float, minutes: Int,
                         via: List<GeoPoint> = emptyList(),
                         autoFit: Boolean = true,
                         side: DetourSide = DetourSide.EITHER): PlannedRoute = withContext(Dispatchers.IO) {
        require(speed.isFinite() && speed in 1f..110f && minutes in 5..240) {
            "Choose 1–110 km/h and a target of 5–240 minutes."
        }
        require(via.size <= 5) { "Up to five custom waypoints are supported." }
        val stops = listOf(start) + via + listOf(end)
        require(stops.all { RouteGeometry.valid(it.latitude, it.longitude) }) {
            "A point lies outside valid GPS coordinates."
        }
        val targetMetres = RouteFit.targetDistanceMetres(speed.toDouble(), minutes)
        val directMinimum = stops.zipWithNext().sumOf { (a, b) -> length(a, b) }
        if (targetMetres < directMinimum * 0.985) error(
            "These stops are at least " + String.format(Locale.UK, "%.2f", directMinimum / 1000.0) +
            " km apart. Your speed and time cover only " +
            String.format(Locale.UK, "%.2f", targetMetres / 1000.0) +
            " km. Remove a waypoint, shorten the trip or change the target."
        )

        // Start by measuring a real routed baseline, not a straight-line estimate.
        val baseline = if (stops.size == 2 && length(start, end) < 10.0) {
            Response(listOf(start, end), 0.0)
        } else request(mode, stops)
        var best = baseline
        var attempted = if (baseline.metres == 0.0 && length(start, end) < 10.0 && via.isEmpty()) 0 else 1
        var lastError: String? = null

        if (targetMetres < baseline.metres * 0.985) error(
            "The shortest mapped route through your chosen stops is about " +
            String.format(Locale.UK, "%.2f", baseline.metres / 1000.0) +
            " km. Your speed and duration require " +
            String.format(Locale.UK, "%.2f", targetMetres / 1000.0) +
            " km. Remove a waypoint, adjust the end point or allow more time."
        )
        if (!autoFit || RouteFit.accepted(baseline.metres, targetMetres)) {
            return@withContext asPlan(baseline, start, end, mode, speed, minutes,
                attempted, via, autoFit, side)
        }

        val points = stops.map { RouteFit.Point(it.latitude, it.longitude) }
        val longestLeg = RouteFit.mainLeg(points)
        val sides = when (side) {
            DetourSide.EITHER -> listOf(true, false)
            DetourSide.LEFT -> listOf(false)
            DetourSide.RIGHT -> listOf(true)
        }
        var bestRadius = RouteFit.startingRadius(points, baseline.metres, targetMetres)
        val maxTries = if (sides.size == 2) 5 else 8

        // Adjust the detour size using road distances measured by the routing backend.
        for (clockwise in sides) {
            var radius = bestRadius
            var lower = 0.0
            var upper = Double.POSITIVE_INFINITY
            repeat(maxTries) {
                coroutineContext.ensureActive()
                if (RouteFit.accepted(best.metres, targetMetres)) return@withContext asPlan(
                    best, start, end, mode, speed, minutes, attempted, via, autoFit, side)
                if (attempted > 0) delay(1150L) // respect public demo server request interval
                val candidateStops = RouteFit.detour(points, radius, clockwise, longestLeg)
                    .map { GeoPoint(it.lat, it.lon) }
                try {
                    val candidate = request(mode, candidateStops)
                    attempted++
                    if (abs(candidate.metres - targetMetres) < abs(best.metres - targetMetres)) {
                        best = candidate
                        bestRadius = radius
                    }
                    if (candidate.metres < targetMetres) {
                        lower = radius
                        radius = if (upper.isFinite()) (lower + upper) / 2 else (radius * 1.7)
                    } else {
                        upper = radius
                        radius = if (lower > 0) (lower + upper) / 2 else (radius * 0.56)
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastError = error.localizedMessage
                    if (lastError?.contains("rate limit", ignoreCase = true) == true) {
                        return@withContext if (attempted > 0) asPlan(best, start, end,
                            mode, speed, minutes, attempted, via, autoFit, side)
                        else throw error
                    }
                    radius = (radius * 0.73).coerceAtLeast(80.0)
                }
                radius = radius.coerceIn(80.0, 120000.0)
            }
        }
        if (best.metres < 1.0) error(
            lastError ?: "No road or path could be generated for this journey."
        )
        asPlan(best, start, end, mode, speed, minutes, attempted, via, autoFit, side)
    }
}
