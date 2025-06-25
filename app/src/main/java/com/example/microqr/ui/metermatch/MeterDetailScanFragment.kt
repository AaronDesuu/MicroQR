package com.example.microqr.ui.metermatch

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
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.microqr.R
import com.example.microqr.databinding.FragmentMeterDetailScanBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.ui.reader.QrCodeDrawable
import com.example.microqr.ui.reader.QrCodeViewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import boofcv.abst.fiducial.MicroQrCodeDetector
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.ConfigMicroQrCode
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MeterDetailScanFragment : Fragment() {

    companion object {
        private const val TAG = "MeterDetailScanFragment"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val ARG_METER_SERIAL = "meter_serial"
        private const val ARG_METER_NUMBER = "meter_number"
        private const val ARG_METER_PLACE = "meter_place"
        private const val ARG_FROM_FILE = "from_file"

        fun createBundle(meter: MeterStatus): Bundle {
            return Bundle().apply {
                putString(ARG_METER_SERIAL, meter.serialNumber)
                putString(ARG_METER_NUMBER, meter.number)
                putString(ARG_METER_PLACE, meter.place)
                putString(ARG_FROM_FILE, meter.fromFile)
            }
        }
    }

    private var _binding: FragmentMeterDetailScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MeterDetailScanViewModel by viewModels()
    private val filesViewModel: FilesViewModel by activityViewModels()

    // Camera-related variables - properly managed lifecycle
    private var barcodeScanner: BarcodeScanner? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraController: LifecycleCameraController? = null
    private var mlKitAnalyzer: MlKitAnalyzer? = null
    private lateinit var handler: Handler

    private var microQrRunnable: Runnable? = null
    private var scanLineAnimator: ObjectAnimator? = null
    private var isFlashOn = false
    private var isCameraInitialized = false

    // Meter info from arguments
    private lateinit var targetMeter: MeterStatus

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract meter info from arguments
        arguments?.let { bundle ->
            val serialNumber = bundle.getString(ARG_METER_SERIAL) ?: ""
            val number = bundle.getString(ARG_METER_NUMBER) ?: ""
            val place = bundle.getString(ARG_METER_PLACE) ?: ""
            val fromFile = bundle.getString(ARG_FROM_FILE) ?: ""

            targetMeter = MeterStatus(
                number = number,
                serialNumber = serialNumber,
                place = place,
                registered = false, // Will be updated from actual data
                fromFile = fromFile,
                isChecked = false // Will be updated from actual data
            )

            viewModel.setTargetMeter(targetMeter)
        } ?: run {
            // No arguments provided, navigate back
            findNavController().navigateUp()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeterDetailScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        handler = Handler(Looper.getMainLooper())

        // Initialize camera executor here
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupMeterInfo()
        setupObservers()
        setupFlashToggle()
        setupBackButton()
        startScanLineAnimation()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupMeterInfo() {
        binding.meterNumberText.text = "Meter: ${targetMeter.number}"
        binding.targetSerialText.text = "Target S/N: ${targetMeter.serialNumber}"
        binding.meterPlaceText.text = "Location: ${targetMeter.place}"
        binding.instructionText.text = "Scan the QR code on meter ${targetMeter.number}\nto verify it matches the expected serial number"
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupFlashToggle() {
        binding.btnToggleFlash.setOnClickListener {
            toggleFlashlight()
        }
    }

    private fun toggleFlashlight() {
        cameraController?.let { controller ->
            val cameraInfo = controller.cameraInfo
            if (cameraInfo != null && cameraInfo.hasFlashUnit()) {
                val currentTorchState = cameraInfo.torchState.value
                val newTorchState = currentTorchState != TorchState.ON

                controller.cameraControl?.enableTorch(newTorchState)
                isFlashOn = newTorchState
                updateFlashButtonIcon()

                Log.d(TAG, "Flash toggled: ${if (isFlashOn) "ON" else "OFF"}")
            } else {
                Toast.makeText(requireContext(), "Flash not available", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(requireContext(), "Camera not ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlashButtonIcon() {
        val iconRes = if (isFlashOn) {
            R.drawable.ic_flash_on
        } else {
            R.drawable.ic_flash_off
        }
        binding.btnToggleFlash.setImageResource(iconRes)
    }

    private fun observeTorchState() {
        cameraController?.let { controller ->
            controller.cameraInfo?.torchState?.observe(viewLifecycleOwner) { torchState ->
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
            viewModel.scanResult.collect { result ->
                _binding?.statusText?.text = result
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanSuccess.collect { success ->
                if (success) {
                    handleScanSuccess()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isScanning.collect { isScanning ->
                _binding?.let { binding ->
                    binding.scanLine.visibility = if (isScanning) View.VISIBLE else View.GONE
                    binding.progressBar.visibility = if (isScanning) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun handleScanSuccess() {
        stopScanLineAnimation()
        shutdownCamera()

        // Show success and navigate back
        Toast.makeText(requireContext(), getString(R.string.meter_marked_scanned_toast), Toast.LENGTH_LONG)
        // Delay navigation to allow user to see the success message
        handler.postDelayed({
            if (isAdded && _binding != null) {
                findNavController().navigateUp()
            }
        }, 1500)
    }

    private fun startCamera() {
        if (!isAdded || _binding == null || isCameraInitialized) {
            Log.w(TAG, "startCamera called but conditions not met. isAdded: $isAdded, binding: ${_binding != null}, initialized: $isCameraInitialized")
            return
        }

        try {
            viewModel.startScanning()

            // Create new instances each time
            val newCameraController = LifecycleCameraController(requireContext())
            cameraController = newCameraController

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

            // Create new scanner instance
            barcodeScanner?.close() // Close previous if exists
            barcodeScanner = BarcodeScanning.getClient(options)

            val analyzer = MlKitAnalyzer(
                listOf(barcodeScanner!!),
                CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(requireContext())
            ) { result ->
                // Add safety check
                if (!isAdded || _binding == null || !viewModel.isScanning.value) {
                    return@MlKitAnalyzer
                }

                try {
                    val barcodeResults = result.getValue(barcodeScanner!!)
                    if (!barcodeResults.isNullOrEmpty()) {
                        val barcode = barcodeResults[0]
                        barcode.rawValue?.let { rawValue ->
                            Log.i(TAG, "Regular QR Detected: $rawValue")
                            handleQrCodeDetected(rawValue)

                            // Stop MicroQR detection
                            microQrRunnable?.let { handler.removeCallbacks(it) }
                            microQrRunnable = null
                        }

                        // Update overlay
                        _binding?.let { binding ->
                            try {
                                val qrCodeViewModel = QrCodeViewModel(barcode)
                                val qrCodeDrawable = QrCodeDrawable(qrCodeViewModel)
                                binding.viewFinder.overlay.clear()
                                binding.viewFinder.overlay.add(qrCodeDrawable)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error updating overlay: ${e.message}")
                            }
                        }
                    } else {
                        _binding?.viewFinder?.overlay?.clear()
                        if (microQrRunnable == null && viewModel.isScanning.value && isAdded) {
                            startMicroQrDetection()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ML Kit analyzer: ${e.message}")
                    // Continue without crashing
                }
            }

            mlKitAnalyzer = analyzer
            cameraExecutor?.let { executor ->
                newCameraController.setImageAnalysisAnalyzer(executor, analyzer)
            }

            _binding?.let { binding ->
                newCameraController.bindToLifecycle(this)
                binding.viewFinder.controller = newCameraController
                observeTorchState()
                isCameraInitialized = true
                Log.d(TAG, "Camera initialized successfully")
            }

            // Fallback for MicroQR
            handler.postDelayed({
                if (isAdded && viewModel.isScanning.value && microQrRunnable == null) {
                    startMicroQrDetection()
                }
            }, 1500)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera: ${e.message}")
            _binding?.statusText?.text = "Camera initialization failed"
            isCameraInitialized = false
        }
    }

    private fun startMicroQrDetection() {
        if (!isAdded || _binding == null || !viewModel.isScanning.value) {
            return
        }

        microQrRunnable?.let { handler.removeCallbacks(it) }
        microQrRunnable = object : Runnable {
            override fun run() {
                if (!isAdded || _binding == null || !viewModel.isScanning.value) {
                    microQrRunnable = null
                    return
                }

                try {
                    val bitmap = binding.viewFinder.bitmap
                    if (bitmap != null) {
                        val detectedRawValue = detectMicroQrAndGetRawValue(bitmap)
                        if (detectedRawValue != null) {
                            Log.i(TAG, "Micro QR Detected: $detectedRawValue")
                            handleQrCodeDetected(detectedRawValue)
                            microQrRunnable = null
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in micro QR detection: ${e.message}")
                }

                if (isAdded && viewModel.isScanning.value) {
                    handler.postDelayed(this, 300)
                } else {
                    microQrRunnable = null
                }
            }
        }
        handler.post(microQrRunnable!!)
    }

    private fun detectMicroQrAndGetRawValue(bitmap: Bitmap): String? {
        return try {
            val immutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val grayImage = ConvertBitmap.bitmapToGray(immutableBitmap, null as GrayU8?, null)
            val config = ConfigMicroQrCode()
            val detector: MicroQrCodeDetector<GrayU8> = FactoryFiducial.microqr(config, GrayU8::class.java)

            detector.process(grayImage)
            val detections = detector.detections

            if (detections.isNotEmpty()) {
                val qr = detections[0]
                qr.message
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting micro QR code: ${e.message}")
            null
        }
    }

    private fun handleQrCodeDetected(rawValue: String) {
        if (!viewModel.isScanning.value) return

        viewModel.stopScanning()

        // Check if scanned QR matches target meter
        val scannedSerial = extractSerialFromQr(rawValue)
        val targetSerial = targetMeter.serialNumber

        if (scannedSerial == targetSerial) {
            // Perfect match
            viewModel.setSuccessResult("✅ Perfect Match!\nScanned: $scannedSerial\nExpected: $targetSerial")

            // Update the meter as scanned in the database
            filesViewModel.updateMeterCheckedStatus(targetSerial, true, targetMeter.fromFile)
            viewModel.setScanSuccess(true)

        } else {
            // No match - check if serial exists elsewhere
            checkForAlternativeMatches(scannedSerial, rawValue)
        }
    }

    private fun extractSerialFromQr(rawValue: String): String {
        // Extract serial number from QR content (same logic as DetectedFragment)
        return rawValue.trim()
    }

    private fun checkForAlternativeMatches(scannedSerial: String, rawValue: String) {
        // Get all meters from FilesViewModel to check for alternative matches
        val allMeters = filesViewModel.meterStatusList.value ?: emptyList()
        val matchingMeters = allMeters.filter { it.serialNumber == scannedSerial }

        if (matchingMeters.isNotEmpty()) {
            showAlternativeMatchDialog(scannedSerial, matchingMeters)
        } else {
            showNoMatchDialog(scannedSerial)
        }
    }

    private fun showAlternativeMatchDialog(scannedSerial: String, matchingMeters: List<MeterStatus>) {
        val targetSerial = targetMeter.serialNumber
        val meterInfo = matchingMeters.joinToString("\n") {
            "• ${it.number} (${it.place}) from ${it.fromFile}"
        }

        val message = getString(R.string.no_match_dialog_message, targetSerial, scannedSerial)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.serial_mismatch))            .setMessage(message)
            .setPositiveButton(getString(R.string.choose_alternative)) { _, _ ->                showAlternativeMeterSelection(scannedSerial, matchingMeters)
            }
            .setNegativeButton(getString(R.string.scan_again_button)) { _, _ ->                restartScanning()
            }
            .setNeutralButton(getString(R.string.cancel)) { _, _ ->                findNavController().navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    private fun showAlternativeMeterSelection(scannedSerial: String, matchingMeters: List<MeterStatus>) {
        val meterDescriptions = matchingMeters.map {
            "${it.number} - ${it.place} (${it.fromFile})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_meter_to_mark))
            .setItems(meterDescriptions) { _, which ->
                val selectedMeter = matchingMeters[which]

                // Update the selected meter as scanned
                filesViewModel.updateMeterCheckedStatus(
                    selectedMeter.serialNumber,
                    true,
                    selectedMeter.fromFile
                )

                Toast.makeText(requireContext(), getString(R.string.meter_marked_scanned_toast, selectedMeter.number), Toast.LENGTH_LONG)

                // Navigate back
                handler.postDelayed({
                    if (isAdded) {
                        findNavController().navigateUp()
                    }
                }, 1500)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                restartScanning()
            }
            .show()
    }

    private fun showNoMatchDialog(scannedSerial: String) {
        val targetSerial = targetMeter.serialNumber

        val message = getString(R.string.no_match_dialog_message, targetSerial, scannedSerial)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.serial_mismatch))
            .setMessage(message)
            .setNegativeButton(getString(R.string.scan_again_button)) { _, _ ->
                restartScanning()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                findNavController().navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    private fun restartScanning() {
        viewModel.resetScanState()
        // Shutdown camera completely before restarting
        shutdownCamera()

        // Small delay before restarting
        handler.postDelayed({
            if (allPermissionsGranted() && isAdded && _binding != null) {
                startCamera()
            }
        }, 500)
    }

    private fun shutdownCamera() {
        Log.d(TAG, "Shutting down camera")
        viewModel.stopScanning()

        // Stop micro QR detection
        microQrRunnable?.let { handler.removeCallbacks(it) }
        microQrRunnable = null

        // Turn off flash
        if (isFlashOn) {
            cameraController?.cameraControl?.enableTorch(false)
            isFlashOn = false
            updateFlashButtonIcon()
        }

        // Clear overlay
        _binding?.viewFinder?.overlay?.clear()

        // Properly close ML Kit analyzer and scanner
        try {
            mlKitAnalyzer = null
            barcodeScanner?.close()
            barcodeScanner = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing barcode scanner: ${e.message}")
        }

        // Unbind camera controller
        cameraController?.let { controller ->
            try {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    controller.unbind()
                } else {
                    handler.post {
                        controller.unbind()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding camera controller: ${e.message}")
            }
        }

        cameraController = null
        isCameraInitialized = false

        Log.d(TAG, "Camera shutdown complete")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        if (isCameraInitialized) {
            shutdownCamera()
        }
        stopScanLineAnimation()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        if (allPermissionsGranted() && !viewModel.scanSuccess.value && !isCameraInitialized) {
            // Small delay to ensure fragment is fully resumed
            handler.postDelayed({
                if (isAdded && _binding != null) {
                    startCamera()
                }
            }, 100)
        }

        if (scanLineAnimator == null && _binding?.scanLine?.visibility == View.VISIBLE) {
            startScanLineAnimation()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView called")

        shutdownCamera()
        stopScanLineAnimation()

        // Shutdown executor
        cameraExecutor?.let { executor ->
            if (!executor.isShutdown) {
                executor.shutdown()
            }
        }
        cameraExecutor = null

        _binding = null
        Log.d(TAG, "Fragment cleanup complete")
    }
}