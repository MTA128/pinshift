package com.goose.pinshift

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlace(val name: String, val lat: Double, val lon: Double)

class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("pinshift_places_v2", Context.MODE_PRIVATE)

    fun lastPoint(): Pair<Double, Double> = Pair(
        prefs.getString("lastLat", "51.507400")?.toDoubleOrNull() ?: 51.5074,
        prefs.getString("lastLon", "-0.127800")?.toDoubleOrNull() ?: -0.1278
    )

    fun saveLast(lat: Double, lon: Double) {
        prefs.edit().putString("lastLat", lat.toString()).putString("lastLon", lon.toString()).apply()
    }

    private fun load(key: String): List<SavedPlace> {
        return try {
            val data = JSONArray(prefs.getString(key, "[]") ?: "[]")
            (0 until data.length()).mapNotNull { index ->
                val entry = data.optJSONObject(index) ?: return@mapNotNull null
                val lat = entry.optDouble("lat", Double.NaN)
                val lon = entry.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
                else SavedPlace(entry.optString("name", "Location"), lat, lon)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(key: String, places: List<SavedPlace>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(JSONObject().put("name", place.name).put("lat", place.lat).put("lon", place.lon))
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun favourites(): List<SavedPlace> = load("favourites")
    fun recent(): List<SavedPlace> = load("recent")

    fun addFavourite(name: String, lat: Double, lon: Double): List<SavedPlace> {
        val result = listOf(SavedPlace(name.take(38), lat, lon)) +
            favourites().filterNot { it.name == name || (it.lat == lat && it.lon == lon) }
        save("favourites", result.take(25))
        return result.take(25)
    }

    fun removeFavourite(place: SavedPlace): List<SavedPlace> {
        val result = favourites().filterNot { it == place }
        save("favourites", result)
        return result
    }

    fun addRecent(lat: Double, lon: Double): List<SavedPlace> {
        val result = listOf(SavedPlace("Recent", lat, lon)) +
            recent().filterNot { kotlin.math.abs(it.lat - lat) < 0.000001 && kotlin.math.abs(it.lon - lon) < 0.000001 }
        save("recent", result.take(12))
        return result.take(12)
    }
}
