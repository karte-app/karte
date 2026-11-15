package com.karte.app

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** SDK 값을 화면·기록용 한국어 표현으로 바꿉니다. */
object Labels {
    /** 고장·이상과 관련된 경고등만 자동 기록 대상으로 삼습니다. (전조등·주행 모드 표시등 등은 제외) */
    val WARNINGS: Map<TelltaleType, String> = mapOf(
        TelltaleType.MASTER_WARNING to "통합 경고",
        TelltaleType.ENGINE to "엔진 점검",
        TelltaleType.ENGINE_OIL_PRESSURE to "엔진 오일 압력",
        TelltaleType.ENGINE_TEMPERATURE to "냉각수 온도",
        TelltaleType.ABS to "ABS",
        TelltaleType.ESC to "차체 자세 제어(ESC)",
        TelltaleType.MDPS to "전동 파워스티어링(MDPS)",
        TelltaleType.TPMS to "타이어 공기압(TPMS)",
        TelltaleType.AIRBAG to "에어백",
        TelltaleType.LOW_VOLT_BATTERY to "12V 배터리 충전",
        TelltaleType.EV_SYSTEM to "전기차 시스템",
        TelltaleType.HEV_SYSTEM to "하이브리드 시스템",
        TelltaleType.FCEV_SYSTEM to "수소전기차 시스템",
        TelltaleType.POWER_DOWN to "출력 제한",
        TelltaleType.RBS to "회생 제동 시스템",
    )

    fun turnSignal(v: TurnSignalValue?): String? = when (v) {
        null -> null
        TurnSignalValue.LEFT -> "왼쪽"
        TurnSignalValue.RIGHT -> "오른쪽"
        TurnSignalValue.OFF -> "꺼짐"
    }

    fun drivingMode(m: DrivingModeType?): String? = when (m) {
        null -> null
        DrivingModeType.NORMAL -> "노멀"
        DrivingModeType.ECO -> "에코"
        DrivingModeType.SPORT -> "스포츠"
    }

    fun speed(kmh: Float?): String = kmh?.let { "시속 ${it.roundToInt()}km" } ?: UNKNOWN

    /**
     * 조향각 부호: 왼쪽이 음수, 오른쪽이 양수(AAOS 규약).
     * Pleos Connect 에뮬레이터 클러스터의 차선 표시 방향과 일치하는 것을 확인했습니다.
     * Virtual Console의 Steering Angle 입력값은 부호가 반대로 전달됩니다(콘솔 -48 → SDK +48).
     */
    fun steering(angle: Float?): String {
        angle ?: return UNKNOWN
        val a = angle.roundToInt()
        return when {
            abs(a) < 10 -> "직진 (${a}°)"
            a > 0 -> "오른쪽 ${a}°"
            else -> "왼쪽 ${-a}°"
        }
    }

    fun brake(pressed: Boolean?): String = when (pressed) {
        null -> UNKNOWN
        true -> "밟는 중"
        false -> "떼고 있음"
    }

    fun odometer(km: Float?): String = km?.let { String.format(Locale.KOREA, "%,dkm", it.roundToInt()) } ?: UNKNOWN

    fun time(millis: Long): String = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(millis))

    fun place(s: Snapshot): String = s.address
        ?: if (s.latitude != null && s.longitude != null)
            String.format(Locale.US, "%.5f, %.5f", s.latitude, s.longitude) else UNKNOWN

    /** 목록 카드에 쓰는 한 줄 요약. 값이 있는 항목만 이어 붙입니다. */
    fun brief(s: Snapshot): String {
        val parts = ArrayList<String>()
        s.speedKmh?.let { parts.add(speed(it)) }
        s.gear?.let { parts.add("기어 $it") }
        if (s.brakePressed == true) parts.add("브레이크 밟는 중")
        s.steeringAngle?.let { if (abs(it) >= 10f) parts.add("핸들 " + steering(it)) }
        s.turnSignal?.let { if (it != "꺼짐") parts.add("방향지시등 $it") }
        if (s.warnings.isNotEmpty()) parts.add("경고등 " + s.warnings.joinToString(", "))
        return if (parts.isEmpty()) "차량 상태 확인 불가" else parts.joinToString(" · ")
    }

    /** 상세·리포트에 쓰는 전체 스냅샷 항목. 얻지 못한 값은 "확인 불가"로 그대로 보여 줍니다. */
    fun fields(s: Snapshot): List<Pair<String, String>> = listOf(
        "속도" to (speed(s.speedKmh) + (s.speedLimitKmh?.let { " (도로 제한 시속 ${it}km)" } ?: "")),
        "기어" to (s.gear ?: UNKNOWN),
        "브레이크" to brake(s.brakePressed),
        "핸들" to steering(s.steeringAngle),
        "방향지시등" to (s.turnSignal ?: UNKNOWN),
        "주행 모드" to (s.drivingMode ?: UNKNOWN),
        "누적 주행거리" to odometer(s.odometerKm),
        "켜진 경고등" to (if (s.warnings.isEmpty()) "없음" else s.warnings.joinToString(", ")),
        "위치" to place(s),
    )

    /** 좁은 칸에 넣는 경고등 이름. 괄호 안 약어는 뺍니다. */
    fun warningShort(name: String): String = name.substringBefore('(').trim().ifEmpty { name }

    const val UNKNOWN = "확인 불가"
}
