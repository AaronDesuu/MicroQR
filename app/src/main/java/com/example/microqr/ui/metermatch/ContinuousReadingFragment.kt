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
import android.animation.AnimatorSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
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
import com.example.microqr.R
import com.example.microqr.databinding.FragmentContinuousReadingBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.ui.reader.QrCodeDrawable
import com.example.microqr.ui.reader.QrCodeViewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import boofcv.abst.fiducial.MicroQrCodeDetector
import boofcv.android.ConvertBitmap
import boofcv.factory.fiducial.ConfigMicroQrCode
import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8

class ContinuousReadingFragment : Fragment() {

    companion object {
        private const val TAG = "ContinuousReadingFragment"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val ARG_METER_SERIAL_NUMBERS = "meter_serial_numbers"
        private const val ARG_METER_NUMBERS = "meter_numbers"
        private const val ARG_METER_PLACES = "meter_places"
        private const val ARG_METER_FROM_FILES = "meter_from_files"

        fun createBundle(meters: List<MeterStatus>): Bundle {
            return Bundle().apply {
                putStringArrayList(ARG_METER_SERIAL_NUMBERS, ArrayList(meters.map { it.serialNumber }))
                putStringArrayList(ARG_METER_NUMBERS, ArrayList(meters.map { it.number }))
                putStringArrayList(ARG_METER_PLACES, ArrayList(meters.map { it.place }))
                putStringArrayList(ARG_METER_FROM_FILES, ArrayList(meters.map { it.fromFile }))
            }
        }
    }

    private var _binding: FragmentContinuousReadingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContinuousReadingViewModel by viewModels()
    private val filesViewModel: FilesViewModel by activityViewModels()

    // Camera-related variables
    private var barcodeScanner: BarcodeScanner? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraController: LifecycleCameraController? = null
    private var mlKitAnalyzer: MlKitAnalyzer? = null
    private lateinit var handler: Handler

    private var microQrRunnable: Runnable? = null
    private var scanLineAnimator: ObjectAnimator? = null
    private var isFlashOn = false
    private var isCameraInitialized = false

