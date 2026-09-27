package com.newoether.agora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FairyEmblemTest {

    @Test
    fun breathHitsKeyFrames() {
        assertEquals(0f, emblemBreath(0), 1e-3f)
        assertEquals(0.5f, emblemBreath(1050), 1e-3f)
        assertEquals(1f, emblemBreath(2100), 1e-3f)
        assertEquals(0f, emblemBreath(4200), 1e-3f)
    }

    @Test
    fun breathStaysInUnitRangeAcrossCycles() {
        var t = -1000L
        while (t <= 10_000L) {
            val breath = emblemBreath(t)
            assertTrue("breath=$breath at t=$t", breath in -0.001f..1.001f)
            t += 37L
        }
    }
}
