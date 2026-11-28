package vn.anio.signage

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/*
 * Mô hình bố cục đa vùng của AMS (slot `bp_*`, DEVICE-PROTOCOL mục 7.4): chỉ logic thuần (không dùng android.*)
 * để chạy được trong test JVM với manifest thật do gateway sinh ra.
 */

/** Một tệp phát trong vùng: đường dẫn tệp đã cache + thời lượng. */
data class ZMedia(
    val creativeId: String,
    val isVideo: Boolean,
    val path: String,
    val durationMs: Long,
    val fit: Boolean,
    /** mục danh sách phát (trống = suy từ vùng cũ) */
    val itemId: String = "",
    /** video | image | text */
    val kind: String = if (isVideo) "video" else "image",
    /** link phát trực tiếp (stream/external); trống = tệp đã tải ở [path] */
    val remoteUrl: String = "",
    val stream: String = "",
    val textContent: String = "",
    val rich: String = "",
    val style: JSONObject? = null,
    /** video: cắt sau từng ấy ms; null = phát hết tệp */
    val cutMs: Long? = null
) {
    val isText: Boolean get() = kind == "text"
}

data class Zone(
    val id: String,
    val name: String,
    val type: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val z: Int,
    val transition: String,
    val text: String,
    val bgColor: String,
    val textColor: String,
    val fontSize: Int,
    val scrollSpeed: Int,
    val url: String,
    val qrKind: String,
    val qrMode: String,
    val qrValue: String,
    val vietqr: JSONObject?,
    val weatherMode: String,
    val lat: Double?,
    val lon: Double?,
    val label: String,
    val creativeIds: List<String>,
    val hidden: Boolean,
    val playlist: List<PItem> = emptyList(),
    val loop: Boolean = true
) {
    val isMedia: Boolean get() = type == "video" || type == "image" || type == "playlist"
    val area: Double get() = w * h

    // data class dùng equals của JSONObject (so sánh tham chiếu) nên đưa vietqr vào equals bằng chuỗi
    override fun equals(other: Any?): Boolean =
        other is Zone && id == other.id && type == other.type && x == other.x && y == other.y && w == other.w && h == other.h &&
            z == other.z && transition == other.transition && text == other.text && bgColor == other.bgColor &&
            textColor == other.textColor && fontSize == other.fontSize && scrollSpeed == other.scrollSpeed &&
            url == other.url && qrKind == other.qrKind && qrMode == other.qrMode && qrValue == other.qrValue &&
            vietqr?.toString() == other.vietqr?.toString() && weatherMode == other.weatherMode && lat == other.lat &&
            lon == other.lon && label == other.label && creativeIds == other.creativeIds && hidden == other.hidden &&
            playlist == other.playlist && loop == other.loop

    override fun hashCode(): Int = id.hashCode() * 31 + type.hashCode() + creativeIds.hashCode()
}

class Layout(val orientation: String, val zones: List<Zone>) {
    val portrait: Boolean get() = orientation == "portrait"

    /** Khung thiết kế của web (LivePreview): 540x960 khi dọc, 960x540 khi ngang; cỡ chữ của vùng tính theo khung này. */
    val designWidth: Int get() = if (portrait) 540 else 960
}

object LayoutParser {
    private val TYPES = setOf("video", "image", "playlist", "marquee", "text", "clock", "weather", "qr")

    private fun JSONObject.num(name: String, def: Double): Double =
        if (isNull(name) || !has(name)) def else optDouble(name, def).let { if (it.isNaN()) def else it }

    private fun JSONObject.optD(name: String): Double? =
        if (isNull(name) || !has(name)) null else optDouble(name).let { if (it.isNaN()) null else it }

