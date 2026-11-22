package vn.anio.signage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Parsed(val version: String, val items: List<Item>, val complete: Boolean)

object PlaylistIo {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getText(url: String, token: String?): String? {
        val rb = Request.Builder().url(url)
        if (!token.isNullOrEmpty()) rb.header("Authorization", "Bearer $token")
        return http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }

    fun versionOf(text: String): String {
        var v = ""
        try {
            val t = text.trim()
            if (t.startsWith("{")) v = JSONObject(t).optString("version", "")
        } catch (_: Exception) {
        }
        return if (v.isEmpty()) text.hashCode().toString() else v
    }

    fun mediaKind(name: String): Int {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4", "m4v", "mkv", "mov", "webm", "ts" -> 2
            "jpg", "jpeg", "png", "webp" -> 1
            else -> 0
        }
    }

    private fun hashOf(f: File, expected: String): String {
        val alg = when (expected.length) { 32 -> "MD5"; 40 -> "SHA-1"; else -> "SHA-256" }
        val md = MessageDigest.getInstance(alg)
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Lý do tải lỗi gần nhất (host + mã/ngoại lệ) để hiện trong bảng chẩn đoán. */
    @Volatile var lastDownloadError = ""

    fun downloadTo(url: String, dest: File, checksum: String, token: String?): Boolean {
        val sum = checksum.trim().lowercase(Locale.US)
        if (dest.exists() && dest.length() > 0 && (sum.isEmpty() || hashOf(dest, sum) == sum)) return true
        val part = File(dest.path + ".part")
        return try {
            val rb = Request.Builder().url(url)
            if (!token.isNullOrEmpty()) rb.header("Authorization", "Bearer $token")
            http.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    lastDownloadError = "${hostOf(url)} mã ${r.code}"
                    return false
                }
                val body = r.body ?: return false
                body.byteStream().use { i -> part.outputStream().use { o -> i.copyTo(o) } }
            }
            if (sum.isNotEmpty() && hashOf(part, sum) != sum) {
                part.delete()
                lastDownloadError = "${hostOf(url)} sai sha256"
                false
            } else {
                dest.delete()
                part.renameTo(dest)
            }
        } catch (e: Exception) {
            part.delete()
            lastDownloadError = "${hostOf(url)} ${(e.message ?: e.javaClass.simpleName).take(60)}"
            false
        }
    }

    private fun hostOf(url: String): String = try { java.net.URI(url).host ?: url.take(40) } catch (_: Exception) { url.take(40) }

    private fun parseTime(s: String): Long {
        if (s.isEmpty()) return 0
        s.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd HH:mm:ss")) {
            try {
                return SimpleDateFormat(p, Locale.US).parse(s)!!.time
            } catch (_: Exception) {
            }
        }
        return 0
    }

    /** Đọc playlist JSON, tải file về dir (cache), trả danh sách mục phát được. */
    fun parse(text: String, base: String, dir: File, token: String?, prefs: Prefs): Parsed? {
        val t = text.trim()
        val root: JSONObject
        val arr: JSONArray
        try {
            if (t.startsWith("[")) {
                root = JSONObject()
                arr = JSONArray(t)
            } else {
                root = JSONObject(t)
                arr = root.optJSONArray("items") ?: root.optJSONArray("playlist") ?: JSONArray()
            }
        } catch (e: Exception) {
            Logger.log("playlist sai định dạng")
            return null
        }
        val hotline = root.s("hotline", "")
        if (hotline.isNotEmpty() && hotline != prefs.hotline) prefs.hotline = hotline
        val gh = root.s("header", "")
        val gf = root.s("footer", "")
        dir.mkdirs()
        val out = ArrayList<Item>()
        val keep = HashSet<String>()
        var complete = true
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.s("url", o.s("path", ""))
            if (url.isEmpty()) continue
            val id = o.s("id", url.hashCode().toString())
            val type = o.s("type", "").lowercase(Locale.US)
            val isVideo = type == "video" || (type.isEmpty() && mediaKind(url.substringBefore('?')) == 2)
            val ext = url.substringBefore('?').substringAfterLast('.', if (isVideo) "mp4" else "jpg").take(5)
            val dest = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + ext)
            keep.add(dest.name)
            val full = if (url.startsWith("http")) url else base.trimEnd('/') + "/" + url.trimStart('/')
            val useToken = if (full.startsWith(base)) token else null
            if (!downloadTo(full, dest, o.s("checksum", ""), useToken)) {
                Logger.log("tải lỗi $id")
                complete = false
                continue
            }
            var dur = o.optDouble("duration", 10.0)
            dur = if (dur > 1000) dur else dur * 1000
            out.add(
                Item(
                    id = id, isVideo = isVideo, uri = Uri.fromFile(dest),
                    durationMs = dur.toLong().coerceAtLeast(1000),
                    header = o.s("header", gh), footer = o.s("footer", gf),
                    fit = o.s("fit", "") == "fit",
                    startAt = parseTime(o.s("startAt", "")),
                    endAt = parseTime(o.s("endAt", ""))
                )
            )
        }
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        return Parsed(versionOf(text), out, complete)
    }
}

