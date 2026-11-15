package com.karte.app

import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * 카르테: 주행 중 느낀 차량 이상 증상을 말 한마디로 남기고,
 * 그 순간의 차량 상태를 함께 저장해 반복되는 조건을 찾아 주는 앱.
 *
 * - 주행 중: 큰 버튼 하나와 음성(STT/TTS)만 사용. 목록·입력·리포트는 숨깁니다.
 * - 정차 중: 기록 목록, 재현 조건 분석, 정비소용 리포트, 길 안내를 사용합니다.
 *
 * 화면은 Pleos Connect UI Design Guide와 기본 테마 토큰을 따릅니다:
 * 무채색 위주, 모서리 반경 0, 테두리 버튼, 눌림 시 95% 축소, 주간·야간 모드, 가로·세로 창 대응.
 */
class MainActivity : Activity(), VehicleMonitor.Listener, Voice.Listener {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var store: RecordStore
    private lateinit var vehicle: VehicleMonitor
    private lateinit var car: CarBridge
    private lateinit var location: LocationMonitor
    private lateinit var navi: NaviBridge
    private lateinit var voice: Voice

    /** 버튼을 누른 순간(말하기 전)의 차량 상태 */
    private var pendingSnapshot: Snapshot? = null
    private var renderQueued = false
    private var restrictedShown: Boolean? = null
    private var dialog: Dialog? = null
    private var statusMessage = "이상을 느낀 순간 버튼을 누르고 말해 주세요."

    private lateinit var palette: Palette
    /** 차량 디스플레이는 화소 밀도가 낮아(160dpi) 창 크기에 맞춰 글자와 여백을 키웁니다. */
    private var uiScale = 1f
    private var landscape = true

    private lateinit var micButton: LinearLayout
    private lateinit var micIcon: ImageView
    private lateinit var micLabel: TextView
    private lateinit var statusText: TextView
    private val tiles = HashMap<String, TextView>()
    private lateinit var parkedActions: LinearLayout
    private lateinit var headerText: TextView
    private lateinit var reportButton: View
    private lateinit var serviceButton: View
    private lateinit var drivingNotice: View
    private lateinit var scroll: ScrollView
    private lateinit var patternBox: LinearLayout
    private lateinit var listBox: LinearLayout

