package com.karte.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 증상을 느낀 순간의 차량 상태.
 * 차량·에뮬레이터가 값을 주지 않은 항목은 지어내지 않고 null로 남깁니다.
 */
data class Snapshot(
    val timeMillis: Long,
    val speedKmh: Float? = null,
    val gear: String? = null,
    val brakePressed: Boolean? = null,
    val steeringAngle: Float? = null,
    val turnSignal: String? = null,
    val drivingMode: String? = null,
    val odometerKm: Float? = null,
    val speedLimitKmh: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val warnings: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeMillis", timeMillis)
        putOpt("speedKmh", speedKmh?.toDouble())
        putOpt("gear", gear)
        putOpt("brakePressed", brakePressed)
        putOpt("steeringAngle", steeringAngle?.toDouble())
        putOpt("turnSignal", turnSignal)
        putOpt("drivingMode", drivingMode)
        putOpt("odometerKm", odometerKm?.toDouble())
        putOpt("speedLimitKmh", speedLimitKmh)
        putOpt("latitude", latitude)
        putOpt("longitude", longitude)
        putOpt("address", address)
        put("warnings", JSONArray(warnings))
    }

    companion object {
        fun fromJson(o: JSONObject): Snapshot {
            fun has(k: String) = o.has(k) && !o.isNull(k)
            val warnings = ArrayList<String>()
            o.optJSONArray("warnings")?.let { a -> for (i in 0 until a.length()) warnings.add(a.getString(i)) }
            return Snapshot(
                timeMillis = o.getLong("timeMillis"),
                speedKmh = if (has("speedKmh")) o.getDouble("speedKmh").toFloat() else null,
                gear = if (has("gear")) o.getString("gear") else null,
                brakePressed = if (has("brakePressed")) o.getBoolean("brakePressed") else null,
                steeringAngle = if (has("steeringAngle")) o.getDouble("steeringAngle").toFloat() else null,
                turnSignal = if (has("turnSignal")) o.getString("turnSignal") else null,
                drivingMode = if (has("drivingMode")) o.getString("drivingMode") else null,
                odometerKm = if (has("odometerKm")) o.getDouble("odometerKm").toFloat() else null,
                speedLimitKmh = if (has("speedLimitKmh")) o.getInt("speedLimitKmh") else null,
                latitude = if (has("latitude")) o.getDouble("latitude") else null,
                longitude = if (has("longitude")) o.getDouble("longitude") else null,
                address = if (has("address")) o.getString("address") else null,
                warnings = warnings,
            )
        }
    }
}

/** 기록이 만들어진 경로 */
enum class Source(val label: String) {
    VOICE("음성"),
    TEXT("직접 입력"),
    AUTO("자동 기록"),
    SAMPLE("예시"),
}

/** 증상 분류. 키워드 규칙 기반이라 결과를 그대로 설명할 수 있습니다. */
enum class Category(val label: String, val keywords: List<String>) {
    WARNING("경고등", listOf("경고등")),
    NOISE("소음", listOf("소리", "소음", "끼익", "삐걱", "덜컹", "딱딱", "잡소리", "웅웅", "쇳소리", "달그락")),
    VIBRATION("진동", listOf("떨", "진동", "덜덜", "흔들")),
    BRAKING("제동", listOf("브레이크", "제동", "밀리", "밀려")),
    STEERING("조향", listOf("핸들", "쏠", "조향", "스티어링")),
    POWERTRAIN("가속·변속", listOf("가속", "변속", "울컥", "출력", "꿀렁", "말타기", "RPM", "알피엠")),
    OTHER("기타", emptyList());

    companion object {
        fun classify(text: String): Category =
            entries.firstOrNull { c -> c.keywords.any { text.contains(it, ignoreCase = true) } } ?: OTHER
    }
}

data class Record(
    val id: String,
    val text: String,
    val category: Category,
    val source: Source,
    val snapshot: Snapshot,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("category", category.name)
        put("source", source.name)
        put("snapshot", snapshot.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): Record = Record(
            id = o.getString("id"),
            text = o.getString("text"),
            category = runCatching { Category.valueOf(o.getString("category")) }.getOrDefault(Category.OTHER),
            source = runCatching { Source.valueOf(o.getString("source")) }.getOrDefault(Source.TEXT),
            snapshot = Snapshot.fromJson(o.getJSONObject("snapshot")),
        )
    }
}

/** 길 안내 대상 정비소 */
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val poiId: String = "",
    val poiSubId: String = "",
)
