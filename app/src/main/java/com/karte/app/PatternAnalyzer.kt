package com.karte.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** "n건 중 count건에서 같은 조건이었다"는 관찰 하나 */
data class Finding(val label: String, val count: Int, val total: Int) {
    val ratio: Float get() = if (total == 0) 0f else count.toFloat() / total
    override fun toString(): String = "$label $count/$total"
}

data class Pattern(val category: Category, val title: String, val total: Int, val findings: List<Finding>) {
    fun summary(): String =
        if (findings.isEmpty()) "$title ${total}건: 아직 뚜렷한 공통 조건이 없어요"
        else "$title ${total}건의 공통 조건: " + findings.joinToString(" · ")
}

/**
 * 같은 분류의 증상 기록에서 반복되는 주행 조건을 찾습니다.
 * 학습 모델이 아니라 단순 집계라서, 모든 결과를 "몇 건 중 몇 건"으로 되짚어 확인할 수 있습니다.
 */
object PatternAnalyzer {
    /** 이 건수부터 공통 조건을 계산합니다. */
    const val MIN_RECORDS = 2
    /** 이 건수부터 "반복 증상"으로 안내합니다. */
    const val CONFIDENT_RECORDS = 3
    private const val THRESHOLD = 0.7f
    private const val SAME_PLACE_METERS = 300.0

    fun analyze(records: List<Record>): List<Pattern> =
        records.filter { it.category != Category.OTHER }
            .groupBy { groupTitle(it) }
            .filter { it.value.size >= MIN_RECORDS }
            .map { (title, group) ->
                val category = group.first().category
                Pattern(category, title, group.size, findingsOf(group.map { it.snapshot }, category))
            }
            .sortedByDescending { it.total }

    /**
     * 같은 증상끼리 묶는 기준. 경고등은 종류가 다르면 다른 증상이므로 경고등 이름별로 따로 묶습니다.
     */
    fun groupTitle(record: Record): String =
        if (record.category == Category.WARNING) record.text.removePrefix(WARNING_PREFIX).trim() + " 경고등"
        else record.category.label + " 증상"

    /** 경고등 자동 기록 문장의 머리말 */
    const val WARNING_PREFIX = "경고등 켜짐:"

    fun speedBand(kmh: Float): String = when {
        kmh < 1f -> "정차 중"
        kmh < 30f -> "저속(시속 30km 미만)"
        kmh < 80f -> "중속(시속 30~80km)"
        else -> "고속(시속 80km 이상)"
    }

    fun steeringState(angle: Float): String = when {
        angle >= 30f -> "우회전 중"
        angle <= -30f -> "좌회전 중"
        abs(angle) < 10f -> "직진 중"
        else -> "완만한 조향"
    }

    private fun findingsOf(snaps: List<Snapshot>, category: Category): List<Finding> {
        val out = ArrayList<Finding>()

        top(snaps.mapNotNull { it.speedKmh }.map { speedBand(it) })?.let { out.add(it) }

        val brakes = snaps.mapNotNull { it.brakePressed }
        accept("브레이크 밟는 중", brakes.count { it }, brakes.size)?.let { out.add(it) }

        top(snaps.mapNotNull { it.steeringAngle }.map { steeringState(it) }.filter { it != "완만한 조향" },
            snaps.count { it.steeringAngle != null })?.let { out.add(it) }

        top(snaps.mapNotNull { it.gear }.map { "기어 $it" })?.let { out.add(it) }

        val signals = snaps.mapNotNull { it.turnSignal }
        top(signals.filter { it != "꺼짐" }.map { "방향지시등 $it" }, signals.size)?.let { out.add(it) }

        val modes = snaps.mapNotNull { it.drivingMode }
        top(modes.filter { it != "노멀" }.map { "$it 모드" }, modes.size)?.let { out.add(it) }

        // 경고등 묶음에서는 "그 경고등이 켜져 있었다"가 당연하므로 조건에서 뺍니다.
        if (category != Category.WARNING) {
            top(snaps.flatMap { it.warnings.distinct() }.map { "$it 경고등 켜짐" }, snaps.size)?.let { out.add(it) }
        }

        samePlace(snaps)?.let { out.add(it) }

        return out.sortedWith(compareByDescending<Finding> { it.ratio }.thenByDescending { it.count })
    }

    /** 가장 많이 나온 값이 기준 비율을 넘으면 관찰로 채택합니다. */
    private fun top(values: List<String>, total: Int = values.size): Finding? {
        val best = values.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return accept(best.key, best.value, total)
    }

    private fun accept(label: String, count: Int, total: Int): Finding? =
        if (total >= MIN_RECORDS && count >= MIN_RECORDS && count.toFloat() / total >= THRESHOLD)
            Finding(label, count, total) else null

    private fun samePlace(snaps: List<Snapshot>): Finding? {
        val located = snaps.filter { it.latitude != null && it.longitude != null }
        if (located.size < MIN_RECORDS) return null
        val best = located.maxOf { base ->
            located.count { distanceMeters(base.latitude!!, base.longitude!!, it.latitude!!, it.longitude!!) <= SAME_PLACE_METERS }
        }
        return accept("같은 장소 반경 300m 안", best, located.size)
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