    private val ticker = object : Runnable {
        override fun run() {
            navi.refreshLocation()
            renderState()
            main.postDelayed(this, 5_000)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RecordStore(this)
        vehicle = VehicleMonitor(this, this)
        car = CarBridge(this) { queueRender() }
        location = LocationMonitor(this)
        navi = NaviBridge(this)
        voice = Voice(this, this)
        rebuildUi()

        vehicle.start()
        car.start()
        navi.start()
        voice.start()
        requestRuntimePermissions()
        main.postDelayed(ticker, 1_000)
    }

    /** 주간·야간 전환이나 창 크기 변경 시 화면을 다시 만듭니다. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dialog?.dismiss()
        rebuildUi()
    }

    private fun rebuildUi() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        palette = Palette(night)
        val (w, h) = windowBounds()
        landscape = w >= h * 1.15f
        val widthDp = w / resources.displayMetrics.density
        uiScale = (widthDp / (if (landscape) BASE_WIDTH_LANDSCAPE else BASE_WIDTH_PORTRAIT)).coerceIn(1f, 2f)
        window.decorView.setBackgroundColor(palette.surface)
        tiles.clear()
        restrictedShown = null
        setContentView(buildUi())
        onListeningChanged(voice.listening)
        renderState()
        renderRecords()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        voice.stop()
        navi.stop()
        location.stop()
        car.stop()
        vehicle.stop()
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val missing = RUNTIME_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) location.start() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        location.start()
        car.startSpeed()
    }

    // ---------------------------------------------------------------- recording

    private fun captureSnapshot(): Snapshot = Snapshot(
        timeMillis = System.currentTimeMillis(),
        speedKmh = car.speedKmh,
        gear = car.gear,
        brakePressed = vehicle.brakePressed,
        steeringAngle = vehicle.steeringAngle,
        turnSignal = Labels.turnSignal(vehicle.turnSignal),
        drivingMode = Labels.drivingMode(vehicle.drivingMode),
        odometerKm = vehicle.odometerKm,
        speedLimitKmh = navi.speedLimitKmh,
        latitude = location.latitude,
        longitude = location.longitude,
        address = navi.address,
        warnings = vehicle.activeWarnings.mapNotNull { Labels.WARNINGS[it] },
    )

    private fun onMicClicked() {
        if (voice.listening) {
            voice.finish()
            return
        }
        // 증상을 느낀 순간에 가장 가까운 시점이므로 말하기 전에 먼저 상태를 잡아 둡니다.
        pendingSnapshot = captureSnapshot()
        navi.refreshLocation()
        if (voice.listen()) setStatus("듣고 있어요. 어떤 증상인지 말해 주세요.")
    }

    private fun saveRecord(
        text: String,
        source: Source,
        snapshot: Snapshot,
        category: Category = Category.classify(text),
        spoken: String? = null,
    ) {
        val snap = if (snapshot.address == null && navi.address != null) snapshot.copy(address = navi.address) else snapshot
        val saved = Record(UUID.randomUUID().toString(), text, category, source, snap)
        store.add(saved)

        val records = store.records
        val title = PatternAnalyzer.groupTitle(saved)
        val sameCount = records.count { it.category != Category.OTHER && PatternAnalyzer.groupTitle(it) == title }
        val repeated = category != Category.OTHER && sameCount >= PatternAnalyzer.CONFIDENT_RECORDS &&
            PatternAnalyzer.analyze(records).any { it.title == title && it.findings.isNotEmpty() }
        val feedback = spoken ?: buildString {
            append("기록했어요.")
            if (category != Category.OTHER) append(" $title ${sameCount}번째예요.")
            if (repeated) append(" 반복되는 조건이 보여요. 정차 후 확인해 보세요.")
        }
        setStatus("기록했어요: \"$text\"")
        voice.speak(feedback)
        renderRecords()
    }

    // ---------------------------------------------------------------- callbacks

    override fun onVehicleStateChanged() = queueRender()

    override fun onWarningsTurnedOn(types: List<TelltaleType>) {
        val names = types.mapNotNull { Labels.WARNINGS[it] }
        if (names.isEmpty()) return
        val joined = names.joinToString(", ")
        saveRecord(
            text = "${PatternAnalyzer.WARNING_PREFIX} $joined",
            source = Source.AUTO,
            snapshot = captureSnapshot(),
            category = Category.WARNING,
            spoken = "$joined 경고등이 켜졌어요. 지금 차량 상태를 함께 기록해 뒀어요.",
        )
    }

    override fun onListeningChanged(listening: Boolean) {
        val bg = if (listening) palette.danger else palette.primary
        val fg = if (listening) palette.onDanger else palette.onPrimary
        micButton.background = solid(bg)
        micLabel.text = if (listening) "듣는 중 · 누르면 끝내기" else "증상 말하기"
        micLabel.setTextColor(fg)
        micIcon.imageTintList = ColorStateList.valueOf(fg)
    }

    override fun onPartialText(text: String) = setStatus("\"$text\"")

    override fun onFinalText(text: String) {
        saveRecord(text, Source.VOICE, pendingSnapshot ?: captureSnapshot())
        pendingSnapshot = null
    }

    override fun onVoiceError(message: String) {
        pendingSnapshot = null
        setStatus(if (isDrivingRestricted()) message else "$message\n정차 중에는 '직접 입력'으로도 남길 수 있어요.")
    }

    // ---------------------------------------------------------------- rendering

    /** 주행 규제 상태. 플랫폼 값이 없으면 기어가 P가 아닐 때를 주행으로 봅니다. */
    private fun isDrivingRestricted(): Boolean =
        car.restricted ?: (car.gear != null && car.gear != "P")

    private fun queueRender() {
        if (renderQueued) return
        renderQueued = true
        main.post {
            renderQueued = false
            renderState()
        }
    }

    private fun renderState() {
        tiles[T_SPEED]?.text = Labels.speed(car.speedKmh)
        tiles[T_GEAR]?.text = car.gear ?: Labels.UNKNOWN
        tiles[T_BRAKE]?.text = Labels.brake(vehicle.brakePressed)
        tiles[T_STEERING]?.text = Labels.steering(vehicle.steeringAngle)
        tiles[T_SIGNAL]?.text = Labels.turnSignal(vehicle.turnSignal) ?: Labels.UNKNOWN
        tiles[T_MODE]?.text = Labels.drivingMode(vehicle.drivingMode) ?: Labels.UNKNOWN
        tiles[T_ODOMETER]?.text = Labels.odometer(vehicle.odometerKm)
        val warnings = vehicle.activeWarnings.mapNotNull { Labels.WARNINGS[it] }.map { Labels.warningShort(it) }
        tiles[T_WARNING]?.apply {
            // 칸 크기가 변하지 않도록 한 줄로만 보여 주고, 여러 개면 개수를 붙입니다.
            text = when (warnings.size) {
                0 -> "없음"
                1 -> warnings[0]
                else -> "${warnings[0]} 외 ${warnings.size - 1}"
            }
            setTextColor(if (warnings.isEmpty()) palette.text else palette.dangerText)
        }
        applyRestriction(isDrivingRestricted())
    }

    private fun applyRestriction(restricted: Boolean) {
        if (restrictedShown == restricted) return
        restrictedShown = restricted
        val parkedOnly = if (restricted) View.GONE else View.VISIBLE
        drivingNotice.visibility = if (restricted) View.VISIBLE else View.GONE
        scroll.visibility = parkedOnly
        parkedActions.visibility = parkedOnly
        reportButton.visibility = parkedOnly
        serviceButton.visibility = parkedOnly
        // 주행 중에는 유일한 조작 대상인 버튼을 더 크게 보여 줍니다.
        micButton.layoutParams = micButton.layoutParams.apply { height = px(micHeight(restricted)) }
        if (restricted) {
            dialog?.dismiss()
            dialog = null
        }
    }

    private fun renderRecords() {
        val records = store.records
        headerText.text = "증상 기록 ${records.size}건"
        patternBox.removeAllViews()
        listBox.removeAllViews()

        PatternAnalyzer.analyze(records).forEach { patternBox.addView(patternCard(it), block(bottom = 10)) }

        if (records.isEmpty()) {
            listBox.addView(
                text("아직 기록이 없어요.\n이상을 느낀 순간 '증상 말하기'를 누르고 말해 보세요.", S_BODY, palette.textSub),
                block(top = 20),
            )
        }
        records.forEach { listBox.addView(recordRow(it, clickable = true)) }
    }

    private fun micHeight(driving: Boolean): Int = when {
        !landscape -> if (driving) 200 else 96
        driving -> 176
        else -> 116
    }

    private fun setStatus(message: String) {
        statusMessage = message
        statusText.text = message
    }

    // ---------------------------------------------------------------- parked-only actions

    private fun showTextInput() {
        if (isDrivingRestricted()) return
        val snapshot = captureSnapshot()
        val input = EditText(this).apply {
            hint = "예: 브레이크 밟을 때 끼익 소리가 나요"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(S_BODY).toFloat())
            setTextColor(palette.text)
            setHintTextColor(palette.textSub)
            background = bordered(palette.surface, palette.outline)
            setPadding(px(16), px(14), px(16), px(14))
        }
        showPanel(
            title = "증상 직접 입력",
            content = input,
            actions = listOf(
                Action("취소") {},
                Action("기록", primary = true) {
                    val value = input.text.toString().trim()
                    if (value.isNotEmpty()) saveRecord(value, Source.TEXT, snapshot)
                },
            ),
        )
    }

