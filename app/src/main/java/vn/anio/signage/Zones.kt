package vn.anio.signage

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Cỡ chữ theo khung thiết kế của web: font_size (px ở khung 540x960 hoặc 960x540) nhân theo bề rộng thật của màn hình. */
interface ScaledZone {
    fun applyScale(scale: Float)
}

/** Khung chia vùng theo phần trăm: con có tag là [Zone]; vẽ theo thứ tự thêm vào (đã sắp theo z). */
class PctFrame(ctx: Context, private val designWidth: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val z = c.tag as? Zone ?: continue
            c.measure(
                MeasureSpec.makeMeasureSpec((w * z.w / 100.0).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * z.h / 100.0).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val z = c.tag as? Zone ?: continue
            val x = (w * z.x / 100.0).toInt()
            val y = (h * z.y / 100.0).toInt()
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = if (designWidth > 0) w.toFloat() / designWidth else 1f
        for (i in 0 until childCount) (getChildAt(i) as? ScaledZone)?.applyScale(s)
    }
}

/** Gọi về bên phát: tệp trong vùng đã phát xong (proof_of_play) và lỗi tải/phát. */
interface ZoneEvents {
    fun onZonePlayed(creativeId: String, ms: Long)
    fun onZoneError(zoneId: String, creativeId: String, msg: String)
}

