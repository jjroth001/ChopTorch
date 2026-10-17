package com.kuniec.latarka

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measures chops during calibration.
 *
 * Uses the same gravity filter and reversal idea as [ShakeDetector], but with loose rules (a low
 * strength floor, a long snap-back window, a low dominance share) so it sees the user's chops
 * whatever the current settings are. For each chop it notes how strong it was (the weaker of its
 * two opposite spikes, because [ShakeDetector] needs both to clear the threshold), which axis it
 * was on, how long the snap-back took and how cleanly it stayed on that axis.
 */
class ChopRecorder(private val minGapMs: Long = MIN_GAP_MS) {

    data class Chop(
        val timeMs: Long,
        val strengthG: Float,
        /** 0 = X, 1 = Y, 2 = Z. */
        val axis: Int = 1,
        /** Time from the end of the swing to the start of the snap-back. */
        val snapBackMs: Long = 0L,
        /** The smaller share of movement on [axis] at the two spikes' peaks. */
        val dominance: Float = 1f
    )

    companion object {
        /** Spikes weaker than this (in g) are ignored. It is also the lowest threshold setting. */
        const val FLOOR_G = 1.0f

        /** A reversal this soon after the previous chop belongs to that chop. */
        const val MIN_GAP_MS = 250L

        /** Loose versions of the detector's rules, so slow or wobbly chops are still seen. */
        private const val REVERSAL_WINDOW_MS = 450L
        private const val DOMINANCE_RATIO = 0.5f

        /** Samples of one spike further apart than this start a new spike. */
        private const val SPIKE_JOIN_MS = 60L

        private const val NONE = Long.MIN_VALUE / 2
    }

    /** Strongest movement on a single dominant axis seen so far, in g, chop or not. */
    var peakG = 0f
        private set

    /** Overall movement in the latest sample, in g, for live display. */
    var movementG = 0f
        private set

    private var lastSampleTime = -1L
    private val gravity = FloatArray(3)
    private var lastChopTime = NONE

    // Per axis; index 0 is the positive spike, 1 the negative one.
    private val spikeTime = Array(3) { LongArray(2) { NONE } }
    private val spikePeak = Array(3) { FloatArray(2) }
    private val spikeDominance = Array(3) { FloatArray(2) }

    /** A chop whose second spike may still be growing. */
    private class Pending(
        val axis: Int,
        val sign: Int,
        val time: Long,
        val firstPeak: Float,
        val firstDominance: Float,
        val snapBackMs: Long
    ) {
        var secondPeak = 0f
        var secondDominance = 1f
        var lastTime = time
    }

    private var pending: Pending? = null

    /**
     * Feeds one raw accelerometer sample (m/s²) with its sensor timestamp in milliseconds.
     *
     * @return a chop once its strength is final, otherwise null.
     */
    fun onSensorChanged(x: Float, y: Float, z: Float, timeMs: Long): Chop? {
        val raw = floatArrayOf(x, y, z)
        val dtMs = if (lastSampleTime < 0) Long.MAX_VALUE else timeMs - lastSampleTime
        lastSampleTime = timeMs
        if (dtMs > ShakeDetector.RESEED_GAP_MS || dtMs < 0) {
            raw.copyInto(gravity)
            clearSpikes()
            return finishPending()
        }
        val dt = dtMs / 1000f
        val alpha = ShakeDetector.GRAVITY_TAU_S / (ShakeDetector.GRAVITY_TAU_S + dt)
        for (i in 0..2) {
            gravity[i] = alpha * gravity[i] + (1 - alpha) * raw[i]
        }

        val linear = FloatArray(3) { (raw[it] - gravity[it]) / SensorManager.GRAVITY_EARTH }
        val total = sqrt(linear[0] * linear[0] + linear[1] * linear[1] + linear[2] * linear[2])
        movementG = total
        val axis = (0..2).firstOrNull { total > 0f && abs(linear[it]) >= DOMINANCE_RATIO * total }
        val mag = if (axis == null) 0f else abs(linear[axis])
        val dominance = if (total > 0f) mag / total else 0f
        peakG = max(peakG, mag)
        val sign = if (axis == null || mag < FLOOR_G) -1 else if (linear[axis] > 0) 0 else 1

        // While the second spike of a chop keeps going, it only adds to that chop's strength.
        val p = pending
        var finished: Chop? = null
        if (p != null) {
            if (axis == p.axis && sign == p.sign && timeMs - p.lastTime <= SPIKE_JOIN_MS) {
                if (mag > p.secondPeak) {
                    p.secondPeak = mag
                    p.secondDominance = dominance
                }
                p.lastTime = timeMs
                return null
            }
            finished = finishPending()
        }

        if (axis == null || sign < 0) return finished
        val opposite = 1 - sign
        if (timeMs - spikeTime[axis][sign] > SPIKE_JOIN_MS || mag > spikePeak[axis][sign]) {
            spikePeak[axis][sign] = mag
            spikeDominance[axis][sign] = dominance
        }
        spikeTime[axis][sign] = timeMs

        val snapBack = timeMs - spikeTime[axis][opposite]
        if (snapBack <= REVERSAL_WINDOW_MS) {
            if (timeMs - lastChopTime >= minGapMs) {
                pending = Pending(
                    axis, sign, timeMs, spikePeak[axis][opposite], spikeDominance[axis][opposite], snapBack
                ).also {
                    it.secondPeak = mag
                    it.secondDominance = dominance
                }
                lastChopTime = timeMs
            }
            clearSpikes()
        }
        return finished
    }