    private fun addSamples() {
        if (isDrivingRestricted()) return
        store.addAll(Samples.create(System.currentTimeMillis()))
        setStatus("예시 기록 5건을 넣었어요. 실제 차량 데이터가 아닙니다.")
        renderRecords()
    }

    private fun confirmClear() {
        if (isDrivingRestricted()) return
        showPanel(
            title = "전체 삭제",
            content = text("저장된 증상 기록 ${store.records.size}건을 모두 지울까요?", S_BODY, palette.text),
            actions = listOf(
                Action("취소") {},
                Action("삭제", primary = true) {
                    store.clear()
                    setStatus("기록을 모두 지웠어요.")
                    renderRecords()
                },
            ),
        )
    }

    private fun showDetail(record: Record) {
        if (isDrivingRestricted()) return
        val body = column()
        body.addView(recordRow(record, clickable = false, showBrief = false))
        body.addView(snapshotTable(record.snapshot), block(top = 6))
        showPanel(
            title = "증상 기록 상세",
            content = ScrollView(this).apply { addView(body) },
            actions = listOf(Action("닫기", primary = true) {}),
        )
    }

    private fun showReport() {
        if (isDrivingRestricted()) return
        val records = store.records
        val body = column()
        body.addView(
            text(
                "작성 ${Labels.time(System.currentTimeMillis())}   ·   현재 누적 주행거리 ${Labels.odometer(vehicle.odometerKm)}",
                S_LABEL, palette.textSub,
            ),
        )
        body.addView(text("반복되는 조건", S_TITLE_SMALL, palette.text, bold = true), block(top = 18, bottom = 8))
        val patterns = PatternAnalyzer.analyze(records)
        if (patterns.isEmpty()) {
            body.addView(text("같은 증상의 기록이 2건 이상 쌓이면 표시됩니다.", S_BODY, palette.textSub))
        }
        patterns.forEach { body.addView(patternCard(it), block(bottom = 10)) }

        body.addView(text("증상 기록 ${records.size}건 (최신순)", S_TITLE_SMALL, palette.text, bold = true), block(top = 18))
        if (records.isEmpty()) body.addView(text("아직 기록이 없어요.", S_BODY, palette.textSub), block(top = 8))
        records.forEach { r ->
            body.addView(recordRow(r, clickable = false, showBrief = false))
            body.addView(snapshotTable(r.snapshot, compact = true), block(bottom = 6))
        }
        body.addView(
            text("차량이 제공한 값만 기록했으며, 얻지 못한 항목은 '확인 불가'로 표시합니다.", S_CAPTION, palette.textSub),
            block(top = 14),
        )
        showPanel(
            title = "정비소용 증상 리포트",
            content = ScrollView(this).apply { addView(body) },
            actions = listOf(Action("닫기", primary = true) {}),
            tall = true,
            scrolling = true,
        )
    }

