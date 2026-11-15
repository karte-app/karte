package com.karte.app

import android.content.Context
import org.json.JSONArray
import java.io.File

/** 증상 기록을 앱 내부 저장소(JSON)에만 보관합니다. 차량 밖으로 전송하지 않습니다. */
class RecordStore(context: Context) {
    private val file = File(context.filesDir, "records.json")
    private val prefs = context.getSharedPreferences("karte", Context.MODE_PRIVATE)
    private val items = ArrayList<Record>()

    init {
        load()
    }

    /** 최신 기록이 앞에 오도록 정렬된 목록 */
    val records: List<Record>
        get() = items.sortedByDescending { it.snapshot.timeMillis }

    fun add(record: Record) {
        items.add(record)
        save()
    }

    fun addAll(records: List<Record>) {
        items.addAll(records)
        save()
    }

    fun clear() {
        items.clear()
        save()
    }

    var servicePlace: Place?
        get() {
            val name = prefs.getString("place_name", null) ?: return null
            return Place(
                name = name,
                latitude = java.lang.Double.longBitsToDouble(prefs.getLong("place_lat", 0L)),
                longitude = java.lang.Double.longBitsToDouble(prefs.getLong("place_lng", 0L)),
                address = prefs.getString("place_address", "") ?: "",
                poiId = prefs.getString("place_poi", "") ?: "",
                poiSubId = prefs.getString("place_poi_sub", "") ?: "",
            )
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) {
                e.remove("place_name")
            } else {
                e.putString("place_name", value.name)
                e.putLong("place_lat", java.lang.Double.doubleToRawLongBits(value.latitude))
                e.putLong("place_lng", java.lang.Double.doubleToRawLongBits(value.longitude))
                e.putString("place_address", value.address)
                e.putString("place_poi", value.poiId)
                e.putString("place_poi_sub", value.poiSubId)
            }
            e.apply()
        }

    private fun load() {
        items.clear()
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) items.add(Record.fromJson(array.getJSONObject(i)))
        }
    }

    private fun save() {
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        }
    }
}
