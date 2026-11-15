package ai.acchil.note

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private var carProperties: CarPropertyManager? = null
    private lateinit var store: MomentStore

    // 현재 차량 상태 (100ms마다 샘플로 기록)
    private var gear: VehicleGear? = null
    private var speedKph = 0f
    private var brake = false
    private var steering = 0f

    private val recorder = MomentRecorder { m -> onMomentFinalized(m) }
    private val detector = AcchilDetector(
        onEvent = { type, detail, ctx ->
            Log.i(TAG, "EVENT $type $detail $ctx")
            recorder.event(type, detail, ctx)
        },
        onTripEnd = {
            recorder.flush()
            render()
        },
    )

    private lateinit var content: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MomentStore(this)
        content = FrameLayout(this)
        setContentView(content)
        render()

        vehicle = Vehicle(applicationContext)
        try {
            vehicle.initialize()
            registerVehicleListeners()
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle SDK initialize failed", e)
        }
        if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) startCarSpeed()
        else requestPermissions(arrayOf(Car.PERMISSION_SPEED), REQ_SPEED)

        main.post(sampler)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == REQ_SPEED && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCarSpeed()
    }

    override fun onDestroy() {
        main.removeCallbacks(sampler)
        carProperties?.unregisterCallback(speedCallback)
        car?.disconnect()
        unregisterVehicleListeners()
        vehicle.release()
        super.onDestroy()
    }

    private val sampler = object : Runnable {
        override fun run() {
            recorder.sample(Sample(SystemClock.elapsedRealtime(), speedKph, brake, steering, detector.cruiseOn))
            main.postDelayed(this, 100)
        }
    }

    private fun onMomentFinalized(m: Moment) {
        Log.i(TAG, "MOMENT ${m.types} ${m.context} samples=${m.samples.size}")
        store.add(m)
        render()
    }

    // ---------------------------------------------------------------- 차량 신호

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(g: VehicleGear?) = onMain {
            val changed = isParked() != (g == null || g == VehicleGear.GEAR_PARK)
            gear = g
            g?.let(detector::onGear)
            if (changed) render()
        }
        override fun onFailed(e: Exception) = fail("gear", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = onMain {
            brake = pressed == true
            detector.onBrake(brake)
        }
        override fun onFailed(e: Exception) = fail("brake", e)
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = onMain {
            angle ?: return@onMain
            steering = angle
            detector.onSteering(angle)
        }
        override fun onFailed(e: Exception) = fail("steering", e)
    }

    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) = onMain { detector.onCruise(state) }
        override fun onFailed(e: Exception) = fail("cruise", e)
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = onMain {
            detector.onTurnSignal(value != null && value != TurnSignalValue.OFF)
        }
        override fun onFailed(e: Exception) = fail("turnSignal", e)
    }

    private val wiperArea = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) = onMain { detector.raining = state == WiperState.ON }
        override fun onFailed(e: Exception) = fail("wiper", e)
    }

    private val lowBeamListener = object : LightStateListener {
        override fun onLightStateUpdated(state: LightState?) = onMain { detector.night = state == LightState.ON }
        override fun onFailed(e: Exception) = fail("lowBeam", e)
    }

    private fun registerVehicleListeners() {
        safely("gear") { vehicle.getGear().registerGearSelection(gearListener) }
        safely("brake") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        safely("steering") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        safely("cruise") { vehicle.getDrivingMode().registerCruiseControlState(cruiseListener) }
        safely("turnSignal") { vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener) }
        safely("wiper") { vehicle.getWiper().registerWiperState(wiperArea, wiperListener) }
        safely("lowBeam") { vehicle.getLight().registerLowBeamLightState(lowBeamListener) }
    }

    private fun unregisterVehicleListeners() {
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { vehicle.getDrivingMode().unregisterCruiseControlState(cruiseListener) }
        runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
        runCatching { vehicle.getWiper().unregisterWiperState(wiperArea, wiperListener) }
        runCatching { vehicle.getLight().unregisterLowBeamLightState(lowBeamListener) }
    }

    // Pleos Vehicle SDK에 차속 API가 없어 표준 AAOS 속성을 쓴다
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            onMain {
                speedKph = mps * 3.6f
                detector.onSpeed(speedKph)
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
    }

    private fun startCarSpeed() {
        safely("speed") {
            val c = Car.createCar(this).also { car = it }
            val manager = (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).also { carProperties = it }
            manager.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST)
        }
    }

    // ---------------------------------------------------------------- 화면

    private val p by lazy { Palette.of(this) }

    private fun isParked() = gear == null || gear == VehicleGear.GEAR_PARK

    private fun todayMoments(): List<Moment> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return store.moments.filter { it.wallTime >= start }.sortedByDescending { it.wallTime }
    }

    private fun render() {
        content.removeAllViews()
        content.setBackgroundColor(p.background)
        content.addView(if (isParked()) reviewScreen() else drivingScreen())
    }

    /** 주행 중: 1~2초 안에 읽히는 상태 표시만. 조작 요소 없음. */
    private fun drivingScreen(): View {
        val today = todayMoments()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(statusPill("기록 중", p.green), wrap())
            addView(text("${today.size}", Type.DISPLAY, p.text1, 700), wrap(top = 20))
            addView(text("오늘 감지된 아찔 순간", Type.TITLE, p.text2, 500), wrap())
        }
    }

    /** 정차 중: 오늘 회고 / 주간 리포트 두 탭. */
    private fun reviewScreen(): View {
        val today = todayMoments()
        val tagged = today.count { it.cause != null }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(32), dp(40), dp(40))
        }
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (tab == Tab.TODAY) {
                    addView(text("오늘의 아찔 회고", Type.HEADLINE, p.text1, 700))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(
                            if (today.isEmpty()) "아직 감지된 순간이 없어요" else "${today.size}번 감지 · 원인 기록 $tagged/${today.size}",
                            Type.BODY, p.text2,
                        ))
                        today.flatMap { it.types }.groupingBy { it }.eachCount().forEach { (type, n) ->
                            addView(badge("${AcchilDetector.label(type)} $n", p.colorFor(type)), wrap(start = 10))
                        }
                    }, wrap(top = 6))
                } else {
                    addView(text("주간 아찔 리포트", Type.HEADLINE, p.text1, 700))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(tabs(), wrap(start = 16))
            addView(text("시연 데이터", Type.LABEL, p.text3).apply {
                setPadding(dp(16), dp(10), dp(4), dp(10))
                setOnClickListener {
                    store.moments.removeAll { DEMO_DETAIL in it.details }
                    store.moments += DemoData.moments()
                    store.save()
                    render()
                }
            }, wrap(start = 12))
            if (store.moments.isNotEmpty()) addView(text("초기화", Type.LABEL, p.text3).apply {
                setPadding(dp(16), dp(10), dp(4), dp(10))
                setOnClickListener { store.clear(); render() }
            }, wrap(start = 4))
        }, matchWrap())

        if (tab == Tab.REPORT) {
            list.addView(reportContent(), matchWrap(top = 24))
        } else {
            if (today.isEmpty()) {
                list.addView(cardContainer().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(32), dp(64), dp(32), dp(64))
                    addView(text("오늘은 아찔한 순간이 없었어요", Type.TITLE, p.text1, 600), wrap())
                }, matchWrap(top = 28))
            }
            today.forEach { list.addView(momentCard(it), matchWrap(top = 24)) }
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
    }

    private enum class Tab { TODAY, REPORT }
    private var tab = Tab.TODAY

    /** 세그먼트 탭: 오늘 회고 / 주간 리포트. */
    private fun tabs() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = rounded(p.surfaceHigh, 999)
        listOf(Tab.TODAY to "오늘 회고", Tab.REPORT to "주간 리포트").forEach { (t, label) ->
            val selected = t == tab
            addView(text(label, Type.BODY, if (selected) p.text1 else p.text2, if (selected) 700 else 500).apply {
                gravity = Gravity.CENTER
                if (selected) background = rounded(p.surface, 999)
                setOnClickListener { tab = t; render() }
            }, LinearLayout.LayoutParams(dp(150), dp(52)))
        }
    }

    // ---------------------------------------------------------------- 주간 리포트

    private fun reportContent(): View {
        val st = WeeklyStats(store.moments)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1행: 요약 + 요일별 막대 / 이번 주 패턴
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cardContainer().apply {
                addView(text("최근 7일", Type.BODY, p.text2, 600))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(text("${st.thisWeek.size}", 64f, p.text1, 700))
                    addView(text("번", Type.TITLE, p.text1, 600).apply { setPadding(0, 0, 0, dp(10)) }, wrap(start = 4))
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(changePill(st.changePct).apply { }, wrap().apply { bottomMargin = dp(14) })
                }, matchWrap())
                addView(WeekChartView(context, p, st), matchHeight(240, top = 12))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE, AcchilDetector.SWERVE).forEach { type ->
                        addView(View(context).apply { background = rounded(p.colorFor(type), 999) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(20) })
                        addView(text(AcchilDetector.label(type), Type.LABEL, p.text2, 500), wrap(start = 8))
                    }
                }, matchWrap(top = 16))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.35f))

            addView(cardContainer().apply {
                addView(text("이번 주 패턴", Type.TITLE, p.text1, 700))
                val insights = st.insights()
                if (insights.isEmpty()) {
                    addView(text("기록이 더 쌓이면 보여드릴게요", Type.BODY, p.text2), wrap(top = 20))
                }
                insights.forEach { ins ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(18), dp(20), dp(18))
                        background = rounded(p.surfaceLow, 18)
                        addView(badge(ins.tag, insightColor(ins.tag)), wrap())
                        addView(text(ins.title, Type.TITLE, p.text1, 600), wrap(top = 10))
                    }, matchWrap(top = 16))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(20) })
        }, matchWrap())

        // 2행: 유형 / 원인 / 시간대
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val types = listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE, AcchilDetector.SWERVE)
                .map { t -> Triple(AcchilDetector.label(t), st.byType.firstOrNull { it.first == t }?.second ?: 0, p.colorFor(t)) }
            val causes = st.byCause.take(4).map { (c, n) -> Triple(c, n, p.purple) }
            val times = st.byTime.map { (l, n) -> Triple(l.substringBefore("("), n, p.buttonFilled) }
            listOf(
                Triple("유형", types, null),
                Triple("원인", causes, "기록된 원인이 없어요"),
                Triple("시간대", times, null),
            ).forEachIndexed { k, (title, rows, empty) ->
                addView(barCard(title, rows, empty), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (k > 0) marginStart = dp(20)
                })
            }
        }, matchWrap(top = 20))
        return root
    }

    private fun insightColor(tag: String) = when (tag) {
        "원인" -> p.purple
        "날씨" -> p.blue
        "크루즈" -> p.blue
        else -> p.buttonFilled
    }

    /** 지난주 대비: 줄면 초록, 늘면 빨강. */
    private fun changePill(pct: Int?): View {
        val (label, color) = when {
            pct == null -> "지난주 기록 없음" to p.buttonFilled
            pct < 0 -> "지난주보다 ${-pct}% ↓" to p.green
            pct > 0 -> "지난주보다 $pct% ↑" to p.red
            else -> "지난주와 같아요" to p.buttonFilled
        }
        return text(label, Type.BODY, color, 700).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = rounded(p.alpha(color, 0.12f), 999)
        }
    }

    private fun barCard(title: String, rows: List<Triple<String, Int, Int>>, empty: String?) = cardContainer().apply {
        addView(text(title, Type.TITLE, p.text1, 700))
        if (rows.isEmpty() && empty != null) {
            addView(text(empty, Type.BODY, p.text2), wrap(top = 20))
            return@apply
        }
        val max = maxOf(1, rows.maxOf { it.second })
        rows.forEach { (label, n, color) ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(label, Type.BODY, p.text1, 500), LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = rounded(p.surfaceHigh, 999)
                    if (n > 0) addView(View(context).apply { background = rounded(color, 999) }, LinearLayout.LayoutParams(0, -1, n.toFloat()))
                    if (max - n > 0) addView(View(context), LinearLayout.LayoutParams(0, -1, (max - n).toFloat()))
                }, LinearLayout.LayoutParams(0, dp(14), 1f))
                addView(text("$n", Type.BODY, p.text1, 700).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            }, matchWrap(top = 18))
        }
    }

    private fun momentCard(m: Moment): View {
        val card = cardContainer()

        // 머리줄: 감지 유형 · 시각 · 상황 태그
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            m.types.forEachIndexed { k, type ->
                if (k > 0) addView(text("+", Type.TITLE, p.text3, 500), wrap(start = 8))
                addView(text(AcchilDetector.label(type), Type.TITLE, p.colorFor(type), 700), wrap(start = if (k > 0) 8 else 0))
            }
            addView(text(SimpleDateFormat("a h:mm", Locale.KOREA).format(m.wallTime), Type.BODY, p.text3), wrap(start = 16))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            m.context.forEach { addView(chip(AcchilDetector.label(it)), wrap(start = 8)) }
        }, matchWrap())

        card.addView(ReplayView(this, p).apply { moment = m }, matchHeight(360, top = 20))

        card.addView(View(this).apply { setBackgroundColor(p.surfaceHigh) }, matchHeight(1, top = 24))

        val answered = m.cause != null
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(if (answered) "기록했어요" else "무슨 일이었나요?", Type.BODY, p.text1, 600))
        }, matchWrap(top = 20))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            CAUSES.forEachIndexed { k, cause ->
                addView(causeButton(cause, cause == m.cause) {
                    m.cause = cause
                    store.save()
                    Log.i(TAG, "CAUSE ${m.types} -> $cause")
                    render()
                }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (k > 0) marginStart = dp(10) })
            }
        }, matchWrap(top = 14))
        return card
    }

    // ---------------------------------------------------------------- 컴포넌트

    private fun cardContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = rounded(p.surface, 24)
    }

    /** Basic button (선택 시 Filled). 가이드: Pressed 상태를 시각적으로 구분. */
    private fun causeButton(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = Type.BODY
        typeface = Type.asta(context, if (selected) 700 else 500)
        gravity = Gravity.CENTER
        setTextColor(if (selected) p.onFilled else p.text1)
        background = rounded(if (selected) p.buttonFilled else p.buttonBasic, 16)
        foreground = RippleDrawable(ColorStateList.valueOf(p.alpha(p.text1, 0.12f)), null, rounded(Color.WHITE, 16))
        setOnClickListener { onClick() }
    }

    private fun statusPill(label: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(8), dp(20), dp(8))
        background = rounded(p.alpha(color, 0.12f), 999)
        addView(View(context).apply { background = rounded(color, 999) }, LinearLayout.LayoutParams(dp(12), dp(12)))
        addView(text(label, Type.BODY, color, 700), wrap(start = 10))
    }

    private fun badge(label: String, color: Int) = text(label, Type.LABEL, color, 700).apply {
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(p.alpha(color, 0.1f), 999)
    }

    private fun chip(label: String) = text(label, Type.LABEL, p.text2, 500).apply {
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(p.surfaceHigh, 999)
    }

    private fun text(value: String, size: Float, color: Int, weight: Int = 400) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Type.asta(context, weight)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wrap(top: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); marginStart = dp(start) }

    private fun matchWrap(top: Int = 0) = matchHeight(-1, top)

    /** heightDp < 0 이면 WRAP_CONTENT. */
    private fun matchHeight(heightDp: Int, top: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (heightDp < 0) LinearLayout.LayoutParams.WRAP_CONTENT else dp(heightDp),
        ).apply { topMargin = dp(top) }

    // ---------------------------------------------------------------- 공통

    private fun fail(name: String, e: Exception) {
        Log.w(TAG, "FAILED $name", e)
    }

    private inline fun safely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail(name, e)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val TAG = "AcchilNote"
        private const val REQ_SPEED = 1
        private const val DEMO_DETAIL = "시연용 데이터"
        private val CAUSES = listOf("끼어들기", "보행자", "주정차 차량", "공사", "내 실수", "기타")
    }
}