    private fun onServiceClicked() {
        if (isDrivingRestricted()) return
        val saved = store.servicePlace
        if (saved == null) {
            pickPlace()
            return
        }
        val where = if (saved.address.isBlank()) saved.name else "${saved.name}\n${saved.address}"
        showPanel(
            title = "정비소 길 안내",
            content = text("$where\n\n이 정비소로 길 안내를 시작할까요?", S_BODY, palette.text),
            actions = listOf(
                Action("닫기") {},
                Action("정비소 변경") { pickPlace() },
                Action("안내 시작", primary = true) { startRoute(saved) },
            ),
        )
    }

    /**
     * 단골 정비소 등록. 내비게이션 즐겨찾기·최근 목적지에서 고르거나,
     * 정비소에 와 있을 때 지금 위치를 저장합니다.
     */
    private fun pickPlace() {
        setStatus("내비게이션에서 장소 목록을 불러오는 중이에요.")
        navi.loadPlaces { places ->
            if (isFinishing || isDestroyed || isDrivingRestricted()) return@loadPlaces
            val sorted = places.sortedByDescending { p -> SERVICE_KEYWORDS.any { p.name.contains(it) } }
            setStatus(if (sorted.isEmpty()) "내비게이션 즐겨찾기·최근 목적지에 장소가 없어요." else "단골 정비소를 골라 주세요.")

            val list = column()
            var panel: Dialog? = null
            fun choose(place: Place) {
                panel?.dismiss()
                store.servicePlace = place
                setStatus("${place.name}을(를) 단골 정비소로 저장했어요.")
                onServiceClicked()
            }
            val lat = location.latitude
            val lng = location.longitude
            if (lat != null && lng != null) {
                list.addView(
                    button("지금 있는 곳을 단골 정비소로 저장", primary = false) {
                        choose(Place("단골 정비소", lat, lng, navi.address ?: ""))
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, px(H_BUTTON)).apply { bottomMargin = px(8) },
                )
            }
            sorted.forEach { place ->
                val label = if (place.address.isBlank()) place.name else "${place.name}  ·  ${place.address}"
                list.addView(
                    button(label, primary = false) { choose(place) },
                    LinearLayout.LayoutParams(MATCH_PARENT, px(H_BUTTON)).apply { bottomMargin = px(8) },
                )
            }
            if (list.childCount == 0) {
                list.addView(text("내비게이션에서 단골 정비소를 즐겨찾기에 추가한 뒤 다시 눌러 주세요.", S_BODY, palette.text))
            }
            panel = showPanel(
                title = "단골 정비소 등록",
                content = ScrollView(this).apply { addView(list) },
                actions = listOf(Action("취소") {}),
                scrolling = true,
            )
        }
    }

