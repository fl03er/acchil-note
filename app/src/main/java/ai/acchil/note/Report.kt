package ai.acchil.note

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** 최근 7일(오늘 포함)과 그 전 7일을 비교한 주간 통계. */
class WeeklyStats(all: List<Moment>, now: Long = System.currentTimeMillis()) {
    val dayStarts: List<Long>
    val thisWeek: List<Moment>
    val lastWeek: List<Moment>

    init {
        val today = startOfDay(now)
        dayStarts = (6 downTo 0).map { today - it * DAY }
        thisWeek = all.filter { it.wallTime >= dayStarts.first() }
        lastWeek = all.filter { it.wallTime >= dayStarts.first() - 7 * DAY && it.wallTime < dayStarts.first() }
    }

    /** 요일별 개수 (순간마다 대표 유형 하나로). */
    val perDay: List<Map<String, Int>> = dayStarts.map { start ->
        thisWeek.filter { it.wallTime in start until start + DAY }.groupingBy { it.types.first() }.eachCount()
    }

    val byType: List<Pair<String, Int>> =
        thisWeek.flatMap { it.types }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

    val byCause: List<Pair<String, Int>> =
        thisWeek.mapNotNull { it.cause }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

    val tagged = thisWeek.count { it.cause != null }

    val byContext: List<Pair<String, Int>> =
        thisWeek.flatMap { it.context }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

    val byTime: List<Pair<String, Int>> = TIME_BUCKETS.map { (label, range) ->
        label to thisWeek.count { hourOf(it.wallTime) in range }
    }

    /** 지난주 대비 변화율(%). 지난주 기록이 없으면 null. */
    val changePct: Int? = if (lastWeek.isEmpty()) null
    else ((thisWeek.size - lastWeek.size) * 100f / lastWeek.size).roundToInt()

    /** 리포트 상단에 보여 줄 패턴 문장. 데이터로 확인되는 것만. */
    fun insights(): List<Insight> {
        val n = thisWeek.size
        if (n == 0) return emptyList()
        val out = mutableListOf<Insight>()
        byCause.firstOrNull()?.let { (cause, c) ->
            if (c >= 2) out += Insight("원인", "'$cause'가 ${c}번으로 가장 많았어요")
        }
        byTime.maxByOrNull { it.second }?.let { (label, c) ->
            if (c >= 2) out += Insight("시간대", "$label 시간대에 가장 많았어요")
        }
        val rain = thisWeek.count { AcchilDetector.RAIN in it.context }
        if (rain >= 2) out += Insight("날씨", "비 올 때 ${rain}번 있었어요")
        val cruise = thisWeek.count { AcchilDetector.INTERVENTION in it.types }
        if (cruise >= 2) out += Insight("크루즈", "크루즈 중 직접 개입이 ${cruise}번 있었어요")
        return out.take(3)
    }

    data class Insight(val tag: String, val title: String)

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        val TIME_BUCKETS = listOf(
            "출근(6~10시)" to 6..9,
            "낮(10~17시)" to 10..16,
            "퇴근(17~21시)" to 17..20,
            "밤(21~6시)" to (21..23) + (0..5),
        ).map { (l, r) -> l to r.toSet() }

        fun startOfDay(time: Long) = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun hourOf(time: Long) = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)

    }
}

