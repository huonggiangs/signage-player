package vn.anio.signage

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Danh sách phát nhiều mục của một vùng (docs/PLAYLIST.md bên AMS) + hàng đợi sự kiện BỀN (proof_of_play, item_impression).
 * Chỉ logic thuần (không dùng android.*) để chạy được trong test JVM.
 */

/** Mục manifest của một vùng: video | image | text. */
data class PItem(
    val id: String,
    val type: String,
    val mediaId: String,
    val url: String,
    /** vắng = tệp AMS (tải về, kiểm sha256); "stream" = link đã duyệt; "external" = link https ngoài */
    val delivery: String,
    val sha256: String,
    val textContent: String,
    val rich: String,
    val style: JSONObject?,
    /** giây hiển thị đã giải (ảnh/chữ = duration ?? 8; video = duration hoặc null = phát hết tệp) */
    val playS: Double?,
    val name: String
) {
    val isText: Boolean get() = type == "text"
    val isVideo: Boolean get() = type == "video"
    val isRemote: Boolean get() = delivery == "stream" || delivery == "external"
}

object Playlists {
    const val DEFAULT_ITEM_MS = 8_000L
    /** Màn chờ khi mọi mục đều lỗi, sau đó thử lại cả danh sách. */
    const val WAIT_SCREEN_MS = 8_000L
    /** Mục hiển thị ổn định đủ lâu thì ghi một item_impression. */
    const val IMPRESSION_STABLE_MS = 1_000L
    const val MAX_ITEMS = 50
    private val TYPES = setOf("video", "image", "text")

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    fun parse(arr: JSONArray?): List<PItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<PItem>()
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val o = arr.optJSONObject(i) ?: continue
            val type = o.str("type")
            if (type !in TYPES) continue
            val id = o.str("id")
            if (id.isEmpty() || id.length > 80 || !ids.add(id)) continue
            val text = o.str("text_content")
            val mediaId = o.str("media_id")
            val url = o.str("url")
            if (type == "text") { if (text.isBlank() && o.str("rich").isBlank()) continue }
            else if (mediaId.isEmpty() && url.isEmpty()) continue
            val playS = if (o.isNull("play_s") || !o.has("play_s")) null else o.optDouble("play_s").takeIf { !it.isNaN() && it > 0 }
            out.add(
                PItem(id, type, mediaId, url, o.str("delivery"), o.str("sha256"), text.take(2000), o.str("rich").take(4000),
                    o.optJSONObject("style"), playS, o.str("name"))
            )
        }
        return out
    }

    /** id ổn định cho mục suy ra từ vùng cũ (creative_ids + durations): cùng manifest -> cùng id. */
    fun legacyItemId(zoneId: String, creativeId: String): String = "lg-$zoneId-$creativeId".take(80)

    /** Thời lượng hiển thị ảnh/chữ (ms): play_s nếu có, không thì 8 giây. */
    fun staticMs(playS: Double?): Long =
        if (playS != null && playS > 0) (playS.coerceIn(0.5, 3600.0) * 1000).toLong() else DEFAULT_ITEM_MS

    private val iso = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun isoMillis(ms: Long): String = iso.get()!!.format(Date(ms))

    /** Đoán kiểu stream từ đuôi URL (mục chỉ có url). */
    fun streamKindOf(url: String): String {
        val p = url.substringBefore('?').substringBefore('#').lowercase()
        return when { p.endsWith(".m3u8") -> "hls"; p.endsWith(".mpd") -> "dash"; p.endsWith(".webm") -> "webm"; else -> "mp4" }
    }
}

/**
 * Bộ điều phối tuần tự của một vùng: lần lượt từng mục, hết thì quay lại đầu nếu [loop], không thì giữ mục cuối ([Step.Finished]).
 * Mục lỗi: bỏ qua sang mục kế; LIÊN TIẾP mọi mục đều lỗi thì [Step.AllFailed] (trình phát hiện màn chờ rồi [restart]) để không lặp nóng.
 */
