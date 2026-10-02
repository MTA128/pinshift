package com.goose.pinshift

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import kotlin.math.*

private val Ink = Color(0xFF0A1423)
private val Panel = Color(0xFF142338)
private val Edge = Color(0xFF2B4157)
private val Mint = Color(0xFF5FE3BC)
private val White = Color(0xFFEDF5FF)
private val Subtle = Color(0xFF9CB0C6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(10, 20, 35)
        window.navigationBarColor = AndroidColor.rgb(10, 20, 35)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Mint, onPrimary = Ink, secondary = Mint,
                background = Ink, surface = Panel, onSurface = White
            )) {
                Studio(this)
            }
        }
    }

    fun canMock(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun locationGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun startEngine(point: GeoPoint, routeFrom: GeoPoint?, kmh: Float) {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = if (routeFrom == null) MockLocationService.ACTION_START else MockLocationService.ACTION_ROUTE
            putExtra(MockLocationService.EXTRA_LAT, point.latitude)
            putExtra(MockLocationService.EXTRA_LON, point.longitude)
            if (routeFrom != null) {
                putExtra(MockLocationService.EXTRA_FROM_LAT, routeFrom.latitude)
                putExtra(MockLocationService.EXTRA_FROM_LON, routeFrom.longitude)
                putExtra(MockLocationService.EXTRA_SPEED, kmh)
            }
        }
        startForegroundService(intent)
    }

    fun stopEngine() {
        startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
    }

    fun developerSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

