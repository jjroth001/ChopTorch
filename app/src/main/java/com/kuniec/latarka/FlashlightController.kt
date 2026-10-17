package com.kuniec.latarka

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Controller class for managing the device's flashlight state.
 *
 * Uses [CameraManager.setTorchMode], which needs no camera permission and does not open the
 * camera. The on/off state is tracked through a [CameraManager.TorchCallback], so it stays in
 * sync when the torch is switched from Quick Settings or another app, and it recovers on its
 * own after the camera app releases the flash.
 *
 * Call [release] when finished to unregister the callback.
 *
 * @property context The context used to access the camera service.
 */
class FlashlightController(private val context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Camera whose flash unit we control; resolved lazily and re-resolved on failure. */
    private var torchCameraId: String? = null

    @Volatile private var isFlashlightOn = false
    @Volatile private var isTorchAvailable = true

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == resolveTorchCameraId()) {
                isFlashlightOn = enabled
                isTorchAvailable = true
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == resolveTorchCameraId()) {
                // Another app (usually the camera) is holding the flash. It becomes
                // available again automatically; no restart needed.
                isTorchAvailable = false
                isFlashlightOn = false
            }
        }
    }

    init {
        cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
    }

    /**
     * Toggles the flashlight state between on and off.
     */
    fun toggleFlashlight() {
        setTorch(!isFlashlightOn)
    }

    /**
     * Explicitly turns off the flashlight if it is currently on.
     */
    fun turnOff() {
        if (isFlashlightOn) setTorch(false)
    }

    /**
     * Unregisters the torch callback.
     */
    fun release() {
        cameraManager.unregisterTorchCallback(torchCallback)
    }

    private fun setTorch(on: Boolean) {
        val cameraId = resolveTorchCameraId() ?: run {
            Log.w(TAG, "No camera with a flash unit found")
            return
        }
        if (!isTorchAvailable) {
            Log.w(TAG, "Torch is currently in use by another app")
            return
        }
        try {
            cameraManager.setTorchMode(cameraId, on)
            isFlashlightOn = on
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode", e)
            // Camera list can change (e.g. external cameras); look it up again next time.
            torchCameraId = null
        }
    }

    /**
     * Finds a camera that actually has a flash, preferring the back-facing one.
     */
    private fun resolveTorchCameraId(): String? {
        torchCameraId?.let { return it }
        return try {
            val withFlash = cameraManager.cameraIdList.filter { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            val back = withFlash.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            (back ?: withFlash.firstOrNull()).also { torchCameraId = it }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing cameras", e)
            null
        }
    }

    private companion object {
        const val TAG = "FlashlightController"
    }
}