class PlaylistSequencer(val size: Int, val loop: Boolean) {
    sealed class Step {
        data class Play(val index: Int) : Step()
        object AllFailed : Step()
        object Finished : Step()
    }

    var index = -1
        private set
    private var failStreak = 0

    fun start(): Step { index = -1; failStreak = 0; return advance() }
    fun restart(): Step = start()
    fun onItemEnded(): Step { failStreak = 0; return advance() }

    fun onItemFailed(): Step {
        failStreak++
        if (size > 0 && failStreak >= size) { failStreak = 0; return Step.AllFailed }
        return advance()
    }

    /** Mục sẽ chạy kế tiếp (để nạp trước); null nếu hết và không lặp. */
    fun peekNext(): Int? {
        if (size == 0) return null
        val n = index + 1
        return if (n < size) n else if (loop) 0 else null
    }

    private fun advance(): Step {
        val n = peekNext() ?: return Step.Finished
        index = n
        return Step.Play(n)
    }
}

// ------------------------------------------------------------------ hàng đợi sự kiện bền

/** Một sự kiện chờ gửi. [atMs] = giờ MÁY (chưa cộng lệch đồng hồ): giờ gửi được tính lại theo độ lệch mới nhất. */
data class QEvent(
    val id: String,
    val kind: String,
    val atMs: Long,
    val payload: JSONObject,
    var attempts: Int = 0,
    var nextAtMs: Long = 0L
) {
    /** Dạng gửi lên /v1/events. */
    fun toWire(offsetMs: Long): JSONObject {
        val o = JSONObject(payload.toString())
        val iso = Playlists.isoMillis(atMs + offsetMs)
        if (kind == "item_impression") o.put("played_at", iso) else o.put("ts", iso)
        return o
    }
}

/** Nơi lưu bền (một tệp JSON); test dùng bản trong bộ nhớ. */
interface TextStore {
    fun read(): String?
    fun write(text: String)
}

class MemStore(var text: String? = null) : TextStore {
    override fun read() = text
    override fun write(text: String) { this.text = text }
}

object Backoff {
    /** Lùi mũ có nhiễu: nửa cố định + nửa ngẫu nhiên, trần 15 phút. */
    fun delayMs(attempts: Int, rnd: java.util.Random = java.util.Random()): Long {
        val cap = minOf(900_000L, 1000L shl minOf(attempts.coerceAtLeast(1), 20))
        return cap / 2 + (rnd.nextDouble() * (cap / 2)).toLong()
    }
}

/**
 * Hàng đợi BỀN: chỉ xóa sau khi máy chủ xác nhận. Gửi khi đủ [FLUSH_COUNT] bản ghi hoặc sau [FLUSH_INTERVAL_MS].
 * Trùng id (log_id/event_id) bị bỏ. Quá [TTL_MS] (máy chủ từ chối sự kiện > 48 giờ) hoặc quá [maxItems] thì bỏ bản cũ nhất.
 */
class EventQueue(private val store: TextStore? = null, private val maxItems: Int = MAX_ITEMS) {
    private val items = ArrayList<QEvent>()
    private var lastFlushAtMs = 0L
    private val rnd = java.util.Random()

    init { load() }

    @Synchronized
    fun add(e: QEvent, nowMs: Long): Boolean {
        if (e.id.isEmpty() || items.any { it.id == e.id }) return false
        items.add(e)
        prune(nowMs)
        save()
        return true
    }

    @Synchronized fun size(): Int = items.size

    @Synchronized fun snapshot(): List<QEvent> = items.map { it.copy() }

    @Synchronized
    fun shouldFlush(nowMs: Long): Boolean {
        val due = items.count { it.nextAtMs <= nowMs }
        if (due == 0) return false
        if (due >= FLUSH_COUNT) return true
        val base = if (lastFlushAtMs > 0L) lastFlushAtMs else items.minOf { it.atMs }
        return nowMs - base >= FLUSH_INTERVAL_MS
    }