    // Meter list from arguments
    private lateinit var meterList: List<MeterStatus>

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract meter list from arguments
        arguments?.let { bundle ->
            val serialNumbers = bundle.getStringArrayList(ARG_METER_SERIAL_NUMBERS)
            val numbers = bundle.getStringArrayList(ARG_METER_NUMBERS)
            val places = bundle.getStringArrayList(ARG_METER_PLACES)
            val fromFiles = bundle.getStringArrayList(ARG_METER_FROM_FILES)

            if (serialNumbers != null && numbers != null && places != null && fromFiles != null &&
                serialNumbers.size == numbers.size && numbers.size == places.size && places.size == fromFiles.size) {

                val meters = serialNumbers.mapIndexed { index, serialNumber ->
                    MeterStatus(
                        serialNumber = serialNumber,
                        number = numbers[index],
                        place = places[index],
                        fromFile = fromFiles[index],
                        registered = false, // Default value for continuous reading
                        isChecked = false   // We're only scanning unchecked meters
                    )
                }

                if (meters.isNotEmpty()) {
                    meterList = meters
                    viewModel.initializeMeterList(meters)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.continuous_reading_no_meters), Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.continuous_reading_no_meters), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        } ?: run {
            Toast.makeText(requireContext(), getString(R.string.continuous_reading_no_meters), Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContinuousReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupObservers()
        setupClickListeners()

        // Initialize camera components
        handler = Handler(Looper.getMainLooper())
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check camera permission and start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupUI() {
        // Initialize progress
        binding.progressText.text = viewModel.getCurrentMeterPosition()
        binding.progressIndicator.progress = 0
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentMeter.collect { meter ->
                if (meter != null) {
                    // Update all meter information displays
                    binding.currentMeterNumber.text = meter.number
                    binding.currentMeterPlace.text = meter.place
                    binding.currentMeterSerial.text = meter.serialNumber
                } else {
                    // Reset to completion state
                    binding.currentMeterNumber.text = getString(R.string.continuous_reading_completed)
                    binding.currentMeterPlace.text = getString(R.string.continuous_reading_all_done)
                    binding.currentMeterSerial.text = getString(R.string.continuous_reading_finished)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanResult.collect { result ->
                binding.statusText.text = result
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scannedCount.collect { count ->
                binding.progressText.text = viewModel.getCurrentMeterPosition()
                val progress = viewModel.getProgressPercentage()

                // Animate progress bar
                val animator = ObjectAnimator.ofInt(
                    binding.progressIndicator,
                    "progress",
                    binding.progressIndicator.progress,
                    progress.toInt()
                )
                animator.duration = 300
                animator.start()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentMeterScanned.collect { scanned ->
                if (scanned) {
                    // Update database when meter is successfully scanned
                    val currentMeter = viewModel.currentMeter.value
                    if (currentMeter != null) {
                        // Update the meter status in the database
                        filesViewModel.updateMeterCheckedStatus(
                            currentMeter.serialNumber,
                            true,
                            currentMeter.fromFile
                        )
                        Log.d(TAG, "✅ Updated database: ${currentMeter.serialNumber} marked as scanned")
                    }

                    // Show success feedback and auto-advance after delay
                    showSuccessFeedback()
                    handler.postDelayed({
                        if (_binding != null) {
                            viewModel.moveToNextMeter()
                            startNextMeterScan()
                        }
                    }, 2000) // 2 second delay
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isCompleted.collect { completed ->
                if (completed) {
                    showCompletionSummary()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.errorMessage.collect { error ->
                if (!error.isNullOrEmpty()) {
                    showErrorFeedback(error)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            showExitConfirmation()
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnSkipMeter.setOnClickListener {
            showSkipConfirmation()
        }

        binding.btnCompleteReading.setOnClickListener {
            showCompletionSummary()
        }
    }

    private fun startCamera() {
        try {
            if (!isCameraInitialized) {
                // Initialize barcode scanner
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                barcodeScanner = BarcodeScanning.getClient(options)

                // Initialize MicroQR detector
                val microQrConfig = ConfigMicroQrCode()
                val microQrDetector = FactoryFiducial.microqr(microQrConfig, GrayU8::class.java)

                // Create camera controller
                cameraController = LifecycleCameraController(requireContext()).apply {
                    setEnabledUseCases(CameraController.IMAGE_ANALYSIS)

                    // Set up ML Kit analyzer for regular QR codes
                    mlKitAnalyzer = MlKitAnalyzer(
                        listOf(barcodeScanner!!),
                        CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                        cameraExecutor!!
                    ) { result ->
                        processMLKitResult(result)
                    }

                    setImageAnalysisAnalyzer(cameraExecutor!!, mlKitAnalyzer!!)
                }

                // Bind camera to lifecycle
                cameraController!!.bindToLifecycle(this)
                binding.viewFinder.controller = cameraController

                isCameraInitialized = true

                // Start scanning for the first meter
                startNextMeterScan()
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Camera initialization failed", exc)
            Toast.makeText(requireContext(), getString(R.string.camera_initialization_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startNextMeterScan() {
        if (viewModel.isCompleted.value) {
            return
        }

        viewModel.startScanning()
        startScanLineAnimation()

        // Update status text with current meter info
        val currentMeter = viewModel.currentMeter.value
        if (currentMeter != null) {
            binding.statusText.text = getString(
                R.string.continuous_reading_scan_instructions,
                currentMeter.number,
                currentMeter.serialNumber
            )
        }

        // Start MicroQR scanning runnable - using same pattern as MeterDetailScanFragment
        microQrRunnable?.let { handler.removeCallbacks(it) }
        microQrRunnable = object : Runnable {
            override fun run() {
                if (!isAdded || _binding == null || !viewModel.isScanning.value) {
                    Log.d(TAG, "Skipping Micro QR detection: Fragment not ready, or already navigating.")
                    return
                }

                performMicroQrScan()

                // Schedule next detection - using 1000ms like MeterDetailScanFragment
                if (isAdded && viewModel.isScanning.value) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(microQrRunnable!!, 1000) // Start with delay like MeterDetailScanFragment
    }

    private fun processMLKitResult(result: MlKitAnalyzer.Result) {
        if (!viewModel.isScanning.value) return

        val barcodeResults = result.getValue(barcodeScanner!!)
        if (barcodeResults != null && barcodeResults.isNotEmpty()) {
            val barcode = barcodeResults[0]
            val scannedValue = barcode.rawValue

            if (!scannedValue.isNullOrEmpty()) {
                Log.d(TAG, "MLKit QR Code detected: $scannedValue")

                handler.post {
                    viewModel.onScanSuccess(scannedValue)
                }
            }
        }
    }

    private fun performMicroQrScan() {
        try {
            if (!viewModel.isScanning.value || !isCameraInitialized || !isAdded || _binding == null) {
                return
            }

            // Use the exact same method as MeterDetailScanFragment
            _binding?.viewFinder?.getBitmap()?.let { bitmap ->
                val grayImage = ConvertBitmap.bitmapToGray(bitmap, null as GrayU8?, null)

                val config = ConfigMicroQrCode()
                val detector = FactoryFiducial.microqr(config, GrayU8::class.java)

                detector.process(grayImage)

                // Use detections property instead of totalFound() and getMessage()
                if (detector.detections.isNotEmpty()) {
                    val detection = detector.detections[0]
                    val microQrData = detection.message
                    Log.d(TAG, "MicroQR Code detected: $microQrData")

                    handler.post {
                        if (isAdded && _binding != null && viewModel.isScanning.value) {
                            viewModel.onScanSuccess(microQrData)
                        }
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MicroQR detection error (non-critical): ${e.message}")
        }
    }

    private fun toggleFlash() {
        val camera = cameraController?.cameraInfo?.cameraSelector?.let {
            cameraController?.cameraControl
        }

        camera?.let {
            isFlashOn = !isFlashOn
            it.enableTorch(isFlashOn)

            val iconRes = if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            binding.btnFlash.setIconResource(iconRes)
        }
    }

    private fun startScanLineAnimation() {
        scanLineAnimator?.cancel()

        // Use scan_frame_container like in MeterDetailScanFragment
        _binding?.let { binding ->
            binding.scanFrameContainer.post {
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
                        interpolator = AccelerateDecelerateInterpolator()
                    }
                    scanLineAnimator?.start()
                }
            }
        }
    }

    private fun showSuccessFeedback() {
        // Implement success animation/feedback
        val currentMeter = viewModel.currentMeter.value
        if (currentMeter != null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.continuous_reading_meter_scanned, currentMeter.number),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showErrorFeedback(error: String) {
        // Log the error with more context
        val currentMeter = viewModel.currentMeter.value
        if (currentMeter != null) {
            Log.w(TAG, "❌ Scan error for meter ${currentMeter.number} (${currentMeter.serialNumber}): $error")
        }

        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()

        // Reset scan state after error
        handler.postDelayed({
            if (_binding != null) {
                viewModel.resetScanState()
                startNextMeterScan()
            }
        }, 3000) // Longer delay for error messages
    }

    private fun showSkipConfirmation() {
        val currentMeter = viewModel.currentMeter.value ?: return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.continuous_reading_skip_confirm_title))
            .setMessage(getString(R.string.continuous_reading_skip_confirm_message, currentMeter.number))
            .setPositiveButton(getString(R.string.skip)) { _, _ ->
                // Log the skip action
                Log.d(TAG, "⏭️ User skipped meter: ${currentMeter.serialNumber} (${currentMeter.number})")

                // Note: We don't update the database when skipping - meter remains unscanned
                viewModel.skipCurrentMeter()
                startNextMeterScan()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showExitConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.continuous_reading_exit_confirm_title))
            .setMessage(getString(R.string.continuous_reading_exit_confirm_message))
            .setPositiveButton(getString(R.string.exit)) { _, _ ->
                findNavController().navigateUp()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCompletionSummary() {
        val summary = viewModel.getSummaryReport()

        val message = StringBuilder().apply {
            append(getString(R.string.continuous_reading_summary_title))
            append("\n\n")
            append(getString(R.string.continuous_reading_summary_total, summary.totalMeters))
            append("\n")
            append(getString(R.string.continuous_reading_summary_scanned, summary.scannedMeters))
            append("\n")
            append(getString(R.string.continuous_reading_summary_skipped, summary.skippedMeters))

            if (summary.scannedMeters > 0) {
                append("\n\n")
                append(getString(R.string.continuous_reading_database_updated, summary.scannedMeters))
            }

            if (summary.skippedMeters > 0) {
                append("\n")
                append(getString(R.string.continuous_reading_skipped_remain_unscanned))
            }
        }.toString()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.continuous_reading_complete))
            .setMessage(message)
            .setPositiveButton(getString(R.string.finish)) { _, _ ->
                // Log the completion
                Log.d(TAG, "🎉 Continuous reading completed: ${summary.scannedMeters}/${summary.totalMeters} scanned")
                findNavController().navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (isCameraInitialized && !viewModel.isCompleted.value) {
            startNextMeterScan()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopScanning()
        microQrRunnable?.let { handler.removeCallbacks(it) }
        scanLineAnimator?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Clean up camera resources
        viewModel.stopScanning()
        microQrRunnable?.let { handler.removeCallbacks(it) }
        scanLineAnimator?.cancel()

        cameraController?.unbind()
        cameraController = null
        mlKitAnalyzer = null
        barcodeScanner = null

        cameraExecutor?.shutdown()
        cameraExecutor = null

        _binding = null
    }
}