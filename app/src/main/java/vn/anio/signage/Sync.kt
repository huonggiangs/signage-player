package vn.anio.signage

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Đồng bộ ba nguồn: AMS (gateway /v1), USB, LAN. */
class Sync(
    private val ctx: Context,
    private val prefs: Prefs,
    private val notify: () -> Unit,
    private val playing: () -> String,
    private val state: () -> Int
) {
    val items = ConcurrentHashMap<Src, List<Item>>()
    @Volatile var amsDown = false
    /** Phiên bản manifest đang giữ (hiện trong chế độ gỡ lỗi). */
    val etag: String get() = manifestEtag
    @Volatile var lastLogFlush = 0L

    private val gw = Gateway(ctx, prefs)
    private var fails = 0
    private var oks = 0
    private var manifestEtag = ""
    private var manifestAt = 0L
    private var nextHb = 300
    private var lanVersion = ""
    /** Lệnh cần giao diện thực hiện (message, set_volume, set_orientation); chạy trên luồng chính, trả true nếu hợp lệ. */
    @Volatile var uiCommand: ((String, org.json.JSONObject) -> Boolean)? = null
    private val net = Executors.newSingleThreadScheduledExecutor()
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val amsDir = File(ctx.cacheDir, "ams")
    private val manifestFile = File(ctx.filesDir, "manifest.json")
    private val eventsFile = File(ctx.filesDir, "events.jsonl")
    private var lastFlush = System.currentTimeMillis()
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun start() {
        io.execute { safe { loadCachedManifest() } }
        net.scheduleWithFixedDelay({ safe { healthTick() } }, 3, 15, TimeUnit.SECONDS)
        net.schedule(Runnable { hbTick() }, 5, TimeUnit.SECONDS)
        net.scheduleWithFixedDelay({ safe { flushEvents(false) } }, 60, 60, TimeUnit.SECONDS)
        net.scheduleWithFixedDelay({ safe { flushLogs() } }, 120, 600, TimeUnit.SECONDS)
        io.scheduleWithFixedDelay({ safe { syncAll() } }, 1, 60, TimeUnit.SECONDS)
    }

    fun stop() {
        net.shutdownNow()
        io.shutdownNow()
    }

    fun syncNow() { try { io.execute { safe { syncAll() } } } catch (_: Exception) {} }
    fun healthNow() { try { net.execute { safe { healthTick() } } } catch (_: Exception) {} }
    fun usbRemoved() {
        items[Src.USB] = emptyList()
        fire()
    }

    private fun fire() { main.post(notify) }

    private inline fun safe(f: () -> Unit) {
        try { f() } catch (e: Exception) { Logger.log("lỗi đồng bộ: ${e.message}") }
    }

    // ------------------------------------------------------------ kết nối ams

    private fun healthTick() {
        if (!prefs.isSel(Src.AMS)) return
        if (gw.health()) {
            fails = 0
            oks++
            if (amsDown && oks >= 2) {
                amsDown = false
                Logger.log("ams.anio.vn có mạng lại")
                fire()
            }
        } else {
            oks = 0
            fails++
            if (fails >= 3 && !amsDown) {
                amsDown = true
                Logger.log("mất mạng ams.anio.vn, chuyển nguồn dự phòng")
                fire()
            }
        }
    }

    private fun hbTick() {
        val delay = try { pollOnce() } catch (e: Exception) {
            Logger.log("heartbeat lỗi: ${e.message}")
            Diag.lastError = "poll: " + (e.message ?: e.javaClass.simpleName).take(80)
            PollPolicy.REGISTER_RETRY_S
        }
        try { net.schedule(Runnable { hbTick() }, delay.toLong(), TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    /** Một vòng hỏi AMS (đăng ký nếu chưa có mã -> heartbeat -> lệnh/manifest); trả số giây tới lần sau. */
    private fun pollOnce(): Int {
        if (!prefs.isSel(Src.AMS)) return 60
        if (prefs.deviceId.isEmpty()) return if (gw.register()) 5 else PollPolicy.REGISTER_RETRY_S
        val hb = gw.heartbeat(state(), manifestEtag)
        if (!hb.ok && prefs.deviceId.isEmpty()) return 5 // unknown_device: đăng ký lại ngay
        if (!hb.ok) return PollPolicy.delay(0, hb.retryAfter).coerceAtLeast(60).coerceAtMost(300)
        nextHb = PollPolicy.delay(hb.nextInterval)
        if (hb.etag.isNotEmpty() && hb.etag != manifestEtag) io.execute { safe { syncAms() } }
        handleCommands(hb.commands)
        return nextHb
    }

    /** Nút "Đồng bộ ngay": hỏi AMS (lệnh/thông báo) + tải lại manifest tức thì, không đợi chu kỳ. */
    fun pollNow() {
        try {
            net.execute { safe { pollOnce() } }
            io.execute { safe { manifestEtag = ""; syncAms() } }
        } catch (_: Exception) {}
    }

    private fun handleCommands(cmds: List<Cmd>) {
        if (cmds.isEmpty()) return
        val ack = ArrayList<Pair<String, Boolean>>()
        var restart = false
        val done = ArrayList(prefs.doneCmds)
        for (c in cmds) {
            if (done.contains(c.id)) { ack.add(Pair(c.id, true)); continue }
            Logger.log("lệnh từ ams: ${c.type}")
            var ok = true
            when (c.type) {
                "refresh_manifest" -> {
                    manifestEtag = ""
                    io.execute { safe { syncAms() } }
                }
                "reset_pin" -> prefs.resetPin()
                "reboot" -> restart = true
                "message", "set_volume", "set_orientation" -> ok = runUi(c)
            }
            // lưu id TRƯỚC khi ack để giao lại sau khi app khởi động lại vẫn bị bỏ qua
            done.add(c.id)
            prefs.doneCmds = done
            ack.add(Pair(c.id, ok))
            Diag.command(c.type + if (ok) " ✓" else " ✗")
        }
        try { gw.ackCommands(ack) } catch (e: Exception) { Logger.log("ack lỗi: ${e.message}") }
        if (restart) main.postDelayed({ App.restart(ctx) }, 1500)
    }

    private fun runUi(c: Cmd): Boolean {
        val h = uiCommand ?: return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var res = false
        main.post {
            try { res = h(c.type, c.args) } catch (e: Exception) { Logger.log("lệnh ${c.type} lỗi: ${e.message}") }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return res
    }

    private fun loadCachedManifest() {
        if (!manifestFile.exists()) return
        val res = ManifestBuilder.parse(manifestFile.readText(), amsDir, prefs, false) ?: return
        if (items[Src.AMS].isNullOrEmpty()) {
            items[Src.AMS] = res.items
            Logger.log("nạp manifest đã lưu: ${res.items.size} mục")
            fire()
        }
    }

    private fun syncAms() {
        if (prefs.deviceId.isEmpty()) return
        val r = try {
            gw.manifest(manifestEtag)
        } catch (e: Exception) {
            Diag.manifestCode = -1
            Diag.lastError = "manifest: " + (e.message ?: e.javaClass.simpleName).take(80)
            throw e
        }
        manifestAt = System.currentTimeMillis()
        Diag.manifestAt = manifestAt
        Diag.manifestCode = r.code
        if (r.code == 304) return
        if (!r.ok) {
            Logger.log("manifest lỗi ${r.code} ${r.errCode()}")
            Diag.lastError = "manifest ${r.code} ${r.errCode()}"
            return
        }
        val res = ManifestBuilder.parse(r.body, amsDir, prefs, true) ?: return
        if (res.complete) {
            manifestEtag = if (r.etag.isNotEmpty()) r.etag else res.version
            try { manifestFile.writeText(r.body) } catch (_: Exception) {}
        }
        items[Src.AMS] = res.items
        Logger.log("manifest ams: ${res.items.size} mục")
        fire()
    }

    // ------------------------------------------------------------ sự kiện phát (proof_of_play)

    fun recordPlay(item: Item, playedMs: Long) = recordPlayId(item.creativeId, playedMs)

    /** Ghi proof_of_play cho một creative (chương trình đa vùng ghi theo từng tệp của từng vùng). */
    @Synchronized
    fun recordPlayId(creativeId: String, playedMs: Long) {
        if (creativeId.isEmpty() || playedMs <= 0) return
        val now = System.currentTimeMillis()
        val o = JSONObject()
            .put("type", "proof_of_play")
            .put("event_id", Uuid7.gen(now + prefs.clockOffset * 1000))
            .put("ts", isoFmt.format(Date(now + prefs.clockOffset * 1000)))
            .put("creative_id", creativeId)
            .put("duration_ms", playedMs.coerceIn(0L, 3_600_000L))
        try { eventsFile.appendText(o.toString() + "\n") } catch (_: Exception) {}
    }

    @Synchronized
    private fun flushEvents(force: Boolean) {
        if (!prefs.isSel(Src.AMS) || prefs.deviceId.isEmpty() || !eventsFile.exists()) return
        val lines = eventsFile.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && lines.size < 20 && now - lastFlush < 300_000) return
        val batch = lines.take(50)
        val arr = JSONArray()
        for (l in batch) {
            try { arr.put(JSONObject(l)) } catch (_: Exception) {}
        }
        val r = gw.postEvents(arr)
        lastFlush = now
        if (r.ok || (r.code in 400..499 && r.code != 429)) {
            if (!r.ok) Logger.log("events bị từ chối ${r.code} ${r.errCode()}, bỏ lô")
            val rest = lines.drop(batch.size)
            eventsFile.writeText(if (rest.isEmpty()) "" else rest.joinToString("\n") + "\n")
        }
    }

    /** Gửi nhật ký thiết bị lên AMS (/v1/logs, <=8 KB) mỗi 10 phút để debug từ web; chỉ khi bật chế độ gỡ lỗi. */
    private fun flushLogs() {
        if (!prefs.debug || !prefs.isSel(Src.AMS) || prefs.deviceId.isEmpty()) return
        val batch = Logger.drain(60)
        if (batch.isEmpty()) return
        val arr = JSONArray()
        for (o in batch) arr.put(o)
        val r = try { gw.postLogs(arr) } catch (e: Exception) { null }
        if (r == null || (!r.ok && (r.code == 429 || r.code >= 500))) Logger.restore(batch)
        else lastLogFlush = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ USB, LAN

    private fun syncAll() {
        if (prefs.isSel(Src.AMS) && prefs.deviceId.isNotEmpty() &&
            (manifestEtag.isEmpty() || System.currentTimeMillis() - manifestAt > 900_000)
        ) safe { syncAms() }
        if (prefs.isSel(Src.USB)) safe { syncUsb() }
        if (prefs.isSel(Src.LAN)) safe { syncLan() }
    }

    private fun syncUsb() {
        val old = items[Src.USB].orEmpty().map { it.id }
        val now = UsbScanner.scan(ctx, prefs)
        items[Src.USB] = now
        if (old != now.map { it.id }) {
            Logger.log("USB: ${now.size} mục")
            fire()
        }
    }

    private fun syncLan() {
        val url = prefs.lanUrl
        if (url.isEmpty()) {
            items[Src.LAN] = emptyList()
            return
        }
        val text = PlaylistIo.getText(url, null) ?: return
        val ver = PlaylistIo.versionOf(text)
        if (ver == lanVersion && !items[Src.LAN].isNullOrEmpty()) return
        val res = PlaylistIo.parse(text, url.substringBeforeLast('/'), File(ctx.cacheDir, "lan"), null, prefs) ?: return
        if (res.complete) lanVersion = ver
        items[Src.LAN] = res.items
        Logger.log("playlist LAN: ${res.items.size} mục")
        fire()
    }
}