    private fun finishPending(): Chop? {
        val p = pending ?: return null
        pending = null
        return Chop(
            timeMs = p.time,
            strengthG = min(p.firstPeak, p.secondPeak),
            axis = p.axis,
            snapBackMs = p.snapBackMs,
            dominance = min(p.firstDominance, p.secondDominance)
        )
    }

    private fun clearSpikes() {
        spikeTime.forEach { it.fill(NONE) }
    }
}

/**
 * Groups recorded chops into chop-chop gestures of [shakeCount] chops each. A chop that comes
 * more than [maxGapMs] after the previous one starts a new gesture.
 */
class ChopGestureCollector(private val shakeCount: Int, private val maxGapMs: Long = MAX_GAP_MS) {

    companion object {
        const val MAX_GAP_MS = 1500L
    }

    private val current = mutableListOf<ChopRecorder.Chop>()
    private val finished = mutableListOf<List<ChopRecorder.Chop>>()

    val gestures: List<List<ChopRecorder.Chop>> get() = finished

    /** Adds a chop and returns the gesture it completes, if any. */
    fun add(chop: ChopRecorder.Chop): List<ChopRecorder.Chop>? {
        if (current.isNotEmpty() && chop.timeMs - current.last().timeMs > maxGapMs) current.clear()
        current += chop
        if (current.size < shakeCount) return null
        val gesture = current.toList()
        finished += gesture
        current.clear()
        return gesture
    }

    fun reset() {
        current.clear()
        finished.clear()
    }
}

/**
 * Raw accelerometer samples, kept so candidate settings can be tried on them afterwards.
 */
class MotionRecording {

    private companion object {
        /** About half an hour at the calibration sampling rate; later samples are dropped. */
        const val MAX_SAMPLES = 90_000
    }

    private var count = 0
    private var xs = FloatArray(1024)
    private var ys = FloatArray(1024)
    private var zs = FloatArray(1024)
    private var ts = LongArray(1024)

    val size: Int get() = count

    fun add(x: Float, y: Float, z: Float, timeMs: Long) {
        if (count == MAX_SAMPLES) return
        if (count == ts.size) {
            val n = min(ts.size * 2, MAX_SAMPLES)
            xs = xs.copyOf(n)
            ys = ys.copyOf(n)
            zs = zs.copyOf(n)
            ts = ts.copyOf(n)
        }
        xs[count] = x
        ys[count] = y
        zs[count] = z
        ts[count] = timeMs
        count++
    }

    /** Runs a fresh [ShakeDetector] with [config] over the recording; returns its toggle times. */
    fun replay(config: FlashlightConfig): List<Long> {
        val detector = ShakeDetector(config)
        val toggles = mutableListOf<Long>()
        for (i in 0 until count) {
            if (detector.onSensorChanged(xs[i], ys[i], zs[i], ts[i])) toggles += ts[i]
        }
        return toggles
    }
}

/**
 * Turns calibration recordings into detection settings by trying them out.
 *
 * It learns the user's chop style (axis, snap-back time, cleanness) from the measured chops, then
 * replays both recordings through the real [ShakeDetector] at every threshold from 1.0 to 5.0 g.
 * The thresholds that catch every chop-chop and never trigger during everyday movement form the
 * safe range, and the recommendation sits in its middle.
 */
object Calibration {

    const val MIN_THRESHOLD_G = 1.0f
    const val MAX_THRESHOLD_G = 5.0f

