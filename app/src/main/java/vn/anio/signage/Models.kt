package vn.anio.signage

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val APP_VERSION = "1.0.0"

/** org.json trả chuỗi "null" khi giá trị là null; hàm này trả mặc định. */
fun JSONObject.s(name: String, def: String = ""): String = if (isNull(name)) def else optString(name, def)

enum class Src(val label: String, val desc: String) {
    AMS("ams.anio.vn", "Nguồn live, cần có mạng"),
    USB("USB", "Ổ USB cắm vào Android TV box"),
    LAN("LAN", "Thư mục chia sẻ trong mạng nội bộ")
}

data class Item(
    val id: String,
    val isVideo: Boolean,
    val uri: Uri,
    val durationMs: Long,
    val header: String,
    val footer: String,
    val fit: Boolean,
    val startAt: Long,
    val endAt: Long,
    val creativeId: String = "",
    val campaignId: String = "",
    val slotId: String = "",
    val dayStartMin: Int = 0,
    val dayEndMin: Int = 1440
) {
    fun valid(now: Long): Boolean {
        if (!((startAt <= 0 || now >= startAt) && (endAt <= 0 || now <= endAt))) return false
        if (dayStartMin <= 0 && dayEndMin >= 1440) return true
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
        c.timeInMillis = now
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m >= dayStartMin && m < dayEndMin
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var landscape: Boolean
        get() = sp.getBoolean("landscape", false)
        set(v) { sp.edit().putBoolean("landscape", v).apply() }
    var volume: Int
        get() = sp.getInt("volume", 60)
        set(v) { sp.edit().putInt("volume", v).apply() }
    var sources: Int
        get() = sp.getInt("sources", 3) // ams + usb
        set(v) { sp.edit().putInt("sources", v).apply() }
    var hotline: String
        get() = sp.getString("hotline", "0365361688") ?: "0365361688"
        set(v) { sp.edit().putString("hotline", v).apply() }
    var gwBase: String
        get() = sp.getString("gwBase", "https://gw-ams.anio.vn") ?: "https://gw-ams.anio.vn"
        set(v) { sp.edit().putString("gwBase", v).apply() }
    var seed: String
        get() = sp.getString("seed", "") ?: ""
        set(v) { sp.edit().putString("seed", v).apply() }
    var pub: String
        get() = sp.getString("pub", "") ?: ""
        set(v) { sp.edit().putString("pub", v).apply() }
    var deviceId: String
        get() = sp.getString("deviceId", "") ?: ""
        set(v) { sp.edit().putString("deviceId", v).apply() }
    var trust: Int
        get() = sp.getInt("trust", 0)
        set(v) { sp.edit().putInt("trust", v).apply() }
    var clockOffset: Long
        get() = sp.getLong("clockOffset", 0L)
        set(v) { sp.edit().putLong("clockOffset", v).apply() }
    var region: String
        get() = sp.getString("region", "") ?: ""
        set(v) { sp.edit().putString("region", v).apply() }
    var lanUrl: String
        get() = sp.getString("lanUrl", "") ?: ""
        set(v) { sp.edit().putString("lanUrl", v).apply() }
    var usbTree: String
        get() = sp.getString("usbTree", "") ?: ""
        set(v) { sp.edit().putString("usbTree", v).apply() }
    var firstRun: Boolean
        get() = sp.getBoolean("firstRun", true)
        set(v) { sp.edit().putBoolean("firstRun", v).apply() }
    var failCount: Int
        get() = sp.getInt("failCount", 0)
        set(v) { sp.edit().putInt("failCount", v).apply() }
    var lockUntil: Long
        get() = sp.getLong("lockUntil", 0L)
        set(v) { sp.edit().putLong("lockUntil", v).apply() }
    var batAsked: Boolean
        get() = sp.getBoolean("batAsked", false)
        set(v) { sp.edit().putBoolean("batAsked", v).apply() }

    fun isSel(s: Src): Boolean = (sources and (1 shl s.ordinal)) != 0
    fun setSel(s: Src, on: Boolean) {
        sources = if (on) sources or (1 shl s.ordinal) else sources and (1 shl s.ordinal).inv()
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val k = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, 10000, 256))
        return Base64.encodeToString(k.encoded, Base64.NO_WRAP)
    }

    fun checkPin(pin: String): Boolean {
        val h = sp.getString("pwHash", "") ?: ""
        val s = sp.getString("pwSalt", "") ?: ""
        if (h.isEmpty() || s.isEmpty()) return pin == "1234"
        return hash(pin, Base64.decode(s, Base64.NO_WRAP)) == h
    }

    /** Lệnh reset_pin từ ams: về mật khẩu mặc định 1234 và bắt buộc đổi lại. */
    fun resetPin() {
        sp.edit().remove("pwHash").remove("pwSalt").putBoolean("firstRun", true)
            .putInt("failCount", 0).putLong("lockUntil", 0L).apply()
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        sp.edit()
            .putString("pwSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pwHash", hash(pin, salt))
            .apply()
    }
}

object Logger {
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) { file = File(ctx.cacheDir, "signage.log") }

    @Synchronized
    fun log(msg: String) {
        val f = file ?: return
        try {
            if (f.length() > 300_000) {
                val keep = f.readLines().takeLast(1500)
                f.writeText(keep.joinToString("\n") + "\n")
            }
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(n: Int): String = try {
        file?.readLines()?.takeLast(n)?.joinToString("\n") ?: ""
    } catch (_: Exception) {
        ""
    }
}
