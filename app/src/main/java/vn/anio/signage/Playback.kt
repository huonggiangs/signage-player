package vn.anio.signage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Một lớp hiển thị: PlayerView (video) + ImageView (ảnh), có ExoPlayer riêng để preload. */
class Slot(private val ctx: Context, val view: View, private val onEvent: (Slot, Int) -> Unit) : ZoneEvents {
    companion object {
        const val ENDED = 1
        const val ERROR = 2
    }

    private val pv: PlayerView = view.findViewById(R.id.pv)
    private val iv: ImageView = view.findViewById(R.id.iv)
    private val lv: FrameLayout = view.findViewById(R.id.lv)
    /** Bộ vẽ bố cục đa vùng khi mục đang nạp là chương trình có layout. */
    var renderer: ZoneRenderer? = null
        private set
    var clockMs: () -> Long = { System.currentTimeMillis() }
    var playedCb: ((String, Long) -> Unit)? = null
    var lastError: String = ""
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    var loaded: Item? = null
    var ready = false
    var failed = false
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    private val gainCb: () -> Unit = { main.post { try { player.volume = PlayerGain.value } catch (_: Exception) {} } }

    init {
        pv.player = player
        player.volume = PlayerGain.value
        PlayerGain.listen(gainCb)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (loaded?.isVideo != true) return
                if (playbackState == Player.STATE_READY) ready = true
                if (playbackState == Player.STATE_ENDED) onEvent(this@Slot, ENDED)
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                onEvent(this@Slot, ERROR)
            }
        })
    }

    override fun onZonePlayed(creativeId: String, ms: Long) { playedCb?.invoke(creativeId, ms) }

    override fun onZoneError(zoneId: String, creativeId: String, msg: String) {
        lastError = "vùng $zoneId $creativeId: $msg"
    }

    private fun dropRenderer() {
        renderer?.release()
        renderer = null
        lv.removeAllViews()
        lv.visibility = View.GONE
    }

    fun load(item: Item) {
        if (loaded == item && !failed) return
        dropRenderer()
        loaded = item
        ready = false
        failed = false
        val g = ++gen
        if (item.layout != null) {
            // chương trình đa vùng: mỗi vùng có trình phát riêng, sẵn sàng ngay (vùng tự nạp tệp)
            player.stop()
            player.clearMediaItems()
            pv.visibility = View.GONE
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            lv.visibility = View.VISIBLE
            try {
                renderer = ZoneRenderer(ctx, lv, item, clockMs, this)
                ready = true
            } catch (e: Exception) {
                Logger.log("error", "layout", "dựng bố cục lỗi: ${e.message}")
                dropRenderer()
                failed = true
                onEvent(this, ERROR)
            }
        } else if (item.isVideo) {
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            pv.visibility = View.VISIBLE
            player.setMediaItem(MediaItem.fromUri(item.uri))
            player.playWhenReady = false
            player.prepare()
        } else {
            player.stop()
            player.clearMediaItems()
            pv.visibility = View.GONE
            iv.visibility = View.VISIBLE
            iv.scaleType = if (item.fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            Thread {
                val bmp = decode(item.uri)
                main.post {
                    if (g == gen) {
                        if (bmp == null) {
                            failed = true
                            onEvent(this, ERROR)
                        } else {
                            iv.setImageBitmap(bmp)
                            ready = true
                        }
                    }
                }
            }.start()
        }
    }

    fun start() {
        renderer?.start()
        if (loaded?.isVideo == true) player.play()
    }

    fun clear() {
        gen++
        dropRenderer()
        player.stop()
        player.clearMediaItems()
        iv.setImageDrawable(null)
        loaded = null
        ready = false
        failed = false
    }

    fun release() {
        PlayerGain.unlisten(gainCb)
        clear()
        player.release()
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            val m = maxOf(o.outWidth, o.outHeight)
            while (m / s > 2048) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            o2.inPreferredConfig = Bitmap.Config.RGB_565
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Throwable) {
            null
        }
    }
}

