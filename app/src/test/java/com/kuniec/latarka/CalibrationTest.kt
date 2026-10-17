package com.kuniec.latarka

import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CalibrationTest {

    private val g = SensorManager.GRAVITY_EARTH

    private companion object {
        const val X = 0
        const val Y = 1
        const val Z = 2
    }

    /** A linear-acceleration signal on one axis. */
    private class Signal(val axis: Int, val at: (Long) -> Double)

    /** One chop: a sine of linear acceleration starting at [start] (ms). */
    private fun chop(start: Long, ampG: Double = 3.5, axis: Int = Y, periodMs: Double = 200.0) = Signal(axis) { t ->
        val u = (t - start).toDouble()
        if (u in 0.0..periodMs) ampG * g * sin(2 * PI * u / periodMs) else 0.0
    }

    /** A chop-chop: two chops [gapMs] apart. */
    private fun chopChop(start: Long, ampG: Double = 3.5, axis: Int = Y, gapMs: Long = 450) =
        listOf(chop(start, ampG, axis), chop(start + gapMs, ampG, axis))

    /** [count] chop-chops, 2.5 s apart, starting at 1.5 s. */
    private fun chopChops(count: Int = 5, ampG: Double = 3.0, axis: Int = Y) =
        (0 until count).flatMap { chopChop(1500L + it * 2500L, ampG, axis) }

    /** A one-way push, like setting the phone down. */
    private fun bump(start: Long, ampG: Double, axis: Int = Y) = Signal(axis) { t ->
        if (t >= start && t < start + 60) ampG * g else 0.0
    }

    /** Feeds 50 Hz samples (gravity on Z plus the signals) to [onSample]. */
    private fun feed(signals: List<Signal>, totalMs: Long, onSample: (Float, Float, Float, Long) -> Unit) {
        var t = 1000L
        while (t < 1000L + totalMs) {
            val v = DoubleArray(3)
            signals.forEach { v[it.axis] += it.at(t) }
            onSample(v[0].toFloat(), v[1].toFloat(), (v[2] + g).toFloat(), t)
            t += 20
        }
    }

    private fun lengthOf(signals: List<Signal>) = 3000L + 2500L * (signals.size / 2)

    private fun chopsIn(signals: List<Signal>, totalMs: Long = lengthOf(signals)): List<ChopRecorder.Chop> {
        val recorder = ChopRecorder()
        val chops = mutableListOf<ChopRecorder.Chop>()
        feed(signals, totalMs) { x, y, z, t -> recorder.onSensorChanged(x, y, z, t)?.let { chops += it } }
        return chops
    }

    /** Records the chop step the way the app does: raw samples plus the chop-chops found in them. */
    private fun chopStep(signals: List<Signal>): Pair<MotionRecording, List<List<ChopRecorder.Chop>>> {
        val recording = MotionRecording()
        val recorder = ChopRecorder()
        val collector = ChopGestureCollector(shakeCount = 2)
        feed(signals, lengthOf(signals)) { x, y, z, t ->
            recording.add(x, y, z, t)
            recorder.onSensorChanged(x, y, z, t)?.let { collector.add(it) }
        }
        return recording to collector.gestures
    }

    private fun everydayStep(signals: List<Signal>) = MotionRecording().also { r ->
        feed(signals, 20_000L) { x, y, z, t -> r.add(x, y, z, t) }
    }

    private fun toggles(config: FlashlightConfig, signals: List<Signal>): Int {
        val detector = ShakeDetector(config)
        var count = 0
        feed(signals, lengthOf(signals)) { x, y, z, t -> if (detector.onSensorChanged(x, y, z, t)) count++ }
        return count
    }

    // --- Recording ---

    @Test
    fun `recorder measures a chop's strength, axis and style`() {
        val chops = chopsIn(listOf(chop(1500, ampG = 3.0)))
        assertEquals(1, chops.size)
        assertEquals(3.0f, chops[0].strengthG, 0.6f)
        assertEquals(Y, chops[0].axis)
        assertTrue(chops[0].snapBackMs in 0L..150L)
        assertTrue(chops[0].dominance > 0.9f)
    }

    @Test
    fun `recorder sees chops weaker than the default threshold`() {
        val chops = chopsIn(chopChop(1500, ampG = 1.6))
        assertEquals(2, chops.size)
        assertTrue(chops.all { it.strengthG in 1.0f..2.0f })
    }

    @Test
    fun `one-way bumps are not chops`() {
        assertTrue(chopsIn(listOf(bump(1500, 2.5), bump(2500, 2.5))).isEmpty())
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

    // --- Chop style ---

    @Test
    fun `style is learned from the chops with room to spare`() {
        val style = Calibration.learnStyle(
            listOf(
                ChopRecorder.Chop(0, 3f, axis = Y, snapBackMs = 80, dominance = 0.95f),
                ChopRecorder.Chop(500, 3f, axis = Y, snapBackMs = 100, dominance = 0.9f)
            )
        )
        assertEquals(FlashlightConfig.AXIS_Y, style.chopAxes)
        assertEquals(200L, style.reversalWindowMs) // 100 * 1.5 + 50
        assertEquals(0.8f, style.dominanceRatio, 0.001f) // 0.9 - 0.1
    }

    @Test
    fun `detector only counts chops on the allowed axes`() {
        val onY = chopChop(1500)
        assertEquals(1, toggles(FlashlightConfig(chopAxes = FlashlightConfig.AXIS_Y), onY))
        assertEquals(0, toggles(FlashlightConfig(chopAxes = FlashlightConfig.AXIS_X), onY))
    }

    @Test
    fun `detector rejects a snap-back slower than its window`() {
        val slow = listOf(chop(1500, periodMs = 500.0), chop(2100, periodMs = 500.0))
        assertEquals(0, toggles(FlashlightConfig(reversalWindowMs = 100L), slow))
        assertEquals(1, toggles(FlashlightConfig(reversalWindowMs = 450L), slow))
    }

    // --- Tuning ---

    @Test
    fun `tuning catches every chop-chop and ignores other directions`() {
        val (recording, gestures) = chopStep(chopChops(ampG = 3.0, axis = Y))
        assertEquals(5, gestures.size)
        // Everyday movement that would look like chop-chops, but on the X axis.
        val everyday = everydayStep((0 until 6).flatMap { chopChop(2000L + it * 3000L, ampG = 2.0, axis = X) })

        val r = Calibration.tune(recording, gestures, everyday, FlashlightConfig(), nowMs = 42L)
        assertEquals(FlashlightConfig.AXIS_Y, r.config.chopAxes)
        assertNotNull(r.safeRange)
        val i = r.indexOf(r.recommendedG)
        assertEquals(5, r.caught[i])
        assertEquals(0, r.accidental!![i])
        assertEquals(42L, r.config.calibratedAtMs)
        assertTrue(r.recommendedG in r.safeRange!!)
    }

    @Test
    fun `chop-like everyday movement pushes the safe range up`() {
        val (recording, gestures) = chopStep(chopChops(ampG = 3.5))
        val everyday = everydayStep((0 until 6).flatMap { chopChop(2000L + it * 3000L, ampG = 1.6) })

        val r = Calibration.tune(recording, gestures, everyday, FlashlightConfig(), nowMs = 0L)
        val safe = r.safeRange!!
        // Just below the safe range, everyday movement sets it off.
        assertTrue("safe range $safe should start above 1 g", safe.start > Calibration.MIN_THRESHOLD_G)
        assertTrue(r.accidental!![r.indexOf(safe.start) - 1] > 0)
        assertTrue("safe range $safe should end below the chops", safe.endInclusive < 3.6f)
        assertEquals(0, r.accidental!![r.indexOf(r.recommendedG)])
    }

    @Test
    fun `slider positions outside the safe range report misses and accidents`() {
        val (recording, gestures) = chopStep(chopChops(ampG = 3.0))
        val everyday = everydayStep((0 until 6).flatMap { chopChop(2000L + it * 3000L, ampG = 1.6) })
        val r = Calibration.tune(recording, gestures, everyday, FlashlightConfig(), nowMs = 0L)

        assertTrue(r.accidental!![r.indexOf(Calibration.MIN_THRESHOLD_G)] > 0)
        assertTrue(r.caught[r.indexOf(Calibration.MAX_THRESHOLD_G)] < 5)
    }

    @Test
    fun `skipping everyday movement stays close to the chops`() {
        val (recording, gestures) = chopStep(chopChops(ampG = 3.0))
        val r = Calibration.tune(recording, gestures, everyday = null, current = FlashlightConfig(), nowMs = 0L)
        assertNull(r.accidental)
        assertEquals(5, r.caught[r.indexOf(r.recommendedG)])
        assertTrue(r.recommendedG > 1.5f)
    }

    @Test
    fun `when nothing is safe it still picks a working setting`() {
        val (recording, gestures) = chopStep(chopChops(ampG = 2.0))
        // Everyday movement just as strong as the chops, in the same direction.
        val everyday = everydayStep((0 until 6).flatMap { chopChop(2000L + it * 3000L, ampG = 2.2) })
        val r = Calibration.tune(recording, gestures, everyday, FlashlightConfig(), nowMs = 0L)
        assertNull(r.safeRange)
        // Catching every chop-chop comes first, then as few accidents as possible.
        val i = r.indexOf(r.recommendedG)
        assertEquals(5, r.caught[i])
        assertTrue(r.accidental!!.withIndex().filter { r.caught[it.index] == 5 }.all { it.value >= r.accidental!![i] })
    }

    @Test
    fun `timing follows the user's rhythm`() {
        val signals = listOf(
            chopChop(1500, gapMs = 400), chopChop(4000, gapMs = 700)
        ).flatten()
        val (recording, gestures) = chopStep(signals)
        val r = Calibration.tune(recording, gestures, null, FlashlightConfig(), 0L)
        assertEquals(300L, r.config.slopDelayMs) // about 400 * 0.7, rounded to 50
        assertEquals(1000L, r.config.resetTimeoutMs) // about 700 * 1.4, rounded to 50
    }

    @Test
    fun `calibrated settings detect gentle chops the defaults miss`() {
        val signals = chopChops(count = 3, ampG = 1.7)
        assertEquals(0, toggles(FlashlightConfig(), signals))

        val (recording, gestures) = chopStep(signals)
        val everyday = everydayStep(listOf(bump(3000, 0.8)))
        val r = Calibration.tune(recording, gestures, everyday, FlashlightConfig(), nowMs = 0L)
        assertEquals(3, toggles(r.config, signals))
    }
}