object UsbScanner {
    fun scan(ctx: Context, prefs: Prefs): List<Item> {
        File("/storage").listFiles()?.forEach { r ->
            if (r.name != "emulated" && r.name != "self" && r.isDirectory) {
                val d = File(r, "ANIO")
                if (d.isDirectory && d.canRead() && d.listFiles() != null) return fromFiles(d)
            }
        }
        if (prefs.usbTree.isNotEmpty()) return fromTree(ctx, prefs)
        return emptyList()
    }

    private fun spec(text: String?): List<Pair<String, Double>> {
        if (text == null) return emptyList()
        return try {
            val a = JSONObject(text).optJSONArray("items") ?: return emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val f = o.s("file", "")
                if (f.isEmpty()) null else Pair(f, o.optDouble("duration", 10.0))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun order(names: List<String>, spec: List<Pair<String, Double>>): List<Pair<String, Double>> {
        if (spec.isEmpty()) return names.sortedBy { it.lowercase(Locale.US) }.map { Pair(it, 10.0) }
        return spec.filter { it.first in names }
    }

    private fun mk(name: String, uri: Uri, dur: Double) = Item(
        id = "usb-$name", isVideo = PlaylistIo.mediaKind(name) == 2, uri = uri,
        durationMs = (dur * 1000).toLong().coerceAtLeast(1000),
        header = "", footer = "", fit = false, startAt = 0, endAt = 0
    )

    private fun fromFiles(d: File): List<Item> {
        val files = d.listFiles()?.filter { it.isFile && PlaylistIo.mediaKind(it.name) != 0 } ?: return emptyList()
        val pl = File(d, "playlist.json")
        val sp = spec(if (pl.exists()) pl.readText() else null)
        val byName = files.associateBy { it.name }
        return order(byName.keys.toList(), sp).map { mk(it.first, Uri.fromFile(byName[it.first]!!), it.second) }
    }

    private fun fromTree(ctx: Context, prefs: Prefs): List<Item> {
        return try {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(prefs.usbTree)) ?: return emptyList()
            val dir = root.findFile("ANIO")?.takeIf { it.isDirectory } ?: root
            val docs = dir.listFiles().filter { it.isFile && PlaylistIo.mediaKind(it.name ?: "") != 0 }
            if (docs.isEmpty()) return emptyList()
            val cache = File(ctx.cacheDir, "usb")
            cache.mkdirs()
            val plDoc = dir.findFile("playlist.json")
            val sp = spec(plDoc?.let { d -> ctx.contentResolver.openInputStream(d.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } })
            val byName = docs.associateBy { it.name!! }
            val ordered = order(byName.keys.toList(), sp)
            val keep = HashSet<String>()
            val items = ArrayList<Item>()
            for ((name, dur) in ordered) {
                val doc = byName[name]!!
                val dest = File(cache, name)
                keep.add(name)
                if (!dest.exists() || dest.length() != doc.length()) {
                    ctx.contentResolver.openInputStream(doc.uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                }
                items.add(mk(name, Uri.fromFile(dest), dur))
            }
            cache.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            items
        } catch (e: Exception) {
            Logger.log("đọc USB lỗi: ${e.message}")
            emptyList()
        }
    }
}

