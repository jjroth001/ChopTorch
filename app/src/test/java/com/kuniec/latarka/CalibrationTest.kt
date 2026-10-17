package com.kuniec.latarka

import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CalibrationTest {

    private val g = SensorManager.GRAVITY_EARTH

    /** One chop: a sine of linear acceleration on Y starting at [start] (ms). */
    private fun chop(start: Long, ampG: Double = 3.5, periodMs: Double = 200.0): (Long) -> Double = { t ->
        val u = (t - start).toDouble()
        if (u in 0.0..periodMs) ampG * g * sin(2 * PI * u / periodMs) else 0.0
    }

    /** A one-way push on Y, like setting the phone down. */
    private fun bump(start: Long, ampG: Double): (Long) -> Double = { t ->
        if (t >= start && t < start + 60) ampG * g else 0.0
    }

    /** Feeds 50 Hz samples (gravity on Z, signals on Y) to [onSample]. */
    private fun feed(signals: List<(Long) -> Double>, totalMs: Long, onSample: (Float, Float, Float, Long) -> Unit) {
        var t = 1000L
        while (t < 1000L + totalMs) {
            onSample(0f, signals.sumOf { it(t) }.toFloat(), g, t)
            t += 20
        }
    }

    private fun record(signals: List<(Long) -> Double>, totalMs: Long = 4000L): Pair<List<ChopRecorder.Chop>, Float> {
        val recorder = ChopRecorder()
        val chops = mutableListOf<ChopRecorder.Chop>()
        feed(signals, totalMs) { x, y, z, t -> recorder.onSensorChanged(x, y, z, t)?.let { chops += it } }
        return chops to recorder.peakG
    }

    @Test
    fun `recorder measures a chop's strength`() {
        val (chops, _) = record(listOf(chop(1500, ampG = 3.0)))
        assertEquals(1, chops.size)
        assertEquals(3.0f, chops[0].strengthG, 0.6f)
    }

    @Test
    fun `recorder sees chops weaker than the default threshold`() {
        val (chops, _) = record(listOf(chop(1500, ampG = 1.6), chop(2000, ampG = 1.6)))
        assertEquals(2, chops.size)
        assertTrue(chops.all { it.strengthG in 1.0f..2.0f })
    }

    @Test
    fun `one-way bumps are not chops but count as movement`() {
        val (chops, peak) = record(listOf(bump(1500, 2.5), bump(2500, 2.5)))
        assertTrue(chops.isEmpty())
        assertTrue(peak > 1.5f)
    }

    @Test
    fun `collector groups chops into pairs and restarts after a long pause`() {
        val collector = ChopGestureCollector(shakeCount = 2)
        assertNull(collector.add(ChopRecorder.Chop(1000, 3f)))
        // Too long after the first one: starts a new gesture instead of completing.
        assertNull(collector.add(ChopRecorder.Chop(3000, 3f)))
        assertEquals(2, collector.add(ChopRecorder.Chop(3450, 3f))?.size)
        assertEquals(1, collector.gestures.size)
    }

    private fun gesture(start: Long, gap: Long, a: Float, b: Float) =
        listOf(ChopRecorder.Chop(start, a), ChopRecorder.Chop(start + gap, b))

    @Test
    fun `threshold sits below the weakest chop when everyday movement is low`() {
        val gestures = listOf(gesture(0, 450, 3.4f, 3.0f), gesture(5000, 500, 2.9f, 3.6f))
        val r = Calibration.compute(gestures, everydayG = 1.4f, current = FlashlightConfig(), nowMs = 42L)
        assertEquals(2.2f, r.config.gForceThreshold, 0.001f)
        assertEquals(Calibration.Margin.PLENTY, r.margin)
        assertEquals(42L, r.config.calibratedAtMs)
    }

    @Test
    fun `strong everyday movement raises the threshold but never above the chops`() {
        val gestures = listOf(gesture(0, 450, 3.0f, 3.0f))
        val tight = Calibration.compute(gestures, everydayG = 2.0f, current = FlashlightConfig(), nowMs = 0L)
        assertEquals(Calibration.Margin.TIGHT, tight.margin)
        assertEquals(2.5f, tight.config.gForceThreshold, 0.001f)

        val overlap = Calibration.compute(gestures, everydayG = 2.8f, current = FlashlightConfig(), nowMs = 0L)
        assertEquals(Calibration.Margin.OVERLAP, overlap.margin)
        assertEquals(2.7f, overlap.config.gForceThreshold, 0.001f)
    }

    @Test
    fun `skipping the everyday step uses the chops alone`() {
        val r = Calibration.compute(listOf(gesture(0, 450, 2.0f, 2.0f)), everydayG = null, current = FlashlightConfig(), nowMs = 0L)
        assertEquals(Calibration.Margin.UNKNOWN, r.margin)
        assertEquals(1.5f, r.config.gForceThreshold, 0.001f)
    }

    @Test
    fun `timing follows the user's rhythm within safe limits`() {
        val r = Calibration.compute(
            listOf(gesture(0, 400, 3f, 3f), gesture(5000, 700, 3f, 3f)),
            everydayG = null, current = FlashlightConfig(), nowMs = 0L
        )
        assertEquals(300L, r.config.slopDelayMs) // 400 * 0.7 = 280, rounded to 300
        assertEquals(1000L, r.config.resetTimeoutMs) // 700 * 1.4 = 980, rounded to 1000

        val fast = Calibration.compute(listOf(gesture(0, 260, 3f, 3f)), null, FlashlightConfig(), 0L)
        assertEquals(250L, fast.config.slopDelayMs)
        assertTrue(fast.config.resetTimeoutMs >= fast.config.slopDelayMs + 200)
    }

    @Test
    fun `calibrated settings detect gentle chops the defaults miss`() {
        val signals = listOf(chop(1500, ampG = 1.7), chop(1950, ampG = 1.7))

        fun toggles(config: FlashlightConfig): Int {
            val detector = ShakeDetector(config)
            var count = 0
            feed(signals, 3000L) { x, y, z, t -> if (detector.onSensorChanged(x, y, z, t)) count++ }
            return count
        }
        assertEquals(0, toggles(FlashlightConfig()))

        val (chops, _) = record(signals)
        val collector = ChopGestureCollector(shakeCount = 2)
        chops.forEach { collector.add(it) }
        val result = Calibration.compute(collector.gestures, everydayG = 0.5f, current = FlashlightConfig(), nowMs = 0L)
        assertEquals(1, toggles(result.config))
    }
}
