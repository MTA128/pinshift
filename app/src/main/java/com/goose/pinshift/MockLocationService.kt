package com.goose.pinshift

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.math.*

class MockLocationService : Service() {
    companion object {
        const val ACTION_START = "com.goose.pinshift.START"
        const val ACTION_ROUTE = "com.goose.pinshift.ROUTE"
        const val ACTION_STOP = "com.goose.pinshift.STOP"
        const val ACTION_STATUS = "com.goose.pinshift.STATUS"
        const val EXTRA_LAT = "latitude"
        const val EXTRA_LON = "longitude"
        const val EXTRA_FROM_LAT = "fromLatitude"
        const val EXTRA_FROM_LON = "fromLongitude"
        const val EXTRA_SPEED = "kmh"
        const val EXTRA_ROUTE_POINTS = "routePoints"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "pinshift_location_v2"
        private const val NOTIFICATION_ID = 62
        @Volatile var running = false
            private set
    }
    private lateinit var locationManager: LocationManager
    private lateinit var notifications: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private val providers = linkedSetOf<String>()
    private var latitude = 51.5074
    private var longitude = -0.1278
    private var bearing = 0f
    private var speedMs = 0f
    private var route: Route? = null
    private var lastStatusTime = 0L

    private data class Route(
        val fromLat: Double, val fromLon: Double,
        val toLat: Double, val toLon: Double,
        val metres: Double, val speedMetresPerSecond: Double,
        val startedAt: Long,
        val points: List<Pair<Double, Double>>
    )