/** Vùng video/ảnh: danh sách tệp lặp vô hạn, ExoPlayer riêng cho vùng, chuyển cảnh fade/slide/zoom đơn giản. */
class MediaZone(
    private val ctx: Context,
    private val zone: Zone,
    private val items: List<ZMedia>,
    private val audio: Boolean,
    private val events: ZoneEvents
) : FrameLayout(ctx), ScaledZone {
    private val h = Handler(Looper.getMainLooper())
    private val iv = ImageView(ctx)
    private var pv: PlayerView? = null
    private var player: ExoPlayer? = null
    private var idx = -1
    private var started = false
    private var released = false
    private var gen = 0
    private var shownAt = 0L
    private var status = "chờ"
    private val next = Runnable { advance() }

    init {
        setBackgroundColor(Color.BLACK)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.visibility = View.GONE
        if (items.any { it.isVideo }) {
            val p = PlayerView(ctx)
            p.useController = false
            p.setShutterBackgroundColor(Color.TRANSPARENT)
            p.setKeepContentOnPlayerReset(true)
            val pl = ExoPlayer.Builder(ctx).build()
            pl.volume = if (audio) 1f else 0f
            pl.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) status = "đang phát"
                    if (state == Player.STATE_ENDED && started) advance()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val cid = items.getOrNull(idx)?.creativeId ?: ""
                    status = "lỗi phát"
                    Logger.log("vùng ${zone.id}: lỗi phát $cid: ${error.errorCodeName}")
                    events.onZoneError(zone.id, cid, "lỗi phát ${error.errorCodeName}")
                    h.removeCallbacks(next)
                    h.postDelayed(next, 3000)
                }
            })
            p.player = pl
            p.visibility = View.GONE
            pv = p
            player = pl
            addView(p, LayoutParams(-1, -1))
        }
        addView(iv, LayoutParams(-1, -1))
        if (items.isNotEmpty()) show(0)
    }

    override fun applyScale(scale: Float) {}

    /** Bắt đầu phát (đã nạp sẵn mục đầu ở chế độ tạm dừng để lên hình ngay). */
    fun start() {
        if (released) return
        started = true
        shownAt = SystemClock.elapsedRealtime()
        val m = items.getOrNull(idx) ?: return
        if (m.isVideo) {
            player?.playWhenReady = true
            player?.play()
        } else {
            scheduleImage(m)
        }
    }

    private fun advance() {
        if (released || items.isEmpty()) return
        reportCurrent()
        show((idx + 1) % items.size)
        if (started) {
            val m = items[idx]
            shownAt = SystemClock.elapsedRealtime()
            if (!m.isVideo) scheduleImage(m)
        }
    }

    private fun scheduleImage(m: ZMedia) {
        h.removeCallbacks(next)
        if (items.size > 1) h.postDelayed(next, Math.max(2000L, m.durationMs))
    }

    private fun reportCurrent() {
        val m = items.getOrNull(idx) ?: return
        if (!started || shownAt <= 0) return
        val ms = SystemClock.elapsedRealtime() - shownAt
        if (ms > 0) events.onZonePlayed(m.creativeId, ms)
        shownAt = 0
    }

    private fun show(i: Int) {
        idx = i
        val m = items[i]
        val g = ++gen
        status = "nạp"
        if (m.isVideo) {
            iv.visibility = View.GONE
            pv?.visibility = View.VISIBLE
            val pl = player ?: return
            pv?.resizeMode = if (m.fit) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            pl.repeatMode = if (items.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            pl.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(m.path))))
            pl.playWhenReady = started
            pl.prepare()
            animateIn(pv)
        } else {
            player?.let { it.stop(); it.clearMediaItems() }
            pv?.visibility = View.GONE
            iv.visibility = View.VISIBLE
            iv.scaleType = if (m.fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            Thread {
                val bmp = decode(m.path)
                h.post {
                    if (g == gen && !released) {
                        if (bmp == null) {
                            status = "lỗi ảnh"
                            Logger.log("vùng ${zone.id}: không đọc được ảnh ${m.creativeId}")
                            events.onZoneError(zone.id, m.creativeId, "không đọc được ảnh")
                            if (items.size > 1) { h.removeCallbacks(next); h.postDelayed(next, 3000) }
                        } else {
                            iv.setImageBitmap(bmp)
                            status = "đang phát"
                            animateIn(iv)
                        }
                    }
                }
            }.start()
        }
    }

    private fun animateIn(v: View?) {
        if (v == null || !started) return
        val tr = zone.transition
        v.animate().cancel()
        v.alpha = 1f; v.translationX = 0f; v.scaleX = 1f; v.scaleY = 1f
        when (tr) {
            "fade" -> { v.alpha = 0f; v.animate().alpha(1f).setDuration(350).start() }
            "slide-left" -> { v.translationX = width.toFloat(); v.animate().translationX(0f).setDuration(350).start() }
            "slide-right" -> { v.translationX = -width.toFloat(); v.animate().translationX(0f).setDuration(350).start() }
            "zoom" -> { v.alpha = 0f; v.scaleX = 0.85f; v.scaleY = 0.85f; v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).start() }
        }
    }

    private fun decode(path: String): Bitmap? = try {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        val m = maxOf(o.outWidth, o.outHeight)
        while (m / s > 2048) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        o2.inPreferredConfig = Bitmap.Config.RGB_565
        BitmapFactory.decodeFile(path, o2)
    } catch (e: Throwable) {
        null
    }

    /** Dòng gỡ lỗi: vùng, tệp đang phát, vị trí trong danh sách, trạng thái. */
    fun stateLine(): String {
        val m = items.getOrNull(idx)
        return zone.id + ":" + (m?.creativeId?.takeLast(6) ?: "-") + " " + (idx + 1) + "/" + items.size + " " + status + if (audio) " (tiếng)" else ""
    }

    fun release() {
        if (released) return
        reportCurrent()
        released = true
        gen++
        h.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) {}
        player = null
        pv?.player = null
        iv.setImageDrawable(null)
    }
}

/** Chữ chạy ngang; tốc độ theo scroll_speed như LivePreview của web. */
class MarqueeZone(ctx: Context, private val zone: Zone) : FrameLayout(ctx), ScaledZone {
    private val tv = TextView(ctx)
    private var anim: ObjectAnimator? = null