    private fun startRoute(place: Place) {
        setStatus("${place.name} 길 안내를 요청했어요.")
        navi.routeTo(place) { ok, error ->
            val message = if (ok) "${place.name} 길 안내를 시작했어요." else "길 안내를 시작하지 못했어요. (${error ?: "알 수 없는 오류"})"
            setStatus(message)
            voice.speak(if (ok) "${place.name} 길 안내를 시작했어요." else "길 안내를 시작하지 못했어요.")
        }
    }

    // ---------------------------------------------------------------- panel (system popup)

    private class Action(val label: String, val primary: Boolean = false, val run: () -> Unit)

    /**
     * 디자인 가이드의 System Popup 구성(제목, 본문, 확인·취소 버튼 가로 정렬)을 따른 팝업.
     * 시스템 기본 대화상자는 이 화면 밀도에서 글씨가 작아 직접 그립니다.
     * 주행 규제가 켜지면 [applyRestriction]에서 닫습니다.
     */
    private fun showPanel(
        title: String,
        content: View,
        actions: List<Action>,
        tall: Boolean = false,
        scrolling: Boolean = false,
    ): Dialog {
        dialog?.dismiss()
        val panel = Dialog(this)
        panel.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val box = column().apply {
            background = solid(palette.surface)
            setPadding(px(32), px(28), px(32), px(28))
        }
        box.addView(text(title, S_TITLE, palette.text, bold = true), block(bottom = 16))
        box.addView(
            content,
            if (scrolling) LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEachIndexed { i, action ->
            row.addView(
                button(action.label, primary = action.primary) {
                    panel.dismiss()
                    action.run()
                },
                LinearLayout.LayoutParams(0, px(H_BUTTON), 1f).apply { if (i > 0) leftMargin = px(10) },
            )
        }
        box.addView(row, block(top = 22))

        panel.setContentView(box)
        panel.setOnDismissListener { if (dialog === panel) dialog = null }
        val (w, h) = windowBounds()
        val width = (w * (if (landscape) 0.62f else 0.9f)).toInt()
        val height = when {
            tall -> (h * 0.9f).toInt()
            scrolling -> (h * 0.62f).toInt()
            else -> WRAP_CONTENT
        }
        panel.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(if (palette.night) 0.6f else 0.4f)
        }
        panel.show()
        // show() 이후에 지정해야 크기가 적용됩니다.
        panel.window?.setLayout(width, height)
        dialog = panel
        return panel
    }

    private fun windowBounds(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }

    // ---------------------------------------------------------------- screen