    /** Every threshold that is tried, in 0.1 g steps. */
    val thresholds: FloatArray = FloatArray(41) { (10 + it) / 10f }

    /** A replayed toggle this close to a recorded chop-chop counts as catching it. */
    private const val CATCH_BEFORE_MS = 400L
    private const val CATCH_AFTER_MS = 600L

    /** A style is accepted when its safe range is at least this many 0.1 g steps wide. */
    private const val MIN_SAFE_STEPS = 3

    private const val SNAP_BACK_FACTOR = 1.5f
    private const val SNAP_BACK_EXTRA_MS = 50f
    private const val MIN_WINDOW_MS = 150L
    private const val MAX_WINDOW_MS = 450L
    private const val DOMINANCE_MARGIN = 0.1f
    private const val MIN_DOMINANCE = 0.5f
    private const val MAX_DOMINANCE = 0.8f
    private const val DEFAULT_WINDOW_MS = 300L
    private const val DEFAULT_DOMINANCE = 0.6f

    private const val MIN_SLOP_MS = 250L
    private const val MAX_SLOP_MS = 600L
    private const val MAX_RESET_MS = 2000L

    /** How strict the chop rules are: which axes count, snap-back time and cleanness. */
    data class Style(val reversalWindowMs: Long, val dominanceRatio: Float, val chopAxes: Int)

    class Result(
        /** The suggested settings, at the recommended threshold. */
        val config: FlashlightConfig,
        val gestureCount: Int,
        /** Chop-chops caught at each of [thresholds]. */
        val caught: IntArray,
        /** Accidental triggers at each of [thresholds], or null if everyday movement wasn't recorded. */
        val accidental: IntArray?,
        /** Thresholds that catch everything with no accidents, or null if there are none. */
        val safeRange: ClosedFloatingPointRange<Float>?,
        val weakestChopG: Float,
        val strongestChopG: Float
    ) {
        val recommendedG: Float get() = config.gForceThreshold

        /** The index into [thresholds] closest to [g]. */
        fun indexOf(g: Float): Int = ((g - MIN_THRESHOLD_G) * 10).roundToInt().coerceIn(0, thresholds.size - 1)

        fun configAt(g: Float): FlashlightConfig = config.copy(gForceThreshold = thresholds[indexOf(g)])
    }

    /**
     * @param chopRecording raw samples from the chop step.
     * @param gestures the chop-chops found in [chopRecording]; must not be empty.
     * @param everyday raw samples from the everyday step, or null if it was skipped.
     * @param current the settings to start from; fields calibration doesn't learn are kept.
     * @param nowMs the time to record as the calibration time.
     */
    fun tune(
        chopRecording: MotionRecording,
        gestures: List<List<ChopRecorder.Chop>>,
        everyday: MotionRecording?,
        current: FlashlightConfig,
        nowMs: Long
    ): Result {
        val chops = gestures.flatten()
        require(chops.isNotEmpty()) { "No chops recorded" }
        val strengths = chops.map { it.strengthG }.sorted()

        val (slop, reset) = rhythm(gestures, current)
        val base = current.copy(slopDelayMs = slop, resetTimeoutMs = reset, calibratedAtMs = nowMs)
        val windows = gestures.map { g -> (g.first().timeMs - CATCH_BEFORE_MS)..(g.last().timeMs + CATCH_AFTER_MS) }

        // Strictest first: a stricter style ignores more of the movement nobody has tested yet.
        val learned = learnStyle(chops)
        val candidates = listOf(
            learned,
            learned.copy(chopAxes = FlashlightConfig.AXIS_ALL),
            Style(
                max(learned.reversalWindowMs, DEFAULT_WINDOW_MS),
                min(learned.dominanceRatio, DEFAULT_DOMINANCE),
                FlashlightConfig.AXIS_ALL
            ),
            Style(MAX_WINDOW_MS, MIN_DOMINANCE, FlashlightConfig.AXIS_ALL)
        ).distinct()

        var best: Sweep? = null
        for (style in candidates) {
            val sweep = sweep(style.applyTo(base), chopRecording, windows, everyday)
            if (best == null || sweep.betterThan(best)) best = sweep
            if (sweep.safeRun.count() >= MIN_SAFE_STEPS) break
        }
        val chosen = best!!

        val safe = chosen.safeRun
        val recommendedIndex = when {
            safe.isEmpty() -> chosen.bestEffortIndex()
            // Without everyday data the low end of the range is untested, so stay near the chops.
            everyday == null -> max(safe.first, safe.last - (safe.last - safe.first + 1) / 4)
            else -> (safe.first + safe.last + 1) / 2
        }
        return Result(
            config = chosen.config.copy(gForceThreshold = thresholds[recommendedIndex]),
            gestureCount = gestures.size,
            caught = chosen.caught,
            accidental = chosen.accidental,
            safeRange = if (safe.isEmpty()) null else thresholds[safe.first]..thresholds[safe.last],
            weakestChopG = strengths.first(),
            strongestChopG = strengths.last()
        )
    }

