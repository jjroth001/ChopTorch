package com.kuniec.latarka

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure logic class for detecting a Motorola-style "chop-chop" gesture from accelerometer data.
 *
 * A single chop is a sharp swing followed by a sharp stop or snap back. On the accelerometer
 * that shows up as a strong spike on one axis followed, shortly after, by a strong spike in the
 * opposite direction on the same axis. Generic bumps (pocket, walking, setting the phone down)
 * rarely produce that reversal on a single dominant axis.
 *
 * The gesture fires when [FlashlightConfig.shakeCount] chops happen in a row, each separated by
 * at least [FlashlightConfig.slopDelayMs] (so the two reversals inside one chop count once) and
 * at most [FlashlightConfig.resetTimeoutMs].
 *
 * All timing uses the timestamp passed in, which should come from SensorEvent.timestamp rather
 * than the wall clock, so batched events delivered in a burst keep their real spacing.
 *
 * This class is independent of Android Framework classes except for constants,
 * making it easily unit-testable.
 */
class ShakeDetector(private var config: FlashlightConfig) {

    internal companion object {
        /** Low-pass time constant for the gravity estimate, in seconds. */
        const val GRAVITY_TAU_S = 0.25f

        /** A gap in sensor data longer than this re-seeds the gravity estimate. */
        const val RESEED_GAP_MS = 1000L

        /** The opposite-direction spike must follow the first spike within this window. */
        const val REVERSAL_WINDOW_MS = 300L

        /** The spiking axis must carry at least this share of the total linear acceleration. */
        const val DOMINANCE_RATIO = 0.6f

        /** Total acceleration below this (in g) is treated as free fall. */
        const val FREE_FALL_G = 0.3f

        /** Ignore chops for this long after a free fall. */
        const val DROP_IGNORE_MS = 1500L
    }

    private var lastSampleTime: Long = -1
    private var lastChopTime: Long = Long.MIN_VALUE / 2
    private var lastToggleTime: Long = Long.MIN_VALUE / 2
    private var lastDropTime: Long = Long.MIN_VALUE / 2
    private var chopCount = 0

    // Gravity estimate (low-pass); linear acceleration is raw minus gravity.
    private val gravity = FloatArray(3)

    // Per axis: last time a strong positive / negative spike was seen.
    private val lastPositiveSpike = LongArray(3) { Long.MIN_VALUE / 2 }
    private val lastNegativeSpike = LongArray(3) { Long.MIN_VALUE / 2 }

    /**
     * Updates the configuration used by the detector.
     */
    fun updateConfig(newConfig: FlashlightConfig) {
        config = newConfig
    }

    /**
     * Processes raw accelerometer values and determines if a flashlight toggle should occur.
     *
     * @param x Raw X-axis acceleration (m/s²)
     * @param y Raw Y-axis acceleration (m/s²)
     * @param z Raw Z-axis acceleration (m/s²)
     * @param currentTime Sensor timestamp in milliseconds
     * @return true if the chop gesture was completed and a toggle should occur, false otherwise.
     */
    fun onSensorChanged(x: Float, y: Float, z: Float, currentTime: Long): Boolean {
        val raw = floatArrayOf(x, y, z)

        // 1. Free-fall detection
        if (config.isDropPreventionEnabled) {
            val totalRawG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (totalRawG < FREE_FALL_G) {
                lastDropTime = currentTime
            }
        }

        // 2. Gravity estimate. Seed it from the first sample (or after a long gap) so the first
        //    readings are not mistaken for a huge shake.
        val dtMs = if (lastSampleTime < 0) Long.MAX_VALUE else currentTime - lastSampleTime
        lastSampleTime = currentTime
        if (dtMs > RESEED_GAP_MS || dtMs < 0) {
            raw.copyInto(gravity)
            return false
        }
        val dt = dtMs / 1000f
        val alpha = GRAVITY_TAU_S / (GRAVITY_TAU_S + dt)
        for (i in 0..2) {
            gravity[i] = alpha * gravity[i] + (1 - alpha) * raw[i]
        }

        val linear = FloatArray(3) { raw[it] - gravity[it] }
        val linearTotal = sqrt(linear[0] * linear[0] + linear[1] * linear[1] + linear[2] * linear[2])
        val threshold = config.gForceThreshold * SensorManager.GRAVITY_EARTH

        // Ignore everything during the post-toggle cooldown and shortly after a drop.
        if (currentTime - lastToggleTime < config.cooldownMs) return false
        if (config.isDropPreventionEnabled && currentTime - lastDropTime < DROP_IGNORE_MS) return false

        // A chop sequence that went quiet for too long starts over.
        if (chopCount > 0 && currentTime - lastChopTime > config.resetTimeoutMs) {
            chopCount = 0
        }

        // 3. Look for a direction reversal on a dominant axis.
        for (axis in 0..2) {
            val value = linear[axis]
            if (abs(value) < threshold) continue
            if (abs(value) < DOMINANCE_RATIO * linearTotal) continue

            val reversed = if (value > 0) {
                lastPositiveSpike[axis] = currentTime
                currentTime - lastNegativeSpike[axis] <= REVERSAL_WINDOW_MS
            } else {
                lastNegativeSpike[axis] = currentTime
                currentTime - lastPositiveSpike[axis] <= REVERSAL_WINDOW_MS
            }
            if (!reversed) continue

            // Both reversals inside a single chop collapse into one count.
            if (currentTime - lastChopTime < config.slopDelayMs) {
                clearSpikes()
                return false
            }

            chopCount++
            lastChopTime = currentTime
            clearSpikes()

            if (chopCount >= config.shakeCount) {
                chopCount = 0
                lastToggleTime = currentTime
                return true
            }
            return false
        }
        return false
    }

    private fun clearSpikes() {
        lastPositiveSpike.fill(Long.MIN_VALUE / 2)
        lastNegativeSpike.fill(Long.MIN_VALUE / 2)
    }
}
