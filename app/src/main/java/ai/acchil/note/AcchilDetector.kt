package ai.acchil.note

import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.os.SystemClock
import kotlin.math.abs

/**
 * 아찔 순간 감지 규칙. acchil-probe 에서 시나리오로 검증한 규칙을 그대로 옮겼다.
 * - INTERVENTION: 크루즈 작동 중 브레이크, 또는 작동 → 해제 전이
 * - SWERVE: 0.5초 안에 조향각 90° 이상 변화
 * - HARD_BRAKE: 1초 안에 14 km/h 이상 감속 (AAOS 차속이 들어올 때만)
 * - onTripEnd: 주행 후 P 진입
 */
class AcchilDetector(
    private val onEvent: (type: String, detail: String, context: List<String>) -> Unit,
    private val onTripEnd: () -> Unit,
) {
    var raining = false
    var night = false
    val cruiseOn get() = cruise == CruiseControl.ACTIVATED

    private var cruise: CruiseControl? = null
    private var lastTurnSignalAt = 0L
    private var lastSteer: Pair<Long, Float>? = null
    private val speeds = ArrayDeque<Pair<Long, Float>>()
    private val lastEmit = mutableMapOf<String, Long>()
    private var driving = false

    fun onCruise(state: CruiseControl?) {
        val was = cruise
        cruise = state
        if (was == CruiseControl.ACTIVATED && state != CruiseControl.ACTIVATED) {
            fire(INTERVENTION, "크루즈 해제")
        }
    }

    fun onBrake(pressed: Boolean) {
        if (pressed && cruise == CruiseControl.ACTIVATED) fire(INTERVENTION, "크루즈 중 브레이크")
    }

    fun onSteering(angle: Float) {
        val now = now()
        lastSteer?.let { (t, a) ->
            if (now - t <= 500 && abs(angle - a) >= 90f) fire(SWERVE, "핸들 %.0f°".format(angle - a))
        }
        lastSteer = now to angle
    }

    fun onSpeed(kph: Float) {
        val now = now()
        if (kph > 5f) driving = true
        speeds.addLast(now to kph)
        while (speeds.isNotEmpty() && now - speeds.first().first > 1000) speeds.removeFirst()
        val peak = speeds.maxOf { it.second }
        if (peak - kph >= HARD_BRAKE_KPH_PER_S) fire(HARD_BRAKE, "1초에 -%.0fkm/h".format(peak - kph))
    }

    fun onTurnSignal(on: Boolean) {
        if (on) lastTurnSignalAt = now()
    }

    fun onGear(gear: VehicleGear) {
        if (gear == VehicleGear.GEAR_DRIVE) driving = true
        if (gear == VehicleGear.GEAR_PARK && driving) {
            driving = false
            onTripEnd()
        }
    }

    private fun fire(type: String, detail: String) {
        val now = now()
        if (now - (lastEmit[type] ?: 0L) < 3000) return
        lastEmit[type] = now
        val context = buildList {
            if (raining) add(RAIN)
            if (night) add(NIGHT)
            if (now - lastTurnSignalAt < 3000) add(LANE_CHANGE)
        }
        onEvent(type, detail, context)
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val INTERVENTION = "INTERVENTION"
        const val HARD_BRAKE = "HARD_BRAKE"
        const val SWERVE = "SWERVE"
        const val RAIN = "rain"
        const val NIGHT = "night"
        const val LANE_CHANGE = "lane-change"

        // 약 0.4g. 12로 두면 일반 정차(약 10km/h/s)도 샘플 지터로 걸렸다
        private const val HARD_BRAKE_KPH_PER_S = 14f

        fun label(key: String) = when (key) {
            INTERVENTION -> "운전자 개입"
            HARD_BRAKE -> "급제동"
            SWERVE -> "급회피"
            RAIN -> "비"
            NIGHT -> "야간"
            LANE_CHANGE -> "차로 변경 중"
            else -> key
        }
    }
}
