package com.karte.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Pleos Vehicle SDK로 차량 상태를 구독해 "지금 이 순간" 값을 항상 들고 있습니다.
 * 증상 기록 버튼을 누른 즉시 스냅샷을 만들 수 있도록 조회가 아니라 구독(listener) 방식을 씁니다.
 */
class VehicleMonitor(context: Context, private val listener: Listener) {

    interface Listener {
        fun onVehicleStateChanged()
        fun onWarningsTurnedOn(types: List<TelltaleType>)
    }

    private val main = Handler(Looper.getMainLooper())
    /** Vehicle Service가 없는 환경에서도 앱이 죽지 않도록 생성 실패를 허용합니다. */
    private val vehicle: Vehicle? = try {
        Vehicle(context.applicationContext)
    } catch (t: Throwable) {
        Log.w(TAG, "vehicle sdk unavailable: ${t.message}")
        null
    }
    private var started = false

    var brakePressed: Boolean? = null
        private set
    var steeringAngle: Float? = null
        private set
    var turnSignal: TurnSignalValue? = null
        private set
    var drivingMode: DrivingModeType? = null
        private set
    var odometerKm: Float? = null
        private set

    private var telltalesOn: Set<TelltaleType> = emptySet()
    private var telltaleBaselineReady = false

    /** 현재 켜져 있는 고장 관련 경고등 */
    val activeWarnings: List<TelltaleType>
        get() = Labels.WARNINGS.keys.filter { it in telltalesOn }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = update { brakePressed = status }
        override fun onFailed(e: Exception) = failed("brake", e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = update { steeringAngle = angle }
        override fun onFailed(e: Exception) = failed("steering", e)
    }
    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = update { turnSignal = state }
        override fun onFailed(e: Exception) = failed("turnSignal", e)
    }
    private val drivingModeListener = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) = update { drivingMode = state }
        override fun onFailed(e: Exception) = failed("drivingMode", e)
    }
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = update { odometerKm = value }
        override fun onFailed(e: Exception) = failed("odometer", e)
    }
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            main.post { applyTelltales(telltales) }
        }
        override fun onFailed(e: Exception) = failed("telltale", e)
    }

    fun start() {
        if (started) return
        started = true
        val vehicle = vehicle ?: return
        attempt("initialize") { vehicle.initialize() }
        // 서비스 바인딩 직후에는 값이 비어 있을 수 있어 한 번 더 읽습니다.
        main.postDelayed({ readAll(); registerAll() }, 300)
        main.postDelayed({ readAll() }, 2500)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        val vehicle = vehicle ?: return
        attempt("unregister") {
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
            vehicle.getDrivingMode().unregisterDrivingModeState(drivingModeListener)
            vehicle.getOdometer().unregisterDistanceDriven(odometerListener)
            vehicle.getSafety().unregisterTelltales(telltaleListener)
        }
        attempt("release") { vehicle.release() }
    }

    private fun readAll() {
        val vehicle = vehicle ?: return
        attempt("getBrake") {
            vehicle.getBrake().getCurrentBrakePedalStatus({ v -> update { brakePressed = v } }, { e -> failed("brake", e) })
        }
        attempt("getSteering") {
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ v -> update { steeringAngle = v } }, { e -> failed("steering", e) })
        }
        attempt("getTurnSignal") {
            vehicle.getTurnSignal().getCurrentTurnSignalState({ v -> update { turnSignal = v } }, { e -> failed("turnSignal", e) })
        }
        attempt("getDrivingMode") {
            vehicle.getDrivingMode().getCurrentDrivingModeState({ v -> update { drivingMode = v } }, { e -> failed("drivingMode", e) })
        }
        attempt("getOdometer") {
            vehicle.getOdometer().getCurrentDistanceDriven({ v -> update { odometerKm = v } }, { e -> failed("odometer", e) })
        }
        attempt("getTelltales") {
            vehicle.getSafety().getTelltalesInfo({ v -> main.post { applyTelltales(v) } }, { e -> failed("telltale", e) })
        }
    }

    private fun registerAll() {
        val vehicle = vehicle ?: return
        attempt("regBrake") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        attempt("regSteering") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        attempt("regTurnSignal") { vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener) }
        attempt("regDrivingMode") { vehicle.getDrivingMode().registerDrivingModeState(drivingModeListener) }
        attempt("regOdometer") { vehicle.getOdometer().registerDistanceDriven(odometerListener) }
        attempt("regTelltales") { vehicle.getSafety().registerTelltales(telltaleListener) }
    }

    /**
     * 경고등 상태를 반영하고, 새로 켜진 고장 관련 경고등을 알립니다.
     * Pleos Connect 에뮬레이터(v2.1.0)에서 확인한 동작: 콜백은 "지금 켜져 있는 경고등 전체"를 매번 전달하고,
     * 꺼진 경고등은 목록에서 빠집니다. 그래서 매 콜백을 전체 스냅샷으로 보고 이전 집합과 비교합니다.
     * 앱 시작 시점에 이미 켜져 있던 경고등은 기준선으로만 삼고 새 기록을 만들지 않습니다.
     */
    private fun applyTelltales(telltales: Telltales?) {
        val list = telltales?.telltales ?: return
        val nowOn = list.filter { it.state != TelltaleState.OFF }.map { it.type }.toSet()
        val turnedOn = (nowOn - telltalesOn).filter { Labels.WARNINGS.containsKey(it) }
        telltalesOn = nowOn
        if (telltaleBaselineReady && turnedOn.isNotEmpty()) listener.onWarningsTurnedOn(turnedOn)
        telltaleBaselineReady = true
        listener.onVehicleStateChanged()
    }

    private fun update(block: () -> Unit) {
        main.post {
            block()
            listener.onVehicleStateChanged()
        }
    }

    private fun failed(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$what threw: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "KarteVehicle"
    }
}
