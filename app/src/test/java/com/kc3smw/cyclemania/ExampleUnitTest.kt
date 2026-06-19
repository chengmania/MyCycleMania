package com.kc3smw.cyclemania

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun haversine_isCorrect() {
        val dist = RideRecorder.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertTrue("Should be ~111km", dist > 110_000 && dist < 112_000)
    }
}
