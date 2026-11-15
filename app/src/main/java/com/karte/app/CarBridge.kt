package com.karte.app

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * AAOS 표준 Car API 연동.
 * - 차속: Pleos 문서가 안내하는 CarPropertyManager의 PERF_VEHICLE_SPEED
 * - 기어: Vehicle SDK의 Gear API가 deprecated되며 안내하는 GEAR_SELECTION 프로퍼티
 * - 주행 규제: CarUxRestrictionsManager (주행 규제 개발 가이드)
 */
class CarBridge(private val context: Context, private val onChanged: () -> Unit) {

    var speedKmh: Float? = null
        private set

    /** 현재 기어 표시("P", "D" 등). 값을 얻지 못하면 null */
    var gear: String? = null
        private set

    /** 주행 규제 활성 여부. 값을 얻지 못하면 null */
    var restricted: Boolean? = null
        private set

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var speedRegistered = false
    private var gearRegistered = false

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSecond = value.value as? Float ?: return
            speedKmh = abs(metersPerSecond) * 3.6f
            onChanged()
        }

        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            gear = gearLabel(raw)
            onChanged()
        }

        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    fun start() {
        try {
            val c = Car.createCar(context) ?: return
            car = c
            uxManager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.let { manager ->
                manager.registerListener { restrictions ->
                    restricted = restrictions.isRequiresDistractionOptimization
                    onChanged()
                }
                restricted = manager.currentCarUxRestrictions?.isRequiresDistractionOptimization
            }
        } catch (t: Throwable) {
            Log.w(TAG, "car api unavailable: ${t.message}")
        }
        startSpeed()
    }

    /** 권한이 허용된 뒤 다시 호출하면 아직 시작하지 못한 구독을 시작합니다. */
    @Suppress("DEPRECATION")
    fun startSpeed() {
        val manager = try {
            car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (t: Throwable) {
            null
        } ?: return
        propertyManager = manager
        if (!speedRegistered) {
            try {
                speedRegistered = manager.registerCallback(
                    speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "speed unavailable: ${t.message}")
            }
        }
        if (!gearRegistered) {
            try {
                gearRegistered = manager.registerCallback(
                    gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "gear unavailable: ${t.message}")
            }
        }
    }

    private fun gearLabel(raw: Int): String? = when (raw) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        VehicleGear.GEAR_FIRST -> "1단"
        VehicleGear.GEAR_SECOND -> "2단"
        VehicleGear.GEAR_THIRD -> "3단"
        VehicleGear.GEAR_FOURTH -> "4단"
        VehicleGear.GEAR_FIFTH -> "5단"
        VehicleGear.GEAR_SIXTH -> "6단"
        VehicleGear.GEAR_SEVENTH -> "7단"
        VehicleGear.GEAR_EIGHTH -> "8단"
        VehicleGear.GEAR_NINTH -> "9단"
        else -> null
    }

    @Suppress("DEPRECATION")
    fun stop() {
        try {
            propertyManager?.unregisterCallback(speedCallback)
            propertyManager?.unregisterCallback(gearCallback)
            uxManager?.unregisterListener()
            car?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "stop: ${t.message}")
        }
        speedRegistered = false
        gearRegistered = false
    }

    private companion object {
        const val TAG = "KarteCar"
    }
}
