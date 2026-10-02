package com.goose.pinshift

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var pin: Marker
    private lateinit var latField: EditText
    private lateinit var lonField: EditText
    private lateinit var addressField: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("point", MODE_PRIVATE) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            status.text = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE) ?: "Status changed"
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(220, 232, 246))
        setPadding(dp(3), dp(10), dp(3), dp(8))
    }
    private fun field(hintValue: String) = EditText(this).apply {
        hint = hintValue
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        setBackgroundColor(Color.rgb(37, 50, 68))
        setPadding(dp(12), 0, dp(12), 0)
    }
    private fun button(value: String, onTap: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { onTap() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "PinShift/1.0 (com.goose.pinshift)"
        Configuration.getInstance().osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
            setBackgroundColor(Color.rgb(16, 23, 35))
        }
        val scroll = ScrollView(this).apply { fillViewport = true; addView(column) }
        setContentView(scroll)
        column.addView(label("PinShift", 29f))
        column.addView(label("Choose a location on the map, search an address, or enter coordinates.", 13f))
        map = MapView(this).apply {
            setTileSource(XYTileSource("OpenStreetMap", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")))
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            setOnTouchListener { view, event ->
                val done = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
                view.parent?.requestDisallowInterceptTouchEvent(!done)
                false
            }
        }
        column.addView(map, LinearLayout.LayoutParams(-1, dp(360)))
        val initialLat = prefs.getString("lat", "51.507400")?.toDoubleOrNull() ?: 51.5074
        val initialLon = prefs.getString("lon", "-0.127800")?.toDoubleOrNull() ?: -0.1278
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                choose(point.latitude, point.longitude, false)
                return true
            }
            override fun longPressHelper(point: GeoPoint): Boolean = false
        }))
        pin = Marker(map).apply {
            position = GeoPoint(initialLat, initialLon)
            title = "PinShift location"
            isDraggable = true
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) {}
                override fun onMarkerDrag(marker: Marker) {}
                override fun onMarkerDragEnd(marker: Marker) {
                    choose(marker.position.latitude, marker.position.longitude, false)
                }
            })
        }
        map.overlays.add(pin)
        column.addView(label("Tap anywhere or drag the pin. Map © OpenStreetMap contributors.", 12f))
        column.addView(label("Search address or place"))
        addressField = field("e.g. Tower Bridge, London")
        column.addView(addressField, LinearLayout.LayoutParams(-1, dp(50)))
        column.addView(button("Find address") { findAddress() })
        column.addView(label("Exact coordinates"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        latField = field("Latitude").apply { inputType = 12290 }
        lonField = field("Longitude").apply { inputType = 12290 }
        row.addView(latField, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(lonField, LinearLayout.LayoutParams(0, dp(50), 1f))
        column.addView(row)
        column.addView(button("Show coordinates on map") {
            readPoint()?.let { choose(it.first, it.second, true) }
        })
        status = label("Mock GPS off", 14f)
        column.addView(status)
        column.addView(button("Move here / Start spoofing") { moveHere() })
        column.addView(button("Stop and restore real location") {
            startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
            status.text = "Stopping simulated GPS…"
        })
        column.addView(button("Open Developer options") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        column.addView(label("Select PinShift in Developer options > Select mock location app. Other apps can detect mock locations.", 12f))
        choose(initialLat, initialLon, true)
        registerReceiver(receiver, IntentFilter(MockLocationService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
        updateStatus()
    }
    private fun choose(lat: Double, lon: Double, recenter: Boolean) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        pin.position = GeoPoint(lat, lon)
        latField.setText(String.format(Locale.US, "%.6f", lat))
        lonField.setText(String.format(Locale.US, "%.6f", lon))
        prefs.edit().putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
        if (recenter) map.controller.setCenter(pin.position)
        map.invalidate()
    }
    private fun readPoint(): Pair<Double, Double>? {
        val lat = latField.text.toString().trim().toDoubleOrNull()
        val lon = lonField.text.toString().trim().toDoubleOrNull()
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            status.text = "Latitude must be −90 to 90; longitude −180 to 180."
            return null
        }
        return Pair(lat, lon)
    }
    private fun findAddress() {
        val query = addressField.text.toString().trim()
        if (query.isBlank()) { status.text = "Enter an address first."; return }
        if (!Geocoder.isPresent()) { status.text = "Geocoder unavailable. Use coordinates."; return }
        status.text = "Searching for " + query
        try {
            Geocoder(this, Locale.UK).getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (addresses.isEmpty()) { status.text = "No address found."; return@runOnUiThread }
                        val names = addresses.map {
                            it.getAddressLine(0) ?: String.format(Locale.US, "%.6f, %.6f", it.latitude, it.longitude)
                        }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity).setTitle("Select an address")
                            .setItems(names) { _, index ->
                                val selected = addresses[index]
                                choose(selected.latitude, selected.longitude, true)
                                status.text = "Pin selected. Tap Move here to spoof."
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
                override fun onError(errorMessage: String?) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) status.text = "Search failed. Use coordinates."
                    }
                }
            })
        } catch (_: Exception) { status.text = "Search failed. Use coordinates." }
    }
    private fun mockAllowed(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }
    private fun locationAllowed() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun moveHere() {
        val point = readPoint() ?: return
        choose(point.first, point.second, true)
        if (!mockAllowed()) {
            status.text = "Choose PinShift under Developer options > Select mock location app."
            return
        }
        if (!locationAllowed()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 101)
            return
        }
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = if (MockLocationService.running) MockLocationService.ACTION_MOVE else MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_LAT, point.first)
            putExtra(MockLocationService.EXTRA_LON, point.second)
        }
        try {
            if (MockLocationService.running) startService(intent) else startForegroundService(intent)
            status.text = "Applying location…"
        } catch (e: Exception) { status.text = "Could not start: " + e.message }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 101) {
            if (locationAllowed()) moveHere() else status.text = "Location permission required."
        }
    }
    private fun updateStatus() {
        status.text = if (MockLocationService.running) "Mock GPS active." else "Mock GPS off. Select a point and tap Move here."
    }
    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) map.onResume()
        if (::status.isInitialized) updateStatus()
    }
    override fun onPause() { if (::map.isInitialized) map.onPause(); super.onPause() }
    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (::map.isInitialized) map.onDetach()
        super.onDestroy()
    }
}