    /** Lô kế (cũ trước), chỉ bản ghi đã tới hạn thử lại; không xóa. */
    @Synchronized
    fun nextBatch(nowMs: Long, max: Int = BATCH_MAX): List<QEvent> =
        items.filter { it.nextAtMs <= nowMs }.sortedWith(compareBy<QEvent> { it.atMs }.thenBy { it.id }).take(max.coerceIn(1, BATCH_MAX)).map { it.copy() }

    @Synchronized
    fun markAcked(ids: Collection<String>, nowMs: Long) {
        val s = ids.toSet()
        items.removeAll { it.id in s }
        lastFlushAtMs = nowMs
        save()
    }

    /** Lỗi tạm (mạng/5xx/429/401/lệch giờ...): GIỮ NGUYÊN, lùi mũ (hoặc theo Retry-After). */
    @Synchronized
    fun markFailed(ids: Collection<String>, nowMs: Long, retryAfterS: Int = 0) {
        val s = ids.toSet()
        for (e in items) if (e.id in s) {
            e.attempts += 1
            e.nextAtMs = nowMs + if (retryAfterS > 0) retryAfterS * 1000L else Backoff.delayMs(e.attempts, rnd)
        }
        lastFlushAtMs = nowMs
        save()
    }

    /** Máy chủ từ chối dứt khoát một sự kiện ĐƠN LẺ (400/422): thử lại vài lần rồi bỏ để không nghẽn hàng đợi. */
    @Synchronized
    fun markRejected(ids: Collection<String>, nowMs: Long) {
        val s = ids.toSet()
        for (e in items) if (e.id in s) { e.attempts += 1; e.nextAtMs = nowMs + Backoff.delayMs(e.attempts + 2, rnd) }
        items.removeAll { it.id in s && it.attempts >= MAX_REJECT_ATTEMPTS }
        lastFlushAtMs = nowMs
        save()
    }

    private fun prune(nowMs: Long) {
        items.removeAll { nowMs - it.atMs > TTL_MS }
        if (items.size > maxItems) { items.sortBy { it.atMs }; while (items.size > maxItems) items.removeAt(0) }
    }

    private fun save() {
        val st = store ?: return
        try {
            val a = JSONArray()
            for (e in items) a.put(
                JSONObject().put("id", e.id).put("kind", e.kind).put("atMs", e.atMs).put("payload", e.payload)
                    .put("attempts", e.attempts).put("nextAtMs", e.nextAtMs)
            )
            st.write(JSONObject().put("v", 1).put("lastFlushAtMs", lastFlushAtMs).put("events", a).toString())
        } catch (_: Throwable) {}
    }

    private fun load() {
        val st = store ?: return
        val root = try { JSONObject(st.read() ?: return) } catch (_: Exception) { return }
        lastFlushAtMs = root.optLong("lastFlushAtMs", 0L)
        val a = root.optJSONArray("events") ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val p = o.optJSONObject("payload") ?: continue
            if (id.isEmpty() || items.any { it.id == id }) continue
            items.add(QEvent(id, o.optString("kind", ""), o.optLong("atMs", 0L), p, o.optInt("attempts", 0), o.optLong("nextAtMs", 0L)))
        }
    }

    companion object {
        const val FLUSH_INTERVAL_MS = 5 * 60_000L
        const val FLUSH_COUNT = 50
        const val BATCH_MAX = 50
        const val MAX_ITEMS = 5000
        const val MAX_REJECT_ATTEMPTS = 5
        /** máy chủ từ chối sự kiện cũ hơn 48 giờ (ts_out_of_range): bỏ sau 47 giờ */
        const val TTL_MS = 47L * 3600_000

        fun itemImpression(logId: String, programId: String, zoneId: String, itemId: String, mediaId: String, itemType: String, atMs: Long): QEvent {
            val p = JSONObject().put("type", "item_impression").put("log_id", logId).put("program_id", programId)
                .put("zone_id", zoneId).put("item_id", itemId).put("item_type", itemType)
            if (mediaId.isNotEmpty() && itemType != "text") p.put("media_id", mediaId)
            return QEvent(logId, "item_impression", atMs, p)
        }

        fun proofOfPlay(eventId: String, creativeId: String, durationMs: Long, atMs: Long): QEvent {
            val p = JSONObject().put("type", "proof_of_play").put("event_id", eventId).put("creative_id", creativeId)
                .put("duration_ms", durationMs.coerceIn(0L, 3_600_000L))
            return QEvent(eventId, "proof_of_play", atMs, p)
        }
    }
}