private fun displayPoint(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.6f, %.6f", lat, lon)

@Composable
private fun Studio(activity: MainActivity) {
    val store = remember { PlaceStore(activity) }
    val original = remember { store.lastPoint() }
    var selectedLat by remember { mutableDoubleStateOf(original.first) }
    var selectedLon by remember { mutableDoubleStateOf(original.second) }
    var latitudeText by remember { mutableStateOf(String.format(Locale.US, "%.6f", original.first)) }
    var longitudeText by remember { mutableStateOf(String.format(Locale.US, "%.6f", original.second)) }
    var focus by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (MockLocationService.running) "SIMULATION ACTIVE" else "STANDBY") }
    var detail by remember { mutableStateOf("Ready to choose a location") }
    var favourites by remember { mutableStateOf(store.favourites()) }
    var recent by remember { mutableStateOf(store.recent()) }
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var resultDialog by remember { mutableStateOf<List<Address>>(emptyList()) }
    var routeMode by remember { mutableStateOf(false) }
    var startLat by remember { mutableDoubleStateOf(original.first) }
    var startLon by remember { mutableDoubleStateOf(original.second) }
    var speed by remember { mutableFloatStateOf(25f) }
    var busy by remember { mutableStateOf(false) }

    fun select(lat: Double, lon: Double, recenter: Boolean) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        selectedLat = lat
        selectedLon = lon
        latitudeText = String.format(Locale.US, "%.6f", lat)
        longitudeText = String.format(Locale.US, "%.6f", lon)
        store.saveLast(lat, lon)
        if (recenter) focus++
    }
    fun readPoint(): GeoPoint? {
        val lat = latitudeText.trim().toDoubleOrNull()
        val lon = longitudeText.trim().toDoubleOrNull()
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            detail = "Latitude must be −90 to 90 and longitude −180 to 180"
            return null
        }
        return GeoPoint(lat, lon)
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        detail = if (activity.locationGranted()) "Location allowed. Press Start again." else "Precise location permission is required"
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun start(isRoute: Boolean) {
        val point = readPoint() ?: return
        select(point.latitude, point.longitude, true)
        if (!activity.canMock()) {
            detail = "Select PinShift as mock location app in Android Developer options"
            activity.developerSettings()
            return
        }
        if (!activity.locationGranted()) {
            detail = "Allow precise location, then press Start again"
            locationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        val systemLocation = activity.getSystemService(LocationManager::class.java)
        if (!systemLocation.isLocationEnabled) {
            detail = "Turn on Location in Android settings first"
            return
        }
        try {
            activity.startEngine(
                point,
                if (isRoute) GeoPoint(startLat, startLon) else null,
                speed
            )
            recent = store.addRecent(point.latitude, point.longitude)
            status = "STARTING"
            detail = if (isRoute) "Starting straight-line simulation…" else "Applying selected coordinates…"
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (error: Exception) {
            status = "ERROR"
            detail = error.localizedMessage ?: "Could not start location service"
        }
    }
    fun search() {
        val text = query.trim()
        if (text.isEmpty()) { detail = "Type an address or place name"; return }
        if (!Geocoder.isPresent()) { detail = "Address lookup unavailable; use coordinates"; return }
        busy = true
        detail = "Looking for " + text
        try {
            Geocoder(activity, Locale.UK).getFromLocationName(text, 6,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        activity.runOnUiThread {
                            busy = false
                            resultDialog = addresses.toList()
                            if (addresses.isEmpty()) detail = "No matches. Try a postcode or coordinates."
                        }
                    }
                    override fun onError(errorMessage: String?) {
                        activity.runOnUiThread {
                            busy = false
                            detail = "Search unavailable; try exact coordinates"
                        }
                    }
                })
        } catch (_: Exception) { busy = false; detail = "Search unavailable; try coordinates" }
    }

    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                detail = intent.getStringExtra(MockLocationService.EXTRA_MESSAGE) ?: ""
                status = if (MockLocationService.running) "SIMULATION ACTIVE" else "STANDBY"
            }
        }
        activity.registerReceiver(receiver, IntentFilter(MockLocationService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
        onDispose { activity.unregisterReceiver(receiver) }
    }

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp))
                .background(Brush.linearGradient(listOf(Mint, Color(0xFF69A8FF)))),
                contentAlignment = Alignment.Center) {
                Text("⌖", color = Ink, fontSize = 37.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("PINSHIFT", fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp, color = White)
                Text("LOCATION STUDIO  /  V2.0", color = Subtle, fontSize = 10.sp, letterSpacing = 1.7.sp)
            }
            Surface(shape = RoundedCornerShape(30.dp), color = if (MockLocationService.running) Color(0xFF173A37) else Panel) {
                Text(if (MockLocationService.running) "● LIVE" else "● READY",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (MockLocationService.running) Mint else Subtle,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
            LocationMap(selectedLat, selectedLon, focus) { lat, lon ->
                select(lat, lon, false)
            }
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(12.dp),
                color = Ink.copy(alpha = 0.93f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = Mint, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (routeMode) "DESTINATION PIN" else "SELECTED POSITION", color = White,
                        fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp)
                }
            }
            Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = RoundedCornerShape(14.dp),
                color = Ink.copy(alpha = 0.96f)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("GPS COORDINATES", color = Subtle, fontSize = 9.sp, letterSpacing = 1.2.sp)
                    Text(displayPoint(selectedLat, selectedLon), color = Mint,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Text("© OpenStreetMap contributors",
                Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 6.dp)
                    .background(Ink.copy(alpha = 0.78f), RoundedCornerShape(5.dp)).padding(4.dp),
                color = White, fontSize = 8.sp)
        }
        Spacer(Modifier.height(13.dp))
        Surface(shape = RoundedCornerShape(21.dp), color = Panel) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FIND YOUR LOCATION", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp, color = White, modifier = Modifier.weight(1f))
                    Text("SEARCH • PIN • COORDINATES", color = Subtle, fontSize = 9.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Address, place or postcode") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp),
                        colors = entryColors()
                    )
                    Button(onClick = { search() }, enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(13.dp)) {
                        Text(if (busy) "..." else "FIND", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(
                        value = latitudeText, onValueChange = { latitudeText = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Latitude") }, shape = RoundedCornerShape(13.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = entryColors()
                    )
                    OutlinedTextField(
                        value = longitudeText, onValueChange = { longitudeText = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Longitude") }, shape = RoundedCornerShape(13.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = entryColors()
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        readPoint()?.let { select(it.latitude, it.longitude, true); detail = "Pin moved to coordinates" }
                    }) { Text("APPLY COORDINATES", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val clip = activity.getSystemService(ClipboardManager::class.java)
                        clip.setPrimaryClip(ClipData.newPlainText("PinShift position", displayPoint(selectedLat, selectedLon)))
                        detail = "Coordinates copied"
                    }) { Text("COPY", fontSize = 11.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !routeMode, onClick = { routeMode = false },
                label = { Text("STATIC LOCATION") })
            FilterChip(selected = routeMode, onClick = {
                if (!routeMode) { startLat = selectedLat; startLon = selectedLon }
                routeMode = true
            }, label = { Text("MOVEMENT SIMULATOR") })
        }
        if (routeMode) {
            Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Text("STRAIGHT-LINE SIMULATION", fontSize = 12.sp,
                        color = White, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    Text("Start: " + displayPoint(startLat, startLon), color = Subtle, fontSize = 11.sp)
                    Text("End: " + displayPoint(selectedLat, selectedLon), color = Subtle, fontSize = 11.sp)
                    Text("Distance: " + String.format(Locale.US, "%.2f km", distance(startLat, startLon, selectedLat, selectedLon) / 1000.0),
                        color = Subtle, fontSize = 11.sp)
                    TextButton(onClick = { startLat = selectedLat; startLon = selectedLon; detail = "Route start set. Select a destination on the map." }) {
                        Text("SET CURRENT PIN AS START", fontSize = 11.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SPEED", color = Subtle, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.0f km/h", speed), color = Mint, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = speed, onValueChange = { speed = it },
                        valueRange = 1f..110f)
                    Text("This draws a direct path, not a street navigation route.", color = Subtle, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(onClick = { start(routeMode) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(15.dp)) {
                Text(if (routeMode) "START ROUTE" else "MOVE HERE",
                    fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            OutlinedButton(onClick = {
                try { activity.stopEngine(); status = "STOPPING"; detail = "Restoring normal location…" }
                catch (e: Exception) { detail = "Stop error: " + e.localizedMessage }
            }, modifier = Modifier.height(56.dp), shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, Edge)) {
                Text("STOP", color = White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(17.dp),
            color = if (MockLocationService.running) Color(0xFF18382F) else Panel) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(status, color = if (MockLocationService.running) Mint else White,
                    fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                Text(detail, color = Subtle, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SAVED PLACES", color = White, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), letterSpacing = 1.sp)
            TextButton(onClick = { saveName = ""; saveDialog = true }) { Text("+ SAVE PIN") }
        }
        if (favourites.isEmpty()) {
            Text("Your favourite locations will appear here.", color = Subtle, fontSize = 12.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(favourites) { place ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Panel) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(place.name, modifier = Modifier.clickable {
                                select(place.lat, place.lon, true); detail = "Selected " + place.name
                            }.padding(start = 13.dp, top = 13.dp, bottom = 13.dp),
                                color = White, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                favourites = store.removeFavourite(place)
                            }) { Text("×", color = Subtle, fontSize = 19.sp) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("RECENT DESTINATIONS", color = White, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        if (recent.isEmpty()) Text("No recent simulated locations.", color = Subtle, fontSize = 12.sp)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent) { place ->
                AssistChip(onClick = { select(place.lat, place.lon, true) },
                    label = { Text(displayPoint(place.lat, place.lon), fontSize = 10.sp) })
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Edge)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { activity.developerSettings() }) {
            Text("OPEN ANDROID DEVELOPER OPTIONS   ↗", fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        Text("PinShift uses Android's mock location APIs. Android tags these locations as simulated; other apps can detect, reject or override them. No root or stealth modifications.",
            color = Subtle, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(26.dp))
    }

    if (saveDialog) {
        AlertDialog(onDismissRequest = { saveDialog = false },
            title = { Text("Save this place") },
            text = {
                OutlinedTextField(value = saveName, onValueChange = { saveName = it.take(38) },
                    singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    favourites = store.addFavourite(saveName.ifBlank { "Saved location" }, selectedLat, selectedLon)
                    detail = "Location saved on your phone"
                    saveDialog = false
                }) { Text("SAVE") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("CANCEL") } })
    }
    if (resultDialog.isNotEmpty()) {
        AlertDialog(onDismissRequest = { resultDialog = emptyList() },
            title = { Text("Choose a search result") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    resultDialog.forEach { address ->
                        val addressLabel = address.getAddressLine(0)
                            ?: displayPoint(address.latitude, address.longitude)
                        Text(addressLabel, Modifier.fillMaxWidth().clickable {
                            select(address.latitude, address.longitude, true)
                            query = addressLabel
                            detail = "Pin updated. Press Move here to apply."
                            resultDialog = emptyList()
                        }.padding(vertical = 12.dp), color = White, fontSize = 13.sp)
                        HorizontalDivider(color = Edge)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { resultDialog = emptyList() }) { Text("CLOSE") } })
    }
}

@Composable
private fun entryColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = White, unfocusedTextColor = White,
    focusedBorderColor = Mint, unfocusedBorderColor = Edge,
    focusedLabelColor = Mint, unfocusedLabelColor = Subtle,
    cursorColor = Mint
)

private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val a = sin(Math.toRadians(lat2 - lat1) / 2).pow(2.0) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(Math.toRadians(lon2 - lon1) / 2).pow(2.0)
    return 6371000.0 * 2.0 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceAtLeast(0.0)))
}

@Composable
private fun LocationMap(lat: Double, lon: Double, focusRequest: Int, onPicked: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val currentPick by rememberUpdatedState(onPicked)
    val lastFocus = remember { intArrayOf(-1) }
    val map = remember(context) {
        Configuration.getInstance().userAgentValue = "PinShift/2.0 (Personal Location Studio)"
        Configuration.getInstance().osmdroidBasePath = java.io.File(context.cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid/tiles")
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(14.0)
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                    currentPick(point.latitude, point.longitude)
                    return true
                }
                override fun longPressHelper(point: GeoPoint): Boolean = false
            }))
            val pin = Marker(this).apply {
                position = GeoPoint(lat, lon)
                isDraggable = true
                title = "Selected PinShift location"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        currentPick(marker.position.latitude, marker.position.longitude)
                    }
                })
            }
            overlays.add(pin)
            controller.setCenter(GeoPoint(lat, lon))
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                )
                false
            }
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, update = {
        (it.overlays.firstOrNull { overlay -> overlay is Marker } as? Marker)
            ?.position = GeoPoint(lat, lon)
        if (lastFocus[0] != focusRequest) {
            it.controller.setCenter(GeoPoint(lat, lon))
            lastFocus[0] = focusRequest
        }
        it.invalidate()
    }, modifier = Modifier.fillMaxWidth().height(323.dp).clip(RoundedCornerShape(24.dp)))
}