    private val worker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                advanceRoute()
                publishPosition()
                val now = SystemClock.elapsedRealtime()
                if (now - lastStatusTime >= 5000L) {
                    lastStatusTime = now
                    val journey = route
                    if (journey != null) {
                        val completed = progress(journey)
                        report(String.format(Locale.US, "Travelling at %.0f km/h • %.0f%% complete",
                            journey.speedMetresPerSecond * 3.6, completed * 100.0))
                    }
                }
                handler.postDelayed(this, 1000L)
            } catch (error: Exception) {
                report("Mock GPS stopped: " + (error.localizedMessage ?: "provider failure"))
                shutdown()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "PinShift simulated location", NotificationManager.IMPORTANCE_LOW
        ))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            report("Simulation stopped. Normal Android location restored.")
            return START_NOT_STICKY
        }
        val targetLat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val targetLon = intent?.getDoubleExtra(EXTRA_LON, Double.NaN) ?: Double.NaN
        if (!valid(targetLat, targetLon)) {
            report("Could not start: invalid destination coordinates")
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        try {
            val newRoute = if (intent?.action == ACTION_ROUTE) {
                val a = intent.getDoubleExtra(EXTRA_FROM_LAT, Double.NaN)
                val b = intent.getDoubleExtra(EXTRA_FROM_LON, Double.NaN)
                val kmh = intent.getFloatExtra(EXTRA_SPEED, 25f).coerceIn(1f, 110f)
                val values = intent.getDoubleArrayExtra(EXTRA_ROUTE_POINTS)
                    ?: throw IllegalArgumentException("Generate a routed path first")
                if (!valid(a,b) || values.size < 4 || values.size % 2 != 0 || values.size > 1600)
                    throw IllegalArgumentException("Invalid routed path")
                val road = values.toList().chunked(2).map { Pair(it[0],it[1]) }
                if (road.any { !valid(it.first,it.second) })
                    throw IllegalArgumentException("Invalid route points")
                val pathMetres = pathDistance(road)
                if (pathMetres < 5.0) throw IllegalArgumentException("Route is too short")
                Route(a,b,targetLat,targetLon,pathMetres,
                    kmh.toDouble()/3.6,SystemClock.elapsedRealtime(),road)
            } else null

            route = newRoute
            if (newRoute == null) {
                latitude = targetLat
                longitude = targetLon
                speedMs = 0f
                bearing = 0f
            } else {
                latitude = newRoute.fromLat
                longitude = newRoute.fromLon
                speedMs = newRoute.speedMetresPerSecond.toFloat()
                bearing = direction(newRoute.fromLat, newRoute.fromLon, newRoute.toLat, newRoute.toLon)
            }
            if (!running) {
                startForeground(NOTIFICATION_ID, notification())
                createProviders()
                running = true
                worker.run()
            } else {
                publishPosition()
                notifications.notify(NOTIFICATION_ID, notification())
            }
            if (newRoute != null) report("Road-network journey started")
            else report(String.format(Locale.US, "Position fixed at %.6f, %.6f", latitude, longitude))
        } catch (error: Exception) {
            report("Could not start location engine: " + (error.localizedMessage ?: "unknown error"))
            shutdown()
        }
        return START_NOT_STICKY
    }

    private fun valid(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    private fun createProviders() {
        val precise = ProviderProperties.Builder()
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setHasBearingSupport(true)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true).build()
        val network = ProviderProperties.Builder()
            .setAccuracy(ProviderProperties.ACCURACY_COARSE)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build()

        for ((name, props) in listOf(LocationManager.GPS_PROVIDER to precise,
            LocationManager.NETWORK_PROVIDER to network)) {
            try { locationManager.removeTestProvider(name) } catch (_: Exception) { }
            try {
                locationManager.addTestProvider(name, props)
                providers.add(name)
                locationManager.setTestProviderEnabled(name, true)
            } catch (error: Exception) {
                if (name == LocationManager.GPS_PROVIDER) throw error
            }
        }
        if (providers.isEmpty()) throw IllegalStateException("No test provider could be registered")
    }

    private fun publishPosition() {
        for (provider in providers) {
            val location = Location(provider).apply {
                latitude = this@MockLocationService.latitude
                longitude = this@MockLocationService.longitude
                accuracy = if (provider == LocationManager.GPS_PROVIDER) 3f else 30f
                altitude = 0.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                speed = speedMs
                bearing = this@MockLocationService.bearing
            }
            locationManager.setTestProviderLocation(provider, location)
        }
    }

    private fun progress(route: Route) =
        if (route.metres < 0.5) 1.0 else {
            val elapsed = (SystemClock.elapsedRealtime()-route.startedAt).coerceAtLeast(0L)/1000.0
            val total = route.metres/route.speedMetresPerSecond
            val x = (elapsed/total).coerceIn(0.0,1.0)
            // A smooth varying speed profile with the same average over the whole route.
            RouteTiming.distanceFraction(x)
        }

    private fun advanceRoute() {
        val current = route ?: return
        val p = progress(current)
        val coordinates = interpolateRoad(current.points,p)
        latitude = coordinates.first
        longitude = coordinates.second
        speedMs = (current.speedMetresPerSecond *
            (1.0 + 0.18*sin(2.0*Math.PI*p))).toFloat().coerceAtLeast(0f)
        if (p >= 1.0) {
            route = null
            speedMs = 0f
            report("Route finished. Holding the destination location.")
            notifications.notify(NOTIFICATION_ID, notification())
        }
    }

    private fun metres(a: Double, b: Double, c: Double, d: Double): Double {
        val diffLat = Math.toRadians(c - a)
        val diffLon = Math.toRadians(d - b)
        val h = sin(diffLat / 2.0).pow(2.0) + cos(Math.toRadians(a)) *
            cos(Math.toRadians(c)) * sin(diffLon / 2.0).pow(2.0)
        return 6371000.0 * 2.0 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }

    private fun direction(a: Double, b: Double, c: Double, d: Double): Float {
        val dLon = Math.toRadians(d - b)
        val y = sin(dLon) * cos(Math.toRadians(c))
        val x = cos(Math.toRadians(a)) * sin(Math.toRadians(c)) -
            sin(Math.toRadians(a)) * cos(Math.toRadians(c)) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun pathDistance(points: List<Pair<Double,Double>>): Double {
        return points.zipWithNext().sumOf { (a,b) -> metres(a.first,a.second,b.first,b.second) }
    }
    private fun interpolateRoad(points: List<Pair<Double,Double>>, fraction: Double): Pair<Double,Double> {
        if (fraction <= 0.0) return points.first()
        if (fraction >= 1.0) return points.last()
        val distance = pathDistance(points)
        var remaining = distance * fraction
        for (i in 0 until points.lastIndex) {
            val a=points[i]; val b=points[i+1]
            val segment=metres(a.first,a.second,b.first,b.second)
            if (remaining <= segment && segment > 0.0) {
                val blend=(remaining/segment).coerceIn(0.0,1.0)
                bearing=direction(a.first,a.second,b.first,b.second)
                return Pair(a.first+(b.first-a.first)*blend,a.second+(b.second-a.second)*blend)
            }
            remaining -= segment
        }
        return points.last()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PinShift • simulation active")
            .setContentText(String.format(Locale.US, "Position: %.6f, %.6f", latitude, longitude))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
            .build()
    }

    private fun report(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    private fun shutdown() {
        handler.removeCallbacks(worker)
        running = false
        route = null
        speedMs = 0f
        for (provider in providers.toList()) {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
        }
        providers.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(worker)
        running = false
        for (provider in providers.toList()) {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) { }
        }
        providers.clear()
        super.onDestroy()
    }
}