    init {
        setBackgroundColor(LayoutParser.argb(zone.bgColor, Color.parseColor("#DC2626")))
        clipChildren = true
        tv.text = zone.text.ifEmpty { " " }
        tv.setTextColor(LayoutParser.argb(zone.textColor, Color.WHITE))
        tv.setSingleLine(true)
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER_VERTICAL
        addView(tv, LayoutParams(-2, -1))
    }

    override fun applyScale(scale: Float) {
        val px = (if (zone.fontSize > 0) zone.fontSize else 18) * scale
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px.coerceAtLeast(10f))
        post { restart() }
    }

    private fun restart() {
        anim?.cancel()
        val cw = width.toFloat()
        if (cw <= 0f) return
        tv.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        val tw = tv.measuredWidth.toFloat()
        tv.layout(0, 0, tw.toInt(), height)
        val a = ObjectAnimator.ofFloat(tv, View.TRANSLATION_X, cw, -tw)
        a.duration = LayoutParser.marqueePassSec(zone.scrollSpeed) * 1000L * ((tw + cw) / cw).coerceIn(1f, 6f).toLong().coerceAtLeast(1L)
        a.interpolator = LinearInterpolator()
        a.repeatCount = ObjectAnimator.INFINITE
        a.start()
        anim = a
    }

    fun stop() { anim?.cancel(); anim = null }
}

/** Văn bản tĩnh. */
class TextZone(ctx: Context, private val zone: Zone) : FrameLayout(ctx), ScaledZone {
    private val tv = TextView(ctx)

    init {
        setBackgroundColor(LayoutParser.argb(zone.bgColor, Color.parseColor("#111827")))
        tv.text = zone.text
        tv.setTextColor(LayoutParser.argb(zone.textColor, Color.WHITE))
        tv.gravity = Gravity.CENTER
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(12, 8, 12, 8)
        addView(tv, LayoutParams(-1, -1))
    }

    override fun applyScale(scale: Float) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((if (zone.fontSize > 0) zone.fontSize else 24) * scale).coerceAtLeast(10f))
    }
}

/** Đồng hồ số giờ Việt Nam, lấy giờ theo độ lệch đồng hồ đã đồng bộ với AMS. */
class ClockZone(ctx: Context, private val zone: Zone, private val clockMs: () -> Long) : FrameLayout(ctx), ScaledZone {
    private val tv = TextView(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val tz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val tf = SimpleDateFormat("HH:mm:ss", Locale("vi", "VN")).apply { timeZone = tz }
    private val df = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi", "VN")).apply { timeZone = tz }
    private var scale = 1f
    private val tick = object : Runnable {
        override fun run() {
            val d = Date(clockMs())
            tv.text = tf.format(d) + "\n" + df.format(d)
            h.postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(LayoutParser.argb(zone.bgColor, Color.parseColor("#111827")))
        tv.setTextColor(LayoutParser.argb(zone.textColor, Color.WHITE))
        tv.gravity = Gravity.CENTER
        tv.typeface = Typeface.DEFAULT_BOLD
        addView(tv, LayoutParams(-1, -1))
        tick.run()
    }

    override fun applyScale(scale: Float) {
        this.scale = scale
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((if (zone.fontSize > 0) zone.fontSize else 28) * scale).coerceAtLeast(10f))
    }

    fun stop() { h.removeCallbacksAndMessages(null) }
}

/** Bộ nhớ đệm thời tiết 10 phút (RAM + tệp) để mỗi lần dựng lại bố cục không gọi mạng lại và offline vẫn hiện lần trước. */
object WeatherCache {
    private class E(val at: Long, val json: String)

    private val mem = ConcurrentHashMap<String, E>()
    private val io = Executors.newSingleThreadExecutor()
    const val TTL_MS = 600_000L

    private fun key(lat: Double, lon: Double) = "%.3f_%.3f".format(Locale.US, lat, lon)