/** 최근 7일 요일별 막대. 막대는 대표 유형 색으로 쌓는다. */
class WeekChartView(context: Context, private val p: Palette, private val stats: WeeklyStats) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val order = listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE, AcchilDetector.SWERVE)

    override fun onDraw(c: Canvas) {
        val labelH = 30f * dp
        val countH = 26f * dp
        val chartTop = countH
        val chartBottom = height - labelH
        val maxN = max(3, stats.perDay.maxOf { it.values.sum() })
        val col = width / 7f
        val barW = minOf(col * 0.46f, 44f * dp)
        val days = listOf("일", "월", "화", "수", "목", "금", "토")

        stats.perDay.forEachIndexed { i, counts ->
            val cx = col * i + col / 2
            val isToday = i == 6
            // 바탕 트랙
            fill.color = p.surfaceHigh
            rect.set(cx - barW / 2, chartTop, cx + barW / 2, chartBottom)
            c.drawRoundRect(rect, 10f * dp, 10f * dp, fill)
            // 유형별로 아래부터 쌓기
            val total = counts.values.sum()
            var y = chartBottom
            val unit = (chartBottom - chartTop) / maxN
            c.save()
            c.clipRect(cx - barW / 2, chartTop, cx + barW / 2, chartBottom)
            val clip = android.graphics.Path().apply { addRoundRect(rect, 10f * dp, 10f * dp, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            order.forEach { type ->
                val n = counts[type] ?: 0
                if (n == 0) return@forEach
                fill.color = p.colorFor(type)
                c.drawRect(cx - barW / 2, y - n * unit, cx + barW / 2, y, fill)
                y -= n * unit
            }
            c.restore()
            // 개수
            text.typeface = Type.asta(context, 700)
            text.textSize = Type.LABEL * dp
            text.color = if (total > 0) p.text1 else p.text3
            c.drawText(if (total > 0) "$total" else "-", cx, minOf(y, chartBottom) - 8f * dp, text)
            // 요일
            val cal = Calendar.getInstance().apply { timeInMillis = stats.dayStarts[i] }
            text.typeface = Type.asta(context, if (isToday) 700 else 400)
            text.color = if (isToday) p.text1 else p.text3
            c.drawText(if (isToday) "오늘" else days[cal.get(Calendar.DAY_OF_WEEK) - 1], cx, height - 6f * dp, text)
        }
    }
}

/**
 * 시연용: 지난 2주치 아찔 순간을 만든다 (오늘은 실제 기록만 둔다).
 * 이번 주가 지난주보다 줄어든 모습, 출퇴근·우천·끼어들기 패턴이 보이도록 구성.
 */
object DemoData {
    fun moments(now: Long = System.currentTimeMillis()): List<Moment> {
        val rnd = Random(7)
        val today = WeeklyStats.startOfDay(now)
        // (며칠 전, 시, 분, 유형, 원인, 비?)
        val plan = listOf(
            // 지난주 (7~13일 전): 10건
            Triple(13, 8 to 12, listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE)) to ("끼어들기" to true),
            Triple(12, 18 to 40, listOf(AcchilDetector.HARD_BRAKE)) to ("끼어들기" to false),
            Triple(12, 22 to 5, listOf(AcchilDetector.SWERVE)) to ("주정차 차량" to false),
            Triple(11, 7 to 55, listOf(AcchilDetector.INTERVENTION)) to (null to true),
            Triple(10, 8 to 30, listOf(AcchilDetector.HARD_BRAKE)) to ("보행자" to false),
            Triple(9, 19 to 2, listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE)) to ("끼어들기" to true),
            Triple(8, 8 to 20, listOf(AcchilDetector.HARD_BRAKE)) to ("끼어들기" to false),
            Triple(8, 17 to 48, listOf(AcchilDetector.SWERVE)) to ("공사" to false),
            Triple(7, 8 to 41, listOf(AcchilDetector.INTERVENTION)) to ("끼어들기" to true),
            Triple(7, 18 to 33, listOf(AcchilDetector.HARD_BRAKE)) to ("끼어들기" to false),
            // 이번 주 (1~6일 전): 6건 + 오늘 실제 기록
            Triple(6, 8 to 15, listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE)) to ("끼어들기" to true),
            Triple(6, 18 to 47, listOf(AcchilDetector.HARD_BRAKE)) to ("끼어들기" to true),
            Triple(5, 18 to 22, listOf(AcchilDetector.HARD_BRAKE)) to ("내 실수" to false),
            Triple(3, 8 to 9, listOf(AcchilDetector.INTERVENTION)) to ("끼어들기" to true),
            Triple(3, 8 to 52, listOf(AcchilDetector.SWERVE)) to ("주정차 차량" to false),
            Triple(1, 7 to 58, listOf(AcchilDetector.INTERVENTION, AcchilDetector.HARD_BRAKE)) to (null to false),
        )
        return plan.mapIndexed { k, (what, how) ->
            val (daysAgo, hm, types) = what
            val (cause, rain) = how
            val wall = today - daysAgo * WeeklyStats.DAY + (hm.first * 60 + hm.second) * 60_000L
            val t0 = 1_000_000L + k * 100_000L
            Moment(
                id = wall, wallTime = wall, t0 = t0,
                types = types.toMutableList(),
                details = mutableListOf("시연용 데이터"),
                context = buildSet {
                    if (rain) add(AcchilDetector.RAIN)
                    if (hm.first >= 19 || hm.first < 6) add(AcchilDetector.NIGHT)
                }.toMutableSet(),
                samples = samplesFor(types, t0, rnd),
                cause = cause,
            )
        }
    }

    private fun samplesFor(types: List<String>, t0: Long, rnd: Random): List<Sample> {
        val swerve = AcchilDetector.SWERVE in types
        val cruise = AcchilDetector.INTERVENTION in types
        val cruiseSpeed = if (swerve) 40f else 80f + rnd.nextInt(30)
        return (-50..30).map { k ->
            val t = t0 + k * 100L
            val after = k >= 0
            val speed = when {
                swerve -> if (after) cruiseSpeed - minOf(k, 10) * 0.6f else cruiseSpeed
                after -> cruiseSpeed - minOf(k, 15) * 2f
                else -> cruiseSpeed
            }
            val steer = if (swerve && k in 0..4) 120f - k * 40f else 0f
            Sample(t, speed, after && k < 16 && !swerve || (swerve && k in 0..3), steer, cruise && !after)
        }
    }
}
