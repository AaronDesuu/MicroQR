package com.example.microqr.ui.reader // Or your appropriate package

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * Toggles the flashlight of the given camera.
 *
 * @param camera The Camera object whose flash needs to be toggled.
 * @param executor The executor to run the flashlight toggle operation.
 * @return True if the flashlight was successfully enabled, false if it was disabled or an error occurred.
 */
fun toggleFlashlight(camera: Camera?, executor: Executor): Boolean {
    camera?.let {
        val cameraControl: CameraControl = it.cameraControl
        val cameraInfo: CameraInfo = it.cameraInfo
        return if (cameraInfo.hasFlashUnit()) {
            val isFlashOn = cameraInfo.torchState.value == androidx.camera.core.TorchState.ON
            val future: ListenableFuture<Void> = cameraControl.enableTorch(!isFlashOn)
            try {
                future.get() // Wait for the operation to complete
                !isFlashOn // Return the new state
            } catch (e: InterruptedException) {
                // Handle error
                e.printStackTrace()
                false
            } catch (e: ExecutionException) {
                // Handle error
                e.printStackTrace()
                false
            }
        } else {
            // Device does not have a flash unit
            false
        }
    }
    return false // Camera is null
}