    private fun buildUi(): View {
        val edge = px(24)
        val root = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            setPadding(edge, px(20), edge, px(20))
        }
        if (landscape) {
            root.addView(buildControls(), LinearLayout.LayoutParams(0, MATCH_PARENT, 0.4f))
            root.addView(vLine(), LinearLayout.LayoutParams(px(1), MATCH_PARENT).apply { setMargins(edge, 0, edge, 0) })
            root.addView(buildRecords(), LinearLayout.LayoutParams(0, MATCH_PARENT, 0.6f))
        } else {
            root.addView(buildControls(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            root.addView(hLine(), LinearLayout.LayoutParams(MATCH_PARENT, px(1)).apply { setMargins(0, px(16), 0, px(16)) })
            root.addView(buildRecords(), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        return root
    }

    /** 증상 말하기 버튼, 안내 문구, 실시간 차량 상태, 정차 중 버튼 */
    private fun buildControls(): View {
        val column = column()

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        title.addView(text("카르테", S_HEADLINE, palette.text, bold = true))
        title.addView(
            text("말 한마디로 남기는 내 차 증상 기록", S_LABEL, palette.textSub, lines = 1),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = px(12); bottomMargin = px(5) },
        )
        column.addView(title)

        micIcon = ImageView(this).apply { setImageResource(R.drawable.ic_mic) }
        micLabel = text("증상 말하기", S_TITLE, palette.onPrimary, bold = true, lines = 1)
        micButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            addView(micIcon, LinearLayout.LayoutParams(px(40), px(40)).apply { rightMargin = px(12) })
            addView(micLabel)
            setOnClickListener { onMicClicked() }
        }
        pressable(micButton)
        column.addView(micButton, LinearLayout.LayoutParams(MATCH_PARENT, px(micHeight(false))).apply { topMargin = px(14) })

        statusText = text(statusMessage, S_BODY, palette.textSub, lines = 2).apply { minLines = 2 }
        column.addView(statusText, block(top = 10))

        column.addView(text("지금 차량 상태", S_CAPTION, palette.textSub, bold = true), block(top = 12, bottom = 6))
        val perRow = if (landscape) 2 else 4
        val grid = column()
        TILE_KEYS.chunked(perRow).forEachIndexed { r, keys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            keys.forEachIndexed { c, key ->
                row.addView(
                    tile(key),
                    LinearLayout.LayoutParams(0, px(H_TILE), 1f).apply {
                        if (c > 0) leftMargin = px(8)
                        if (r > 0) topMargin = px(8)
                    },
                )
            }
            grid.addView(row)
        }
        if (landscape) {
            column.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            column.addView(grid)
        }

        parkedActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf<Pair<String, () -> Unit>>(
            "직접 입력" to { showTextInput() },
            "예시 기록" to { addSamples() },
            "전체 삭제" to { confirmClear() },
        ).forEachIndexed { i, (name, action) ->
            parkedActions.addView(
                button(name, primary = false, onClick = action),
                LinearLayout.LayoutParams(0, px(H_BUTTON), 1f).apply {
                    if (i > 0) leftMargin = px(8)
                    topMargin = px(10)
                },
            )
        }
        column.addView(parkedActions)
        return column
    }

    /** 기록 목록, 반복 조건, 리포트·정비소 버튼. 주행 중에는 안내 문구만 보입니다. */
    private fun buildRecords(): View {
        val column = column()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerText = text("증상 기록", S_TITLE, palette.text, bold = true, lines = 1)
        header.addView(headerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        reportButton = button("리포트", primary = false) { showReport() }
        serviceButton = button("정비소 안내", primary = true) { onServiceClicked() }
        header.addView(reportButton, LinearLayout.LayoutParams(px(130), px(H_BUTTON)))
        header.addView(serviceButton, LinearLayout.LayoutParams(px(170), px(H_BUTTON)).apply { leftMargin = px(8) })
        column.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, px(H_BUTTON)))

        drivingNotice = column().apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(text("주행 중", S_TITLE, palette.text, bold = true).apply { gravity = Gravity.CENTER })
            addView(
                text("음성 기록만 사용할 수 있어요.\n기록과 리포트는 정차 후 확인하세요.", S_BODY, palette.textSub).apply { gravity = Gravity.CENTER },
                block(top = 8),
            )
        }
        column.addView(drivingNotice, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        patternBox = column()
        listBox = column()
        val content = column().apply {
            addView(patternBox)
            addView(listBox)
        }
        scroll = ScrollView(this).apply { addView(content) }
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = px(12) })
        return column
    }

    // ---------------------------------------------------------------- components

    /** 차량 상태 한 칸. 높이를 고정하고 값은 한 줄로만 보여 줍니다. */
    private fun tile(key: String): View {
        val value = text(Labels.UNKNOWN, S_BODY_LARGE, palette.text, bold = true, lines = 1)
        tiles[key] = value
        return column().apply {
            background = solid(palette.container)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14), 0, px(14), 0)
            addView(text(key, S_CAPTION, palette.textSub, lines = 1))
            addView(value, block(top = 2))
        }
    }

    /** 반복 조건 카드: 묶음 이름, 조건별 건수와 비율 막대 */
    private fun patternCard(pattern: Pattern): View = column().apply {
        background = solid(palette.container)
        setPadding(px(16), px(14), px(16), px(14))

        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tag("반복 조건", palette.onPrimary, palette.primary))
        head.addView(
            text("${pattern.title} ${pattern.total}건", S_BODY, palette.text, bold = true, lines = 1),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = px(10) },
        )
        addView(head)

        if (pattern.findings.isEmpty()) {
            addView(text("아직 뚜렷한 공통 조건이 없어요.", S_LABEL, palette.textSub), block(top = 8))
        }
        pattern.findings.forEach { f ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(f.label, S_BODY, palette.text, lines = 1), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(ratioBar(f.ratio), LinearLayout.LayoutParams(px(90), px(8)).apply { rightMargin = px(12) })
            row.addView(
                text("${f.count}/${f.total}", S_BODY, palette.text, bold = true, lines = 1).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(px(46), WRAP_CONTENT),
            )
            addView(row, block(top = 8))
        }
        if (pattern.total < PatternAnalyzer.CONFIDENT_RECORDS) {
            addView(text("기록이 3건 이상 쌓이면 더 믿을 수 있어요.", S_CAPTION, palette.textSub), block(top = 8))
        }
    }

    private fun ratioBar(ratio: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = solid(palette.outlineWeak)
        val filled = ratio.coerceIn(0f, 1f)
        addView(View(this@MainActivity).apply { background = solid(palette.primary) }, LinearLayout.LayoutParams(0, MATCH_PARENT, filled))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f - filled))
    }

    /**
     * 기록 한 줄: 분류 태그(색), 기록 경로 태그(테두리), 시각, 증상 문장, 스냅샷 요약.
     * 목록에서는 눌러서 상세를 엽니다.
     */
    private fun recordRow(record: Record, clickable: Boolean, showBrief: Boolean = true): View {
        val color = palette.category(record.category)
        val row = column().apply { setPadding(0, px(14), 0, px(14)) }

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(tag(record.category.label, color, palette.tint(color)))
        meta.addView(tag(record.source.label, palette.textSub, Color.TRANSPARENT, stroke = palette.outlineWeak), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = px(6) })
        meta.addView(
            text(Labels.time(record.snapshot.timeMillis), S_CAPTION, palette.textSub, lines = 1).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        row.addView(meta)
        row.addView(text(record.text, S_BODY_LARGE, palette.text, bold = true, lines = 2), block(top = 8))
        if (showBrief) {
            row.addView(text(Labels.brief(record.snapshot), S_LABEL, palette.textSub, lines = 2), block(top = 4))
        }

        val wrapper = column()
        wrapper.addView(row)
        wrapper.addView(hLine(), LinearLayout.LayoutParams(MATCH_PARENT, px(1)))
        if (clickable) {
            wrapper.isClickable = true
            wrapper.isFocusable = true
            wrapper.setOnClickListener { showDetail(record) }
            pressable(wrapper, scale = 0.98f)
        }
        return wrapper
    }

    /** 스냅샷 항목을 이름-값 표로 보여 줍니다. compact면 두 열로 나눕니다. */
    private fun snapshotTable(snapshot: Snapshot, compact: Boolean = false): View {
        val fields = Labels.fields(snapshot)
        fun cell(label: String, value: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(7), 0, px(7))
            addView(text(label, S_LABEL, palette.textSub, lines = 1), LinearLayout.LayoutParams(px(if (compact) 120 else 170), WRAP_CONTENT))
            addView(text(value, S_LABEL, palette.text, bold = true, lines = 2), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val table = column()
        if (!compact || !landscape) {
            fields.forEach { (l, v) -> table.addView(cell(l, v)) }
            return table
        }
        fields.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (l, v) -> line.addView(cell(l, v), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            table.addView(line)
        }
        return table
    }

    /** 색 라벨. 분류는 옅은 배경 + 같은 색 글자, 경로는 테두리만 씁니다. */
    private fun tag(label: String, fg: Int, bg: Int, stroke: Int? = null): TextView =
        text(label, S_CAPTION, fg, bold = true, lines = 1).apply {
            background = if (stroke != null) bordered(bg, stroke) else solid(bg)
            setPadding(px(9), px(3), px(9), px(4))
        }

    /**
     * 기본 버튼. primary는 채움(검정/흰색), 그 외는 테두리 버튼.
     * 가이드의 버튼 인터랙션에 따라 누르면 95%로 줄어듭니다.
     */
    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        text(label, S_BODY, if (primary) palette.onPrimary else palette.onSecondary, bold = true, lines = 1).apply {
            gravity = Gravity.CENTER
            background = if (primary) solid(palette.primary) else bordered(palette.secondary, palette.outline)
            setPadding(px(12), 0, px(12), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            pressable(this)
        }

    private fun pressable(view: View, scale: Float = 0.95f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).alpha(0.8f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false, lines: Int = 0): TextView = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** 기본 테마 토큰의 모서리 반경은 0입니다. */
    private fun solid(color: Int): GradientDrawable = GradientDrawable().apply { setColor(color) }

    private fun bordered(color: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        setStroke(maxOf(1, px(1)), stroke)
    }

    private fun hLine(): View = View(this).apply { setBackgroundColor(palette.divider) }

    private fun vLine(): View = View(this).apply { setBackgroundColor(palette.divider) }

    private fun block(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(top)
            bottomMargin = px(bottom)
        }

    /** 설계 기준(dp) 값을 현재 창 배율에 맞춘 픽셀로 바꿉니다. */
    private fun px(value: Int): Int = (value * resources.displayMetrics.density * uiScale).toInt()

    private companion object {
        /** 이 너비(dp)에서 배율 1.0으로 설계했습니다. */
        const val BASE_WIDTH_LANDSCAPE = 1120f
        const val BASE_WIDTH_PORTRAIT = 620f

        // 글자 크기(설계 기준). 1680px 너비 창에서 1.5배가 되어 시스템 팝업(제목 38, 버튼 28)과 비슷해집니다.
        const val S_HEADLINE = 30
        const val S_TITLE = 25
        const val S_TITLE_SMALL = 22
        const val S_BODY_LARGE = 20
        const val S_BODY = 18
        const val S_LABEL = 16
        const val S_CAPTION = 14

        const val H_BUTTON = 64
        const val H_TILE = 70

        const val T_SPEED = "속도"
        const val T_GEAR = "기어"
        const val T_BRAKE = "브레이크"
        const val T_STEERING = "핸들"
        const val T_SIGNAL = "방향지시등"
        const val T_MODE = "주행 모드"
        const val T_ODOMETER = "누적 주행거리"
        const val T_WARNING = "켜진 경고등"
        val TILE_KEYS = listOf(T_SPEED, T_GEAR, T_BRAKE, T_STEERING, T_SIGNAL, T_MODE, T_ODOMETER, T_WARNING)

        /** 설치 후 사용자 허용이 필요한 권한 */
        val RUNTIME_PERMISSIONS = listOf(
            "pleos.car.permission.FUSED_LOCATION",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_POWERTRAIN",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_SAFETY",
            "pleos.car.permission.PRIVATE_CAR_SAFETY",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.STT_SERVICE",
            "pleos.car.permission.TTS_SERVICE",
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH",
        )

        val SERVICE_KEYWORDS = listOf("블루핸즈", "오토큐", "하이테크", "서비스센터", "정비", "카센터", "공업사", "모터스")
    }
}
