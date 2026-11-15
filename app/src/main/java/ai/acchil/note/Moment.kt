package ai.acchil.note

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 100ms 간격으로 기록하는 차량 상태 한 칸. */
data class Sample(
    val t: Long,
    val speedKph: Float,
    val brake: Boolean,
    val steeringDeg: Float,
    val cruiseOn: Boolean,
)

/** 아찔 순간 하나. 3초 안에 겹친 이벤트(개입 + 급제동 등)는 한 순간으로 묶는다. */
class Moment(
    val id: Long,
    val wallTime: Long,
    val t0: Long,
    val types: MutableList<String> = mutableListOf(),
    val details: MutableList<String> = mutableListOf(),
    val context: MutableSet<String> = linkedSetOf(),
    var samples: List<Sample> = emptyList(),
    var cause: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("wall", wallTime); put("t0", t0)
        put("types", JSONArray(types)); put("details", JSONArray(details)); put("context", JSONArray(context.toList()))
        put("cause", cause ?: JSONObject.NULL)
        put("samples", JSONArray().apply {
            samples.forEach { s ->
                put(JSONArray().put(s.t).put(s.speedKph.toDouble()).put(if (s.brake) 1 else 0)
                    .put(s.steeringDeg.toDouble()).put(if (s.cruiseOn) 1 else 0))
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Moment {
            fun strings(key: String) = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
            val sa = o.getJSONArray("samples")
            return Moment(
                id = o.getLong("id"), wallTime = o.getLong("wall"), t0 = o.getLong("t0"),
                types = strings("types").toMutableList(), details = strings("details").toMutableList(),
                context = strings("context").toMutableSet(),
                samples = (0 until sa.length()).map { i ->
                    val r = sa.getJSONArray(i)
                    Sample(r.getLong(0), r.getDouble(1).toFloat(), r.getInt(2) == 1, r.getDouble(3).toFloat(), r.getInt(4) == 1)
                },
                cause = if (o.isNull("cause")) null else o.getString("cause"),
            )
        }
    }
}

/** 기기 안에만 저장한다 (영상·음성·위치 없음). */
class MomentStore(context: Context) {
    private val file = File(context.filesDir, "moments.json")
    val moments = mutableListOf<Moment>()

    init {
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) moments += Moment.fromJson(a.getJSONObject(i))
        }
    }

    fun add(m: Moment) {
        moments += m
        save()
    }

    fun save() {
        file.writeText(JSONArray().apply { moments.forEach { put(it.toJson()) } }.toString())
    }

    fun clear() {
        moments.clear()
        save()
    }
}

/**
 * 최근 12초 신호를 들고 있다가, 이벤트가 나면 앞 5초 + 뒤 3초를 잘라 순간으로 확정한다.
 */
class MomentRecorder(private val onFinalized: (Moment) -> Unit) {
    private val buffer = ArrayDeque<Sample>()
    private var pending: Moment? = null

    fun sample(s: Sample) {
        buffer.addLast(s)
        while (buffer.isNotEmpty() && s.t - buffer.first().t > BUFFER_MS) buffer.removeFirst()
        pending?.let { if (s.t >= it.t0 + AFTER_MS) finalize() }
    }

    fun event(type: String, detail: String, context: List<String>) {
        val now = SystemClock.elapsedRealtime()
        val m = pending?.takeIf { now - it.t0 < MERGE_MS }
            ?: Moment(id = System.currentTimeMillis(), wallTime = System.currentTimeMillis(), t0 = now).also { pending = it }
        if (type !in m.types) m.types += type
        m.details += detail
        m.context += context
    }

    /** 운행 종료 등으로 3초를 다 못 채워도 지금까지로 확정한다. */
    fun flush() {
        if (pending != null) finalize()
    }

    private fun finalize() {
        val m = pending ?: return
        pending = null
        m.samples = buffer.filter { it.t >= m.t0 - BEFORE_MS && it.t <= m.t0 + AFTER_MS }
        onFinalized(m)
    }

    companion object {
        private const val BUFFER_MS = 12_000L
        const val BEFORE_MS = 5_000L
        const val AFTER_MS = 3_000L
        private const val MERGE_MS = 3_000L
    }
}
