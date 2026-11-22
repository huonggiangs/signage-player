package vn.anio.signage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dùng manifest THẬT do edge-gateway sinh ra (fixture: manifest-layout.json, lấy từ GET /v1/manifest của gateway AMS). */
class LayoutModelTest {
    private fun manifest(): JSONObject =
        JSONObject(javaClass.getResourceAsStream("/manifest-layout.json")!!.bufferedReader().readText())

    private fun slot(): JSONObject = manifest().getJSONArray("schedule").getJSONObject(0)

    private fun layout(): Layout = LayoutParser.parse(slot().optJSONObject("layout"))!!

    private fun media(): Map<String, ZMedia> {
        val m = HashMap<String, ZMedia>()
        val ca = manifest().getJSONArray("creatives")
        for (i in 0 until ca.length()) {
            val c = ca.getJSONObject(i)
            m[c.getString("id")] = ZMedia(c.getString("id"), c.getString("media_type") == "video", "/tmp/" + c.getString("id"), (c.getDouble("duration_s") * 1000).toLong(), c.getString("fit") == "fit")
        }
        return m
    }

    @Test fun slotKhongCoLayoutThiPhatTuanTu() {
        assertNull(LayoutParser.parse(null))
        assertNull(LayoutParser.parse(JSONObject("""{"orientation":"portrait","zones":[]}""")))
        assertNull(LayoutParser.parse(JSONObject("""{"zones":[{"id":"a","type":"lạ"}]}""")))
    }

    @Test fun docLayoutTuManifestThat() {
        val l = layout()
        assertTrue(l.portrait)
        assertEquals(540, l.designWidth)
        assertEquals(7, l.zones.size)
        // vẽ theo z tăng dần, cùng z giữ thứ tự khai báo
        assertEquals(listOf("main", "side", "q1", "q2", "w", "clk", "tick"), l.zones.map { it.id })
        val main = l.zones.first { it.id == "main" }
        assertEquals("video", main.type)
        assertEquals("fade", main.transition)
        assertEquals(100.0, main.w, 0.0)
        assertEquals(55.0, main.h, 0.0)
        assertEquals(2, main.creativeIds.size)
        val tick = l.zones.first { it.id == "tick" }
        assertEquals("Xin chao quy khach", tick.text)
        assertEquals(60, tick.scrollSpeed)
        assertEquals(18, tick.fontSize)
        val w = l.zones.first { it.id == "w" }
        assertEquals("fixed", w.weatherMode)
        assertEquals(21.0285, w.lat!!, 1e-9)
        assertEquals(105.8542, w.lon!!, 1e-9)
        assertEquals("Ha Noi", w.label)
    }

