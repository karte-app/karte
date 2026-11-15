package com.karte.app

import java.util.UUID

/**
 * 재현 조건 분석 화면을 바로 확인할 수 있게 하는 예시 기록.
 * 실제 차량 데이터가 아니며, 화면에 "예시"로 표시됩니다.
 */
object Samples {
    private const val DAY = 24L * 60 * 60 * 1000

    fun create(now: Long): List<Record> = listOf(
        record(
            "브레이크 밟을 때 앞쪽에서 끼익 소리 나", Category.NOISE,
            Snapshot(now - 9 * DAY, 22f, "D", true, 48f, "오른쪽", "노멀", 18230f, 30, 37.48545, 127.03637, "예시 위치 A"),
        ),
        record(
            "우회전하면서 설 때 또 끼익 소리", Category.NOISE,
            Snapshot(now - 5 * DAY, 17f, "D", true, 62f, "오른쪽", "노멀", 18410f, 30, 37.48560, 127.03650, "예시 위치 A"),
        ),
        record(
            "브레이크에서 쇳소리 같은 소음", Category.NOISE,
            Snapshot(now - 2 * DAY, 26f, "D", true, 35f, "꺼짐", "에코", 18575f, 50, 37.48530, 127.03620, "예시 위치 A"),
        ),
        record(
            "고속에서 핸들이 떨려", Category.VIBRATION,
            Snapshot(now - 4 * DAY, 104f, "D", false, 2f, "꺼짐", "노멀", 18470f, 110, null, null, "예시 위치 B"),
        ),
        record(
            "경고등 켜짐: 타이어 공기압(TPMS)", Category.WARNING,
            Snapshot(
                now - 1 * DAY, 58f, "D", false, -3f, "꺼짐", "노멀", 18602f, 60, null, null, "예시 위치 C",
                listOf("타이어 공기압(TPMS)"),
            ),
        ),
    )

    private fun record(text: String, category: Category, snapshot: Snapshot) =
        Record(UUID.randomUUID().toString(), text, category, Source.SAMPLE, snapshot)
}