    /** null = không có bố cục hợp lệ (slot phát tuần tự như thiết bị cũ). */
    fun parse(o: JSONObject?): Layout? {
        if (o == null) return null
        val arr = o.optJSONArray("zones") ?: return null
        val zones = ArrayList<Zone>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val type = j.s("type")
            if (type !in TYPES) continue
            val qr = j.optJSONObject("qr")
            val wt = j.optJSONObject("weather")
            val ids = ArrayList<String>()
            val ca = j.optJSONArray("creative_ids")
            if (ca != null) for (k in 0 until ca.length()) ca.optString(k).takeIf { it.isNotEmpty() }?.let { ids.add(it) }
            zones.add(
                Zone(
                    id = j.s("id", "z$i"),
                    name = j.s("name"),
                    type = type,
                    x = j.num("x", 0.0).coerceIn(0.0, 100.0),
                    y = j.num("y", 0.0).coerceIn(0.0, 100.0),
                    w = j.num("w", 100.0).coerceIn(1.0, 100.0),
                    h = j.num("h", 100.0).coerceIn(1.0, 100.0),
                    z = j.optInt("z", 0),
                    transition = j.s("transition", "none"),
                    text = j.s("text"),
                    bgColor = j.s("bg_color"),
                    textColor = j.s("text_color"),
                    fontSize = j.optInt("font_size", 0),
                    scrollSpeed = j.optInt("scroll_speed", 60),
                    url = j.s("url"),
                    qrKind = j.s("qr_kind"),
                    qrMode = qr?.s("mode") ?: "",
                    qrValue = qr?.s("value") ?: "",
                    vietqr = qr?.optJSONObject("vietqr"),
                    weatherMode = wt?.s("mode") ?: "",
                    lat = wt?.optD("lat"),
                    lon = wt?.optD("lon"),
                    label = wt?.s("label") ?: "",
                    creativeIds = ids,
                    hidden = j.optBoolean("hidden", false),
                    playlist = Playlists.parse(j.optJSONArray("playlist")),
                    loop = j.optBoolean("loop", true)
                )
            )
        }
        if (zones.isEmpty()) return null
        // vẽ theo z tăng dần (cùng z giữ thứ tự khai báo)
        val sorted = zones.withIndex().sortedWith(compareBy({ it.value.z }, { it.index })).map { it.value }
        return Layout(if (o.s("orientation") == "portrait") "portrait" else "landscape", sorted)
    }

    /**
     * Mục phát theo vùng media. Vùng có `playlist` (AMS mới) phát đúng danh sách đó (video/ảnh tệp đã tải, link phát trực tiếp, chữ);
     * vùng cũ (chỉ `creative_ids`) đọc thành danh sách với id dẫn xuất ổn định. Mục có tệp chưa tải được bị bỏ.
     * null nếu bố cục có vùng media nhưng không vùng nào có mục phát.
     */
    fun zoneMedia(layout: Layout, media: Map<String, ZMedia>): Map<String, List<ZMedia>>? {
        val zm = HashMap<String, List<ZMedia>>()
        for (z in layout.zones) {
            if (!z.isMedia || z.hidden) continue
            val l = if (z.playlist.isNotEmpty()) z.playlist.mapNotNull { fromItem(it, media) }
            else z.creativeIds.mapNotNull { cid -> media[cid]?.copy(itemId = Playlists.legacyItemId(z.id, cid)) }
            if (l.isNotEmpty()) zm[z.id] = l
        }
        if (layout.zones.any { it.isMedia && !it.hidden } && zm.isEmpty()) return null
        return zm
    }

    private fun fromItem(it: PItem, media: Map<String, ZMedia>): ZMedia? {
        if (it.isText) {
            return ZMedia(it.mediaId, false, "", Playlists.staticMs(it.playS), true, it.id, "text", "", "", it.textContent, it.rich, it.style, null)
        }
        val cut = if (it.isVideo) it.playS?.let { s -> (s * 1000).toLong() } else null
        if (it.isRemote || (it.mediaId.isEmpty() && it.url.isNotEmpty())) {
            if (it.url.isEmpty()) return null
            val dur = if (it.isVideo) (cut ?: 30_000L) else Playlists.staticMs(it.playS)
            return ZMedia(it.mediaId, it.isVideo, "", dur, true, it.id, it.type, it.url, Playlists.streamKindOf(it.url), "", "", null, cut)
        }
        val m = media[it.mediaId] ?: return null
        val dur = if (it.isVideo) (cut ?: m.durationMs) else Playlists.staticMs(it.playS)
        return m.copy(itemId = it.id, isVideo = it.isVideo, kind = it.type, durationMs = dur, cutMs = cut)
    }

    /** Chọn vùng video được bật tiếng: vùng media có video, diện tích lớn nhất (ngang bằng thì z cao hơn). */
    fun primaryAudioZone(layout: Layout, media: Map<String, List<ZMedia>>): String? =
        layout.zones.filter { it.isMedia && !it.hidden && media[it.id].orEmpty().any { m -> m.isVideo } }
            .sortedWith(compareByDescending<Zone> { it.area }.thenByDescending { it.z })
            .firstOrNull()?.id

    /** Thời gian chương trình đa vùng ở trên màn hình: một chu kỳ của vùng dài nhất (5 giây đến 10 phút); không có vùng media thì 15 giây. */
    fun programDurationMs(media: Map<String, List<ZMedia>>): Long {
        val longest = media.values.maxOfOrNull { l -> l.sumOf { it.durationMs } } ?: 0L
        return if (longest <= 0) 15_000L else longest.coerceIn(5_000L, 600_000L)
    }

    /** #RRGGBB / #AARRGGBB -> ARGB; sai định dạng thì trả [def]. */
    fun argb(hex: String, def: Int): Int {
        val s = hex.trim().removePrefix("#")
        return try {
            when (s.length) {
                6 -> (0xFF000000L or s.toLong(16)).toInt()
                8 -> s.toLong(16).toInt()
                else -> def
            }
        } catch (_: Exception) {
            def
        }
    }

    /** Một lượt chạy hết chiều ngang của chữ chạy (giây), giống LivePreview của web. */
    fun marqueePassSec(speed: Int): Int = Math.max(4, Math.round(28 - speed * 0.22).toInt())
}