    @Test fun nguonQrKhopManifest() {
        val l = layout()
        val q1 = l.zones.first { it.id == "q1" }
        val src = QrLogic.source(q1)
        assertTrue(src is QrSource.ImageUrl)
        assertEquals("https://img.vietqr.io/image/970436-0123456789-qr_only.png?amount=50000&addInfo=Thanh+toan&accountName=ANIO", (src as QrSource.ImageUrl).url)
        // gateway cũ không gửi qr_kind/url: app tự dựng đúng cùng URL từ cấu hình gốc
        val raw = slot().getJSONObject("layout").getJSONArray("zones").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "q1" }
        }
        raw.remove("qr_kind"); raw.remove("url")
        val old = LayoutParser.parse(JSONObject().put("zones", org.json.JSONArray().put(raw)))!!.zones[0]
        assertEquals(src.url, (QrLogic.source(old) as QrSource.ImageUrl).url)
        val q2 = QrLogic.source(l.zones.first { it.id == "q2" })
        assertEquals(QrSource.Text("https://anio.vn"), q2)
    }

    @Test fun duLieuAnhQrNhungTrongManifest() {
        // 1x1 PNG
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
        val z = LayoutParser.parse(JSONObject("""{"zones":[{"id":"q","type":"qr","x":0,"y":0,"w":10,"h":10,"qr_kind":"data_url","url":"data:image/png;base64,$png"}]}"""))!!.zones[0]
        val s = QrLogic.source(z)
        assertTrue(s is QrSource.Bytes)
        assertEquals(0x89, (s as QrSource.Bytes).data[0].toInt() and 0xff)
        assertEquals(QrSource.None, QrLogic.source(LayoutParser.parse(JSONObject("""{"zones":[{"id":"q","type":"qr","qr_kind":"data_url","url":"hong"}]}"""))!!.zones[0]))
    }

    @Test fun veMaQrTuChuoi() {
        val m = QrLogic.matrix("https://anio.vn")
        assertNotNull(m)
        assertTrue(m!!.size >= 21)
        assertEquals(m.size, m[0].size)
        assertTrue(m.any { r -> r.any { it } })
        assertNull(QrLogic.matrix(""))
    }

    @Test fun chonVungCoTiengVaThoiLuong() {
        val l = layout()
        val zm = LayoutParser.zoneMedia(l, media())!!
        assertTrue(zm.containsKey("main") && zm.containsKey("side"))
        assertEquals(2, zm["main"]!!.size)
        // vùng video lớn nhất được bật tiếng
        assertEquals("main", LayoutParser.primaryAudioZone(l, zm))
        // 2 video x 15 giây
        assertEquals(30_000L, LayoutParser.programDurationMs(zm))
    }

    @Test fun khongCoTepNaoTaiDuocThiBoBoCuc() {
        assertNull(LayoutParser.zoneMedia(layout(), emptyMap()))
        val onlyText = LayoutParser.parse(JSONObject("""{"zones":[{"id":"m","type":"marquee","text":"hi"}]}"""))!!
        assertEquals(emptyMap<String, List<ZMedia>>(), LayoutParser.zoneMedia(onlyText, emptyMap()))
        assertEquals(15_000L, LayoutParser.programDurationMs(emptyMap()))
    }

    @Test fun mauVaToCDoChuChay() {
        assertEquals(0xFFDC2626.toInt(), LayoutParser.argb("#DC2626", 0))
        assertEquals(0x80112233.toInt(), LayoutParser.argb("#80112233", 0))
        assertEquals(7, LayoutParser.argb("khong-phai-mau", 7))
        assertEquals(15, LayoutParser.marqueePassSec(60))
        assertEquals(6, LayoutParser.marqueePassSec(100))
        assertEquals(28, LayoutParser.marqueePassSec(0))
    }

    @Test fun thoiTiet() {
        val json = """{"current":{"time":"2026-11-22T13:00","temperature_2m":27.6,"relative_humidity_2m":71,"weather_code":3,"wind_speed_10m":11.4}}"""
        val w = WeatherLogic.parse(json)!!
        assertEquals(27.6, w.tempC, 1e-9)
        assertEquals(71, w.humidity)
        assertEquals(3, w.code)
        assertEquals("Nhiều mây", WeatherLogic.label(w.code))
        assertEquals("Mưa rào", WeatherLogic.label(81))
        assertNull(WeatherLogic.parse("{}"))
        assertNull(WeatherLogic.parse("khong phai json"))
        assertTrue(WeatherLogic.url(21.0285, 105.8542).startsWith("https://api.open-meteo.com/v1/forecast?latitude=21.0285&longitude=105.8542&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m"))
        // chế độ gps nhưng web không gửi toạ độ: dùng Hà Nội
        val z = LayoutParser.parse(JSONObject("""{"zones":[{"id":"w","type":"weather","weather":{"mode":"gps"}}]}"""))!!.zones[0]
        assertEquals(Pair(WeatherLogic.DEFAULT_LAT, WeatherLogic.DEFAULT_LON), WeatherLogic.coords(z))
        assertFalse(z.lat != null)
    }
}