/** Điều phối phát tuần tự, preload mục kế tiếp, chọn nguồn theo ưu tiên, watchdog. */
class Playback(
    private val ctx: Context,
    private val media: FrameLayout,
    private val prefs: Prefs,
    private val sync: Sync,
    private val ui: Ui
) {
    interface Ui {
        fun onShow(item: Item, src: Src)
        fun onIdle()
    }

    private val h = Handler(Looper.getMainLooper())
    private val slotA = Slot(ctx, LayoutInflater.from(ctx).inflate(R.layout.slot, media, false)) { s, e -> onSlotEvent(s, e) }
    private val slotB = Slot(ctx, LayoutInflater.from(ctx).inflate(R.layout.slot, media, false)) { s, e -> onSlotEvent(s, e) }
    private var front: Slot = slotA
    private var back: Slot = slotB

    var active: Src? = null
        private set
    var current: Item? = null
        private set
    private var running = false
    private var preloaded = false
    private var shownAt = 0L
    private var fails = 0
    private var shown = false
    private var lastPos = -1L
    private var lastMove = 0L

    private val endRun = Runnable { next() }
    private val retry = Runnable { if (current == null) next() }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { onTick() } catch (e: Exception) { Logger.log("tick lỗi: ${e.message}") }
            h.postDelayed(this, 500)
        }
    }

    init {
        media.addView(slotB.view)
        media.addView(slotA.view)
        for (s in listOf(slotA, slotB)) {
            s.clockMs = { System.currentTimeMillis() + prefs.clockOffset * 1000 }
            s.playedCb = { cid, ms -> sync.recordPlayId(cid, ms) }
        }
    }

    /** Dòng gỡ lỗi: nguồn, mục đang phát, vùng/tệp, manifest. */
    fun debugLine(): String {
        val c = current ?: return "không có mục phát (màn chờ)"
        val sb = StringBuilder()
        sb.append(active?.label ?: "-").append(" · slot ").append(c.slotId.ifEmpty { c.id }.takeLast(10))
        if (c.layout != null) {
            sb.append(" · ").append(c.layout.orientation).append(' ').append(c.layout.zones.size).append(" vùng\n").append(front.renderer?.stateLine() ?: "")
        } else {
            sb.append(" · ").append(if (c.isVideo) "video " else "ảnh ").append(c.creativeId.takeLast(8))
        }
        if (front.lastError.isNotEmpty()) sb.append("\nlỗi: ").append(front.lastError)
        return sb.toString()
    }

    fun start() {
        running = true
        next()
        h.postDelayed(tick, 500)
    }

    fun stop() {
        running = false
        h.removeCallbacksAndMessages(null)
        slotA.release()
        slotB.release()
    }

    fun onSyncChanged() {
        if (running && current == null) {
            h.removeCallbacks(retry)
            next()
        }
    }

    private fun valid(s: Src): List<Item> {
        val now = System.currentTimeMillis()
        return sync.items[s].orEmpty().filter { it.valid(now) }
    }

    private fun ready(s: Src): Boolean =
        prefs.isSel(s) && !(s == Src.AMS && sync.amsDown) && valid(s).isNotEmpty()

    fun pickSource(): Src? {
        Src.values().firstOrNull { ready(it) }?.let { return it }
        // không còn nguồn nào sẵn sàng: phát tiếp bản đã cache của ams nếu có
        if (prefs.isSel(Src.AMS) && valid(Src.AMS).isNotEmpty()) return Src.AMS
        return null
    }

    private fun target(): Pair<Src, Item>? {
        val src = pickSource() ?: return null
        val l = valid(src)
        val cur = if (src == active) l.indexOfFirst { it.id == current?.id } else -1
        return Pair(src, l[(cur + 1) % l.size])
    }

    private fun next() {
        h.removeCallbacks(endRun)
        h.removeCallbacks(retry)
        val t = target()
        if (t == null) {
            reportPlay()
            shown = false
            current = null
            active = null
            ui.onIdle()
            h.postDelayed(retry, 30_000)
            return
        }
        if (t.first != active) {
            Logger.log("nguồn phát: ${t.first.label}")
            active = t.first
        }
        back.load(t.second)
        waitReady(back, t.second, 0)
    }

    private fun waitReady(slot: Slot, item: Item, waited: Int) {
        if (!running) return
        if (slot.failed) {
            Logger.log("lỗi phát ${item.id}")
            fail(item)
        } else if (slot.ready) {
            swap(slot, item)
        } else if (waited >= 6000) {
            Logger.log("quá hạn tải ${item.id}")
            fail(item)
        } else {
            h.postDelayed({ waitReady(slot, item, waited + 100) }, 100)
        }
    }

    private fun fail(item: Item) {
        h.removeCallbacks(endRun)
        reportPlay()
        shown = false
        current = item
        fails++
        h.postDelayed({ next() }, if (fails > 3) 5000L else 300L)
    }

    private fun swap(slot: Slot, item: Item) {
        fails = 0
        reportPlay()
        val old = front
        front = slot
        back = old
        slot.view.bringToFront()
        slot.start()
        old.clear()
        current = item
        shownAt = SystemClock.elapsedRealtime()
        preloaded = false
        lastPos = -1
        lastMove = shownAt
        shown = true
        ui.onShow(item, active ?: Src.AMS)
        if (!item.isVideo) h.postDelayed(endRun, item.durationMs)
    }

    private fun reportPlay() {
        val c = current ?: return
        if (shown) sync.recordPlay(c, SystemClock.elapsedRealtime() - shownAt)
    }

    private fun onSlotEvent(slot: Slot, e: Int) {
        if (!running || slot !== front) return
        val cur = current ?: return
        if (e == Slot.ENDED) {
            next()
        } else {
            Logger.log("lỗi phát ${cur.id}")
            fail(cur)
        }
    }

    private fun onTick() {
        val cur = current ?: return
        val now = SystemClock.elapsedRealtime()
        val remaining: Long = if (cur.isVideo) {
            val d = front.player.duration
            if (d > 0) d - front.player.currentPosition else Long.MAX_VALUE
        } else {
            cur.durationMs - (now - shownAt)
        }
        if (!preloaded && remaining <= 3000) {
            preloaded = true
            target()?.let { back.load(it.second) }
        }
        if (cur.isVideo) {
            val p = front.player.currentPosition
            if (p != lastPos) {
                lastPos = p
                lastMove = now
            } else if (now - lastMove > 60_000) {
                Logger.log("watchdog: video đứng hình, phát mục kế tiếp")
                lastMove = now
                next()
            }
        }
    }
}
