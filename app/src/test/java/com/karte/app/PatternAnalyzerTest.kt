package com.karte.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternAnalyzerTest {

    private val now = 1_800_000_000_000L

    @Test
    fun classify_usesFirstMatchingCategory() {
        assertEquals(Category.NOISE, Category.classify("브레이크 밟을 때 끼익 소리 나"))
        assertEquals(Category.VIBRATION, Category.classify("고속에서 핸들이 떨려"))
        assertEquals(Category.BRAKING, Category.classify("브레이크가 밀리는 느낌"))
        assertEquals(Category.WARNING, Category.classify("경고등 켜짐: ABS"))
        assertEquals(Category.OTHER, Category.classify("뭔가 이상해"))
    }

    @Test
    fun sampleNoiseRecords_shareLowSpeedBrakingRightTurn() {
        val patterns = PatternAnalyzer.analyze(Samples.create(now))
        // 2건 이상 쌓인 분류만 분석 대상입니다. 예시에서는 소음 3건뿐입니다.
        assertEquals(1, patterns.size)
        val noise = patterns.single()
        assertEquals(Category.NOISE, noise.category)
        assertEquals("소음 증상", noise.title)
        assertEquals(3, noise.total)

        val labels = noise.findings.associate { it.label to (it.count to it.total) }
        assertEquals(3 to 3, labels["저속(시속 30km 미만)"])
        assertEquals(3 to 3, labels["브레이크 밟는 중"])
        assertEquals(3 to 3, labels["우회전 중"])
        assertEquals(3 to 3, labels["기어 D"])
        assertEquals(3 to 3, labels["같은 장소 반경 300m 안"])
        // 방향지시등은 3건 중 2건(67%)이라 기준(70%)에 못 미쳐 제외됩니다.
        assertFalse(labels.keys.any { it.startsWith("방향지시등") })
    }

    @Test
    fun unknownValues_areNotCountedAsEvidence() {
        fun record(speed: Float?, brake: Boolean?) = Record(
            id = "id-$speed-$brake", text = "소음", category = Category.NOISE, source = Source.TEXT,
            snapshot = Snapshot(timeMillis = now, speedKmh = speed, brakePressed = brake),
        )
        // 속도를 얻은 기록은 1건뿐이므로 속도 조건은 만들지 않습니다.
        val patterns = PatternAnalyzer.analyze(listOf(record(20f, true), record(null, true), record(null, null)))
        val findings = patterns.single().findings
        assertTrue(findings.none { it.label.contains("속") })
        assertEquals(Finding("브레이크 밟는 중", 2, 2), findings.single())
    }

    @Test
    fun warnings_areGroupedByLamp() {
        fun warning(lamp: String, speed: Float) = Record(
            id = "id-$lamp-$speed", text = "${PatternAnalyzer.WARNING_PREFIX} $lamp", category = Category.WARNING,
            source = Source.AUTO, snapshot = Snapshot(timeMillis = now, speedKmh = speed, warnings = listOf(lamp)),
        )
        // ABS 2건과 TPMS 1건은 같은 "경고등"이어도 서로 다른 증상입니다.
        val patterns = PatternAnalyzer.analyze(listOf(warning("ABS", 90f), warning("ABS", 100f), warning("타이어 공기압(TPMS)", 20f)))
        val abs = patterns.single()
        assertEquals("ABS 경고등", abs.title)
        assertEquals(2, abs.total)
        assertEquals(listOf(Finding("고속(시속 80km 이상)", 2, 2)), abs.findings)
    }

    @Test
    fun singleRecord_hasNoPattern() {
        val one = Samples.create(now).filter { it.category == Category.VIBRATION }
        assertTrue(PatternAnalyzer.analyze(one).isEmpty())
    }
}
