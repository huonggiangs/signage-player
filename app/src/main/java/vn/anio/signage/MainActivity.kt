package vn.anio.signage

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.ceil

class MainActivity : Activity(), Playback.Ui {

    private enum class V { PLAY, PIN, MENU, SOURCE, LAN }

    private val PRIMARY = Color.parseColor("#5F60B9")
    private val DARK = Color.parseColor("#1C1F34")
    private val GREY = Color.parseColor("#6C757D")
    private val BORDER = Color.parseColor("#EBEBEB")
    private val RED = Color.parseColor("#EA2F2F")
    private val SOFT = Color.parseColor("#F6F7F9")
    private val YELLOW = Color.parseColor("#FFBD00")

    private lateinit var prefs: Prefs
    private lateinit var sync: Sync
    private lateinit var playback: Playback
    private lateinit var rot: RotateLayout
    private lateinit var stage: FrameLayout
    private lateinit var audio: AudioManager
    private val h = Handler(Looper.getMainLooper())

    private var view = V.PLAY
    private var splashOn = true
    private var pinMode = "enter"
    private var pin = ""
    private var pinErr = ""
    private var holdStart = 0L
    private var holdExit = false
    private var holdActive = false
    private lateinit var splashDevice: TextView
    private lateinit var splashReason: TextView
    private lateinit var pinForgot: TextView

    // overlay views
    private lateinit var splash: FrameLayout
    private lateinit var splashHotline: TextView
    private lateinit var headerTv: TextView
    private lateinit var footerTv: TextView
    private lateinit var holdChip: TextView
    private lateinit var menu: FrameLayout
    private lateinit var pinOv: FrameLayout
    private lateinit var srcOv: FrameLayout
    private lateinit var lanOv: FrameLayout
    private lateinit var volLabel: TextView
    private lateinit var volFill: View
    private lateinit var volRest: View
    private lateinit var rotateBtn: TextView
    private lateinit var srcLine: TextView
    private lateinit var pinTitle: TextView
    private lateinit var pinHint: TextView
    private lateinit var pinCancel: TextView
    private lateinit var dots: List<View>
    private lateinit var srcRows: List<LinearLayout>
    private lateinit var srcBoxes: List<View>
    private lateinit var srcDescs: List<TextView>
    private lateinit var lanInput: EditText
    private lateinit var firstMenuBtn: View
    private lateinit var firstPinKey: View
    private lateinit var firstSrcRow: View

