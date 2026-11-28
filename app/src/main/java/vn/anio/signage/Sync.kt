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
    /** Chi tiết kết quả lệnh giao diện vừa chạy (ví dụ âm lượng thực tế), gửi kèm ack. */
    @Volatile var lastCmdDetail = ""
    @Volatile var realtimeOk = false
    private val net = Executors.newSingleThreadScheduledExecutor()
    private val rtExec = Executors.newSingleThreadExecutor()
    @Volatile private var running = true
    private val cmdLock = Any()
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val amsDir = File(ctx.cacheDir, "ams")
    private val manifestFile = File(ctx.filesDir, "manifest.json")
    private val eventsFile = File(ctx.filesDir, "events.jsonl")
    private val queue = EventQueue(object : TextStore {
        private val f = File(ctx.filesDir, "event-queue.json")
        override fun read(): String? = try { if (f.exists()) f.readText() else null } catch (_: Exception) { null }
        override fun write(text: String) {
            val t = File(ctx.filesDir, "event-queue.json.tmp")
            t.writeText(text)
            if (!t.renameTo(f)) { f.writeText(text); t.delete() }
        }
    })
    private val uploader = EventUploader(
        queue,
        { arr -> val r = gw.postEvents(arr); SendResult(r.code, r.errCode(), r.retryAfter) },
        { prefs.clockOffset * 1000 },
        { System.currentTimeMillis() }
    )
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun start() {
        io.execute { safe { loadCachedManifest() } }
        safe { importLegacyEvents() }
        net.scheduleWithFixedDelay({ safe { healthTick() } }, 3, 15, TimeUnit.SECONDS)
        net.schedule(Runnable { hbTick() }, 5, TimeUnit.SECONDS)
        rtExec.execute { realtimeLoop() }
        net.scheduleWithFixedDelay({ safe { flushEvents(false) } }, 60, 60, TimeUnit.SECONDS)
        net.scheduleWithFixedDelay({ safe { flushLogs() } }, 120, 600, TimeUnit.SECONDS)
        io.scheduleWithFixedDelay({ safe { syncAll() } }, 1, 60, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        rtExec.shutdownNow()
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

    /**
     * Kênh thời gian thực: giữ một yêu cầu long-poll GET /v1/commands/wait (<=25 giây) tới AMS; tin khẩn/lệnh tới là áp ngay (~1–2 giây)
     * thay vì đợi heartbeat. Lỗi mạng/401 thì lùi dần 5→60 giây; heartbeat định kỳ vẫn chạy song song làm dự phòng.
     */
    private fun realtimeLoop() {
        var backoff = 5
        while (running) {
            try {
                if (!prefs.isSel(Src.AMS) || prefs.deviceId.isEmpty() || amsDown) {
                    realtimeOk = false
                    Thread.sleep(3000)
                    continue
                }
                val (r, cmds) = gw.waitCommands(25)
                if (r.ok) {
                    realtimeOk = true
                    backoff = 5
                    if (cmds.isNotEmpty()) handleCommands(cmds)
                } else {
                    realtimeOk = false
                    Diag.lastError = "realtime ${r.code} ${r.errCode()}"
                    val wait = Math.max(backoff, r.retryAfter).coerceAtMost(300)
                    backoff = (backoff * 2).coerceAtMost(60)
                    Thread.sleep(wait * 1000L)
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                realtimeOk = false
                Thread.sleep(backoff * 1000L)
                backoff = (backoff * 2).coerceAtMost(60)
            }
        }
    }

    /** Nút "Đồng bộ ngay": hỏi AMS (lệnh/thông báo) + tải lại manifest tức thì, không đợi chu kỳ. */
    fun pollNow() {
        try {
            net.execute { safe { pollOnce() } }
            io.execute { safe { manifestEtag = ""; syncAms() } }
        } catch (_: Exception) {}
    }

    private fun handleCommands(cmds: List<Cmd>) = synchronized(cmdLock) { handleCommandsLocked(cmds) }

    private fun handleCommandsLocked(cmds: List<Cmd>) {
        if (cmds.isEmpty()) return
        val ack = ArrayList<Pair<String, Boolean>>()
        val details = HashMap<String, String>()
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
                "message", "set_volume", "set_orientation" -> {
                    lastCmdDetail = ""
                    ok = runUi(c)
                    if (lastCmdDetail.isNotEmpty()) details[c.id] = lastCmdDetail
                }
            }
            // lưu id TRƯỚC khi ack để giao lại sau khi app khởi động lại vẫn bị bỏ qua
            done.add(c.id)
            prefs.doneCmds = done
            ack.add(Pair(c.id, ok))
            Diag.command(c.type + if (ok) " ✓" else " ✗")
        }
        try { gw.ackCommands(ack, details) } catch (e: Exception) { Logger.log("ack lỗi: ${e.message}") }
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

    private var retry401 = false

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
        Diag.manifestError = if (r.ok || r.code == 304) "" else r.errCode()
        if (r.code == 401) {
            // chữ ký/thiết bị bị từ chối: bỏ etag (đường dẫn không tham số) rồi thử lại một lần; unknown_device => đăng ký lại ở vòng hỏi kế tiếp
            Diag.lastError = "manifest 401 ${r.errCode()}"
            if (manifestEtag.isNotEmpty()) { manifestEtag = ""; if (!retry401) { retry401 = true; try { syncAms() } finally { retry401 = false }; return } }
            if (prefs.deviceId.isEmpty()) { net.execute { safe { pollOnce() } } }
            return
        }
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
    fun recordPlayId(creativeId: String, playedMs: Long) {
        if (creativeId.isEmpty() || playedMs <= 0) return
        val now = System.currentTimeMillis()
        // giờ máy; giờ gửi = giờ máy + độ lệch MỚI NHẤT lúc gửi (lệch đồng hồ không làm mất sự kiện)
        queue.add(EventQueue.proofOfPlay(Uuid7.gen(now + prefs.clockOffset * 1000), creativeId, playedMs, now), now)
    }

    /** Một mục danh sách phát đã hiển thị ổn định: ghi item_impression (log_id UUIDv7 khử trùng), bền trên máy. */
    fun recordItem(programId: String, zoneId: String, m: ZMedia) {
        if (programId.isEmpty() || zoneId.isEmpty() || m.itemId.isEmpty()) return
        val now = System.currentTimeMillis()
        val logId = Uuid7.gen(now + prefs.clockOffset * 1000)
        queue.add(EventQueue.itemImpression(logId, programId, zoneId, m.itemId, m.creativeId, m.kind, now), now)
        // đủ 50 bản ghi thì gửi ngay, không đợi nhịp 60 giây
        if (queue.shouldFlush(now)) try { net.execute { safe { flushEvents(false) } } } catch (_: Exception) {}
    }

    /** Gửi hàng đợi (proof_of_play + item_impression) lên /v1/events: mỗi 5 phút hoặc đủ 50 bản ghi; chỉ xóa khi máy chủ trả 2xx. */
    private fun flushEvents(force: Boolean) {
        if (!prefs.isSel(Src.AMS) || prefs.deviceId.isEmpty()) return
        uploader.flush(force)
    }

    private fun importLegacyEvents() {
        // 1.1.4 ghi proof_of_play vào events.jsonl: nhập sang hàng đợi mới rồi xóa tệp, không mất dữ liệu khi nâng cấp
        if (!eventsFile.exists()) return
        try {
            val now = System.currentTimeMillis()
            for (l in eventsFile.readLines()) {
                if (l.isBlank()) continue
                val o = try { JSONObject(l) } catch (_: Exception) { continue }
                val id = o.optString("event_id", "")
                var ts = now
                try { isoFmt.parse(o.optString("ts", ""))?.let { ts = it.time } } catch (_: Exception) {}
                o.remove("ts")
                queue.add(QEvent(id, "proof_of_play", ts - prefs.clockOffset * 1000, o), now)
            }
            eventsFile.delete()
        } catch (_: Exception) {}
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
