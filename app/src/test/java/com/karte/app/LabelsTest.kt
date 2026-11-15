package com.karte.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 단위 테스트: SDK 값을 화면·기록 문구로 바꾸는 규칙 */
class LabelsTest {

    @Test
    fun steering_leftIsNegative_rightIsPositive_smallAngleIsStraight() {
        assertEquals("직진 (0°)", Labels.steering(0f))
        assertEquals("직진 (9°)", Labels.steering(9.4f))
        assertEquals("오른쪽 48°", Labels.steering(48f))
        assertEquals("왼쪽 62°", Labels.steering(-62f))
    }

    @Test
    fun valuesTheVehicleDidNotProvide_areShownAsUnknown_notGuessed() {
        val fields = Labels.fields(Snapshot(timeMillis = 0L)).toMap()
        listOf("속도", "기어", "브레이크", "핸들", "방향지시등", "주행 모드", "누적 주행거리", "위치").forEach {
            assertEquals(it, Labels.UNKNOWN, fields[it])
        }
        assertEquals("없음", fields["켜진 경고등"])
        assertEquals("차량 상태 확인 불가", Labels.brief(Snapshot(timeMillis = 0L)))
    }

    @Test
    fun brief_listsOnlyMeaningfulValues() {
        val s = Snapshot(
            timeMillis = 0L, speedKmh = 22.4f, gear = "D", brakePressed = true, steeringAngle = 48f,
            turnSignal = "꺼짐", warnings = listOf("타이어 공기압(TPMS)"),
        )
        assertEquals("시속 22km · 기어 D · 브레이크 밟는 중 · 핸들 오른쪽 48° · 경고등 타이어 공기압(TPMS)", Labels.brief(s))
    }

    @Test
    fun speedLimit_isShownOnlyWhenReceived() {
        assertEquals("시속 22km", Labels.fields(Snapshot(0L, speedKmh = 22f)).toMap()["속도"])
        assertEquals("시속 22km (도로 제한 시속 30km)", Labels.fields(Snapshot(0L, speedKmh = 22f, speedLimitKmh = 30)).toMap()["속도"])
    }

    @Test
    fun odometer_usesThousandsSeparator() {
        assertEquals("18,610km", Labels.odometer(18610.2f))
    }

    @Test
    fun place_fallsBackToCoordinatesWithoutAddress() {
        assertEquals("예시 위치 A", Labels.place(Snapshot(0L, latitude = 37.48545, longitude = 127.03637, address = "예시 위치 A")))
        assertEquals("37.48545, 127.03637", Labels.place(Snapshot(0L, latitude = 37.48545, longitude = 127.03637)))
    }

    @Test
    fun warningShort_dropsAbbreviationSoTheTileStaysOneLine() {
        assertEquals("타이어 공기압", Labels.warningShort("타이어 공기압(TPMS)"))
        assertEquals("차체 자세 제어", Labels.warningShort("차체 자세 제어(ESC)"))
        assertEquals("ABS", Labels.warningShort("ABS"))
    }

    @Test
    fun autoRecordedWarningLamps_are15() {
        assertEquals(15, Labels.WARNINGS.size)
    }
}
