package vn.anio.signage

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Danh tính thiết bị CỐ ĐỊNH: seed Ed25519 suy ra từ ANDROID_ID (theo khóa ký + thiết bị, bền qua gỡ/cài lại CÙNG chữ ký).
 * Cùng seed -> cùng pubkey -> /v1/register trả lại ĐÚNG device_id cũ (đăng ký idempotent theo pubkey) nên mã thiết bị không đổi.
 */
object Identity {
    private const val SALT = "anio-signage-identity-v1"

    /** ANDROID_ID không dùng được: rỗng, hoặc giá trị lỗi nổi tiếng của máy ảo/ROM cũ. */
    fun usableAndroidId(id: String?): Boolean {
        val v = (id ?: "").trim().lowercase(Locale.US)
        return v.length >= 8 && v != "9774d56d682e549c" && v != "unknown" && v.any { it != '0' }
    }

    /** Seed 32 byte xác định theo (ANDROID_ID, applicationId); null nếu ANDROID_ID không dùng được (dùng ngẫu nhiên). */
    fun seedFor(androidId: String?, pkg: String): ByteArray? {
        if (!usableAndroidId(androidId)) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(("$pkg|${androidId!!.trim().lowercase(Locale.US)}").toByteArray(Charsets.UTF_8))
    }
}

/** Chu kỳ hỏi AMS: máy chủ chỉ định `next_interval` (300–600 s) nên thông báo khẩn trễ vài phút; app giới hạn trần để nhận nhanh. */
object PollPolicy {
    const val CAP_S = 30
    const val MIN_S = 10
    const val REGISTER_RETRY_S = 30

    fun delay(serverNext: Int, retryAfter: Int = 0): Int {
        val base = if (serverNext <= 0) CAP_S else serverNext.coerceAtMost(CAP_S)
        return Math.max(Math.max(base, MIN_S), retryAfter)
    }
}

/** Ảnh chụp trạng thái để suy ra lý do màn chờ (hàm thuần: kiểm thử được trên JVM). */
data class DiagSnapshot(
    val amsSelected: Boolean,
    val deviceId: String,
    val registerCode: Int,      // 0 = chưa thử; -1 = lỗi mạng; còn lại là mã HTTP
    val registerError: String,
    val amsDown: Boolean,
    val hbCode: Int,            // 0 = chưa có heartbeat; -1 = lỗi mạng
    val manifestCode: Int,      // 0 = chưa tải manifest
    val manifestSlots: Int,
    val manifestCreatives: Int,
    val downloadFailed: Int,
    val downloadError: String,
    val items: Int,
    val group: String,
    val manifestError: String = ""
)

object IdleReason {
    const val CONNECTED = "Đã kết nối với hệ thống quản trị"
    const val CONNECTING = "Đang kết nối hệ thống quản trị…"
    const val FAILED = "Chưa kết nối được hệ thống quản trị"

    /** Một dòng trung tính cho màn chờ (không mã lỗi, không mã thiết bị). Rỗng nếu nguồn AMS đang tắt. */
    fun neutral(s: DiagSnapshot): String {
        if (!s.amsSelected) return ""
        if (s.deviceId.isEmpty()) {
            return if (s.registerCode == 0) CONNECTING else FAILED
        }
        if (s.amsDown) return FAILED
        if (s.hbCode == 0) return CONNECTING
        if (s.hbCode !in 200..299) return FAILED
        if (s.manifestCode == 0 || s.manifestCode in 200..399) return CONNECTED
        return FAILED
    }

    fun of(s: DiagSnapshot): String {
        if (!s.amsSelected) return "Nguồn ams.anio.vn đang TẮT trong Cài đặt."
        if (s.deviceId.isEmpty()) {
            return when {
                s.registerCode == 0 -> "Chưa đăng ký với AMS (đang kết nối)."
                s.registerCode == -1 -> "Không kết nối được máy chủ AMS: ${s.registerError.ifEmpty { "kiểm tra mạng/DNS" }}."
                s.registerCode == 429 -> "AMS tạm từ chối đăng ký (quá nhiều lần), sẽ thử lại."
                else -> "AMS từ chối đăng ký (mã ${s.registerCode}${if (s.registerError.isEmpty()) "" else " " + s.registerError})."
            }
        }
        if (s.amsDown) return "Mất kết nối tới máy chủ AMS."
        if (s.hbCode == -1) return "Heartbeat lỗi mạng, sẽ thử lại."
        if (s.hbCode == 0) return "Đã đăng ký, đang chờ heartbeat đầu tiên."
        if (s.hbCode == 401 || s.hbCode == 403) return "AMS từ chối thiết bị (mã ${s.hbCode}): thiết bị bị chặn hoặc chưa được nhận."
        if (s.hbCode !in 200..299) return "Heartbeat lỗi mã ${s.hbCode}."
        if (s.manifestCode == 0) return "Chưa tải danh sách phát (manifest) từ AMS."
        if (s.manifestCode == 401) return "AMS từ chối chữ ký/thiết bị khi tải danh sách phát (401${if (s.manifestError.isEmpty()) "" else " " + s.manifestError}). Đang thử lại và đăng ký lại; nếu kéo dài, kiểm tra giờ hệ thống của TV và thiết bị có bị xóa trên web không."
        if (s.manifestCode !in 200..399) return "Tải manifest lỗi mã ${s.manifestCode}."
        if (s.items == 0 && s.manifestSlots == 0) {
            return "Web chưa có chương trình nào gửi tới nhóm ${s.group.ifEmpty { "?" }} của thiết bị này: " +
                "hãy xuất bản chương trình, chọn đúng thiết bị hoặc khu vực, đã duyệt nội dung."
        }
        if (s.items == 0 && s.downloadFailed > 0) {
            return "Có ${s.manifestCreatives} tệp nhưng tải lỗi ${s.downloadFailed} tệp" + (if (s.downloadError.isEmpty()) "" else " (${s.downloadError})") + "."
        }
        if (s.items == 0) return "Danh sách phát có ${s.manifestSlots} khung nhưng không có tệp phát được."
        return "Có ${s.items} mục nhưng chưa tới khung giờ phát."
    }
}

