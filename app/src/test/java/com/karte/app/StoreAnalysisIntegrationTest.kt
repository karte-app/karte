package com.karte.app

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 통합 테스트: 기록 저장소(RecordStore) → JSON 파일 → 다시 읽기 → 재현 조건 분석(PatternAnalyzer)을
 * 실제 파일과 실제 JSON 구현으로 이어서 확인합니다. 차량 SDK와 화면은 포함하지 않습니다.
 */
class StoreAnalysisIntegrationTest {

    private val now = 1_800_000_000_000L
    private lateinit var dir: File
    private lateinit var context: FakeContext

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("karte-test").toFile()
        context = FakeContext(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 시연 영상과 같은 순서: 주행 중 음성 기록 1건, 경고등 자동 기록 1건, 정차 후 예시 5건 */
    private fun fillLikeTheDemo(store: RecordStore) {
        val driving = Snapshot(
            timeMillis = now, speedKmh = 22f, gear = "D", brakePressed = true, steeringAngle = 48f,
            turnSignal = "꺼짐", odometerKm = 18610f, latitude = 37.44490, longitude = 127.13890, address = "수정구 시흥동",
        )
        val spoken = "브레이크 밟을 때 끼익 소리"
        store.add(Record("voice-1", spoken, Category.classify(spoken), Source.VOICE, driving))
        store.add(
            Record(
                "auto-1", "${PatternAnalyzer.WARNING_PREFIX} 타이어 공기압(TPMS)", Category.WARNING, Source.AUTO,
                driving.copy(timeMillis = now + 8_000, warnings = listOf("타이어 공기압(TPMS)")),
            ),
        )
        store.addAll(Samples.create(now))
    }

    @Test
    fun records_surviveAppRestart_newestFirst() {
        val first = RecordStore(context)
        fillLikeTheDemo(first)

        val reopened = RecordStore(context)
        assertEquals(7, reopened.records.size)
        assertEquals(first.records, reopened.records)
        assertEquals("auto-1", reopened.records.first().id)
        assertTrue(reopened.records.zipWithNext().all { (a, b) -> a.snapshot.timeMillis >= b.snapshot.timeMillis })
    }

    @Test
    fun reloadedRecords_giveTheSameRepeatConditionsAsTheDemoScreen() {
        fillLikeTheDemo(RecordStore(context))

        val patterns = PatternAnalyzer.analyze(RecordStore(context).records)
        assertEquals(listOf("소음 증상", "타이어 공기압(TPMS) 경고등"), patterns.map { it.title })

        val noise = patterns[0]
        assertEquals(4, noise.total)
        assertEquals(
            setOf(
                Finding("저속(시속 30km 미만)", 4, 4),
                Finding("브레이크 밟는 중", 4, 4),
                Finding("우회전 중", 4, 4),
                Finding("기어 D", 4, 4),
                Finding("같은 장소 반경 300m 안", 3, 4),
            ),
            noise.findings.toSet(),
        )
        // 비율이 낮은 조건이 마지막에 옵니다.
        assertEquals("같은 장소 반경 300m 안", noise.findings.last().label)

        val tpms = patterns[1]
        assertEquals(2, tpms.total)
        assertEquals(listOf(Finding("기어 D", 2, 2)), tpms.findings)
    }

    @Test
    fun clear_removesRecordsFromDisk() {
        val store = RecordStore(context)
        fillLikeTheDemo(store)
        store.clear()
        assertTrue(RecordStore(context).records.isEmpty())
    }

    @Test
    fun brokenFile_isTreatedAsEmpty_andNextSaveRecovers() {
        File(dir, "records.json").writeText("{ not json")
        val store = RecordStore(context)
        assertTrue(store.records.isEmpty())

        store.add(Record("r1", "덜컹 소리", Category.NOISE, Source.TEXT, Snapshot(now)))
        assertEquals(listOf("r1"), RecordStore(context).records.map { it.id })
    }

    @Test
    fun servicePlace_isSavedAndRemoved() {
        val store = RecordStore(context)
        assertNull(store.servicePlace)

        val place = Place("단골 정비소", 37.4449, 127.1389, "수정구 시흥동", "poi-1", "sub-1")
        store.servicePlace = place
        assertEquals(place, RecordStore(context).servicePlace)

        store.servicePlace = null
        assertNull(RecordStore(context).servicePlace)
    }

    /** 앱 내부 저장소 위치와 설정 저장소만 제공하는 테스트용 Context */
    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        private val prefs = HashMap<String, SharedPreferences>()
        override fun getFilesDir(): File = dir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemoryPrefs() }
    }

    private class MemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = (map[key] as String?) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as Int?) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as Long?) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as Float?) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as Boolean?) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()

            private fun set(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor = set(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = set(key, values)
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = set(key, value)
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = set(key, value)
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = set(key, value)
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = set(key, value)
            override fun remove(key: String): SharedPreferences.Editor {
                removed.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                removed.addAll(map.keys)
                return this
            }
            override fun commit(): Boolean {
                removed.forEach { map.remove(it) }
                map.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