    /** Learns which axes the user chops on, how fast they snap back and how clean the chops are. */
    fun learnStyle(chops: List<ChopRecorder.Chop>): Style {
        val axes = chops.fold(0) { mask, c -> mask or (1 shl c.axis) }
        val window = roundTo50(chops.maxOf { it.snapBackMs } * SNAP_BACK_FACTOR + SNAP_BACK_EXTRA_MS)
            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        // The tiny extra keeps float error (0.7999…) from rounding a whole step down.
        val dominance = (floor((chops.minOf { it.dominance } - DOMINANCE_MARGIN) * 20 + 1e-4f) / 20f)
            .coerceIn(MIN_DOMINANCE, MAX_DOMINANCE)
        return Style(window, dominance, axes)
    }

    private fun Style.applyTo(config: FlashlightConfig) =
        config.copy(reversalWindowMs = reversalWindowMs, dominanceRatio = dominanceRatio, chopAxes = chopAxes)

    /** The shortest and longest gap between chops, from the user's rhythm with some slack. */
    private fun rhythm(gestures: List<List<ChopRecorder.Chop>>, current: FlashlightConfig): Pair<Long, Long> {
        val gaps = gestures.flatMap { g -> g.zipWithNext { a, b -> b.timeMs - a.timeMs } }
        if (gaps.isEmpty()) return current.slopDelayMs to current.resetTimeoutMs
        val slop = roundTo50(gaps.min() * 0.7f).coerceIn(MIN_SLOP_MS, MAX_SLOP_MS)
        val reset = roundTo50(gaps.max() * 1.4f).coerceIn(slop + 200, MAX_RESET_MS)
        return slop to reset
    }

    /** One style tried at every threshold. */
    private class Sweep(
        val config: FlashlightConfig,
        val gestureCount: Int,
        val caught: IntArray,
        val accidental: IntArray?
    ) {
        /** The longest run of thresholds that catch every chop-chop with no accidents. */
        val safeRun: IntRange = run {
            var bestRun = IntRange.EMPTY
            var start = -1
            for (i in 0..thresholds.size) {
                val ok = i < thresholds.size && caught[i] == gestureCount && (accidental?.get(i) ?: 0) == 0
                if (ok && start < 0) start = i
                if (!ok && start >= 0) {
                    if (i - start > bestRun.count()) bestRun = start until i
                    start = -1
                }
            }
            bestRun
        }

        /**
         * With no safe threshold: catch as many chop-chops as possible, then have as few accidents
         * as possible, then prefer the stricter threshold.
         */
        fun bestEffortIndex(): Int = thresholds.indices.maxWith(bestEffortOrder)

        private val bestEffortOrder: Comparator<Int> =
            compareBy<Int> { caught[it] }.thenByDescending { accidental?.get(it) ?: 0 }.thenBy { it }

        fun betterThan(other: Sweep): Boolean {
            if (safeRun.count() != other.safeRun.count()) return safeRun.count() > other.safeRun.count()
            val mine = bestEffortIndex()
            val theirs = other.bestEffortIndex()
            if (caught[mine] != other.caught[theirs]) return caught[mine] > other.caught[theirs]
            return (accidental?.get(mine) ?: 0) < (other.accidental?.get(theirs) ?: 0)
        }
    }

    private fun sweep(
        config: FlashlightConfig,
        chopRecording: MotionRecording,
        windows: List<LongRange>,
        everyday: MotionRecording?
    ): Sweep {
        val caught = IntArray(thresholds.size)
        val accidental = everyday?.let { IntArray(thresholds.size) }
        for (i in thresholds.indices) {
            val c = config.copy(gForceThreshold = thresholds[i])
            val toggles = chopRecording.replay(c)
            caught[i] = windows.count { w -> toggles.any { it in w } }
            if (accidental != null) accidental[i] = everyday.replay(c).size
        }
        return Sweep(config, windows.size, caught, accidental)
    }

    private fun roundTo50(ms: Float) = ((ms / 50f).roundToInt() * 50).toLong()
}