/** Trạng thái chẩn đoán (ghi từ các luồng nền, đọc ở giao diện). */
object Diag {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val recent = ArrayDeque<String>()

    @Volatile var registerAt = 0L
    @Volatile var registerCode = 0
    @Volatile var registerError = ""
    @Volatile var hbAt = 0L
    @Volatile var hbCode = 0
    @Volatile var hbNext = 0
    @Volatile var group = ""
    @Volatile var cmdAt = 0L
    @Volatile var manifestAt = 0L
    @Volatile var manifestCode = 0
    @Volatile var manifestError = ""
    @Volatile var manifestSlots = 0
    @Volatile var manifestCreatives = 0
    @Volatile var downloadFailed = 0
    @Volatile var downloadError = ""
    @Volatile var lastError = ""
    @Volatile var realtime = false

    @Synchronized
    fun time(ms: Long): String = if (ms <= 0) "-" else fmt.format(Date(ms))

    @Synchronized
    fun command(text: String) {
        recent.addFirst(time(System.currentTimeMillis()) + " " + text)
        while (recent.size > 4) recent.removeLast()
    }

    @Synchronized
    fun recentCommands(): List<String> = recent.toList()

    fun snapshot(prefs: Prefs, items: Int, amsDown: Boolean) = DiagSnapshot(
        amsSelected = prefs.isSel(Src.AMS), deviceId = prefs.deviceId, registerCode = registerCode, registerError = registerError,
        amsDown = amsDown, hbCode = hbCode, manifestCode = manifestCode, manifestError = manifestError, manifestSlots = manifestSlots, manifestCreatives = manifestCreatives,
        downloadFailed = downloadFailed, downloadError = downloadError, items = items, group = group
    )

    fun table(prefs: Prefs, etag: String, source: String, items: Int, amsDown: Boolean): String {
        val sb = StringBuilder()
        sb.append("Gateway: ").append(prefs.gwBase).append('\n')
        sb.append("Mã thiết bị: ").append(prefs.deviceId.ifEmpty { "(chưa có)" }).append(" · quyền ").append(prefs.trust).append(if (prefs.trust < 1) " (CHƯA DUYỆT trên web: thông báo khẩn chỉ gửi tới thiết bị đã duyệt)" else "")
            .append(" · nhóm ").append(group.ifEmpty { "-" }).append('\n')
        sb.append("Đăng ký: ").append(time(registerAt)).append(" → ").append(codeText(registerCode)).append('\n')
        sb.append("Heartbeat/lệnh: ").append(time(hbAt)).append(" → ").append(codeText(hbCode))
            .append(" · hỏi lại sau ").append(hbNext).append(" giây\n")
        sb.append("Manifest: ").append(time(manifestAt)).append(" → ").append(codeText(manifestCode))
            .append(" · ").append(manifestSlots).append(" khung/").append(manifestCreatives).append(" tệp · phiên bản ").append(etag.ifEmpty { "-" }).append('\n')
        val cmds = recentCommands()
        sb.append("Lệnh gần đây: ").append(if (cmds.isEmpty()) "chưa có" else cmds.joinToString(" | ")).append('\n')
        sb.append("Đang phát: ").append(source.ifEmpty { "không (màn chờ)" })
        if (source.isEmpty()) sb.append('\n').append("Lý do: ").append(IdleReason.of(snapshot(prefs, items, amsDown)))
        if (lastError.isNotEmpty()) sb.append('\n').append("Lỗi gần nhất: ").append(lastError)
        return sb.toString()
    }

    private fun codeText(c: Int): String = when {
        c == 0 -> "chưa thử"
        c == -1 -> "LỖI MẠNG"
        c in 200..399 -> "OK ($c)"
        else -> "LỖI $c"
    }
}

/** Âm lượng đang áp dụng thật (hệ thống hoặc qua trình phát) để báo lên AMS trong heartbeat (heartbeat.volume) — web hiển thị 'Thiết bị báo: N%'. */
object VolumeReport {
    @Volatile var provider: (() -> Int)? = null
    fun current(): Int? = try { provider?.invoke()?.coerceIn(0, 100) } catch (_: Exception) { null }
}

/** Hệ số âm lượng áp lên mọi ExoPlayer khi hệ điều hành KHÔNG cho đổi âm lượng (TV box "volume fixed"/HDMI passthrough). */
object PlayerGain {
    @Volatile var value = 1f
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun set(v: Float) {
        value = v.coerceIn(0f, 1f)
        for (l in listeners) try { l() } catch (_: Exception) {}
    }

    /** Quyết định cách áp âm lượng: hệ thống nếu đọc lại đúng, ngược lại hạ qua trình phát. Hàm thuần để kiểm thử. */
    fun decide(percent: Int, maxSteps: Int, currentSteps: Int, fixed: Boolean): Pair<String, Int> {
        val want = if (maxSteps <= 0) 0 else Math.round(maxSteps * percent / 100f)
        val sysOk = !fixed && maxSteps > 0 && currentSteps == want
        return if (sysOk) Pair("system", Math.round(currentSteps * 100f / maxSteps)) else Pair("player", percent)
    }
}
