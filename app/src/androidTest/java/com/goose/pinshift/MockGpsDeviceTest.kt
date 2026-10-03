package com.goose.pinshift

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MockGpsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val locationManager get() = context.getSystemService(LocationManager::class.java)

    private fun waitFor(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(180)
        }
        return condition()
    }

    private fun intent(action: String, lat: Double = 51.5074, lon: Double = -0.1278) =
        Intent(context, MockLocationService::class.java).apply {
            this.action = action
            putExtra(MockLocationService.EXTRA_LAT, lat)
            putExtra(MockLocationService.EXTRA_LON, lon)
        }

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun openApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanUp() {
        context.startService(intent(MockLocationService.ACTION_STOP))
        assertTrue("Location service did not stop", waitFor { !MockLocationService.running })
        scenario.close()
    }

    @Test fun injectsTwoDifferentStaticLocationsAndRestoresSystem() {
        val ops = context.getSystemService(AppOpsManager::class.java)
        assertEquals("Emulator runner must grant mock-location app-op",
            AppOpsManager.MODE_ALLOWED,
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName))
        ContextCompat.startForegroundService(context, intent(MockLocationService.ACTION_START, 51.5074, -0.1278))
        assertTrue("Foreground service failed to start", waitFor { MockLocationService.running })
        assertTrue("GPS position was never published", waitFor {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                it.isMock && abs(it.latitude - 51.5074) < 0.0001 && abs(it.longitude + 0.1278) < 0.0001
            } ?: false
        })
        context.startService(intent(MockLocationService.ACTION_START, 48.8584, 2.2945))
        assertTrue("GPS location never changed to second target", waitFor {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                it.isMock && abs(it.latitude - 48.8584) < 0.0001 && abs(it.longitude - 2.2945) < 0.0001
            } ?: false
        })
        context.startService(intent(MockLocationService.ACTION_STOP))
        assertTrue(waitFor { !MockLocationService.running })
    }

    @Test fun roadSimulationMovesAlongGeometry() {
        val fromLat = 51.5074
        val fromLon = -0.1278
        val toLat = 51.5075
        val toLon = -0.1264
        val start = intent(MockLocationService.ACTION_ROUTE, toLat, toLon).apply {
            putExtra(MockLocationService.EXTRA_FROM_LAT, fromLat)
            putExtra(MockLocationService.EXTRA_FROM_LON, fromLon)
            putExtra(MockLocationService.EXTRA_SPEED, 90f)
            putExtra(MockLocationService.EXTRA_ROUTE_POINTS, doubleArrayOf(
                fromLat, fromLon, 51.50745, -0.1272, toLat, toLon
            ))
        }
        ContextCompat.startForegroundService(context, start)
        assertTrue("Road simulation did not start", waitFor { MockLocationService.running })
        assertTrue("Road simulation did not advance", waitFor(7000) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                it.isMock && it.longitude > fromLon + 0.00008
            } ?: false
        })
        assertTrue("Road simulation never reached the destination", waitFor(12000) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                abs(it.latitude - toLat) < 0.0001 && abs(it.longitude - toLon) < 0.0001
            } ?: false
        })
        assertTrue("Route should remain holding location", MockLocationService.running)
    }

    @Test fun rejectsInvalidCoordinatesWithoutStarting() {
        ContextCompat.startForegroundService(context, intent(MockLocationService.ACTION_START, 999.0, 2.0))
        assertTrue("Invalid request should not stay active", waitFor { !MockLocationService.running })
    }

    @Test fun favouritesAndHistoryPersistLocally() {
        val places = PlaceStore(context)
        val key = "instrumentation-place"
        val saved = places.addFavourite(key, 12.345678, 45.678901)
        assertTrue(saved.any { it.name == key && it.lat == 12.345678 })
        assertTrue(PlaceStore(context).favourites().any { it.name == key })
        assertTrue(places.addRecent(12.345678, 45.678901).first().lon == 45.678901)
        assertFalse(places.removeFavourite(saved.first { it.name == key }).any { it.name == key })
    }
}