    private var curHeader = ""
    private var curFooter = ""
    private var curSrc: Src? = null
    private var playingId = ""

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (view == V.PLAY && !splashOn) openPin("enter")
                return true
            }
        })
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_MEDIA_MOUNTED) {
                Logger.log("cắm USB")
                h.postDelayed({ sync.syncNow() }, 3000)
            } else {
                Logger.log("rút USB")
                sync.usbRemoved()
            }
        }
    }

    // ------------------------------------------------------------------ vòng đời

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientation()

        sync = Sync(this, prefs, { playback.onSyncChanged() }, { playingId }, { if (playingId.isEmpty()) 0 else 1 })
        setHomeAlias(true)
        buildUi()
        playback = Playback(this, mediaBox, prefs, sync, this)
        applyVolume()

        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 10)

        try {
            val si = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
        } catch (e: Exception) {
            Logger.log("service: ${e.message}")
        }

        val f = IntentFilter()
        f.addAction(Intent.ACTION_MEDIA_MOUNTED)
        f.addAction(Intent.ACTION_MEDIA_REMOVED)
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        f.addAction(Intent.ACTION_MEDIA_EJECT)
        f.addDataScheme("file")
        ContextCompat.registerReceiver(this, usbReceiver, f, ContextCompat.RECEIVER_EXPORTED)

        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { sync.healthNow(); sync.syncNow() }
                override fun onLost(network: Network) { sync.healthNow() }
            })
        } catch (e: Exception) {
            Logger.log("network callback: ${e.message}")
        }

        Logger.log("khởi động app " + APP_VERSION)
        h.postDelayed(debugTick, 3000)
        sync.uiCommand = { type, args -> handleUiCommand(type, args) }
        sync.start()
        showSplash(true)
        h.postDelayed({
            splashOn = false
            showSplash(false)
            playback.start()
            askBatteryOptimization()
        }, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        sync.stop()
        playback.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun askBatteryOptimization() {
        if (prefs.batAsked) return
        prefs.batAsked = true
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        sync.syncNow()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.usbTree = u.toString()
                Logger.log("đã chọn thư mục USB")
                sync.syncNow()
            } catch (e: Exception) {
                Logger.log("không lưu được quyền thư mục: ${e.message}")
            }
        }
    }

    private fun applyOrientation() {
        requestedOrientation = if (prefs.landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun applyVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * prefs.volume / 100f), 0)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ Playback.Ui

    override fun onShow(item: Item, src: Src) {
        playingId = item.id
        curSrc = src
        curHeader = item.header
        curFooter = item.footer
        updateBands()
        if (!splashOn) splash.visibility = View.GONE
    }

    override fun onIdle() {
        curSrc = null
        playingId = ""
        updateBands()
        splashHotline.text = "Hotline/Zalo: " + prefs.hotline
        updateDeviceLine()
        splash.visibility = View.VISIBLE
    }

    private fun updateDeviceLine() {
        val id = prefs.deviceId
        splashDevice.text = if (id.isEmpty()) "Đang kết nối ams.anio.vn…" else "Mã thiết bị: " + id
        updateReason()
    }

    /** Màn chờ luôn nói rõ VÌ SAO chưa có nội dung (tiếng Việt) thay vì chỉ để trống. */
    private fun updateReason() {
        if (!::splashReason.isInitialized || !::sync.isInitialized) return
        splashReason.text = if (curSrc == null) IdleReason.of(Diag.snapshot(prefs, sync.items[Src.AMS]?.size ?: 0, sync.amsDown)) else ""
    }

    private fun updateBands() {
        val on = curSrc == Src.AMS && !splashOn
        headerTv.text = curHeader
        footerTv.text = curFooter
        headerTv.visibility = if (on && curHeader.isNotEmpty()) View.VISIBLE else View.GONE
        footerTv.visibility = if (on && curFooter.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSplash(on: Boolean) {
        splashHotline.text = "Hotline/Zalo: " + prefs.hotline
        updateDeviceLine()
        splash.visibility = if (on) View.VISIBLE else View.GONE
        updateBands()
    }

    // ------------------------------------------------------------------ nhập liệu ẩn

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (view != V.PLAY) resetTimeout()
        if (view == V.PLAY && !splashOn) {
            val right = (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            if (!right && !holdExit) gestures.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> startHold(right)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endHold()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (view != V.PLAY) resetTimeout()
        // Back (gồm chuột phải) trong chế độ phát: không thoát app, giữ 3 giây để thoát
        if (view == V.PLAY && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (!splashOn) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) startHold(true)
                else if (e.action == KeyEvent.ACTION_UP) endHold()
            }
            return true
        }
        if (view == V.PLAY && !splashOn && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
                e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            openPin("enter")
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (view) {
            V.PLAY -> {}
            V.PIN -> if (!(pinMode == "new" && prefs.firstRun)) setView(if (pinMode == "new") V.MENU else V.PLAY)
            V.SOURCE, V.LAN -> setView(V.MENU)
            V.MENU -> setView(V.PLAY)
        }
    }

    private val holdRun = object : Runnable {
        override fun run() {
            val p = (System.currentTimeMillis() - holdStart) / 3000f
            if (p >= 1f) {
                holdChip.visibility = View.GONE
                holdActive = false
                openPin(if (holdExit) "exit" else "enter")
            } else {
                val what = if (holdExit) "thoát app" else "mở cài đặt"
                holdChip.text = "Giữ để $what… " + ceil(3 - p * 3).toInt() + "s"
                holdChip.visibility = View.VISIBLE
                h.postDelayed(this, 100)
            }
        }
    }

    private fun startHold(exit: Boolean) {
        if (holdActive) return
        holdActive = true
        holdExit = exit
        holdStart = System.currentTimeMillis()
        h.removeCallbacks(holdRun)
        h.postDelayed(holdRun, 300)
    }

    private fun endHold() {
        holdActive = false
        holdExit = false
        h.removeCallbacks(holdRun)
        holdChip.visibility = View.GONE
    }

    private val closeRun = Runnable { setView(V.PLAY) }

    private fun resetTimeout() {
        h.removeCallbacks(closeRun)
        h.postDelayed(closeRun, 30_000)
    }

    // ------------------------------------------------------------------ trạng thái màn hình

    private fun setView(v: V) {
        endHold()
        view = v
        menu.visibility = if (v == V.MENU) View.VISIBLE else View.GONE
        pinOv.visibility = if (v == V.PIN) View.VISIBLE else View.GONE
        srcOv.visibility = if (v == V.SOURCE) View.VISIBLE else View.GONE
        lanOv.visibility = if (v == V.LAN) View.VISIBLE else View.GONE
        h.removeCallbacks(closeRun)
        if (v != V.PLAY) resetTimeout()
        when (v) {
            V.MENU -> { refreshMenu(); firstMenuBtn.requestFocus() }
            V.PIN -> { updatePin(); firstPinKey.requestFocus() }
            V.SOURCE -> { refreshSources(); firstSrcRow.requestFocus() }
            V.LAN -> { lanInput.setText(prefs.lanUrl); lanInput.requestFocus() }
            V.PLAY -> {}
        }
    }

    private fun openPin(mode: String) {
        if (splashOn || view != V.PLAY) return
        pinMode = mode
        pin = ""
        pinErr = ""
        setView(V.PIN)
    }

    private fun showMenu() {
        pin = ""
        setView(V.MENU)
    }

    // ------------------------------------------------------------------ mật khẩu

    private fun lockRemaining(): Int = Math.max(0L, (prefs.lockUntil - System.currentTimeMillis() + 999) / 1000).toInt()

    private val lockTick = object : Runnable {
        override fun run() {
            if (view != V.PIN) return
            updatePin()
            if (lockRemaining() > 0) h.postDelayed(this, 1000)
        }
    }

    private fun setHomeAlias(on: Boolean) {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(packageName, "vn.anio.signage.HomeAlias"),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Logger.log("home alias: ${e.message}")
        }
    }

    /** Thoát chế độ kiosk: tắt alias HOME để hệ thống trả về launcher gốc, rồi đóng app. */
    private fun exitApp() {
        Logger.log("thoát app bằng mật khẩu")
        setHomeAlias(false)
        try { stopService(Intent(this, PlaybackService::class.java)) } catch (_: Exception) {}
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
        }
        finishAffinity()
    }

    private fun pinKey(k: String) {
        if (lockRemaining() > 0) { updatePin(); return }
        if (k == "⌫") {
            pin = pin.dropLast(1)
            pinErr = ""
            updatePin()
            return
        }
        if (pin.length >= 4) return
        pin += k
        updatePin()
        if (pin.length == 4) h.postDelayed({ pinDone() }, 150)
    }

    private fun pinDone() {
        if (view != V.PIN || pin.length < 4) return
        if (pinMode == "enter" || pinMode == "exit") {
            if (prefs.checkPin(pin)) {
                prefs.failCount = 0
                if (pinMode == "exit") {
                    exitApp()
                } else if (prefs.firstRun) {
                    pinMode = "new"
                    pin = ""
                    pinErr = ""
                    updatePin()
                } else {
                    showMenu()
                }
            } else {
                prefs.failCount = prefs.failCount + 1
                pin = ""
                pinErr = "Sai mật khẩu"
                if (prefs.failCount >= 5) {
                    prefs.failCount = 0
                    prefs.lockUntil = System.currentTimeMillis() + 60_000
                    Logger.log("nhập sai mật khẩu 5 lần, khóa 60 giây")
                    h.post(lockTick)
                }
                updatePin()
            }
        } else {
            if (prefs.firstRun && pin == "1234") {
                pin = ""
                pinErr = "Chọn mật khẩu khác 1234"
                updatePin()
                return
            }
            prefs.setPin(pin)
            prefs.firstRun = false
            showMenu()
        }
    }

    private fun updatePin() {
        val lock = lockRemaining()
        pinTitle.text = when (pinMode) {
            "enter" -> "Nhập mật khẩu để vào cài đặt"
            "exit" -> "Nhập mật khẩu để thoát app"
            else -> "Nhập mật khẩu mới"
        }
        pinForgot.visibility = if (pinMode == "new") View.GONE else View.VISIBLE
        pinForgot.text = "Quên mật khẩu: gọi " + prefs.hotline + ", vận hành viên cấp lại trên ams.anio.vn"
        val hint = when {
            lock > 0 -> "Khóa $lock giây, thử lại sau"
            pinErr.isNotEmpty() -> pinErr
            pinMode == "new" -> if (prefs.firstRun) "Bắt buộc đổi mật khẩu lần đầu (4 chữ số)" else "Gồm 4 chữ số"
            else -> " "
        }
        pinHint.text = hint
        pinHint.setTextColor(if (lock > 0 || pinErr.isNotEmpty()) RED else GREY)
        pinCancel.visibility = if (pinMode == "new" && prefs.firstRun) View.GONE else View.VISIBLE
        for (i in 0 until 4) dots[i].background = circle(if (i < pin.length) PRIMARY else BORDER)
    }

    // ------------------------------------------------------------------ menu cài đặt

    private fun statusOf(s: Src): String {
        if (playback.current != null && playback.active == s) return "Đang phát"
        if (s == Src.AMS && sync.amsDown) return "Mất mạng, bỏ qua"
        return "Dự phòng"
    }

    private fun refreshMenu() {
        volLabel.text = "Âm lượng " + prefs.volume + "%"
        volFill.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, prefs.volume.toFloat())
        volRest.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - prefs.volume).toFloat())
        debugBtn.text = if (prefs.debug) "Chế độ gỡ lỗi: BẬT (chạm để tắt)" else "Chế độ gỡ lỗi: tắt"
        rotateBtn.text = if (prefs.landscape) "Xoay về màn hình dọc" else "Xoay màn hình ngang"
        val act = playback.active
        val name = if (playback.current == null || act == null) "Chưa có nguồn" else act.label
        val off = if (act != null && act != Src.AMS && prefs.isSel(Src.AMS) && sync.amsDown) " (mất mạng)" else ""
        val dev = if (prefs.deviceId.isEmpty()) "chưa đăng ký" else prefs.deviceId + " (quyền " + prefs.trust + ")"
        srcLine.text = "Nguồn hiện tại: $name$off\nThiết bị ams: $dev"
    }

    private fun refreshSources() {
        var rank = 0
        for ((i, s) in Src.values().withIndex()) {
            val sel = prefs.isSel(s)
            srcBoxes[i].background = box(sel)
            srcRows[i].alpha = 1f
            srcDescs[i].text = if (sel) "Ưu tiên ${++rank} · ${statusOf(s)}" else s.desc
        }
    }

    private fun changeVolume(d: Int) {
        prefs.volume = (prefs.volume + d).coerceIn(0, 100)
        applyVolume()
        refreshMenu()
    }

    // ------------------------------------------------------------------ lệnh từ AMS

    private fun handleUiCommand(type: String, args: org.json.JSONObject): Boolean {
        when (type) {
            "message" -> {
                val text = args.optString("text", "").trim()
                if (text.isEmpty() || text.length > 200) return false
                val d = (if (args.has("duration_s")) args.optInt("duration_s", 15) else args.optInt("seconds", 15)).coerceIn(3, 120)
                showNotice(text, d)
                return true
            }
            "set_volume" -> {
                if (!args.has("percent")) return false
                prefs.volume = args.optInt("percent", 60).coerceIn(0, 100)
                applyVolume()
                if (view == V.MENU) refreshMenu()
                return true
            }
            "set_orientation" -> {
                val o = args.optString("orientation", "")
                if (o != "landscape" && o != "portrait") return false
                prefs.landscape = o == "landscape"
                applyOrientation()
                rot.wantLandscape = prefs.landscape
                if (view == V.MENU) refreshMenu()
                return true
            }
        }
        return false
    }

    private val noticeHide = Runnable { noticeTv.visibility = View.GONE; noticeTv.isSelected = false }

    /** Dải thông báo phủ trên nội dung; thông báo mới thay thông báo cũ, tự tắt sau [seconds] giây. */
    private fun showNotice(text: String, seconds: Int) {
        h.removeCallbacks(noticeHide)
        noticeTv.isSelected = false
        noticeTv.text = text
        if (text.length > 40) {
            noticeTv.setSingleLine(true)
            noticeTv.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            noticeTv.marqueeRepeatLimit = -1
            noticeTv.gravity = Gravity.CENTER_VERTICAL
        } else {
            noticeTv.setSingleLine(false)
            noticeTv.maxLines = 3
            noticeTv.ellipsize = null
            noticeTv.gravity = Gravity.CENTER
        }
        noticeTv.visibility = View.VISIBLE
        noticeTv.bringToFront()
        noticeTv.isSelected = true
        h.postDelayed(noticeHide, seconds * 1000L)
        Logger.log("hiện thông báo ${seconds}s")
    }

    private fun toggleRotate() {
        prefs.landscape = !prefs.landscape
        applyOrientation()
        rot.wantLandscape = prefs.landscape
        refreshMenu()
    }

    // ------------------------------------------------------------------ dựng giao diện

    private lateinit var mediaBox: FrameLayout
    private lateinit var noticeTv: TextView
    private lateinit var debugBtn: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circle(c: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(c)
        return g
    }

    private fun box(on: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(4).toFloat()
        g.setColor(if (on) PRIMARY else Color.WHITE)
        g.setStroke(dp(2), if (on) PRIMARY else BORDER)
        return g
    }

    private fun shape(fill: Int, radius: Int, stroke: Int, strokeW: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        if (strokeW > 0) g.setStroke(dp(strokeW), stroke)
        return g
    }

    /** Nền có viền vàng rõ khi focus bằng D-pad. */
    private fun focusBg(fill: Int, radius: Int, stroke: Int): StateListDrawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), shape(fill, radius, YELLOW, 3))
        sl.addState(intArrayOf(), shape(fill, radius, stroke, 1))
        return sl
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        t.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        return t
    }

    private fun btn(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        val t = tv(text, 14f, if (filled) Color.WHITE else PRIMARY, true)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(48)
        t.setPadding(dp(16), 0, dp(16), 0)
        t.isFocusable = true
        t.isClickable = true
        t.background = focusBg(if (filled) PRIMARY else Color.WHITE, 24, PRIMARY)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun roundBtn(text: String, onClick: () -> Unit): TextView {
        val t = tv(text, 22f, PRIMARY)
        t.gravity = Gravity.CENTER
        t.isFocusable = true
        t.isClickable = true
        t.background = focusBg(Color.WHITE, 22, BORDER)
        t.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun overlay(bg: Int): FrameLayout {
        val f = FrameLayout(this)
        f.setBackgroundColor(bg)
        f.isClickable = true
        f.visibility = View.GONE
        stage.addView(f, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun card(parent: FrameLayout, widthDp: Int): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = shape(Color.WHITE, 20, Color.WHITE, 0)
        c.setPadding(dp(20), dp(20), dp(20), dp(20))
        val lp = FrameLayout.LayoutParams(dp(widthDp), -2)
        lp.gravity = Gravity.CENTER
        parent.addView(c, lp)
        return c
    }

    private fun buildUi() {
        rot = RotateLayout(this)
        rot.setBackgroundColor(Color.BLACK)
        rot.wantLandscape = prefs.landscape
        stage = FrameLayout(this)
        rot.addView(stage, FrameLayout.LayoutParams(-1, -1))
        setContentView(rot)

        mediaBox = FrameLayout(this)
        stage.addView(mediaBox, FrameLayout.LayoutParams(-1, -1))

        // dải header / footer
        headerTv = tv("", 15f, Color.WHITE, true)
        headerTv.gravity = Gravity.CENTER
        headerTv.setBackgroundColor(Color.argb(199, 28, 31, 52))
        headerTv.setPadding(dp(16), dp(14), dp(16), dp(14))
        headerTv.visibility = View.GONE
        stage.addView(headerTv, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        footerTv = tv("", 13f, Color.WHITE)
        footerTv.gravity = Gravity.CENTER
        footerTv.setBackgroundColor(Color.argb(199, 28, 31, 52))
        footerTv.setPadding(dp(16), dp(12), dp(16), dp(12))
        footerTv.visibility = View.GONE
        stage.addView(footerTv, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // màn hình logo / chờ
        splash = FrameLayout(this)
        splash.setBackgroundColor(Color.WHITE)
        splash.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.nindo_logo)
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logo.setPadding(dp(12), 0, dp(12), 0)
        col.addView(logo, LinearLayout.LayoutParams(-1, -2))
        val t1 = tv("Hệ thống quảng cáo đa phương tiện", 16f, DARK, true)
        t1.gravity = Gravity.CENTER
        t1.setPadding(0, dp(8), 0, 0)
        col.addView(t1, LinearLayout.LayoutParams(-1, -2))
        splashHotline = tv("", 14f, GREY)
        splashHotline.gravity = Gravity.CENTER
        splashHotline.setPadding(0, dp(6), 0, 0)
        col.addView(splashHotline, LinearLayout.LayoutParams(-1, -2))
        splashDevice = tv("", 12f, GREY)
        splashDevice.gravity = Gravity.CENTER
        splashDevice.setPadding(0, dp(14), 0, 0)
        col.addView(splashDevice, LinearLayout.LayoutParams(-1, -2))
        splashReason = tv("", 13f, Color.rgb(176, 58, 46))
        splashReason.gravity = Gravity.CENTER
        splashReason.setPadding(dp(24), dp(10), dp(24), 0)
        col.addView(splashReason, LinearLayout.LayoutParams(-1, -2))
        splash.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        stage.addView(splash, FrameLayout.LayoutParams(-1, -1))

        // chip đếm ngược giữ 3 giây
        holdChip = tv("", 12f, Color.WHITE)
        holdChip.background = shape(Color.argb(217, 28, 31, 52), 20, 0, 0)
        holdChip.setPadding(dp(12), dp(6), dp(12), dp(6))
        holdChip.visibility = View.GONE
        val hl = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        hl.setMargins(0, dp(12), dp(12), 0)
        stage.addView(holdChip, hl)

        buildMenu()
        buildPin()
        buildSources()
        buildLan()

        // nhãn gỡ lỗi (chế độ gỡ lỗi trong cài đặt)
        debugTv = tv("", 10f, Color.WHITE)
        debugTv.setBackgroundColor(Color.argb(170, 0, 0, 0))
        debugTv.setPadding(dp(6), dp(3), dp(6), dp(3))
        debugTv.visibility = View.GONE
        stage.addView(debugTv, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        // dải thông báo khẩn từ AMS (lệnh message): thêm CUỐI CÙNG để phủ trên mọi lớp, kể cả màn logo/chờ và cài đặt
        noticeTv = tv("", 30f, Color.WHITE, true)
        noticeTv.setBackgroundColor(Color.argb(235, 234, 47, 47))
        noticeTv.setPadding(dp(24), dp(20), dp(24), dp(20))
        noticeTv.visibility = View.GONE
        stage.addView(noticeTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
    }

    private lateinit var debugTv: TextView
    private val debugTick = object : Runnable {
        override fun run() {
            try { updateDebug() } catch (_: Exception) {}
            h.postDelayed(this, 2000)
        }
    }

    /** Nhãn nhỏ: nguồn, slot/vùng/tệp đang phát, phiên bản manifest, thiết bị đích, bộ nhớ cache. */
    private fun updateDebug() {
        updateReason()
        if (!prefs.debug) { debugTv.visibility = View.GONE; return }
        val c = playback.current
        val cache = java.io.File(cacheDir, "ams").listFiles()?.size ?: 0
        val sb = StringBuilder()
        sb.append("v").append(APP_VERSION).append(" · khu vực ").append(prefs.region.ifEmpty { "-" }).append(" · cache ").append(cache).append(" tệp · giờ máy ")
            .append(Diag.time(System.currentTimeMillis())).append('\n')
        sb.append(Diag.table(prefs, sync.etag, if (curSrc != null) curSrc!!.label else "", sync.items[Src.AMS]?.size ?: 0, sync.amsDown))
        sb.append('\n').append(playback.debugLine())
        if (c != null && c.manifestEtag.isNotEmpty() && c.manifestEtag != sync.etag) sb.append("\n(mục thuộc manifest ").append(c.manifestEtag).append(')')
        debugTv.text = sb.toString()
        debugTv.visibility = View.VISIBLE
        debugTv.bringToFront()
        if (noticeTv.visibility == View.VISIBLE) noticeTv.bringToFront()
    }

    private fun buildMenu() {
        menu = overlay(Color.argb(140, 28, 31, 52))
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        val r = dp(20).toFloat()
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        sheet.background = bg
        sheet.setPadding(dp(16), dp(16), dp(16), dp(16))
        menu.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val title = tv("Cài đặt", 16f, DARK, true)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val hide = tv("Ẩn cài đặt", 12f, PRIMARY, true)
        hide.isFocusable = true
        hide.isClickable = true
        hide.minHeight = dp(44)
        hide.gravity = Gravity.CENTER
        hide.setPadding(dp(12), 0, dp(12), 0)
        hide.background = focusBg(Color.TRANSPARENT, 22, Color.TRANSPARENT)
        hide.setOnClickListener { setView(V.PLAY) }
        head.addView(hide)
        sheet.addView(head, LinearLayout.LayoutParams(-1, -2))

        val volRow = LinearLayout(this)
        volRow.gravity = Gravity.CENTER_VERTICAL
        volRow.setPadding(0, dp(4), 0, dp(4))
        val down = roundBtn("−") { changeVolume(-10) }
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        volLabel = tv("", 12f, GREY)
        mid.addView(volLabel)
        val bar = LinearLayout(this)
        bar.background = shape(BORDER, 3, BORDER, 0)
        volFill = View(this)
        volFill.background = shape(PRIMARY, 3, PRIMARY, 0)
        volRest = View(this)
        bar.addView(volFill, LinearLayout.LayoutParams(0, -1, 60f))
        bar.addView(volRest, LinearLayout.LayoutParams(0, -1, 40f))
        val blp = LinearLayout.LayoutParams(-1, dp(6))
        blp.topMargin = dp(6)
        mid.addView(bar, blp)
        val up = roundBtn("+") { changeVolume(10) }
        val ml = LinearLayout.LayoutParams(0, -2, 1f)
        ml.setMargins(dp(10), 0, dp(10), 0)
        volRow.addView(down)
        volRow.addView(mid, ml)
        volRow.addView(up)
        val vlp = LinearLayout.LayoutParams(-1, -2)
        vlp.topMargin = dp(12)
        sheet.addView(volRow, vlp)
        firstMenuBtn = down

        rotateBtn = btn("", true) { toggleRotate() }
        val rlp = LinearLayout.LayoutParams(-1, dp(48))
        rlp.topMargin = dp(12)
        sheet.addView(rotateBtn, rlp)

        val row = LinearLayout(this)
        val b1 = btn("Nguồn dữ liệu", false) { setView(V.SOURCE) }
        val b2 = btn("Đổi mật khẩu", false) {
            pinMode = "new"
            pin = ""
            pinErr = ""
            setView(V.PIN)
        }
        val l1 = LinearLayout.LayoutParams(0, dp(44), 1f)
        l1.rightMargin = dp(4)
        val l2 = LinearLayout.LayoutParams(0, dp(44), 1f)
        l2.leftMargin = dp(4)
        row.addView(b1, l1)
        row.addView(b2, l2)
        val rowLp = LinearLayout.LayoutParams(-1, -2)
        rowLp.topMargin = dp(12)
        sheet.addView(row, rowLp)

        debugBtn = btn("", false) {
            prefs.debug = !prefs.debug
            refreshMenu()
            updateDebug()
        }
        val dlp = LinearLayout.LayoutParams(-1, dp(44))
        dlp.topMargin = dp(8)
        sheet.addView(debugBtn, dlp)

        val syncBtn = btn("Đồng bộ ngay (hỏi AMS + tải lại danh sách phát)", true) {
            sync.pollNow()
            sync.healthNow()
            Toast.makeText(this, "Đang đồng bộ với AMS…", Toast.LENGTH_SHORT).show()
            h.postDelayed({ updateDebug() }, 1500)
        }
        val sbl = LinearLayout.LayoutParams(-1, dp(44))
        sbl.topMargin = dp(8)
        sheet.addView(syncBtn, sbl)

        srcLine = tv("", 12f, GREY)
        srcLine.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.topMargin = dp(12)
        sheet.addView(srcLine, slp)
    }

    private fun buildPin() {
        pinOv = overlay(Color.argb(235, 28, 31, 52))
        val c = card(pinOv, 300)
        c.gravity = Gravity.CENTER_HORIZONTAL
        pinTitle = tv("", 16f, DARK, true)
        pinTitle.gravity = Gravity.CENTER
        c.addView(pinTitle, LinearLayout.LayoutParams(-1, -2))
        pinHint = tv(" ", 12f, GREY)
        pinHint.gravity = Gravity.CENTER
        pinHint.minHeight = dp(18)
        c.addView(pinHint, LinearLayout.LayoutParams(-1, -2))
        val dotRow = LinearLayout(this)
        dotRow.gravity = Gravity.CENTER
        val ds = ArrayList<View>()
        for (i in 0 until 4) {
            val d = View(this)
            val lp = LinearLayout.LayoutParams(dp(14), dp(14))
            lp.setMargins(dp(6), 0, dp(6), 0)
            dotRow.addView(d, lp)
            ds.add(d)
        }
        dots = ds
        val dlp = LinearLayout.LayoutParams(-1, -2)
        dlp.setMargins(0, dp(14), 0, dp(14))
        c.addView(dotRow, dlp)
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        for (rowKeys in keys) {
            val row = LinearLayout(this)
            for (k in rowKeys) {
                val key = tv(k, 18f, DARK, true)
                key.gravity = Gravity.CENTER
                if (k.isNotEmpty()) {
                    key.isFocusable = true
                    key.isClickable = true
                    key.background = focusBg(SOFT, 12, SOFT)
                    key.setOnClickListener { pinKey(k) }
                    if (k == "1") firstPinKey = key
                }
                val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
                lp.setMargins(dp(4), dp(4), dp(4), dp(4))
                row.addView(key, lp)
            }
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        pinCancel = btn("Hủy", false) { setView(if (pinMode == "new") V.MENU else V.PLAY) }
        val clp = LinearLayout.LayoutParams(-1, dp(44))
        clp.topMargin = dp(8)
        c.addView(pinCancel, clp)
        pinForgot = tv("", 12f, GREY)
        pinForgot.gravity = Gravity.CENTER
        val flp = LinearLayout.LayoutParams(-1, -2)
        flp.topMargin = dp(8)
        c.addView(pinForgot, flp)
    }

    private fun buildSources() {
        srcOv = overlay(Color.argb(235, 28, 31, 52))
        val c = card(srcOv, 310)
        c.addView(tv("Nguồn dữ liệu", 16f, DARK, true))
        val rows = ArrayList<LinearLayout>()
        val boxes = ArrayList<View>()
        val descs = ArrayList<TextView>()
        for (s in Src.values()) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.isFocusable = true
            row.isClickable = true
            row.background = focusBg(Color.WHITE, 12, BORDER)
            val bx = View(this)
            row.addView(bx, LinearLayout.LayoutParams(dp(16), dp(16)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(tv(s.label, 14f, DARK, true))
            val d = tv("", 12f, GREY)
            col.addView(d)
            val cl = LinearLayout.LayoutParams(0, -2, 1f)
            cl.leftMargin = dp(12)
            row.addView(col, cl)
            row.setOnClickListener {
                prefs.setSel(s, !prefs.isSel(s))
                refreshSources()
                sync.syncNow()
                sync.healthNow()
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = dp(10)
            c.addView(row, lp)
            rows.add(row)
            boxes.add(bx)
            descs.add(d)
        }
        srcRows = rows
        srcBoxes = boxes
        srcDescs = descs
        firstSrcRow = rows[0]
        val info = tv("Chọn nhiều nguồn. Phát theo thứ tự ưu tiên; khi ams.anio.vn mất mạng sẽ chạy USB.", 12f, GREY)
        val ilp = LinearLayout.LayoutParams(-1, -2)
        ilp.topMargin = dp(10)
        c.addView(info, ilp)

        val usbBtn = btn("Chọn thư mục USB (Android 10+)", false) {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 20)
            } catch (e: Exception) {
                Logger.log("không mở được bộ chọn thư mục: ${e.message}")
            }
        }
        val lanBtn = btn("Địa chỉ LAN", false) { setView(V.LAN) }
        val done = btn("Xong", true) { setView(V.MENU) }
        for (b in listOf(usbBtn, lanBtn, done)) {
            val lp = LinearLayout.LayoutParams(-1, dp(44))
            lp.topMargin = dp(8)
            c.addView(b, lp)
        }
    }

    private fun buildLan() {
        lanOv = overlay(Color.argb(235, 28, 31, 52))
        val c = card(lanOv, 310)
        c.addView(tv("Địa chỉ LAN", 16f, DARK, true))
        c.addView(tv("Máy chủ HTTP nội bộ trả playlist.json", 12f, GREY))
        lanInput = EditText(this)
        lanInput.hint = "http://192.168.1.10:8080/playlist.json"
        lanInput.setSingleLine(true)
        lanInput.inputType = InputType.TYPE_TEXT_VARIATION_URI
        lanInput.textSize = 13f
        val ilp = LinearLayout.LayoutParams(-1, -2)
        ilp.topMargin = dp(12)
        c.addView(lanInput, ilp)
        val save = btn("Lưu", true) {
            prefs.lanUrl = lanInput.text.toString().trim()
            sync.syncNow()
            setView(V.SOURCE)
        }
        val cancel = btn("Hủy", false) { setView(V.SOURCE) }
        for (b in listOf(save, cancel)) {
            val lp = LinearLayout.LayoutParams(-1, dp(44))
            lp.topMargin = dp(8)
            c.addView(b, lp)
        }
    }
}
