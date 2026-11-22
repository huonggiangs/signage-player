package vn.anio.signage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTest {
    private fun snap(
        sel: Boolean = true, id: String = "dev1", reg: Int = 201, regErr: String = "", down: Boolean = false, hb: Int = 200,
        mf: Int = 200, slots: Int = 1, cre: Int = 1, dlFail: Int = 0, dlErr: String = "", items: Int = 0, group: String = "ALL.all.elevator"
    ) = DiagSnapshot(sel, id, reg, regErr, down, hb, mf, slots, cre, dlFail, dlErr, items, group)

    @Test fun identitySeedIsDeterministic() {
        val a = Identity.seedFor("3f9a1c2b7d4e5f60", "vn.anio.signage")
        val b = Identity.seedFor("3F9A1C2B7D4E5F60", "vn.anio.signage")
        assertNotNull(a)
        assertEquals(32, a!!.size)
        assertArrayEquals(a, b)
    }

    @Test fun identitySeedDiffersPerDeviceAndPackage() {
        val a = Identity.seedFor("3f9a1c2b7d4e5f60", "vn.anio.signage")!!
        assertFalse(a.contentEquals(Identity.seedFor("3f9a1c2b7d4e5f61", "vn.anio.signage")!!))
        assertFalse(a.contentEquals(Identity.seedFor("3f9a1c2b7d4e5f60", "vn.other")!!))
    }

    @Test fun badAndroidIdFallsBackToRandom() {
        assertNull(Identity.seedFor(null, "p"))
        assertNull(Identity.seedFor("", "p"))
        assertNull(Identity.seedFor("9774d56d682e549c", "p"))
        assertNull(Identity.seedFor("0000000000000000", "p"))
        assertTrue(Identity.usableAndroidId("3f9a1c2b7d4e5f60"))
    }

    @Test fun pollDelayIsCappedButHonoursRetryAfter() {
        assertEquals(30, PollPolicy.delay(300))
        assertEquals(30, PollPolicy.delay(600))
        assertEquals(30, PollPolicy.delay(0))
        assertEquals(20, PollPolicy.delay(20))
        assertEquals(10, PollPolicy.delay(1))
        assertEquals(120, PollPolicy.delay(300, 120))
    }

    @Test fun idleReasonCoversEveryStage() {
        assertTrue(IdleReason.of(snap(sel = false)).contains("TẮT"))
        assertTrue(IdleReason.of(snap(id = "", reg = 0)).contains("Chưa đăng ký"))
        assertTrue(IdleReason.of(snap(id = "", reg = -1, regErr = "Unable to resolve host")).contains("Unable to resolve host"))
        assertTrue(IdleReason.of(snap(id = "", reg = 403)).contains("403"))
        assertTrue(IdleReason.of(snap(down = true)).contains("Mất kết nối"))
        assertTrue(IdleReason.of(snap(hb = 0)).contains("heartbeat đầu tiên"))
        assertTrue(IdleReason.of(snap(hb = 401)).contains("401"))
        assertTrue(IdleReason.of(snap(mf = 0)).contains("manifest"))
        assertTrue(IdleReason.of(snap(mf = 500)).contains("500"))
    }

    @Test fun idleReasonExplainsEmptyManifest() {
        val r = IdleReason.of(snap(slots = 0, cre = 0))
        assertTrue(r.contains("chưa có chương trình"))
        assertTrue(r.contains("ALL.all.elevator"))
    }

    @Test fun idleReasonExplainsDownloadFailure() {
        val r = IdleReason.of(snap(slots = 1, cre = 2, dlFail = 2, dlErr = "media.ams.invalid UnknownHost"))
        assertTrue(r.contains("tải lỗi 2"))
        assertTrue(r.contains("media.ams.invalid"))
    }

    @Test fun idleReasonWhenItemsWaitForTime() {
        assertTrue(IdleReason.of(snap(items = 3)).contains("khung giờ"))
    }
}

class EtagSignTest {
    @Test fun weakEtagNormalized() {
        val raw = "W/\"abc123\""
        val n = raw.removePrefix("W/").trim('"')
        assertEquals("abc123", n)
        assertEquals("abc123", java.net.URLEncoder.encode(n, "UTF-8"))
    }
}
