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
import org.osmdroid.views.overlay.Polyline
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*

private val Ink = Color(0xFF0A1423)
private val Panel = Color(0xFF142338)
private val Edge = Color(0xFF2B4157)
private val Mint = Color(0xFF5FE3BC)
private val White = Color(0xFFEDF5FF)
private val Subtle = Color(0xFF9CB0C6)

private enum class PinEdit { DESTINATION, START, WAYPOINT }

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

    fun startEngine(point: GeoPoint, planned: PlannedRoute?) {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = if (planned == null) MockLocationService.ACTION_START else MockLocationService.ACTION_ROUTE
            putExtra(MockLocationService.EXTRA_LAT, point.latitude)
            putExtra(MockLocationService.EXTRA_LON, point.longitude)
            if (planned != null) {
                putExtra(MockLocationService.EXTRA_FROM_LAT, planned.from.latitude)
                putExtra(MockLocationService.EXTRA_FROM_LON, planned.from.longitude)
                putExtra(MockLocationService.EXTRA_SPEED, planned.averageKmh)
                putExtra(MockLocationService.EXTRA_ROUTE_POINTS, planned.points.flatMap {
                    listOf(it.latitude,it.longitude)
                }.toDoubleArray())
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
    var speed by remember { mutableFloatStateOf(5f) }
    var busy by remember { mutableStateOf(false) }
    var travelMode by remember { mutableStateOf(TravelMode.WALK) }
    var minutes by remember { mutableFloatStateOf(40f) }
    var routePreview by remember { mutableStateOf<PlannedRoute?>(null) }
    var planning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val router = remember { RoutingClient() }
    var routeRequestId by remember { mutableIntStateOf(0) }
    var waypointList by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var mapEdit by remember { mutableStateOf(PinEdit.DESTINATION) }
    var autoFit by remember { mutableStateOf(true) }
    var detourSide by remember { mutableStateOf(DetourSide.EITHER) }

    fun invalidateRoute() {
        routeRequestId++
        routePreview = null
        planning = false
    }

    fun select(lat: Double, lon: Double, recenter: Boolean) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        if (selectedLat != lat || selectedLon != lon) {
            invalidateRoute()
        }
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

    fun setStart(lat: Double, lon: Double) {
        if (!RouteGeometry.valid(lat, lon)) return
        startLat = lat
        startLon = lon
        invalidateRoute()
        detail = "Route start updated."
    }

    fun replaceWaypoint(index: Int, lat: Double, lon: Double) {
        if (!RouteGeometry.valid(lat, lon) || index !in waypointList.indices) return
        waypointList = waypointList.toMutableList().also { it[index] = GeoPoint(lat, lon) }
        invalidateRoute()
        detail = "Waypoint " + (index + 1) + " moved."
    }

    fun addWaypoint(lat: Double, lon: Double) {
        if (!RouteGeometry.valid(lat, lon)) return
        if (waypointList.size >= 5) {
            detail = "Maximum five stops. Remove a waypoint first."
            return
        }
        waypointList = waypointList + GeoPoint(lat, lon)
        invalidateRoute()
        detail = "Waypoint " + waypointList.size + " added. Drag it on the map or reorder below."
    }

    fun placeSelected(lat: Double, lon: Double) {
        when {
            !routeMode || mapEdit == PinEdit.DESTINATION -> select(lat, lon, true)
            mapEdit == PinEdit.START -> {
                setStart(lat, lon)
                mapEdit = PinEdit.DESTINATION
            }
            mapEdit == PinEdit.WAYPOINT -> {
                addWaypoint(lat, lon)
                mapEdit = PinEdit.DESTINATION
            }
        }
    }

    fun prepareRoute() {
        val end = readPoint() ?: return
        val from = GeoPoint(startLat,startLon)
        val requestId = ++routeRequestId
        val requestedMode = travelMode
        val requestedSpeed = speed
        val requestedMinutes = minutes.toInt()
        val requestedVia = waypointList.toList()
        val requestedFit = autoFit
        val requestedSide = detourSide
        planning = true
        routePreview = null
        detail = "Measuring real roads and fitting detours. This can take up to a minute."
        scope.launch {
            try {
                val result = router.generate(from, end, requestedMode, requestedSpeed,
                    requestedMinutes, requestedVia, requestedFit, requestedSide)
                if (routeRequestId != requestId) return@launch
                routePreview = result
                detail = if (result.withinTolerance)
                    "Matched your journey duration within 5% on mapped roads and paths."
                else "No close mapped route was found. Check the actual time and adjust your stops if needed."
            } catch (e: Exception) {
                if (routeRequestId == requestId) {
                    detail = e.localizedMessage ?: "Routing service is unavailable. Please retry."
                    routePreview = null
                }
            } finally {
                if (routeRequestId == requestId) planning = false
            }
        }
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
        if (isRoute && routePreview == null) {
            detail = "Generate and review a real road/path route before starting."
            return
        }
        if (isRoute && routePreview?.let {
            it.mode != travelMode || it.averageKmh != speed || it.targetMinutes != minutes.toInt() ||
            it.autoFit != autoFit || it.preferredSide != detourSide ||
            it.waypoints.size != waypointList.size ||
            it.waypoints.indices.any { index ->
                kotlin.math.abs(it.waypoints[index].latitude - waypointList[index].latitude) > 0.000001 ||
                kotlin.math.abs(it.waypoints[index].longitude - waypointList[index].longitude) > 0.000001
            } ||
            kotlin.math.abs(it.from.latitude - startLat) > 0.000001 ||
            kotlin.math.abs(it.from.longitude - startLon) > 0.000001
        } == true) { routePreview = null; detail = "Route settings changed. Generate the route again."; return }
        if (isRoute && routePreview?.to?.let {
            kotlin.math.abs(it.latitude-point.latitude) > 0.000001 ||
            kotlin.math.abs(it.longitude-point.longitude) > 0.000001
        } == true) { routePreview = null; detail = "Destination changed. Generate route again."; return }
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
            activity.startEngine(point,if (isRoute) routePreview else null)
            recent = store.addRecent(point.latitude, point.longitude)
            status = "STARTING"
            detail = if (isRoute) "Following street-network route at selected average speed…" else "Applying selected coordinates…"
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
                Image(painterResource(com.goose.pinshift.R.drawable.ic_goose_route),
                    contentDescription = "GooseRoute goose icon", modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("GOOSEROUTE", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp, color = White)
                Text("BY GOOSE  /  V3.1 BETA", color = Subtle, fontSize = 10.sp, letterSpacing = 1.7.sp)
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
            LocationMap(
                selectedLat, selectedLon, focus,
                routePreview?.points ?: emptyList(),
                if (routeMode) GeoPoint(startLat, startLon) else null,
                if (routeMode) waypointList else emptyList(),
                onPicked = { lat, lon -> placeSelected(lat, lon) },
                onStartDragged = { lat, lon -> setStart(lat, lon) },
                onWaypointDragged = { index, lat, lon -> replaceWaypoint(index, lat, lon) },
                onDestinationDragged = { lat, lon -> select(lat, lon, false) }
            )
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(12.dp),
                color = Ink.copy(alpha = 0.93f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = Mint, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (!routeMode) "SELECTED POSITION"
                        else when (mapEdit) {
                            PinEdit.START -> "TAP TO SET ROUTE START"
                            PinEdit.WAYPOINT -> "TAP TO ADD WAYPOINT"
                            PinEdit.DESTINATION -> "TAP TO SET DESTINATION"
                        }, color = White,
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
                        readPoint()?.let {
                            placeSelected(it.latitude, it.longitude)
                            detail = "Coordinates applied to the selected map-edit mode."
                        }
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
            FilterChip(selected = !routeMode, onClick = { routeMode = false; mapEdit = PinEdit.DESTINATION },
                label = { Text("STATIC LOCATION") })
            FilterChip(selected = routeMode, onClick = {
                if (!routeMode) { startLat = selectedLat; startLon = selectedLon }
                routeMode = true
                mapEdit = PinEdit.DESTINATION
            }, label = { Text("ROUTE SIMULATOR") })
        }
        if (routeMode) {
            Spacer(Modifier.height(6.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Text("REAL ROAD & PATH ROUTING", fontSize = 12.sp,
                        color = White, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                    Text("Choose the start, destination and up to five custom stops. We show a route line only after real road routing succeeds.",
                        color = Subtle, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TravelMode.entries.forEach { mode ->
                            FilterChip(selected = travelMode == mode, onClick = {
                                travelMode = mode
                                speed = mode.suggestedKmh
                                invalidateRoute()
                            }, label = { Text(mode.title, fontSize = 11.sp) })
                        }
                    }
                    Text("Start: " + displayPoint(startLat,startLon), color = Subtle, fontSize = 11.sp)
                    Text("Destination: " + displayPoint(selectedLat,selectedLon), color = Subtle, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        FilterChip(selected = mapEdit == PinEdit.DESTINATION,
                            onClick = { mapEdit = PinEdit.DESTINATION },
                            label = { Text("End", fontSize = 11.sp) })
                        FilterChip(selected = mapEdit == PinEdit.START,
                            onClick = { mapEdit = PinEdit.START; detail = "Tap the map to choose the route start." },
                            label = { Text("Start", fontSize = 11.sp) })
                        FilterChip(selected = mapEdit == PinEdit.WAYPOINT,
                            onClick = { mapEdit = PinEdit.WAYPOINT; detail = "Tap the map to add a custom waypoint." },
                            label = { Text("+ Waypoint", fontSize = 11.sp) })
                    }
                    Text("Tap the map, search an address or apply coordinates to your selected editing mode. You can also drag route markers.",
                        fontSize = 11.sp, color = Subtle, lineHeight = 16.sp)
                    Text("CUSTOM WAYPOINTS (" + waypointList.size + "/5)",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = White,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    if (waypointList.isEmpty()) {
                        Text("No intermediate stops. Choose + Waypoint, then tap the map.",
                            color = Subtle, fontSize = 11.sp)
                    }
                    waypointList.forEachIndexed { index, waypoint ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Text((index + 1).toString() + ". " + displayPoint(waypoint.latitude, waypoint.longitude),
                                modifier = Modifier.weight(1f), color = White, fontSize = 11.sp)
                            TextButton(onClick = {
                                if (index > 0) {
                                    waypointList = waypointList.toMutableList().apply {
                                        val item = removeAt(index)
                                        add(index - 1, item)
                                    }
                                    invalidateRoute()
                                }
                            }, enabled = index > 0, contentPadding = PaddingValues(3.dp)) {
                                Text("↑", fontSize = 18.sp)
                            }
                            TextButton(onClick = {
                                if (index < waypointList.lastIndex) {
                                    waypointList = waypointList.toMutableList().apply {
                                        val item = removeAt(index)
                                        add(index + 1, item)
                                    }
                                    invalidateRoute()
                                }
                            }, enabled = index < waypointList.lastIndex,
                                contentPadding = PaddingValues(3.dp)) {
                                Text("↓", fontSize = 18.sp)
                            }
                            TextButton(onClick = {
                                waypointList = waypointList.filterIndexed { i, _ -> i != index }
                                invalidateRoute()
                            }, contentPadding = PaddingValues(3.dp)) {
                                Text("×", fontSize = 20.sp)
                            }
                        }
                    }
                    if (waypointList.isNotEmpty()) {
                        TextButton(onClick = {
                            waypointList = emptyList()
                            invalidateRoute()
                        }) { Text("CLEAR ALL WAYPOINTS", fontSize = 11.sp) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AVERAGE SPEED", color = Subtle, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.UK, "%.0f km/h",speed), color = Mint, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = speed, onValueChange = { speed = it; invalidateRoute() },
                        valueRange = 1f..110f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TARGET DURATION", color = Subtle, fontWeight = FontWeight.Bold,
                            fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(minutes.toInt().toString() + " minutes", color = Mint, fontWeight = FontWeight.Bold)
                    }
                    Slider(value = minutes, onValueChange = { minutes = it.roundToInt().toFloat(); invalidateRoute() },
                        valueRange = 5f..240f)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 45, 60).forEach { value ->
                            AssistChip(onClick = { minutes = value.toFloat(); invalidateRoute() },
                                label = { Text(value.toString() + "m", fontSize = 10.sp) })
                        }
                    }
                    Text("Required length: " + String.format(Locale.UK,"%.2f km",
                        speed * minutes / 60f) + " at " + String.format(Locale.UK, "%.0f", speed) +
                        " km/h over " + minutes.toInt() + " min",
                        color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fit route to required distance", color = White, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        Switch(checked = autoFit, onCheckedChange = {
                            autoFit = it
                            invalidateRoute()
                        })
                    }
                    if (autoFit) {
                        Text("Detour preference", color = Subtle, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DetourSide.entries.forEach { choice ->
                                FilterChip(selected = detourSide == choice,
                                    onClick = { detourSide = choice; invalidateRoute() },
                                    label = { Text(when(choice) {
                                        DetourSide.EITHER -> "Either"
                                        DetourSide.LEFT -> "Left"
                                        DetourSide.RIGHT -> "Right"
                                    }, fontSize = 11.sp) })
                            }
                        }
                        Text("The planner tests road-snapped detours without changing your average speed or ignoring custom stops.",
                            color = Subtle, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { prepareRoute() }, enabled = !planning,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(if (planning) "SEARCHING REAL ROADS..." else "GENERATE FITTED ROAD ROUTE")
                    }
                    routePreview?.let { plan ->
                        Spacer(Modifier.height(10.dp))
                        Text("ROUTE PREVIEW", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            color = Mint)
                        Text(plan.mode.title + "  •  " +
                            String.format(Locale.UK,"%.2f km",plan.distanceMetres / 1000.0) +
                            "  •  " + String.format(Locale.UK,"%.1f min",plan.actualMinutes),
                            color = White, fontWeight = FontWeight.SemiBold)
                        Text(if (plan.withinTolerance)
                            "Within 5% of target. Average speed: " +
                                String.format(Locale.UK, "%.0f", plan.averageKmh) + " km/h."
                            else "Closest mapped route takes " +
                                String.format(Locale.UK, "%.1f", plan.actualMinutes) +
                                " min, not " + plan.targetMinutes +
                                " min (difference " + String.format(Locale.UK, "%.1f", plan.errorPercent) +
                                "%). Speed unchanged; adjust the stops, speed or time.",
                            color = if (plan.withinTolerance) Mint else Color(0xFFFFD080),
                            fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Evaluated " + (routePreview?.candidatesTried ?: 0) +
                        " road candidate(s). Custom waypoints retain their chosen order. An exact journey length is not always possible.",
                        color = Subtle, fontSize = 11.sp, lineHeight = 16.sp)
                    Text("Playback follows actual mapped roads and paths. Instantaneous speed varies slightly while keeping your chosen average.",
                        color = Subtle, fontSize = 11.sp, lineHeight = 16.sp)
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
        TextButton(onClick = {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT,
                    "Try GooseRoute – GPS route testing with maps, walk/cycle/drive routes and duration controls: https://github.com/MTA128/pinshift")
            }
            activity.startActivity(Intent.createChooser(share,"Share GooseRoute"))
        }) { Text("SHARE GOOSEROUTE ↗", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        Text("GooseRoute uses Android's mock location APIs. Android tags these locations as simulated; other apps can detect, reject or override them. Road routes require internet and are limited by our demo provider.",
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
                            placeSelected(address.latitude, address.longitude)
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
private fun LocationMap(
    lat: Double,
    lon: Double,
    focusRequest: Int,
    routePoints: List<GeoPoint>,
    routeStart: GeoPoint?,
    viaPoints: List<GeoPoint>,
    onPicked: (Double, Double) -> Unit,
    onStartDragged: (Double, Double) -> Unit,
    onWaypointDragged: (Int, Double, Double) -> Unit,
    onDestinationDragged: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val currentPick by rememberUpdatedState(onPicked)
    val currentStartDrag by rememberUpdatedState(onStartDragged)
    val currentViaDrag by rememberUpdatedState(onWaypointDragged)
    val currentEndDrag by rememberUpdatedState(onDestinationDragged)
    val lastFocus = remember { intArrayOf(-1) }

    val map = remember(context) {
        Configuration.getInstance().userAgentValue = "GooseRoute/3.1 (github.com/MTA128/pinshift)"
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
            overlays.add(Polyline(this).apply {
                outlinePaint.color = AndroidColor.rgb(95, 227, 188)
                outlinePaint.strokeWidth = 10f
            })
            val destination = Marker(this).apply {
                position = GeoPoint(lat, lon)
                isDraggable = true
                title = "Route destination"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        currentEndDrag(marker.position.latitude, marker.position.longitude)
                    }
                })
            }
            overlays.add(destination)
            controller.setCenter(GeoPoint(lat, lon))
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP &&
                        event.actionMasked != MotionEvent.ACTION_CANCEL
                )
                false
            }
        }
    }

    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }

    AndroidView(factory = { map }, update = { view ->
        val overlays = view.overlays
        (overlays.firstOrNull {
            it is Marker && it.title == "Route destination"
        } as? Marker)?.position = GeoPoint(lat, lon)
        (overlays.firstOrNull { it is Polyline } as? Polyline)?.setPoints(routePoints)

        overlays.removeAll {
            it is Marker && (it.title == "Route start" || it.title?.startsWith("Waypoint #") == true)
        }

        if (routeStart != null) {
            val startMarker = Marker(view).apply {
                position = GeoPoint(routeStart.latitude, routeStart.longitude)
                title = "Route start"
                isDraggable = true
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        currentStartDrag(marker.position.latitude, marker.position.longitude)
                    }
                })
            }
            overlays.add(startMarker)
        }
        viaPoints.forEachIndexed { index, via ->
            val pointMarker = Marker(view).apply {
                position = GeoPoint(via.latitude, via.longitude)
                title = "Waypoint #" + (index + 1)
                isDraggable = true
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {}
                    override fun onMarkerDrag(marker: Marker) {}
                    override fun onMarkerDragEnd(marker: Marker) {
                        currentViaDrag(index, marker.position.latitude, marker.position.longitude)
                    }
                })
            }
            overlays.add(pointMarker)
        }
        if (lastFocus[0] != focusRequest) {
            view.controller.setCenter(GeoPoint(lat, lon))
            lastFocus[0] = focusRequest
        }
        view.invalidate()
    }, modifier = Modifier.fillMaxWidth().height(323.dp).clip(RoundedCornerShape(24.dp)))
}
