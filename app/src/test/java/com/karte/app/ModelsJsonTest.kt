package com.karte.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 단위 테스트: 기록의 JSON 저장 형식과 분류 규칙 */
class ModelsJsonTest {

    @Test
    fun record_roundTripsThroughJsonText() {
        val record = Record(
            id = "r1", text = "브레이크 밟을 때 끼익 소리", category = Category.NOISE, source = Source.VOICE,
            snapshot = Snapshot(
                timeMillis = 1_800_000_000_000L, speedKmh = 22.4f, gear = "D", brakePressed = true, steeringAngle = 48f,
                turnSignal = "오른쪽", drivingMode = "에코", odometerKm = 18610f, speedLimitKmh = 30,
                latitude = 37.48545, longitude = 127.03637, address = "예시 위치 A", warnings = listOf("ABS", "타이어 공기압(TPMS)"),
            ),
        )
        assertEquals(record, Record.fromJson(JSONObject(record.toJson().toString())))
    }

    @Test
    fun missingValues_areNotWritten_andStayNullAfterReload() {
        val record = Record("r2", "뭔가 이상해", Category.OTHER, Source.TEXT, Snapshot(timeMillis = 5L))
        val json = JSONObject(record.toJson().toString())
        val snapshot = json.getJSONObject("snapshot")
        listOf("speedKmh", "gear", "brakePressed", "steeringAngle", "odometerKm", "latitude", "address").forEach {
            assertFalse(it, snapshot.has(it))
        }
        assertEquals(record, Record.fromJson(json))
    }

    @Test
    fun unknownCategoryOrSource_fallBackInsteadOfFailing() {
        val json = Record("r3", "t", Category.NOISE, Source.VOICE, Snapshot(1L)).toJson()
            .put("category", "FUTURE_CATEGORY").put("source", "FUTURE_SOURCE")
        val loaded = Record.fromJson(json)
        assertEquals(Category.OTHER, loaded.category)
        assertEquals(Source.TEXT, loaded.source)
    }

    @Test
    fun classify_coversEveryCategory_andIgnoresCase() {
        assertEquals(Category.STEERING, Category.classify("핸들이 한쪽으로 쏠려"))
        assertEquals(Category.POWERTRAIN, Category.classify("출발할 때 울컥거려"))
        assertEquals(Category.POWERTRAIN, Category.classify("rpm이 혼자 올라가"))
        assertEquals(Category.VIBRATION, Category.classify("시트가 덜덜거려"))
    }

    @Test
    fun speedAndSteeringBands_haveFixedBoundaries() {
        assertEquals("정차 중", PatternAnalyzer.speedBand(0.5f))
        assertEquals("저속(시속 30km 미만)", PatternAnalyzer.speedBand(29.9f))
        assertEquals("중속(시속 30~80km)", PatternAnalyzer.speedBand(30f))
        assertEquals("고속(시속 80km 이상)", PatternAnalyzer.speedBand(80f))
        assertEquals("우회전 중", PatternAnalyzer.steeringState(30f))
        assertEquals("좌회전 중", PatternAnalyzer.steeringState(-30f))
        assertEquals("직진 중", PatternAnalyzer.steeringState(9f))
        assertEquals("완만한 조향", PatternAnalyzer.steeringState(20f))
    }
}
