package com.goose.pinshift

import android.app.*
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.*
import java.util.Locale

class MockLocationService : Service() {
    companion object {
        const val ACTION_START="com.goose.pinshift.START"
        const val ACTION_MOVE="com.goose.pinshift.MOVE"
        const val ACTION_STOP="com.goose.pinshift.STOP"
        const val ACTION_STATUS="com.goose.pinshift.STATUS"
        const val EXTRA_LAT="latitude"
        const val EXTRA_LON="longitude"
        const val EXTRA_MESSAGE="message"
        @Volatile var running=false
            private set
    }
    private lateinit var lm: LocationManager
    private val handler=Handler(Looper.getMainLooper())
    private var latitude=51.5074
    private var longitude=-0.1278
    private val providers=mutableSetOf<String>()
    private val clock=object: Runnable {
        override fun run() {
            if (!running) return
            try { sendPosition(); handler.postDelayed(this,1000) }
            catch (e: Exception) { notifyStatus("Update failed: "+e.message); stopUpdates() }
        }
    }
    override fun onCreate() {
        super.onCreate()
        lm=getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("location", "PinShift running", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onBind(intent:Intent?): IBinder?=null
    override fun onStartCommand(intent:Intent?, flags:Int, startId:Int):Int {
        if (intent?.action==ACTION_STOP) { stopUpdates(); notifyStatus("Mock location stopped"); return START_NOT_STICKY }
        val lat=intent?.getDoubleExtra(EXTRA_LAT,Double.NaN) ?: Double.NaN
        val lon=intent?.getDoubleExtra(EXTRA_LON,Double.NaN) ?: Double.NaN
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            notifyStatus("Invalid coordinates"); return START_NOT_STICKY
        }
        latitude=lat; longitude=lon
        try {
            if (!running) {
                startForeground(1,notification())
                val fine=ProviderProperties.Builder().setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build()
                for (name in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
                    try { lm.removeTestProvider(name) } catch (_:IllegalArgumentException) {}
                    lm.addTestProvider(name,fine)
                    providers.add(name)
                    lm.setTestProviderEnabled(name,true)
                }
                running=true
                clock.run()
            } else {
                sendPosition()
                getSystemService(NotificationManager::class.java).notify(1,notification())
            }
            notifyStatus(String.format(Locale.US,"Mock location: %.6f, %.6f",latitude,longitude))
        } catch (e:Exception) { notifyStatus("Could not start: "+e.message); stopUpdates() }
        return START_NOT_STICKY
    }
    private fun sendPosition() {
        for (name in providers) {
            val position=Location(name)
            position.latitude=latitude
            position.longitude=longitude
            position.accuracy=2f
            position.time=System.currentTimeMillis()
            position.elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
            lm.setTestProviderLocation(name,position)
        }
    }
    private fun notification():Notification {
        val show=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"location").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PinShift active")
            .setContentText(String.format(Locale.US,"%.6f, %.6f",latitude,longitude))
            .setContentIntent(show).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Stop",stop).build()).build()
    }
    private fun notifyStatus(message:String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE,message))
    }
    private fun stopUpdates() {
        handler.removeCallbacks(clock); running=false
        for (name in providers.toList()) { try { lm.removeTestProvider(name) } catch (_:Exception) {} }
        providers.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacks(clock); running=false
        for (name in providers.toList()) { try { lm.removeTestProvider(name) } catch (_:Exception) {} }
        providers.clear()
        super.onDestroy()
    }
}
