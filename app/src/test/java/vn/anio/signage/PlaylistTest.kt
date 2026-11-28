package vn.anio.signage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    private fun fixture(): JSONObject =
        JSONObject(javaClass.getResource("/manifest-playlist.json")!!.readText())

    private fun layout(): Layout =
        LayoutParser.parse(fixture().getJSONArray("schedule").getJSONObject(0).getJSONObject("layout"))!!

    private fun media(): Map<String, ZMedia> = mapOf(
        "cv1" to ZMedia("cv1", true, "/c/cv1.mp4", 15_000, true),
        "ci1" to ZMedia("ci1", false, "/c/ci1.jpg", 10_000, false)
    )

    // ---------------------------------------------------------------- manifest -> mục phát

    @Test fun parsesPlaylistAndLegacyZone() {
        val l = layout()
        val main = l.zones.first { it.id == "main" }
        assertEquals(6, main.playlist.size)
        assertTrue(main.loop)
        val zm = LayoutParser.zoneMedia(l, media())!!
        // 6 mục khai, mục "khong-co" (tệp chưa tải) bị bỏ -> 5
        assertEquals(listOf("it-video", "it-text", "it-img", "it-ext", "it-stream"), zm["main"]!!.map { it.itemId })
        val v = zm["main"]!![0]; assertEquals("video", v.kind); assertNull(v.cutMs); assertEquals(15_000, v.durationMs)
        val t = zm["main"]!![1]; assertTrue(t.isText); assertEquals(8_000, t.durationMs); assertEquals("#dc2626", t.style!!.getString("background"))
        val i = zm["main"]!![2]; assertEquals(20_000, i.durationMs); assertEquals("/c/ci1.jpg", i.path)
        val e = zm["main"]!![3]; assertEquals("https://cdn.test/x.jpg", e.remoteUrl); assertEquals(8_000, e.durationMs)
        val s = zm["main"]!![4]; assertEquals("hls", s.stream); assertEquals("https://cdn.test/live.m3u8", s.remoteUrl)
        // vùng cũ chỉ có creative_ids: đọc thành danh sách, id dẫn xuất ổn định
        val old = zm["old"]!!
        assertEquals(1, old.size)
        assertEquals("lg-old-ci1", old[0].itemId)
        assertEquals(zm["old"], LayoutParser.zoneMedia(l, media())!!["old"])
    }

    @Test fun videoCutWhenPlaySet() {
        val j = JSONArray("""[{"id":"a","type":"video","media_id":"cv1","url":"u","play_s":5}]""")
        val z = Playlists.parse(j)
        assertEquals(5.0, z[0].playS!!, 0.0)
    }

    @Test fun parseRejectsBadItemsAndDuplicates() {
        val j = JSONArray("""[
          {"id":"a","type":"gif","media_id":"x"},
          {"id":"b","type":"text","text_content":""},
          {"id":"c","type":"image"},
          {"id":"d","type":"image","media_id":"x"},
          {"id":"d","type":"image","media_id":"y"}]""")
        assertEquals(listOf("d"), Playlists.parse(j).map { it.id })
    }

    @Test fun textOnlyProgramIsPlayable() {
        val o = JSONObject("""{"orientation":"portrait","zones":[{"id":"m","type":"video","x":0,"y":0,"w":100,"h":100,
            "playlist":[{"id":"t1","type":"text","text_content":"Xin chào","play_s":8}]}]}""")
        val l = LayoutParser.parse(o)!!
        val zm = LayoutParser.zoneMedia(l, emptyMap())!!
        assertEquals(1, zm["m"]!!.size)
        assertEquals(8_000L, LayoutParser.programDurationMs(zm))
    }

    @Test fun legacyZoneAlsoReadFromOldManifest() {
        // manifest cũ (không có playlist) vẫn phát
        val m = JSONObject(javaClass.getResource("/manifest-layout.json")!!.readText())
        val lay = LayoutParser.parse(m.getJSONArray("schedule").getJSONObject(0).optJSONObject("layout"))
        if (lay != null) assertTrue(lay.zones.all { it.playlist.isEmpty() })
    }

    // ---------------------------------------------------------------- bộ điều phối

    @Test fun sequencerLoopsAndPeeks() {
        val s = PlaylistSequencer(3, true)
        assertEquals(PlaylistSequencer.Step.Play(0), s.start())
        assertEquals(1, s.peekNext())
        assertEquals(PlaylistSequencer.Step.Play(1), s.onItemEnded())
        assertEquals(PlaylistSequencer.Step.Play(2), s.onItemEnded())
        assertEquals(0, s.peekNext())
        assertEquals(PlaylistSequencer.Step.Play(0), s.onItemEnded())
    }

    @Test fun sequencerStopsWithoutLoop() {
        val s = PlaylistSequencer(2, false)
        s.start(); s.onItemEnded()
        assertNull(s.peekNext())
        assertEquals(PlaylistSequencer.Step.Finished, s.onItemEnded())
        assertEquals(1, s.index)
    }

    @Test fun sequencerSkipsFailedItem() {
        val s = PlaylistSequencer(3, true)
        s.start()
        assertEquals(PlaylistSequencer.Step.Play(1), s.onItemFailed())
        assertEquals(PlaylistSequencer.Step.Play(2), s.onItemEnded()) // một mục chạy được thì chuỗi lỗi bị xóa
    }

    @Test fun sequencerAllFailedThenRetry() {
        val s = PlaylistSequencer(3, true)
        s.start()
        assertEquals(PlaylistSequencer.Step.Play(1), s.onItemFailed())
        assertEquals(PlaylistSequencer.Step.Play(2), s.onItemFailed())
        assertEquals(PlaylistSequencer.Step.AllFailed, s.onItemFailed())
        assertEquals(PlaylistSequencer.Step.Play(0), s.restart())
        assertEquals(8_000L, Playlists.WAIT_SCREEN_MS)
    }

    @Test fun singleItemLoopsOnItself() {
        val s = PlaylistSequencer(1, true)
        assertEquals(PlaylistSequencer.Step.Play(0), s.start())
        assertEquals(PlaylistSequencer.Step.Play(0), s.onItemEnded())
        assertEquals(PlaylistSequencer.Step.AllFailed, s.onItemFailed())
    }

    // ---------------------------------------------------------------- hàng đợi bền

    private fun imp(id: String, at: Long) = EventQueue.itemImpression(id, "bp_P1", "main", "it-1", "cv1", "video", at)

    @Test fun wireFormatMatchesProtocol() {
        val e = imp("log-1", 1_000_000L).toWire(2_000L)
        assertEquals("item_impression", e.getString("type"))
        assertEquals("log-1", e.getString("log_id"))
        assertEquals("bp_P1", e.getString("program_id"))
        assertEquals("main", e.getString("zone_id"))
        assertEquals("it-1", e.getString("item_id"))
        assertEquals("cv1", e.getString("media_id"))
        assertEquals("video", e.getString("item_type"))
        assertEquals(Playlists.isoMillis(1_002_000L), e.getString("played_at"))
        assertFalse(e.has("device_id"))
        // chữ: không có media_id
        val t = EventQueue.itemImpression("log-2", "bp_P1", "main", "it-2", "x", "text", 5L)
        assertFalse(t.payload.has("media_id"))
    }

    @Test fun dedupesByLogIdAndPersists() {
        val st = MemStore()
        val q = EventQueue(st)
        assertTrue(q.add(imp("a", 1000), 2000))
        assertFalse(q.add(imp("a", 1000), 2000))
        assertTrue(q.add(imp("b", 1001), 2000))
        val q2 = EventQueue(st) // khởi động lại máy
        assertEquals(listOf("a", "b"), q2.snapshot().map { it.id })
        assertFalse(q2.add(imp("a", 1000), 2000))
    }

    @Test fun flushAt50RecordsOrFiveMinutes() {
        val q = EventQueue()
        val t0 = 10_000_000L
        for (i in 1..49) q.add(imp("l$i", t0), t0)
        assertFalse(q.shouldFlush(t0 + 60_000))
        assertTrue(q.shouldFlush(t0 + 5 * 60_000))
        q.add(imp("l50", t0), t0)
        assertTrue(q.shouldFlush(t0 + 1))
        assertEquals(50, q.nextBatch(t0 + 1).size)
        q.add(imp("l51", t0), t0)
        assertEquals(50, q.nextBatch(t0 + 1).size) // tối đa 50/lô
    }

    @Test fun ttlDropsOldRecords() {
        val q = EventQueue()
        q.add(imp("old", 0), 0)
        q.add(imp("new", 48L * 3600_000), 48L * 3600_000)
        assertEquals(listOf("new"), q.snapshot().map { it.id })
    }

    // ---------------------------------------------------------------- gửi lô (lỗi 1.1.4: không bỏ cả lô khi 4xx)

    private fun fill(q: EventQueue, n: Int, t: Long) { for (i in 1..n) q.add(imp("l$i", t + i), t) }

    @Test fun ackRemovesBatch() {
        val q = EventQueue(); val t = 1_000_000L; fill(q, 50, t)
        val sent = ArrayList<Int>()
        val up = EventUploader(q, { a -> sent.add(a.length()); SendResult(202, "", 0) }, { 0 }, { t + 10 })
        assertEquals(1, up.flush(false))
        assertEquals(0, q.size()); assertEquals(listOf(50), sent)
    }

    @Test fun clockSkew401KeepsEverything() {
        val q = EventQueue(); val t = 1_000_000L; fill(q, 50, t)
        val up = EventUploader(q, { SendResult(401, "stale_timestamp", 0) }, { 0 }, { t + 10 })
        up.flush(false)
        assertEquals(50, q.size()) // 1.1.4 xóa cả 50 ở đây
        assertTrue(q.snapshot().all { it.attempts == 1 && it.nextAtMs > t + 10 })
    }

    @Test fun serverErrorsAndNetworkKeepData() {
        for (code in listOf(500, 502, 503, 429, 408, 403, 404, 409, -1)) {
            val q = EventQueue(); val t = 1_000_000L; fill(q, 50, t)
            EventUploader(q, { SendResult(code, "", 0) }, { 0 }, { t + 10 }).flush(false)
            assertEquals("code $code", 50, q.size())
        }
    }

    @Test fun retryAfterHonored() {
        val q = EventQueue(); val t = 1_000_000L; fill(q, 50, t)
        EventUploader(q, { SendResult(429, "", 30) }, { 0 }, { t + 10 }).flush(false)
        assertTrue(q.snapshot().all { it.nextAtMs == t + 10 + 30_000 })
    }

    @Test fun networkExceptionKeepsData() {
        val q = EventQueue(); val t = 1_000_000L; fill(q, 50, t)
        EventUploader(q, { throw java.io.IOException("x") }, { 0 }, { t + 10 }).flush(false)
        assertEquals(50, q.size())
    }

    @Test fun badEventIsIsolatedNotWholeBatch() {
        val q = EventQueue(); val t = 1_000_000L; fill(q, 8, t)
        // máy chủ từ chối (422) mọi lô có chứa l3
        val up = EventUploader(q, { a ->
            var bad = false
            for (i in 0 until a.length()) if (a.getJSONObject(i).getString("log_id") == "l3") bad = true
            if (bad) SendResult(422, "invalid", 0) else SendResult(202, "", 0)
        }, { 0 }, { t + 10 })
        up.flush(true, 30)
        // 7 sự kiện tốt đã gửi; chỉ l3 còn lại (chờ thử lại, bị bỏ sau vài lần)
        assertEquals(listOf("l3"), q.snapshot().map { it.id })
    }

    @Test fun payloadTooLargeShrinks() {
        val q = EventQueue(); val t = 1_000_000L; fill(q, 40, t)
        val sizes = ArrayList<Int>()
        val up = EventUploader(q, { a -> sizes.add(a.length()); if (a.length() > 10) SendResult(413, "", 0) else SendResult(202, "", 0) }, { 0 }, { t + 10 })
        up.flush(true, 20)
        assertEquals(0, q.size())
        assertTrue(sizes.first() == 40 && sizes.drop(1).any { it <= 10 })
    }

    @Test fun rejectedSingleDroppedOnlyAfterRetries() {
        val q = EventQueue(); val t = 1_000_000L; q.add(imp("x", t), t)
        var now = t + 10
        val up = EventUploader(q, { SendResult(422, "bad", 0) }, { 0 }, { now })
        for (i in 1 until EventQueue.MAX_REJECT_ATTEMPTS) { up.flush(true); assertEquals(1, q.size()); now += 3_600_000L }
        up.flush(true)
        assertEquals(0, q.size())
    }

    @Test fun clockOffsetAppliedAtSendTime() {
        val q = EventQueue(); val t = 1_000_000L; q.add(imp("a", t), t)
        var off = 0L
        var wire: JSONObject? = null
        val up = EventUploader(q, { a -> wire = a.getJSONObject(0); SendResult(401, "stale_timestamp", 0) }, { off }, { t + 10 })
        up.flush(true)
        assertEquals(Playlists.isoMillis(t), wire!!.getString("played_at"))
        off = 5_000L // độ lệch học được sau 401: lần gửi sau dùng giờ đã hiệu chỉnh
        val q2 = q.snapshot()[0]; assertEquals(t, q2.atMs)
        assertEquals(Playlists.isoMillis(t + 5_000), q2.toWire(off).getString("played_at"))
    }

    @Test fun proofOfPlayAndImpressionShareBatch() {
        val q = EventQueue(); val t = 1_000_000L
        q.add(EventQueue.proofOfPlay("e1", "cv1", 12_000, t), t)
        q.add(imp("l1", t + 1), t)
        var types = emptyList<String>()
        EventUploader(q, { a -> types = (0 until a.length()).map { a.getJSONObject(it).getString("type") }; SendResult(202, "", 0) }, { 0 }, { t + 10 }).flush(true)
        assertEquals(listOf("proof_of_play", "item_impression"), types)
        assertEquals(0, q.size())
    }
}
