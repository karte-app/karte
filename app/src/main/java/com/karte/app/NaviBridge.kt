package com.karte.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Pleos NaviHelper SDK 연동.
 * - 스냅샷에 넣을 현재 주소와 도로 제한속도 수신
 * - 내비게이션 즐겨찾기·최근 목적지에서 정비소를 고르고 길 안내 요청
 */
class NaviBridge(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val navi: NaviHelper? = try {
        NaviHelper(context.applicationContext)
    } catch (t: Throwable) {
        Log.w(TAG, "navi sdk unavailable: ${t.message}")
        null
    }

    var connected = false
        private set
    var speedLimitKmh: Int? = null
        private set
    var address: String? = null
        private set

    private val places = LinkedHashMap<String, Place>()
    private var onRouteResult: ((Boolean, String?) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            main.post { speedLimitKmh = speedLimit.takeIf { it > 0 } }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            main.post { address = currentLocationInfo.address?.takeIf { it.isNotBlank() } }
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            main.post {
                bookmarkInfoList.forEach {
                    add(Place(it.name ?: "", it.latitude, it.longitude, it.address ?: "", it.poiId ?: "", it.poiSubId ?: ""))
                }
            }
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            main.post {
                recentDestinationInfoList.forEach {
                    add(Place(it.name ?: "", it.latitude, it.longitude, it.address ?: "", it.poiId ?: "", it.poiSubId ?: ""))
                }
            }
        }

        override fun onRoutingCompleted() {
            main.post { onRouteResult?.invoke(true, null); onRouteResult = null }
        }

        override fun onError(errorCode: NaviErrorCode) {
            main.post { onRouteResult?.invoke(false, errorCode.name); onRouteResult = null }
        }
    }

    fun start() {
        try {
            navi?.initialize(
                onConnected = {
                    main.post {
                        connected = true
                        runCatching { navi?.addListener(listener) }
                        refreshLocation()
                    }
                },
                onFailed = { t -> Log.w(TAG, "navi connect failed: ${t.message}") },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "navi unavailable: ${t.message}")
        }
    }

    fun stop() {
        try {
            navi?.removeListener(listener)
            navi?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "stop: ${t.message}")
        }
        connected = false
    }

    /** 현재 주소를 다시 요청합니다. 결과는 콜백으로 [address]에 반영됩니다. */
    fun refreshLocation() {
        if (!connected) return
        runCatching { navi?.getCurrentLocationInfo() }
    }

    /** 즐겨찾기·최근 목적지를 요청하고, 잠시 뒤 모인 장소 목록을 돌려줍니다. */
    fun loadPlaces(onLoaded: (List<Place>) -> Unit) {
        places.clear()
        if (connected) {
            runCatching { navi?.getBookmarkInfo() }
            runCatching { navi?.getRecentDestinationInfo() }
        }
        main.postDelayed({ onLoaded(places.values.toList()) }, 1200)
    }

    fun routeTo(place: Place, onResult: (Boolean, String?) -> Unit) {
        if (!connected) {
            onResult(false, "내비게이션 서비스에 연결되지 않았어요")
            return
        }
        onRouteResult = onResult
        try {
            navi?.requestRoute(
                RouteInfo(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    displayLongitude = null,
                    displayLatitude = null,
                    poiName = place.name,
                    poiId = place.poiId,
                    poiSubId = place.poiSubId,
                    address = place.address,
                ),
            )
        } catch (t: Throwable) {
            onRouteResult = null
            onResult(false, t.message)
        }
    }

    private fun add(place: Place) {
        if (place.name.isBlank()) return
        places.putIfAbsent(place.name + "@" + place.latitude + "," + place.longitude, place)
    }

    private companion object {
        const val TAG = "KarteNavi"
    }
}
