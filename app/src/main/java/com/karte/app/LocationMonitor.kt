package com.karte.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log

/** Pleos FusedLocation SDK로 차량 위치를 구독합니다. 증상이 "어디서" 났는지 남기는 데 씁니다. */
class LocationMonitor(context: Context) {
    private val fused: FusedLocation? = try {
        FusedLocation(context.applicationContext)
    } catch (t: Throwable) {
        Log.w(TAG, "fused location sdk unavailable: ${t.message}")
        null
    }
    private var started = false

    var latitude: Double? = null
        private set
    var longitude: Double? = null
        private set

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            latitude = location.latitude
            longitude = location.longitude
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    /** FUSED_LOCATION 권한이 허용된 뒤 호출합니다. */
    fun start() {
        if (started) return
        val fused = fused ?: return
        try {
            if (!fused.isSupported()) return
            fused.initialize()
            fused.getLastKnownLocation(listener)
            fused.registerFusedLocationCallback(listener)
            started = true
        } catch (t: Throwable) {
            Log.w(TAG, "location unavailable: ${t.message}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            fused?.unregisterFusedLocationCallback(listener)
            fused?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "stop: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "KarteLocation"
    }
}