    fun peek(ctx: Context, lat: Double, lon: Double): Pair<Weather, Long>? {
        val k = key(lat, lon)
        var e = mem[k]
        if (e == null) {
            try {
                val f = File(ctx.cacheDir, "weather_$k.json")
                if (f.exists()) e = E(f.lastModified(), f.readText())
            } catch (_: Exception) {
            }
        }
        val w = e?.let { WeatherLogic.parse(it.json) } ?: return null
        return Pair(w, e.at)
    }

    /** Lấy dữ liệu mới nếu cache cũ hơn 10 phút; gọi [done] trên luồng chính. */
    fun refresh(ctx: Context, lat: Double, lon: Double, done: (Weather?) -> Unit) {
        val k = key(lat, lon)
        val c = mem[k]
        val main = Handler(Looper.getMainLooper())
        if (c != null && System.currentTimeMillis() - c.at < TTL_MS) {
            val w = WeatherLogic.parse(c.json)
            main.post { done(w) }
            return
        }
        io.execute {
            var w: Weather? = null
            try {
                val text = PlaylistIo.getText(WeatherLogic.url(lat, lon), null)
                val parsed = text?.let { WeatherLogic.parse(it) }
                if (text != null && parsed != null) {
                    mem[k] = E(System.currentTimeMillis(), text)
                    try { File(ctx.cacheDir, "weather_$k.json").writeText(text) } catch (_: Exception) {}
                    w = parsed
                }
            } catch (e: Exception) {
                Logger.log("thời tiết lỗi: ${e.message}")
            }
            main.post { done(w) }
        }
    }
}

/** Thời tiết Open-Meteo theo cấu hình của web (gps/fixed = toạ độ do web gửi). */
class WeatherZone(private val ctx: Context, private val zone: Zone) : FrameLayout(ctx), ScaledZone {
    private val tv = TextView(ctx)
    private val h = Handler(Looper.getMainLooper())
    private val coords = WeatherLogic.coords(zone)
    private var released = false
    private val refresh = object : Runnable {
        override fun run() {
            if (released) return
            WeatherCache.refresh(ctx, coords.first, coords.second) { w -> if (!released && w != null) render(w, false) }
            h.postDelayed(this, WeatherCache.TTL_MS)
        }
    }

    init {
        setBackgroundColor(LayoutParser.argb(zone.bgColor, Color.parseColor("#1D4ED8")))
        tv.setTextColor(LayoutParser.argb(zone.textColor, Color.WHITE))
        tv.gravity = Gravity.CENTER
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.text = (if (zone.label.isEmpty()) "Thời tiết" else zone.label) + "\nĐang lấy dữ liệu…"
        addView(tv, LayoutParams(-1, -1))
        WeatherCache.peek(ctx, coords.first, coords.second)?.let { render(it.first, true) }
        refresh.run()
    }

    private fun render(w: Weather, stale: Boolean) {
        val name = if (zone.label.isEmpty()) "Thời tiết" else zone.label
        tv.text = name + "\n" + Math.round(w.tempC) + "°C · " + WeatherLogic.label(w.code) +
            "\nĐộ ẩm " + w.humidity + "% · Gió " + Math.round(w.windKmh) + " km/h"
    }

    override fun applyScale(scale: Float) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((if (zone.fontSize > 0) zone.fontSize else 20) * scale).coerceAtLeast(10f))
    }

    fun stop() { released = true; h.removeCallbacksAndMessages(null) }
}

/** Mã QR: ảnh (VietQR/ảnh tải lên) hoặc tự vẽ từ chuỗi; ảnh tải từ mạng được lưu cache để offline vẫn hiện. */
class QrZone(private val ctx: Context, private val zone: Zone) : FrameLayout(ctx), ScaledZone {
    private val iv = ImageView(ctx)
    private val msg = TextView(ctx)
    private var released = false
    var status = "chờ"
        private set

    init {
        setBackgroundColor(Color.WHITE)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(6, 6, 6, 6)
        msg.gravity = Gravity.CENTER
        msg.setTextColor(Color.DKGRAY)
        msg.visibility = View.GONE
        addView(iv, LayoutParams(-1, -1))
        addView(msg, LayoutParams(-1, -1))
        load()
    }