/** Nguồn mã QR sau khi giải thích `qr_kind`/`qr` của vùng. */
sealed class QrSource {
    data class Text(val content: String) : QrSource()
    data class ImageUrl(val url: String) : QrSource()
    data class Bytes(val data: ByteArray) : QrSource()
    object None : QrSource()
}

object QrLogic {
    /** URL ảnh VietQR công khai: khớp `vietqrUrl` của gateway/web (img.vietqr.io, không khóa). */
    fun vietqrUrl(v: JSONObject): String {
        val q = ArrayList<String>()
        val amount = v.optLong("amount", 0L)
        if (amount > 0) q.add("amount=" + enc(amount.toString()))
        val memo = v.s("memo")
        if (memo.isNotEmpty()) q.add("addInfo=" + enc(memo))
        val name = v.s("name")
        if (name.isNotEmpty()) q.add("accountName=" + enc(name))
        val qs = q.joinToString("&")
        return "https://img.vietqr.io/image/" + v.s("bin") + "-" + v.s("account") + "-" + v.s("template", "qr_only") + ".png" +
            if (qs.isEmpty()) "" else "?$qs"
    }

    // URLSearchParams: khoảng trắng thành '+', giữ nguyên chữ-số và *-._
    private fun enc(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '*' || ch == '-' || ch == '.' || ch == '_') sb.append(ch)
            else if (ch == ' ') sb.append('+')
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun source(z: Zone): QrSource {
        when (z.qrKind) {
            "image_url" -> return if (z.url.isNotEmpty()) QrSource.ImageUrl(z.url) else QrSource.None
            "data_url" -> return decodeDataUrl(z.url)?.let { QrSource.Bytes(it) } ?: QrSource.None
            "text" -> return if (z.url.isNotEmpty()) QrSource.Text(z.url) else QrSource.None
        }
        // gateway cũ: chỉ có `qr` gốc
        return when (z.qrMode) {
            "vietqr" -> z.vietqr?.let { QrSource.ImageUrl(vietqrUrl(it)) } ?: QrSource.None
            "image" -> decodeDataUrl(z.qrValue)?.let { QrSource.Bytes(it) } ?: QrSource.None
            "url" -> if (z.qrValue.isNotEmpty()) QrSource.Text(z.qrValue) else QrSource.None
            else -> if (z.url.isNotEmpty()) QrSource.Text(z.url) else QrSource.None
        }
    }

    fun decodeDataUrl(u: String): ByteArray? {
        if (!u.startsWith("data:")) return null
        val i = u.indexOf(',')
        if (i < 0) return null
        return try {
            Base64.getDecoder().decode(u.substring(i + 1).trim())
        } catch (_: Exception) {
            null
        }
    }

    /** Ma trận QR (true = ô tối) cạnh [n] x [n]; null nếu nội dung rỗng hoặc quá dài. */
    fun matrix(content: String): Array<BooleanArray>? {
        if (content.isEmpty() || content.length > 1500) return null
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 0,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
            val bm = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
            Array(bm.height) { y -> BooleanArray(bm.width) { x -> bm.get(x, y) } }
        } catch (_: Exception) {
            null
        }
    }
}

class Weather(val tempC: Double, val humidity: Int, val windKmh: Double, val code: Int)

object WeatherLogic {
    /** Open-Meteo, không cần khóa; cùng tham số với trình xem trước của web. */
    fun url(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m&timezone=auto"

    /** Hà Nội: dùng khi chế độ gps nhưng web không gửi toạ độ. */
    const val DEFAULT_LAT = 21.0285
    const val DEFAULT_LON = 105.8542

    fun parse(json: String): Weather? = try {
        val c = JSONObject(json).optJSONObject("current")
        if (c == null || c.isNull("temperature_2m")) null
        else Weather(
            c.optDouble("temperature_2m"),
            c.optDouble("relative_humidity_2m", 0.0).toInt(),
            c.optDouble("wind_speed_10m", 0.0),
            c.optInt("weather_code", 0)
        )
    } catch (_: Exception) {
        null
    }

    fun label(code: Int): String = when (code) {
        0 -> "Trời quang"
        1 -> "Ít mây"
        2 -> "Có mây"
        3 -> "Nhiều mây"
        45, 48 -> "Sương mù"
        in 51..57 -> "Mưa phùn"
        in 61..67 -> "Mưa"
        in 71..77 -> "Tuyết"
        in 80..82 -> "Mưa rào"
        85, 86 -> "Mưa tuyết"
        in 95..99 -> "Dông"
        else -> "Thời tiết"
    }

    fun coords(z: Zone): Pair<Double, Double> = Pair(z.lat ?: DEFAULT_LAT, z.lon ?: DEFAULT_LON)
}
