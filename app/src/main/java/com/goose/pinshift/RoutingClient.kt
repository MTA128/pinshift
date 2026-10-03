package com.goose.pinshift

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    val candidatesTried: Int
) {
    val errorPercent get() = 100 * abs(actualMinutes - targetMinutes) / targetMinutes
    val withinTolerance get() = errorPercent <= 10.0
}

class RoutingClient {
    private data class Response(val points: List<GeoPoint>, val metres: Double)

    private fun length(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)
        return 6371000.0 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1-h).coerceAtLeast(0.0)))
    }

    private fun project(p: GeoPoint, heading: Double, metres: Double): GeoPoint {
        val phi = Math.toRadians(p.latitude)
        val lambda = Math.toRadians(p.longitude)
        val theta = Math.toRadians(heading)
        val delta = metres / 6371000.0
        val newPhi = asin(sin(phi)*cos(delta) + cos(phi)*sin(delta)*cos(theta))
        val newLambda = lambda + atan2(sin(theta)*sin(delta)*cos(phi), cos(delta)-sin(phi)*sin(newPhi))
        return GeoPoint(Math.toDegrees(newPhi), Math.toDegrees(newLambda))
    }

    private fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val delta = Math.toRadians(b.longitude-a.longitude)
        val y = sin(delta)*cos(p2)
        val x = cos(p1)*sin(p2) - sin(p1)*cos(p2)*cos(delta)
        return (Math.toDegrees(atan2(y,x)) + 360) % 360
    }

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
            setRequestProperty("User-Agent","GooseRoute/3.0 (MTA128; github.com/MTA128/pinshift)")
            setRequestProperty("Accept","application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 429) error("Public route server is busy; try again later")
            if (code !in 200..299) error("Routing service error (HTTP " + code + ")")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("code") != "Ok") error("No road/path route found for this mode and location")
            val item = json.getJSONArray("routes").getJSONObject(0)
            val geometry = item.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until geometry.length()).map { i ->
                val coordinate = geometry.getJSONArray(i)
                GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
            }
            if (points.size < 2) error("Routing service returned no route shape")
            return Response(points, item.getDouble("distance"))
        } finally { conn.disconnect() }
    }

    suspend fun generate(start: GeoPoint, end: GeoPoint, mode: TravelMode,
                         speed: Float, minutes: Int): PlannedRoute = withContext(Dispatchers.IO) {
        require(speed in 1f..110f && minutes in 5..240) { "Select 1–110 km/h and 5–240 minutes" }
        val targetLength = speed * 1000.0 * minutes / 60.0
        val direct = length(start, end)
        if (targetLength < direct * .95) {
            error("Requested speed and duration cover " +
                String.format(Locale.UK,"%.1f", targetLength/1000) +
                " km, but the destination is at least " +
                String.format(Locale.UK,"%.1f", direct/1000) +
                " km away. Increase duration or choose another destination.")
        }
        val loop = direct < 50
        val heading = if (loop) 30.0 else bearing(start,end)
        val middle = if (loop) start else project(start,heading,direct/2)
        val radius = if (loop) targetLength/4.8 else (targetLength-direct).coerceAtLeast(150.0)/2.0
        val options = ArrayList<List<GeoPoint>>()
        if (!loop) options.add(listOf(start,end))
        if (loop) {
            options.add(listOf(start,project(start,heading,radius),project(start,heading+95,radius),start))
            options.add(listOf(start,project(start,heading+40,radius*1.25),project(start,heading+155,radius*1.25),start))
            options.add(listOf(start,project(start,heading+120,radius*1.5),project(start,heading+240,radius*1.5),start))
        } else {
            options.add(listOf(start,project(middle,heading+90,radius),end))
            options.add(listOf(start,project(middle,heading-90,radius),end))
            options.add(listOf(start,project(middle,heading+90,radius*1.6),end))
        }
        var best: Response? = null
        var attempted = 0
        var issue: String? = null
        for (option in options.take(4)) {
            if (attempted > 0) delay(1200)
            attempted++
            try {
                val candidate = request(mode,option)
                if (best == null || abs(candidate.metres-targetLength) < abs(best.metres-targetLength)) {
                    best = candidate
                }
                if (best != null && abs(best.metres-targetLength)/targetLength < .07) break
            } catch (e: Exception) {
                issue=e.message
                if (issue?.contains("busy") == true) break
            }
        }
        val chosen = best ?: error(issue ?: "No valid route was found")
        val actual = chosen.metres / (speed.toDouble() * 1000 / 60.0)
        val pts = chosen.points
        val simplified = if (pts.size <= 450) pts else (0 until 450).map { i ->
            pts[(i.toDouble() * pts.lastIndex / 449).roundToInt().coerceIn(0,pts.lastIndex)]
        }
        PlannedRoute(mode, simplified, chosen.metres, speed, minutes, actual, start, end, attempted)
    }
}