// ------------------------------------------------------------------ gửi lô và phân loại phản hồi

class SendResult(val code: Int, val errCode: String, val retryAfterS: Int)

/** Việc phải làm với một lô theo phản hồi. KHÔNG có nhánh "bỏ cả lô khi gặp 4xx" (lỗi 1.1.4). */
sealed class Outcome {
    /** 2xx: máy chủ đã nhận (kể cả sự kiện bị từ chối vĩnh viễn trong thân 202) -> xóa cả lô. */
    object Ack : Outcome()
    /** Lỗi tạm: giữ nguyên, thử lại sau (Retry-After nếu có). */
    data class Retry(val retryAfterS: Int) : Outcome()
    /** 413 hoặc 400/422 khi lô > 1 sự kiện: chia đôi lô để cô lập sự kiện hỏng. */
    object Shrink : Outcome()
    /** 400/422 với lô chỉ 1 sự kiện: sự kiện đó bị từ chối dứt khoát. */
    object Reject : Outcome()
}

object EventPolicy {
    fun classify(code: Int, batchSize: Int, retryAfterS: Int): Outcome = when {
        code in 200..299 -> Outcome.Ack
        code == 413 || ((code == 400 || code == 422) && batchSize > 1) -> Outcome.Shrink
        code == 400 || code == 422 -> Outcome.Reject
        // 401 (lệch giờ/chữ ký/thiết bị lạ), 403, 404, 408, 409 (replay), 425, 429, 5xx, lỗi mạng (code <= 0): giữ dữ liệu
        else -> Outcome.Retry(retryAfterS)
    }
}

/**
 * Gửi hàng đợi lên /v1/events theo lô tuần tự (<= 50 sự kiện/lô). Trả số lô đã được máy chủ nhận.
 * Lỗi tạm dừng vòng gửi (đợi lần sau); không bao giờ xóa dữ liệu khi chưa có 2xx.
 */
class EventUploader(
    private val queue: EventQueue,
    private val send: (JSONArray) -> SendResult,
    private val offsetMs: () -> Long,
    private val now: () -> Long
) {
    private var maxBatch = EventQueue.BATCH_MAX

    fun flush(force: Boolean, maxBatches: Int = 10): Int {
        var acked = 0
        var n = 0
        while (n++ < maxBatches) {
            val t = now()
            if (!force && !queue.shouldFlush(t)) break
            val batch = queue.nextBatch(t, maxBatch)
            if (batch.isEmpty()) break
            val arr = JSONArray()
            for (e in batch) arr.put(e.toWire(offsetMs()))
            val ids = batch.map { it.id }
            val r = try { send(arr) } catch (_: Exception) { SendResult(-1, "", 0) }
            when (val o = EventPolicy.classify(r.code, batch.size, r.retryAfterS)) {
                Outcome.Ack -> { queue.markAcked(ids, now()); acked++; maxBatch = EventQueue.BATCH_MAX }
                is Outcome.Retry -> { queue.markFailed(ids, now(), o.retryAfterS); return acked }
                Outcome.Shrink -> {
                    if (batch.size > 1) { maxBatch = (batch.size / 2).coerceAtLeast(1); continue }
                    queue.markRejected(ids, now())
                }
                Outcome.Reject -> queue.markRejected(ids, now())
            }
        }
        return acked
    }
}
