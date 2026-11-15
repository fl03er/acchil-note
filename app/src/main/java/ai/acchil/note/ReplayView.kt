package ai.acchil.note

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 저장된 신호로 아찔 순간을 다시 그린다.
 * 왼쪽: 위에서 본 도로 장면 (내 차의 속도·브레이크·크루즈·핸들은 실제 기록값)
 * 오른쪽: 속도·핸들 그래프와 크루즈·브레이크 구간
 * 운전자가 원인을 고르면 원인(끼어든 차 등)을 점선으로 겹쳐 그린다. 앱이 본 것이 아니라 운전자가 기록한 것이라서.
 */
class ReplayView(context: Context, private val p: Palette) : View(context) {

    var moment: Moment? = null
        set(value) {
            field = value
            prepare()
            startedAt = SystemClock.uptimeMillis()
            invalidate()
        }

    private var t: LongArray = LongArray(0)
    private var dist = FloatArray(0)   // 누적 주행거리(m)
    private var lateral = FloatArray(0) // 차로 내 좌우 위치(차로 폭 비율)
    private var startedAt = 0L
    private var carW = 0f
    private var carH = 0f

    private val dp = resources.displayMetrics.density
    private val regular = Type.asta(context, 400)
    private val bold = Type.asta(context, 700)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        pathEffect = DashPathEffect(floatArrayOf(9f * dp, 6f * dp), 0f)
    }
    private val rect = RectF()
    private val path = Path()

    // 장면 색: Pleos 계기판처럼 밝은 도로 위 은회색 차
    private val road = if (p.dark) Color.rgb(36, 38, 43) else Color.rgb(233, 235, 239)
    private val shoulder = if (p.dark) Color.rgb(26, 27, 31) else Color.rgb(246, 247, 249)
    private val laneLine = if (p.dark) Color.rgb(88, 92, 100) else Color.WHITE
    private val egoBody = if (p.dark) Color.rgb(196, 199, 205) else Color.rgb(98, 102, 112)
    private val egoGlass = if (p.dark) Color.rgb(70, 74, 82) else Color.rgb(46, 49, 56)

    private fun prepare() {
        val s = moment?.samples ?: emptyList()
        t = LongArray(s.size) { s[it].t }
        dist = FloatArray(s.size)
        lateral = FloatArray(s.size)
        for (i in 1 until s.size) {
            val dt = (s[i].t - s[i - 1].t) / 1000f
            val mps = s[i].speedKph / 3.6f
            dist[i] = dist[i - 1] + mps * dt
            // 핸들 각도에 비례해 옆으로 움직이고, 천천히 차로 중앙으로 돌아온다 (시각화용 근사)
            lateral[i] = (lateral[i - 1] + s[i].steeringDeg / 540f * mps * dt * 0.35f) * 0.97f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val m = moment ?: return
        val s = m.samples
        if (s.size < 2) {
            text.textSize = Type.BODY * dp; text.color = p.text3
            canvas.drawText("기록된 신호가 부족해요", 0f, height / 2f, text)
            return
        }
        val span = s.last().t - s.first().t
        val loop = span + 1500
        val elapsed = (SystemClock.uptimeMillis() - startedAt) % loop
        val now = s.first().t + min(elapsed, span)
        val i = indexAt(now)

        val sceneW = width * 0.36f
        drawScene(canvas, m, i, now, RectF(0f, 0f, sceneW, height.toFloat()))
        drawCharts(canvas, m, now, RectF(sceneW + 32f * dp, 0f, width.toFloat(), height.toFloat()))

        postInvalidateOnAnimation()
    }

    private fun indexAt(time: Long): Int {
        var i = 0
        while (i < t.size - 1 && t[i + 1] <= time) i++
        return i
    }

    // ---------------------------------------------------------------- 장면

    private fun drawScene(c: Canvas, m: Moment, i: Int, now: Long, r: RectF) {
        val s = m.samples[i]
        val radius = 20f * dp
        fill.color = shoulder
        c.drawRoundRect(r, radius, radius, fill)
        c.save()
        path.reset(); path.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.clipPath(path)

        val laneW = r.width() / 3.6f
        val roadL = r.centerX() - laneW * 1.5f
        val scale = 9f * dp // px per m
        fill.color = road
        c.drawRect(roadL, r.top, roadL + laneW * 3, r.bottom, fill)
        stroke.color = laneLine; stroke.strokeWidth = 3f * dp
        c.drawLine(roadL, r.top, roadL, r.bottom, stroke)
        c.drawLine(roadL + laneW * 3, r.top, roadL + laneW * 3, r.bottom, stroke)
        val period = 56f * dp
        val offset = (dist[i] * scale) % period
        for (k in 1..2) {
            val x = roadL + laneW * k
            var y = r.top - period + offset
            while (y < r.bottom) {
                c.drawLine(x, y, x, y + period * 0.45f, stroke)
                y += period
            }
        }

        val egoLaneX = roadL + laneW * 1.5f
        val egoX = egoLaneX + lateral[i] * laneW
        carW = min(laneW * 0.52f, r.height() * 0.12f)
        carH = carW * 2.05f
        val egoY = r.bottom - carH * 0.85f

        drawCause(c, m, now, i, r, roadL, laneW, egoLaneX, egoY, scale)
        drawCar(c, egoX, egoY, s.brake)

        // 상태: 속도 + 크루즈·브레이크 표시
        val x0 = r.left + 20f * dp
        text.typeface = bold; text.color = p.text1; text.textSize = 30f * dp
        c.drawText("%.0f".format(s.speedKph), x0, r.top + 44f * dp, text)
        val numW = text.measureText("%.0f".format(s.speedKph))
        text.typeface = regular; text.color = p.text3; text.textSize = Type.LABEL * dp
        c.drawText("km/h", x0 + numW + 6f * dp, r.top + 44f * dp, text)
        var py = r.top + 58f * dp
        py = pill(c, x0, py, "크루즈", s.cruiseOn, p.blue)
        pill(c, x0, py, "브레이크", s.brake, p.red)
        c.restore()
    }

    private fun drawCause(
        c: Canvas, m: Moment, now: Long, i: Int, r: RectF,
        roadL: Float, laneW: Float, egoLaneX: Float, egoY: Float, scale: Float,
    ) {
        val cause = m.cause ?: return
        val dt = (now - m.t0).toFloat()
        dashed.color = p.purple
        text.typeface = bold; text.color = p.purple; text.textSize = Type.LABEL * dp
        when (cause) {
            "끼어들기" -> {
                if (dt < -2600) return
                val q = ((dt + 2600f) / 2000f).coerceIn(0f, 1f)
                val ease = q * q * (3 - 2 * q)
                val fromX = roadL + laneW * 0.5f
                val x = fromX + (egoLaneX - fromX) * ease
                // 차 길이 단위 간격: 옆 차로 앞쪽에서 들어와 바짝 붙은 뒤, 내가 감속하면 다시 멀어진다
                val gap = if (dt < 0) 2.5f - 1.25f * ease else 1.25f + dt / 3000f * 0.9f
                val y = egoY - carH * gap
                ghostCar(c, x, y, angle = (1 - ease) * 14f * if (q > 0f && q < 1f) 1 else 0)
                caption(c, "끼어든 차", x, y - carH * 0.62f, r)
            }
            "주정차 차량" -> {
                val y = egoY - carH * 1.6f + (dist[i] - distAt(m.t0)) * scale
                // 내 차로 오른쪽에 걸쳐 선 차 (그래서 핸들을 꺾었다)
                val x = egoLaneX + laneW * 0.62f
                ghostCar(c, x, y)
                caption(c, "주정차 차량", x, y - carH * 0.62f, r)
            }
            "보행자" -> {
                if (dt < -2000 || dt > 2500) return
                val x = roadL + laneW * 3.2f - (dt + 2000f) / 4500f * laneW * 3.6f
                val y = egoY - carH * 1.5f
                fill.color = p.alpha(p.purple, 0.15f)
                c.drawCircle(x, y, carW * 0.24f, fill)
                c.drawCircle(x, y, carW * 0.24f, dashed)
                caption(c, "보행자", x, y - carW * 0.45f, r)
            }
            "공사" -> {
                val y = egoY - carH * 1.7f + (dist[i] - distAt(m.t0)) * scale
                for (k in 0..3) {
                    val cx = egoLaneX - laneW * 0.3f + k * laneW * 0.2f
                    fill.color = p.alpha(p.purple, 0.15f)
                    c.drawCircle(cx, y + k * 5f * dp, carW * 0.13f, fill)
                    c.drawCircle(cx, y + k * 5f * dp, carW * 0.13f, dashed)
                }
                caption(c, "공사 구간", egoLaneX, y - carW * 0.4f, r)
            }
            else -> caption(c, cause, egoLaneX, egoY - carH * 1.6f, r)
        }
    }

    /** 원인 라벨: 운전자가 기록한 것임을 함께 표시. */
    private fun caption(c: Canvas, label: String, cx: Float, bottom: Float, r: RectF) {
        val main = label
        val sub = " · 운전자 기록"
        text.textSize = Type.LABEL * dp
        text.typeface = bold
        val w1 = text.measureText(main)
        text.typeface = regular
        val w2 = text.measureText(sub)
        val padH = 10f * dp
        val w = w1 + w2 + padH * 2
        val left = (cx - w / 2).coerceIn(r.left + 8f * dp, r.right - w - 8f * dp)
        rect.set(left, bottom - 28f * dp, left + w, bottom)
        fill.color = p.surface
        c.drawRoundRect(rect, 14f * dp, 14f * dp, fill)
        val base = bottom - 9f * dp
        text.typeface = bold; text.color = p.purple
        c.drawText(main, left + padH, base, text)
        text.typeface = regular; text.color = p.text2
        c.drawText(sub, left + padH + w1, base, text)
    }

    private fun distAt(time: Long) = dist[indexAt(time)]

    /** 위에서 본 승용차. 몸체·유리·사이드미러·후미등. */
    private fun carPath(x: Float, y: Float): Path {
        val w = carW; val h = carH
        return Path().apply {
            addRoundRect(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), w * 0.42f, w * 0.42f, Path.Direction.CW)
        }
    }

    private fun drawCar(c: Canvas, x: Float, y: Float, brake: Boolean) {
        val w = carW; val h = carH
        if (brake) {
            fill.color = p.alpha(p.red, 0.22f)
            c.drawOval(x - w * 0.7f, y + h * 0.38f, x + w * 0.7f, y + h * 0.78f, fill)
        }
        // 그림자
        fill.color = Color.argb(if (p.dark) 90 else 40, 0, 0, 0)
        rect.set(x - w / 2 + 2 * dp, y - h / 2 + 4 * dp, x + w / 2 + 2 * dp, y + h / 2 + 4 * dp)
        c.drawRoundRect(rect, w * 0.42f, w * 0.42f, fill)
        // 사이드미러
        fill.color = egoBody
        c.drawRoundRect(x - w * 0.62f, y - h * 0.16f, x - w * 0.45f, y - h * 0.08f, 3 * dp, 3 * dp, fill)
        c.drawRoundRect(x + w * 0.45f, y - h * 0.16f, x + w * 0.62f, y - h * 0.08f, 3 * dp, 3 * dp, fill)
        // 몸체
        c.drawPath(carPath(x, y), fill)
        // 앞유리·뒷유리·지붕
        fill.color = egoGlass
        c.drawRoundRect(x - w * 0.38f, y - h * 0.24f, x + w * 0.38f, y - h * 0.06f, w * 0.12f, w * 0.12f, fill)
        c.drawRoundRect(x - w * 0.34f, y + h * 0.2f, x + w * 0.34f, y + h * 0.32f, w * 0.1f, w * 0.1f, fill)
        fill.color = p.alpha(Color.WHITE, if (p.dark) 0.15f else 0.12f)
        c.drawRoundRect(x - w * 0.36f, y - h * 0.04f, x + w * 0.36f, y + h * 0.18f, w * 0.1f, w * 0.1f, fill)
        // 전조등
        fill.color = p.alpha(Color.WHITE, 0.85f)
        c.drawRoundRect(x - w * 0.42f, y - h * 0.48f, x - w * 0.18f, y - h * 0.45f, 2 * dp, 2 * dp, fill)
        c.drawRoundRect(x + w * 0.18f, y - h * 0.48f, x + w * 0.42f, y - h * 0.45f, 2 * dp, 2 * dp, fill)
        // 후미등 (브레이크 시 점등)
        fill.color = if (brake) p.red else p.alpha(p.red, 0.45f)
        c.drawRoundRect(x - w * 0.44f, y + h * 0.455f, x + w * 0.44f, y + h * 0.49f, 2 * dp, 2 * dp, fill)
    }

    private fun ghostCar(c: Canvas, x: Float, y: Float, angle: Float = 0f) {
        c.save()
        c.rotate(angle, x, y)
        val body = carPath(x, y)
        fill.color = p.alpha(p.purple, 0.14f)
        c.drawPath(body, fill)
        c.drawPath(body, dashed)
        c.restore()
    }

    /** 상태 알약: 켜지면 색으로 채운다. 다음 줄 y를 돌려준다. */
    private fun pill(c: Canvas, x: Float, y: Float, label: String, on: Boolean, color: Int): Float {
        text.textSize = Type.LABEL * dp * 0.9f
        text.typeface = if (on) bold else regular
        val w = text.measureText(label) + 24f * dp
        val h = 28f * dp
        rect.set(x, y, x + w, y + h)
        fill.color = if (on) color else p.alpha(p.text1, 0.06f)
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        text.color = if (on) Color.WHITE else p.text3
        c.drawText(label, x + 12f * dp, y + h * 0.68f, text)
        return y + h + 6f * dp
    }

    // ---------------------------------------------------------------- 그래프

    private fun drawCharts(c: Canvas, m: Moment, now: Long, r: RectF) {
        val s = m.samples
        val t0 = s.first().t
        val t1 = s.last().t
        fun x(time: Long) = r.left + (time - t0).toFloat() / (t1 - t0) * r.width()

        val headH = 30f * dp
        val speedR = RectF(r.left, r.top + headH, r.right, r.top + r.height() * 0.6f)
        val steerR = RectF(r.left, speedR.bottom + headH + 8f * dp, r.right, r.bottom - 26f * dp)

        // 구간 띠
        for (k in 0 until s.size - 1) {
            val x0 = x(s[k].t); val x1 = x(s[k + 1].t)
            if (s[k].cruiseOn) { fill.color = p.alpha(p.blue, 0.10f); c.drawRect(x0, speedR.top, x1, speedR.bottom, fill) }
            if (s[k].brake) { fill.color = p.alpha(p.red, 0.14f); c.drawRect(x0, speedR.top, x1, speedR.bottom, fill) }
        }
        // 기준선
        stroke.color = p.surfaceHigh; stroke.strokeWidth = 1.5f * dp
        c.drawLine(speedR.left, speedR.bottom, speedR.right, speedR.bottom, stroke)
        c.drawLine(steerR.left, steerR.centerY(), steerR.right, steerR.centerY(), stroke)

        // 속도 선
        val vmax = max(40f, s.maxOf { it.speedKph } * 1.1f)
        line(c, speedR, s.map { x(it.t) to it.speedKph / vmax }, p.text1, 3f * dp)
        header(c, r.left, speedR.top - 10f * dp, "속도")
        legend(c, speedR.right, speedR.top - 10f * dp)

        // 핸들 선 (가운데 0°)
        val amax = max(90f, s.maxOf { abs(it.steeringDeg) })
        line(c, steerR, s.map { x(it.t) to 0.5f + it.steeringDeg / amax / 2f }, p.purple, 2.5f * dp)
        header(c, r.left, steerR.top - 10f * dp, "핸들 각도")

        // 이벤트 시점
        val ex = x(m.t0)
        val eventColor = p.colorFor(m.types.first())
        dashed.color = eventColor
        c.drawLine(ex, speedR.top, ex, steerR.bottom, dashed)
        fill.color = eventColor
        c.drawCircle(ex, speedR.top, 5f * dp, fill)

        // 재생 위치
        stroke.color = p.text3; stroke.strokeWidth = 1.5f * dp
        val px = x(now)
        c.drawLine(px, speedR.top, px, steerR.bottom, stroke)
        text.typeface = bold; text.textSize = Type.LABEL * dp * 0.9f; text.color = p.text2
        val label = if (abs(now - m.t0) < 150) "감지 순간" else "%+.1f초".format((now - m.t0) / 1000f)
        val lw = text.measureText(label)
        c.drawText(label, (px - lw / 2).coerceIn(r.left, r.right - lw), r.bottom - 4f * dp, text)
    }

    private fun header(c: Canvas, x: Float, baseline: Float, title: String) {
        text.textSize = Type.LABEL * dp
        text.typeface = bold; text.color = p.text1
        c.drawText(title, x, baseline, text)
    }

    private fun line(c: Canvas, r: RectF, pts: List<Pair<Float, Float>>, color: Int, width: Float) {
        path.reset()
        pts.forEachIndexed { k, (px, v) ->
            val py = r.bottom - v.coerceIn(0f, 1f) * r.height()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        stroke.color = color; stroke.strokeWidth = width
        c.drawPath(path, stroke)
    }

    private fun legend(c: Canvas, right: Float, baseline: Float) {
        text.textSize = Type.LABEL * dp * 0.9f; text.typeface = regular
        var x = right
        for ((label, color) in listOf("브레이크" to p.alpha(p.red, 0.35f), "크루즈 작동" to p.alpha(p.blue, 0.3f))) {
            x -= text.measureText(label) + 34f * dp
            fill.color = color
            c.drawRoundRect(x, baseline - 12f * dp, x + 16f * dp, baseline, 3 * dp, 3 * dp, fill)
            text.color = p.text2
            c.drawText(label, x + 22f * dp, baseline, text)
        }
    }
}
