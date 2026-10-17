package com.kuniec.latarka

import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShakeDetectorTest {

    private val g = SensorManager.GRAVITY_EARTH
    private lateinit var detector: ShakeDetector

    @Before
    fun setUp() {
        detector = ShakeDetector(
            FlashlightConfig(
                gForceThreshold = 2.0f,
                slopDelayMs = 300L,
                shakeCount = 2,
                cooldownMs = 1000L,
                resetTimeoutMs = 800L,
                isDropPreventionEnabled = true
            )
        )
    }

    /** One chop: a sine of linear acceleration on Y starting at [start] (ms). */
    private fun chop(start: Long, cycles: Double = 1.0, periodMs: Double = 200.0, ampG: Double = 3.5): (Long) -> Double = { t ->
        val u = (t - start).toDouble()
        if (u in 0.0..cycles * periodMs) ampG * g * sin(2 * PI * u / periodMs) else 0.0
    }

    /**
     * Feeds 50 Hz samples (gravity on Z plus the summed linear signals on Y) and
     * returns the timestamps where the detector asked for a toggle.
     */
    private fun run(
        signals: List<(Long) -> Double>,
        totalMs: Long = 3000L,
        freeFall: LongRange? = null
    ): List<Long> {
        val toggles = mutableListOf<Long>()
        var t = 1000L
        while (t < 1000L + totalMs) {
            var x = 0f
            var y = signals.sumOf { it(t) }.toFloat()
            var z = g
            if (freeFall != null && t in freeFall) {
                x = 0f; y = 0f; z = 0f
            }
            if (detector.onSensorChanged(x, y, z, t)) toggles.add(t)
            t += 20
        }
        return toggles
    }

    @Test
    fun `chop chop toggles once`() {
        assertEquals(1, run(listOf(chop(1500), chop(1950))).size)
    }

    @Test
    fun `single chop does not toggle`() {
        assertTrue(run(listOf(chop(1500))).isEmpty())
    }

    @Test
    fun `two reversals inside one chop count as one chop`() {
        assertTrue(run(listOf(chop(1500, cycles = 1.5))).isEmpty())
        assertEquals(1, run(listOf(chop(3500, cycles = 1.5), chop(4000, cycles = 1.5)), totalMs = 5000L).size)
    }

    @Test
    fun `one-direction bumps do not toggle`() {
        val bumps = listOf(1500L, 1800L, 2100L, 2400L).map { s ->
            { t: Long -> if (t >= s && t < s + 60) 3.5 * g else 0.0 }
        }
        assertTrue(run(bumps).isEmpty())
    }

    @Test
    fun `chops too far apart do not toggle`() {
        assertTrue(run(listOf(chop(1500), chop(3000)), totalMs = 4000L).isEmpty())
    }

    @Test
    fun `chop chop right after a drop is ignored`() {
        assertTrue(run(listOf(chop(1500), chop(1950)), freeFall = 1300L until 1400L).isEmpty())
    }

    @Test
    fun `walking motion does not toggle`() {
        val walking = { t: Long -> 1.0 * g * sin(2 * PI * t / 500.0) }
        assertTrue(run(listOf(walking), totalMs = 10_000L).isEmpty())
    }

    @Test
    fun `two separate gestures toggle twice`() {
        val toggles = run(listOf(chop(1500), chop(1950), chop(3500), chop(3950)), totalMs = 4000L)
        assertEquals(2, toggles.size)
    }

    @Test
    fun `first sample is not mistaken for a shake`() {
        assertFalse(detector.onSensorChanged(0f, 0f, g, 1000L))
        assertFalse(detector.onSensorChanged(0f, 0f, g, 1020L))
    }
}
