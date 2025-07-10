package com.example.microqr.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.camera.core.ImageProxy // Not directly used in this version's logic
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.text
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.microqr.R
import com.example.microqr.databinding.FragmentReaderBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import boofcv.abst.fiducial.MicroQrCodeDetector
// import boofcv.alg.fiducial.microqr.MicroQrCode // Not directly used
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.ConfigMicroQrCode
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8

class ReaderFragment : Fragment() {

    companion object {
        private const val TAG = "ReaderFragment"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private var _binding: FragmentReaderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReaderViewModel by viewModels()

    private lateinit var barcodeScanner: BarcodeScanner // For regular QR codes
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraController: LifecycleCameraController
    private lateinit var handler: Handler

    private var microQrRunnable: Runnable? = null
    private var scanLineAnimator: ObjectAnimator? = null

    // Flash related variables
    private var isFlashOn = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkMeterCheckContext()

        handler = Handler(Looper.getMainLooper())
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupObservers()
        setupFlashToggle()
        startScanLineAnimation()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ✅ ADD: Method to handle MeterCheck context
    private fun checkMeterCheckContext() {
        val scanContext = arguments?.getString("scanContext")
        val targetSerial = arguments?.getString("targetSerial")
        val targetLocation = arguments?.getString("targetLocation")
        val meterNumber = arguments?.getString("meterNumber")

        Log.d(TAG, "🎯 ReaderFragment Context Check:")
        Log.d(TAG, "  - scanContext: $scanContext")
        Log.d(TAG, "  - targetSerial: $targetSerial")
        Log.d(TAG, "  - targetLocation: $targetLocation")
        Log.d(TAG, "  - meterNumber: $meterNumber")

        if (scanContext == "METER_CHECK") {
            Log.d(TAG, "✅ MeterCheck context detected - ready for targeted scanning")
            // Optionally update UI to show meter check mode
            updateUIForMeterCheckMode(targetSerial, targetLocation, meterNumber)
        }
    }

    // ✅ ADD: Optional method to update UI for MeterCheck mode
    private fun updateUIForMeterCheckMode(targetSerial: String?, targetLocation: String?, meterNumber: String?) {
        // Update scan result text to show we're in MeterCheck mode
        if (targetSerial != null) {
            // You can use this to update any UI elements to show the target meter info
            val meterInfo = "Scanning for: #$meterNumber\nSerial: $targetSerial\nLocation: $targetLocation"
            Log.d(TAG, "📋 MeterCheck Mode: $meterInfo")

            // If you have a TextView for showing scan instructions, you could update it:
            // binding.scanInstructionText?.text = "Scan QR code for meter #$meterNumber"
        }
    }

    // ✅ ADD: Public method for MeterCheckFragment to call if needed
    fun handleMeterCheckContext(bundle: Bundle) {
        arguments = bundle
        checkMeterCheckContext()
    }

    private fun setupFlashToggle() {
        binding.btnToggleFlash.setOnClickListener {
            toggleFlashlight()
        }
    }

    private fun toggleFlashlight() {
        if (::cameraController.isInitialized) {
            val cameraInfo = cameraController.cameraInfo
            if (cameraInfo != null && cameraInfo.hasFlashUnit()) {
                val currentTorchState = cameraInfo.torchState.value
                val newTorchState = currentTorchState != TorchState.ON

                cameraController.cameraControl?.enableTorch(newTorchState)
                isFlashOn = newTorchState
                updateFlashButtonIcon()

                Log.d(TAG, "Flash toggled: ${if (isFlashOn) "ON" else "OFF"}")
            } else {
                Toast.makeText(requireContext(), "Flash not available", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Device doesn't have flash unit")
            }
        } else {
            Toast.makeText(requireContext(), "Camera not ready", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Camera controller not initialized")
        }
    }

    private fun updateFlashButtonIcon() {
        val iconRes = if (isFlashOn) {
            R.drawable.ic_flash_on  // You'll need this icon
        } else {
            R.drawable.ic_flash_off
        }
        binding.btnToggleFlash.setImageResource(iconRes)

        // Optional: Update content description for accessibility
        val contentDescription = if (isFlashOn) {
            getString(R.string.turn_flash_off) // You'll need this string resource
        } else {
            getString(R.string.turn_flash_on)  // You'll need this string resource
        }
        binding.btnToggleFlash.contentDescription = contentDescription
    }

    private fun observeTorchState() {
        if (::cameraController.isInitialized) {
            cameraController.cameraInfo?.torchState?.observe(viewLifecycleOwner) { torchState ->
                isFlashOn = torchState == TorchState.ON
                updateFlashButtonIcon()
            }
        }
    }

    private fun startScanLineAnimation() {
        _binding?.let { binding ->
            binding.viewFinder.post {
                val scanFrameHeight = binding.scanFrameContainer.height
                if (scanFrameHeight > 0) {
                    scanLineAnimator = ObjectAnimator.ofFloat(
                        binding.scanLine,
                        "translationY",
                        0f,
                        scanFrameHeight.toFloat()
                    ).apply {
                        duration = 2000
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        start()
                    }
                }
            }
        }
    }

    private fun stopScanLineAnimation() {
        scanLineAnimator?.cancel()
        scanLineAnimator = null
        _binding?.scanLine?.visibility = View.GONE
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.navigationTrigger.collect { shouldNavigate ->
                if (shouldNavigate && isAdded && _binding != null) {
                    viewModel.rawQrDataToNavigate.value?.let { rawData ->
                        Log.d(TAG, "Navigating to DetectedFragment with Raw QR Data: $rawData")
                        stopScanLineAnimation() // Stop animation
                        // shutdownCamera() // Optionally shutdown camera fully before navigating

                        // ✅ FIXED: Get scan context from fragment arguments and forward it
                        val scanContext = arguments?.getString("scanContext")
                        Log.d(TAG, "🔍 ReaderFragment scanContext: $scanContext")

                        val bundle = Bundle().apply {
                            putString("rawQrValue", rawData) // Pass the raw QR value

                            // ✅ FORWARD SCAN CONTEXT: Pass through all MeterCheck arguments
                            scanContext?.let {
                                putString("scanContext", it)
                                Log.d(TAG, "✅ Forwarding scanContext: $it")
                            }

                            // ✅ FORWARD METER CHECK DATA: Pass through all meter check arguments
                            arguments?.getString("targetSerial")?.let {
                                putString("targetSerial", it)
                                Log.d(TAG, "✅ Forwarding targetSerial: $it")
                            }
                            arguments?.getString("targetLocation")?.let {
                                putString("targetLocation", it)
                                Log.d(TAG, "✅ Forwarding targetLocation: $it")
                            }
                            arguments?.getString("meterNumber")?.let {
                                putString("meterNumber", it)
                                Log.d(TAG, "✅ Forwarding meterNumber: $it")
                            }
                        }

                        Log.d(TAG, "📦 Final navigation bundle: scanContext=$scanContext, targetSerial=${arguments?.getString("targetSerial")}")

                        findNavController().navigate(
                            R.id.action_ReaderFragment_to_DetectedFragment,
                            bundle
                        )
                        viewModel.resetNavigationTrigger() // Reset trigger after navigation
                        // viewModel.stopScanning() // ViewModel might do this, or do it here
                    } ?: run {
                        Log.e(TAG, "Navigation triggered but rawQrDataToNavigate is null!")
                        viewModel.resetNavigationTrigger() // Reset to avoid potential loops
                    }
                } else {
                    Log.d(TAG, "Navigation triggered but fragment not in a state to navigate.")
                    // Reset trigger if navigation was attempted while fragment was not ready
                    if (shouldNavigate) viewModel.resetNavigationTrigger()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isScanning.collect { isScanning ->
                _binding?.scanLine?.visibility = if (isScanning) View.VISIBLE else View.GONE
            }
        }
    }

    private fun startCamera() {
        if (!isAdded || _binding == null) {
            Log.w(TAG, "startCamera called but fragment not attached or binding null.")
            return
        }
        viewModel.startScanning() // Indicate scanning has started

        cameraController = LifecycleCameraController(requireContext())

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE) // For regular QR codes
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        val analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
            ContextCompat.getMainExecutor(requireContext()) // Use main executor for UI updates from analyzer
        ) { result ->
            if (!viewModel.isReadyToNavigate()) { // Only process if not already navigated/ready
                val barcodeResults = result.getValue(barcodeScanner)
                if (!barcodeResults.isNullOrEmpty()) {
                    val barcode = barcodeResults[0]
                    barcode.rawValue?.let { rawValue ->
                        Log.i(TAG, "Regular QR Detected (ML Kit): $rawValue")
                        viewModel.setRawQrDataForNavigation(rawValue)

                        // Optional: Parse for display or other logic in ReaderFragment
                        // handleRegularQrParsing(rawValue, barcode) // Extracted parsing logic

                        // Stop MicroQR detection if it was running and regular QR is found
                        microQrRunnable?.let { handler.removeCallbacks(it) }
                        microQrRunnable = null

                        viewModel.stopScanning() // Stop further scanning attempts
                        viewModel.triggerNavigation() // Attempt to navigate
                    }
                    // Update overlay for regular QR
                    _binding?.let {
                        val qrCodeViewModel = QrCodeViewModel(barcode)
                        val qrCodeDrawable = QrCodeDrawable(qrCodeViewModel)
                        it.viewFinder.overlay.clear()
                        it.viewFinder.overlay.add(qrCodeDrawable)
                    }
                } else {
                    // No regular QR code found by ML Kit in this frame
                    _binding?.viewFinder?.overlay?.clear() // Clear overlay if no QR
                    // If no regular QR found and not already processing MicroQR, start MicroQR detection.
                    // Also ensure we are not already about to navigate.
                    if (microQrRunnable == null && !viewModel.isReadyToNavigate()) {
                        startMicroQrDetection()
                    }
                }
            }
        }

        cameraController.setImageAnalysisAnalyzer(cameraExecutor, analyzer) // Run analysis on background thread

        // Check if view is still available before binding
        if (_binding != null) {
            cameraController.bindToLifecycle(this)
            binding.viewFinder.controller = cameraController

            // Observe torch state changes after binding
            observeTorchState()

        } else {
            Log.w(TAG, "Binding was null when trying to bind camera controller.")
            shutdownCamera() // Clean up if binding failed
            return
        }

        // Fallback: If MLKit doesn't pick anything up quickly, and we are not already navigating,
        // ensure MicroQR detection starts.
        handler.postDelayed({
            if (isAdded && !viewModel.isReadyToNavigate() && microQrRunnable == null) {
                Log.d(TAG, "Fallback: Starting MicroQR detection as no regular QR found yet.")
                startMicroQrDetection()
            }
        }, 1500) // Delay to give MLKit a chance first
    }

    // Optional: If you still want to parse regular QR for display in ReaderFragment
    private fun handleRegularQrParsing(rawValue: String, barcode: Barcode) {
        // Your existing logic from old handleBarcodeDetection for parsing F6, F7, FMCBLE
        // For example:
        // if (rawValue.contains("F6") || rawValue.contains("F7")) {
        //     val serialId = "${rawValue.substring(7, 8)}${rawValue.substring(3, 5)}${rawValue.substring(8, 15)}"
        //     viewModel.setRegularSerialId(serialId) // If you still use this for display
        //     Toast.makeText(requireContext(), "Regular Serial ID (for display): $serialId", Toast.LENGTH_SHORT).show()
        // } else if (rawValue.contains("FMCBLE")) {
        //     val address = rawValue.substring(7, 24)
        //     viewModel.setAddress(address) // If you still use this for display
        //     Toast.makeText(requireContext(), "Regular Address (for display): $address", Toast.LENGTH_SHORT).show()
        // }
        // Note: The primary action is now viewModel.setRawQrDataForNavigation(rawValue)
    }

    private fun startMicroQrDetection() {
        if (!isAdded || _binding == null || viewModel.isReadyToNavigate()) {
            Log.d(TAG, "Skipping Micro QR detection: Fragment not ready, or already navigating.")
            return
        }

        Log.d(TAG, "Starting Micro QR detection attempts.")
        _binding?.viewFinder?.overlay?.clear() // Clear any regular QR overlay

        microQrRunnable?.let { handler.removeCallbacks(it) } // Remove previous if any
        microQrRunnable = object : Runnable {
            override fun run() {
                if (!isAdded || _binding == null || viewModel.isReadyToNavigate()) {
                    Log.d(TAG, "Micro QR Runnable: Stopping - Fragment not ready or already navigating.")
                    microQrRunnable = null // Clear self
                    return
                }

                val bitmap = binding.viewFinder.bitmap
                if (bitmap != null) {
                    val detectedRawValue = detectMicroQrAndGetRawValue(bitmap)
                    if (detectedRawValue != null) {
                        Log.i(TAG, "Micro QR Detected (BoofCV): $detectedRawValue")

                        Toast.makeText(requireContext(), "Micro QR: $detectedRawValue", Toast.LENGTH_LONG).show()

                        viewModel.setRawQrDataForNavigation(detectedRawValue)
                        viewModel.stopScanning()
                        viewModel.triggerNavigation()
                        microQrRunnable = null // Clear self as we found it
                        return // Exit runnable
                    }
                } else {
                    Log.w(TAG, "Micro QR Runnable: Viewfinder bitmap was null.")
                }
                // If not found and still not ready to navigate, retry
                if (isAdded && !viewModel.isReadyToNavigate()) {
                    handler.postDelayed(this, 300) // Retry interval for micro QR
                } else {
                    microQrRunnable = null // Clear self if conditions changed
                }
            }
        }
        handler.post(microQrRunnable!!)
    }

    private fun detectMicroQrAndGetRawValue(bitmap: Bitmap): String? {
        try {
            // It's good practice to make a copy if the bitmap might be recycled
            val immutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val grayImage = ConvertBitmap.bitmapToGray(immutableBitmap, null as GrayU8?, null)
            val config = ConfigMicroQrCode()
            // config.maxErrors = 1 // Example: Adjust detector sensitivity if needed
            val detector: MicroQrCodeDetector<GrayU8> = FactoryFiducial.microqr(config, GrayU8::class.java)

            detector.process(grayImage)
            val detections = detector.detections

            if (detections.isNotEmpty()) {
                val qr = detections[0]
                val message = qr.message
                Log.d(TAG, "Micro QR raw message from BoofCV: $message")
                return message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting micro QR code with BoofCV", e)
        } finally {
            // If you made a copy: immutableBitmap.recycle() // Though GC usually handles this
        }
        return null
    }

    private fun shutdownCamera() {
        Log.d(TAG, "Shutting down camera.")
        viewModel.stopScanning() // Ensure scanning state is updated
        microQrRunnable?.let { handler.removeCallbacks(it) }
        microQrRunnable = null

        // Turn off flash when shutting down camera
        if (isFlashOn && ::cameraController.isInitialized) {
            cameraController.cameraControl?.enableTorch(false)
            isFlashOn = false
            updateFlashButtonIcon()
        }

        if (::cameraController.isInitialized) {
            // It's generally safe to call unbind().
            // If you need to ensure it's done on the main thread:
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cameraController.unbind()
            } else {
                handler.post {
                    cameraController.unbind()
                }
            }
        }

        if (::barcodeScanner.isInitialized) {
            try {
                barcodeScanner.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing barcode scanner", e)
            }
        }
        // cameraExecutor is shut down in onDestroyView normally
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        super.onPause()
        // If you want to stop scanning aggressively when fragment is paused
        // And not just when navigating away permanently
        if (viewModel.isScanning.value && !viewModel.isReadyToNavigate()) {
            Log.d(TAG, "onPause: Stopping scanning and MicroQR detection.")
            shutdownCamera() // This will also stop micro QR runnable
        }
        stopScanLineAnimation() // Stop animation when paused
    }

    override fun onResume() {
        super.onResume()
        // If permissions are granted, and we aren't already trying to navigate, restart camera
        if (allPermissionsGranted() && !viewModel.isReadyToNavigate() && !viewModel.navigationTrigger.value) {
            Log.d(TAG, "onResume: Restarting camera.")
            // viewModel.resetAllData() // Consider if a full reset is needed on resume
            startCamera()
        }
        // Restart animation if it was stopped
        if (scanLineAnimator == null && _binding?.scanLine?.visibility == View.VISIBLE) {
            startScanLineAnimation()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView called.")
        shutdownCamera() // Ensure camera and related resources are released
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        stopScanLineAnimation()
        _binding = null // Crucial to prevent memory leaks
    }
}