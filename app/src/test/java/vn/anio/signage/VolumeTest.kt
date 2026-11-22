package vn.anio.signage

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTest {
    @Test fun systemWhenReadBackMatches() {
        assertEquals(Pair("system", 60), PlayerGain.decide(60, 15, 9, false))
    }
    @Test fun playerWhenSystemIgnoresChange() {
        assertEquals(Pair("player", 30), PlayerGain.decide(30, 15, 15, false))
    }
    @Test fun playerWhenVolumeFixed() {
        assertEquals(Pair("player", 40), PlayerGain.decide(40, 100, 40, true))
    }
    @Test fun playerWhenNoStreamRange() {
        assertEquals(Pair("player", 50), PlayerGain.decide(50, 0, 0, false))
    }
    @Test fun gainClamped() {
        PlayerGain.set(2f); assertEquals(1f, PlayerGain.value, 0f)
        PlayerGain.set(-1f); assertEquals(0f, PlayerGain.value, 0f)
        PlayerGain.set(1f)
    }
}
