package vn.anio.signage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

class ApiResult(val code: Int, val body: String, val retryAfter: Int, val etag: String) {
    val ok: Boolean get() = code in 200..299

    fun json(): JSONObject? = try { JSONObject(body) } catch (_: Exception) { null }

    fun errCode(): String = json()?.optJSONObject("error")?.s("code") ?: ""
}

class Cmd(val id: String, val type: String, val args: JSONObject)

class Hb(val ok: Boolean, val nextInterval: Int, val etag: String, val retryAfter: Int, val commands: List<Cmd>)

object Uuid7 {
    private val rnd = SecureRandom()

    fun gen(ms: Long): String {
        val b = ByteArray(16)
        rnd.nextBytes(b)
        for (i in 0 until 6) b[i] = ((ms shr (8 * (5 - i))) and 0xff).toByte()
        b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20)
    }
}

/** Client edge-gateway AMS: docs/DEVICE-PROTOCOL.md (ký Ed25519 mọi yêu cầu v1). */
class Gateway(private val ctx: Context, private val prefs: Prefs) {
    private val rnd = SecureRandom()

    private fun b64e(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64d(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun sha256hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun ensureKeys() {
        if (prefs.seed.isNotEmpty() && prefs.pub.isNotEmpty()) return
        val seed = ByteArray(32)
        rnd.nextBytes(seed)
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        prefs.seed = b64e(seed)
        prefs.pub = b64e(priv.generatePublicKey().encoded)
    }

    private fun sign(msg: ByteArray): ByteArray {
        val priv = Ed25519PrivateKeyParameters(b64d(prefs.seed), 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(msg, 0, msg.size)
        return signer.generateSignature()
    }

    fun call(method: String, path: String, body: String?, withDevice: Boolean = true, retried: Boolean = false): ApiResult {
        ensureKeys()
        val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
        val ts = System.currentTimeMillis() / 1000 + prefs.clockOffset
        val nb = ByteArray(16)
        rnd.nextBytes(nb)
        val nonce = b64e(nb)
        val msg = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + sha256hex(bytes)
        val rb = Request.Builder().url(prefs.gwBase.trimEnd('/') + path)
            .header("X-Timestamp", ts.toString())
            .header("X-Nonce", nonce)
            .header("X-Signature", b64e(sign(msg.toByteArray(Charsets.UTF_8))))
        if (withDevice) rb.header("X-Device-Id", prefs.deviceId)
        if (method == "POST") rb.post(bytes.toRequestBody("application/json".toMediaType())) else rb.get()
        val res = PlaylistIo.http.newCall(rb.build()).execute().use { r ->
            ApiResult(r.code, r.body?.string() ?: "", r.header("Retry-After")?.toIntOrNull() ?: 0, (r.header("ETag") ?: "").trim('"'))
        }
        if (res.code == 401 && !retried) {
            val code = res.errCode()
            if (code == "stale_timestamp") {
                val st = res.json()?.optJSONObject("error")?.optJSONObject("details")?.optLong("server_time", 0L) ?: 0L
                if (st > 0) {
                    prefs.clockOffset = st - System.currentTimeMillis() / 1000
                    return call(method, path, body, withDevice, true)
                }
            } else if (code == "unknown_device") {
                prefs.deviceId = ""
            }
        }
        return res
    }

    /** GET /health (không ký): dùng để kiểm tra kết nối mỗi 15 giây. */
    fun health(): Boolean = try {
        PlaylistIo.http.newCall(Request.Builder().url(prefs.gwBase.trimEnd('/') + "/health").build())
            .execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    fun register(): Boolean {
        ensureKeys()
        val b = JSONObject()
            .put("pubkey", prefs.pub)
            .put("kind", "elevator")
            .put("model", Build.MODEL)
            .put("os", "Android " + Build.VERSION.RELEASE)
            .put("app_version", APP_VERSION)
        if (prefs.region.isNotEmpty()) b.put("region", prefs.region)
        val r = call("POST", "/v1/register", b.toString(), false)
        if (!r.ok) {
            Logger.log("register lỗi ${r.code} ${r.errCode()}")
            return false
        }
        val j = r.json() ?: return false
        prefs.deviceId = j.s("device_id")
        prefs.trust = j.optInt("trust_level", 0)
        Logger.log("đã đăng ký ams, device_id=${prefs.deviceId}")
        return prefs.deviceId.isNotEmpty()
    }

    fun heartbeat(state: Int, etag: String): Hb {
        val b = JSONObject()
            .put("state", state)
            .put("app_ver", APP_VERSION)
            .put("cache_used_mb", (dirSize(File(ctx.cacheDir, "ams")) / 1048576L).toInt())
        if (etag.isNotEmpty()) b.put("manifest_etag", etag)
        val r = call("POST", "/v1/heartbeat", b.toString())
        if (!r.ok) return Hb(false, 0, "", r.retryAfter, emptyList())
        val j = r.json() ?: return Hb(false, 0, "", 0, emptyList())
        val cmds = ArrayList<Cmd>()
        val a = j.optJSONArray("commands") ?: JSONArray()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            cmds.add(Cmd(c.s("id"), c.s("type"), c.optJSONObject("args") ?: JSONObject()))
        }
        val hl = j.s("hotline")
        if (hl.isNotEmpty() && hl != prefs.hotline) prefs.hotline = hl
        return Hb(true, j.optInt("next_interval", 300), j.s("manifest_etag"), 0, cmds)
    }

    fun ackCommands(results: List<Pair<String, Boolean>>) {
        if (results.isEmpty()) return
        val acks = JSONArray()
        for ((id, ok) in results.take(20)) acks.put(JSONObject().put("id", id).put("status", if (ok) "ok" else "failed"))
        call("POST", "/v1/commands/ack", JSONObject().put("acks", acks).toString())
    }

    fun manifest(etag: String): ApiResult =
        call("GET", if (etag.isEmpty()) "/v1/manifest" else "/v1/manifest?etag=$etag", null)

    /** Nhật ký thiết bị (mục 8.5): tối đa 8 KB/100 dòng mỗi lần. */
    fun postLogs(entries: JSONArray): ApiResult =
        call("POST", "/v1/logs", JSONObject().put("entries", entries).toString())

    fun postEvents(events: JSONArray): ApiResult =
        call("POST", "/v1/events", JSONObject().put("events", events).toString())

    private fun dirSize(d: File): Long = d.listFiles()?.sumOf { it.length() } ?: 0L
}

/** Dựng danh sách phát từ manifest AMS (mục 7 của DEVICE-PROTOCOL). */
object ManifestBuilder {
    fun parse(text: String, dir: File, prefs: Prefs, download: Boolean): Parsed? {
        val root = try { JSONObject(text) } catch (_: Exception) { return null }
        val hotline = root.s("hotline")
        if (hotline.isNotEmpty() && hotline != prefs.hotline) prefs.hotline = hotline
        val gh = root.s("header")
        val gf = root.s("footer")
        val creatives = HashMap<String, JSONObject>()
        val ca = root.optJSONArray("creatives") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val c = ca.optJSONObject(i) ?: continue
            creatives[c.s("id")] = c
        }
        val slots = ArrayList<JSONObject>()
        val sa = root.optJSONArray("schedule") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val sl = sa.optJSONObject(i) ?: continue
            slots.add(sl)
        }
        slots.sortBy { it.optInt("priority", 100) }
        dir.mkdirs()
        val out = ArrayList<Item>()
        val keep = HashSet<String>()
        val files = HashMap<String, File?>()
        var complete = true
        val etag = root.s("etag")
        val media = HashMap<String, ZMedia>()
        for (slot in slots) {
            val ids = slot.optJSONArray("creative_ids") ?: continue
            val part = ArrayList<Item>()
            for (k in 0 until ids.length()) {
                val cid = ids.optString(k)
                val c = creatives[cid] ?: continue
                val url = c.s("url")
                if (url.isEmpty()) continue
                val isVideo = c.s("media_type", "video") == "video"
                if (!files.containsKey(cid)) {
                    val ext = url.substringBefore('?').substringAfterLast('.', if (isVideo) "mp4" else "jpg").take(5)
                    val dest = File(dir, cid.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext)
                    keep.add(dest.name)
                    val ok = if (download) PlaylistIo.downloadTo(url, dest, c.s("sha256"), null)
                    else dest.exists() && dest.length() > 0
                    if (!ok) {
                        complete = false
                        Logger.log("warn", "cache", "tải creative lỗi $cid")
                    }
                    files[cid] = if (ok) dest else null
                }
                val f = files[cid] ?: continue
                val durS = c.optDouble("duration_s", 10.0)
                val dur = (durS * 1000).toLong().coerceAtLeast(1000)
                media[cid] = ZMedia(cid, isVideo, f.path, dur, c.s("fit") == "fit")
                part.add(
                    Item(
                        id = slot.s("slot_id") + ":" + cid,
                        isVideo = isVideo,
                        uri = Uri.fromFile(f),
                        durationMs = dur,
                        header = slot.s("header", gh),
                        footer = slot.s("footer", gf),
                        fit = c.s("fit") == "fit",
                        startAt = 0,
                        endAt = 0,
                        creativeId = cid,
                        campaignId = c.s("campaign_id", slot.s("campaign_id")),
                        slotId = slot.s("slot_id"),
                        dayStartMin = slot.optInt("start_min", 0),
                        dayEndMin = slot.optInt("end_min", 1440),
                        manifestEtag = etag
                    )
                )
            }
            val lay = layoutItem(slot, media, gh, gf, etag)
            if (lay != null) out.add(lay) else out.addAll(part)
        }
        if (download) dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        return Parsed(etag, out, complete)
    }

    /** Slot có `layout` hợp lệ -> một mục đa vùng; null = phát tuần tự như thiết bị cũ. */
    private fun layoutItem(slot: JSONObject, media: Map<String, ZMedia>, gh: String, gf: String, etag: String): Item? {
        val lay = LayoutParser.parse(slot.optJSONObject("layout")) ?: return null
        val zm = LayoutParser.zoneMedia(lay, media)
        // vùng media khai báo nhưng không có tệp nào tải được: không vẽ chương trình dở dang, phát tuần tự
        if (zm == null) return null
        val sid = slot.s("slot_id")
        return Item(
            id = "$sid:layout",
            isVideo = false,
            uri = Uri.EMPTY,
            durationMs = LayoutParser.programDurationMs(zm),
            header = slot.s("header", gh),
            footer = slot.s("footer", gf),
            fit = false,
            startAt = 0,
            endAt = 0,
            creativeId = "",
            campaignId = slot.s("campaign_id"),
            slotId = sid,
            dayStartMin = slot.optInt("start_min", 0),
            dayEndMin = slot.optInt("end_min", 1440),
            layout = lay,
            zoneMedia = zm,
            manifestEtag = etag
        )
    }
}