    override fun applyScale(scale: Float) {
        msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (12 * scale).coerceAtLeast(9f))
    }

    private fun fail(t: String) {
        status = "lỗi"
        msg.text = t
        msg.visibility = View.VISIBLE
    }

    private fun load() {
        val src = QrLogic.source(zone)
        val main = Handler(Looper.getMainLooper())
        when (src) {
            is QrSource.None -> fail("QR trống")
            is QrSource.Text -> Thread {
                val m = QrLogic.matrix(src.content)
                val bmp = m?.let { toBitmap(it) }
                main.post { if (!released) { if (bmp != null) { iv.setImageBitmap(bmp); status = "ok" } else fail("QR lỗi") } }
            }.start()
            is QrSource.Bytes -> Thread {
                val bmp = try { BitmapFactory.decodeByteArray(src.data, 0, src.data.size) } catch (_: Throwable) { null }
                main.post { if (!released) { if (bmp != null) { iv.setImageBitmap(bmp); status = "ok" } else fail("QR lỗi") } }
            }.start()
            is QrSource.ImageUrl -> Thread {
                val f = File(ctx.cacheDir, "qr_" + sha1(src.url) + ".img")
                var ok = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L
                if (!ok) {
                    try {
                        val r = PlaylistIo.http.newCall(okhttp3.Request.Builder().url(src.url).build()).execute()
                        r.use { resp ->
                            if (resp.isSuccessful) {
                                val b = resp.body?.bytes()
                                if (b != null && b.isNotEmpty()) { f.writeBytes(b); ok = true }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log("QR tải lỗi: ${e.message}")
                    }
                }
                val bmp = if (f.exists() && f.length() > 0) try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } else null
                main.post { if (!released) { if (bmp != null) { iv.setImageBitmap(bmp); status = if (ok) "ok" else "cache" } else fail("Không tải được QR") } }
            }.start()
        }
    }

    private fun toBitmap(m: Array<BooleanArray>): Bitmap {
        val n = m.size
        val scale = Math.max(1, 512 / n)
        val size = n * scale
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[y / scale][x / scale]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun stop() { released = true }
}

/** Dựng và điều khiển toàn bộ vùng của một chương trình đa vùng. */
class ZoneRenderer(
    private val ctx: Context,
    private val host: FrameLayout,
    private val item: Item,
    private val clockMs: () -> Long,
    private val events: ZoneEvents
) {
    private val layout: Layout = item.layout!!
    private val media = ArrayList<MediaZone>()
    private val stoppers = ArrayList<() -> Unit>()
    private val frame = PctFrame(ctx, layout.designWidth)
    private val audioZone = LayoutParser.primaryAudioZone(layout, item.zoneMedia)

    init {
        for (z in layout.zones) {
            if (z.hidden) continue
            val v: View = when (z.type) {
                "video", "image" -> {
                    val list = item.zoneMedia[z.id].orEmpty()
                    val mz = MediaZone(ctx, z, list, z.id == audioZone, events)
                    media.add(mz)
                    mz
                }
                "marquee" -> MarqueeZone(ctx, z).also { stoppers.add { it.stop() } }
                "text" -> TextZone(ctx, z)
                "clock" -> ClockZone(ctx, z, clockMs).also { stoppers.add { it.stop() } }
                "weather" -> WeatherZone(ctx, z).also { stoppers.add { it.stop() } }
                "qr" -> QrZone(ctx, z).also { stoppers.add { it.stop() } }
                else -> continue
            }
            v.tag = z
            frame.addView(v)
        }
        host.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    fun start() { media.forEach { it.start() } }

    fun stateLine(): String = media.joinToString(" | ") { it.stateLine() }

    fun release() {
        media.forEach { it.release() }
        stoppers.forEach { try { it() } catch (_: Exception) {} }
        host.removeView(frame)
    }
}
