package com.kuniec.latarka

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measures chops during calibration.
 *
 * Uses the same gravity filter, dominant-axis rule and reversal rule as [ShakeDetector], but with
 * a low strength floor, so it also sees chops weaker than the current threshold. A chop's strength
 * is the smaller of its two opposite spikes, because [ShakeDetector] needs both to clear the
 * threshold.
 */
class ChopRecorder(private val minGapMs: Long = MIN_GAP_MS) {

    data class Chop(val timeMs: Long, val strengthG: Float)

    companion object {
        /** Spikes weaker than this (in g) are ignored. It is also the lowest threshold setting. */
        const val FLOOR_G = 1.0f

        /** A reversal this soon after the previous chop belongs to that chop. */
        const val MIN_GAP_MS = 250L

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

    /** A chop whose second spike may still be growing. */
    private class Pending(val axis: Int, val sign: Int, val time: Long, val firstPeak: Float) {
        var secondPeak = 0f
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
        val axis = (0..2).firstOrNull { total > 0f && abs(linear[it]) >= ShakeDetector.DOMINANCE_RATIO * total }
        val mag = if (axis == null) 0f else abs(linear[axis])
        peakG = max(peakG, mag)
        val sign = if (axis == null || mag < FLOOR_G) -1 else if (linear[axis] > 0) 0 else 1

        // While the second spike of a chop keeps going, it only adds to that chop's strength.
        val p = pending
        var finished: Chop? = null
        if (p != null) {
            if (axis == p.axis && sign == p.sign && timeMs - p.lastTime <= SPIKE_JOIN_MS) {
                p.secondPeak = max(p.secondPeak, mag)
                p.lastTime = timeMs
                return null
            }
            finished = finishPending()
        }

        if (axis == null || sign < 0) return finished
        val opposite = 1 - sign
        spikePeak[axis][sign] =
            if (timeMs - spikeTime[axis][sign] > SPIKE_JOIN_MS) mag else max(spikePeak[axis][sign], mag)
        spikeTime[axis][sign] = timeMs

        if (timeMs - spikeTime[axis][opposite] <= ShakeDetector.REVERSAL_WINDOW_MS) {
            if (timeMs - lastChopTime >= minGapMs) {
                pending = Pending(axis, sign, timeMs, spikePeak[axis][opposite]).also { it.secondPeak = mag }
                lastChopTime = timeMs
            }
            clearSpikes()
        }
        return finished
    }

    private fun finishPending(): Chop? {
        val p = pending ?: return null
        pending = null
        return Chop(p.time, min(p.firstPeak, p.secondPeak))
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
 * Turns calibration measurements into detection settings.
 */
object Calibration {

    /** How clearly the user's chops stand out from everyday movement. */
    enum class Margin { PLENTY, TIGHT, OVERLAP, UNKNOWN }

    data class Result(
        val config: FlashlightConfig,
        val weakestChopG: Float,
        val strongestChopG: Float,
        /** Strongest chop-like everyday movement, or null if that step was skipped. */
        val everydayG: Float?,
        val margin: Margin
    )

    /** The threshold sits this far below the user's weakest chop. */
    private const val CHOP_FACTOR = 0.75f

    /** The threshold never goes above this share of the weakest chop, or chops would be missed. */
    private const val CEILING_FACTOR = 0.9f

    /** The threshold stays this far above everyday movement, as a factor and in g. */
    private const val EVERYDAY_FACTOR = 1.25f
    private const val EVERYDAY_MIN_GAP_G = 0.3f

    private const val MAX_THRESHOLD_G = 5.0f
    private const val MIN_SLOP_MS = 250L
    private const val MAX_SLOP_MS = 600L
    private const val MAX_RESET_MS = 2000L

    /**
     * @param gestures the chop-chop gestures recorded in the first step; must not be empty.
     * @param everydayG the strongest chop-like movement seen during everyday use, or null.
     * @param current the settings to start from; fields calibration doesn't learn are kept.
     * @param nowMs the time to record as the calibration time.
     */
    fun compute(
        gestures: List<List<ChopRecorder.Chop>>,
        everydayG: Float?,
        current: FlashlightConfig,
        nowMs: Long
    ): Result {
        val strengths = gestures.flatten().map { it.strengthG }.sorted()
        require(strengths.isNotEmpty()) { "No chops recorded" }
        // With enough chops, ignore the single weakest so one half-hearted chop doesn't lower the bar.
        val weakest = if (strengths.size >= 6) strengths[1] else strengths[0]
        val strongest = strengths.last()

        val fromChops = weakest * CHOP_FACTOR
        val ceiling = max(weakest * CEILING_FACTOR, ChopRecorder.FLOOR_G)
        val aboveEveryday =
            everydayG?.let { max(it * EVERYDAY_FACTOR, it + EVERYDAY_MIN_GAP_G) } ?: 0f
        val threshold = roundToTenth(
            min(max(max(fromChops, aboveEveryday), ChopRecorder.FLOOR_G), ceiling)
        ).coerceIn(ChopRecorder.FLOOR_G, MAX_THRESHOLD_G)

        val margin = when {
            everydayG == null -> Margin.UNKNOWN
            aboveEveryday <= fromChops -> Margin.PLENTY
            aboveEveryday <= ceiling -> Margin.TIGHT
            else -> Margin.OVERLAP
        }

        // Rhythm: the gaps between chops inside each gesture.
        val gaps = gestures.flatMap { g -> g.zipWithNext { a, b -> b.timeMs - a.timeMs } }
        var slop = current.slopDelayMs
        var reset = current.resetTimeoutMs
        if (gaps.isNotEmpty()) {
            slop = roundTo50(gaps.min() * 0.7f).coerceIn(MIN_SLOP_MS, MAX_SLOP_MS)
            reset = roundTo50(gaps.max() * 1.4f).coerceIn(slop + 200, MAX_RESET_MS)
        }

        return Result(
            config = current.copy(
                gForceThreshold = threshold,
                slopDelayMs = slop,
                resetTimeoutMs = reset,
                calibratedAtMs = nowMs
            ),
            weakestChopG = weakest,
            strongestChopG = strongest,
            everydayG = everydayG,
            margin = margin
        )
    }

    private fun roundToTenth(g: Float) = (g * 10).roundToInt() / 10f

    private fun roundTo50(ms: Float) = ((ms / 50f).roundToInt() * 50).toLong()
}
