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

    // Camera-related variables - properly managed lifecycle (KEEPING YOUR EXISTING LOGIC)
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
            Toast.makeText(requireContext(), getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract meter info from arguments (KEEPING YOUR EXISTING LOGIC)
        arguments?.let { bundle ->
            val serialNumber = bundle.getString(ARG_METER_SERIAL) ?: ""
            val number = bundle.getString(ARG_METER_NUMBER) ?: ""
            val place = bundle.getString(ARG_METER_PLACE) ?: ""
            val fromFile = bundle.getString(ARG_FROM_FILE) ?: ""

            targetMeter = MeterStatus(
                number = number,
                serialNumber = serialNumber,
                place = place,
                registered = false,
                fromFile = fromFile,
                isChecked = false
            )

            viewModel.setTargetMeter(targetMeter)
        } ?: run {
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

        // Initialize UI with localized strings and animations
        setupUI()
        setupClickListeners()
        startBottomSheetAnimation()

        // Initialize camera system (KEEPING YOUR EXISTING LOGIC)
        handler = Handler(Looper.getMainLooper())
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupObservers()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupUI() {
        // Set meter information with localized strings
        binding.meterNumberText.text = targetMeter.number
        binding.meterSerialText.text = targetMeter.serialNumber
        binding.meterPlaceText.text = targetMeter.place

        // Update instruction text with proper localization
        binding.instructionText.text = getString(R.string.scanning_for_meter, targetMeter.number)

        // Setup initial states
        binding.progressBar.visibility = View.GONE
        binding.successAnimation.visibility = View.GONE

        startScanLineAnimation()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            exitWithAnimation()
        }

        binding.btnToggleFlash.setOnClickListener {
            toggleFlash()
        }
    }

    private fun startBottomSheetAnimation() {
        // Initial setup - position bottom sheet off-screen
        binding.bottomSheetContainer.translationY = binding.bottomSheetContainer.height.toFloat()
        binding.bottomSheetContainer.alpha = 0f

        // Animate bottom sheet sliding up with modal effect
        binding.bottomSheetContainer.post {
            val slideUpAnimator = ObjectAnimator.ofFloat(
                binding.bottomSheetContainer,
                "translationY",
                binding.bottomSheetContainer.height.toFloat(),
                0f
            ).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
            }

            val fadeInAnimator = ObjectAnimator.ofFloat(
                binding.bottomSheetContainer,
                "alpha",
                0f,
                1f
            ).apply {
                duration = 400
                startDelay = 200
                interpolator = AccelerateDecelerateInterpolator()
            }

            // Scale animation for meter info card
            val scaleXAnimator = ObjectAnimator.ofFloat(
                binding.meterInfoCard,
                "scaleX",
                0.8f,
                1f
            ).apply {
                duration = 500
                startDelay = 400
                interpolator = DecelerateInterpolator()
            }

            val scaleYAnimator = ObjectAnimator.ofFloat(
                binding.meterInfoCard,
                "scaleY",
                0.8f,
                1f
            ).apply {
                duration = 500
                startDelay = 400
                interpolator = DecelerateInterpolator()
            }

            AnimatorSet().apply {
                playTogether(slideUpAnimator, fadeInAnimator, scaleXAnimator, scaleYAnimator)
                start()
            }
        }
    }

    private fun exitWithAnimation() {
        // Animate bottom sheet sliding down
        val slideDownAnimator = ObjectAnimator.ofFloat(
            binding.bottomSheetContainer,
            "translationY",
            0f,
            binding.bottomSheetContainer.height.toFloat()
        ).apply {
            duration = 400
            interpolator = AccelerateDecelerateInterpolator()
        }

        val fadeOutAnimator = ObjectAnimator.ofFloat(
            binding.bottomSheetContainer,
            "alpha",
            1f,
            0f
        ).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(slideDownAnimator, fadeOutAnimator)
            start()
        }

        // Navigate back after animation
        Handler(Looper.getMainLooper()).postDelayed({
            findNavController().navigateUp()
        }, 400)
    }

    // KEEPING ALL YOUR EXISTING CAMERA LOGIC BELOW - NO CHANGES

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
            barcodeScanner?.close()
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
                            scheduleMicroQrDetection()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in MLKit analyzer: ${e.message}")
                }
            }

            mlKitAnalyzer = analyzer
            newCameraController.setImageAnalysisAnalyzer(
                ContextCompat.getMainExecutor(requireContext()),
                analyzer
            )

            newCameraController.bindToLifecycle(this)
            binding.viewFinder.controller = newCameraController

            // Observe torch state after binding
            observeTorchState()

            isCameraInitialized = true
            Log.d(TAG, "Camera initialization successful")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera: ${e.message}")
            Toast.makeText(requireContext(), getString(R.string.camera_initialization_failed), Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private fun handleQrCodeDetected(qrData: String) {
        if (!viewModel.isScanning.value) return

        viewModel.stopScanning()
        val scannedSerial = extractSerialFromQr(qrData)

        if (scannedSerial == targetMeter.serialNumber) {
            // Perfect match - update the meter status
            filesViewModel.updateMeterCheckedStatus(
                targetMeter.serialNumber,
                true,
                targetMeter.fromFile
            )
            viewModel.setSuccessResult(getString(R.string.meter_verified))
        } else {
            // Check if this serial matches any other meter in the system
            val allMeters = filesViewModel.meterMatchMeters.value ?: emptyList()
            val matchingMeters = allMeters.filter { it.serialNumber == scannedSerial }

            if (matchingMeters.isNotEmpty()) {
                showAlternativeMatchDialog(scannedSerial, matchingMeters)
            } else {
                showNoMatchDialog(scannedSerial, qrData)
            }
        }
    }

    private fun extractSerialFromQr(qrData: String): String {
        return qrData.trim()
    }

    private fun showNoMatchDialog(scannedSerial: String, rawData: String) {
        val message = """
            ${getString(R.string.expected_serial, targetMeter.serialNumber)}
            ${getString(R.string.scanned_serial, scannedSerial)}

            ${getString(R.string.no_other_matches)}

            ${getString(R.string.verify_correct_meter)}
        """.trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.qr_code_mismatch))
            .setMessage(message)
            .setPositiveButton(getString(R.string.scan_again)) { _, _ ->
                restartScanning()
            }
            .setNegativeButton(getString(R.string.back)) { _, _ ->
                findNavController().navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    private fun showAlternativeMatchDialog(scannedSerial: String, matchingMeters: List<MeterStatus>) {
        val message = buildString {
            append("${getString(R.string.expected_serial, targetMeter.serialNumber)}\n")
            append("${getString(R.string.scanned_serial, scannedSerial)}\n\n")
            append("${getString(R.string.alternative_matches_found)}\n\n")
            matchingMeters.forEach { meter ->
                append("• ${meter.number} (${meter.place})\n")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.serial_mismatch))
            .setMessage(message)
            .setPositiveButton(getString(R.string.choose_alternative)) { _, _ ->
                // Handle alternative selection logic here
                showAlternativeSelectionDialog(matchingMeters)
            }
            .setNegativeButton(getString(R.string.scan_again)) { _, _ ->
                restartScanning()
            }
            .setNeutralButton(getString(R.string.back)) { _, _ ->
                findNavController().navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    private fun showAlternativeSelectionDialog(matchingMeters: List<MeterStatus>) {
        val meterOptions = matchingMeters.map { "${it.number} - ${it.place}" }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_meter_to_mark))
            .setItems(meterOptions) { _, which ->
                val selectedMeter = matchingMeters[which]
                filesViewModel.updateMeterCheckedStatus(
                    selectedMeter.serialNumber,
                    true,
                    selectedMeter.fromFile
                )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.meter_marked_scanned, selectedMeter.number),
                    Toast.LENGTH_LONG
                ).show()

                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded && _binding != null) {
                        findNavController().navigateUp()
                    }
                }, 1500)
            }
            .setNegativeButton(getString(R.string.back)) { _, _ ->
                findNavController().navigateUp()
            }
            .show()
    }

    private fun scheduleMicroQrDetection() {
        if (microQrRunnable != null || !isAdded) return

        Log.d(TAG, "Starting Micro QR detection attempts.")

        microQrRunnable = object : Runnable {
            override fun run() {
                if (!isAdded || _binding == null || !viewModel.isScanning.value) {
                    Log.d(TAG, "Skipping Micro QR detection: Fragment not ready, or already navigating.")
                    return
                }

                try {
                    _binding?.viewFinder?.getBitmap()?.let { bitmap ->
                        val grayImage = ConvertBitmap.bitmapToGray(bitmap, null as GrayU8?, null)

                        val config = ConfigMicroQrCode()
                        val detector = FactoryFiducial.microqr(config, GrayU8::class.java)

                        detector.process(grayImage)

                        if (detector.detections.size > 0) {
                            val detection = detector.detections[0]
                            val microQrData = detection.message

                            Log.i(TAG, "Micro QR Detected: $microQrData")

                            handler.post {
                                if (isAdded && _binding != null && viewModel.isScanning.value) {
                                    handleQrCodeDetected(microQrData)
                                }
                            }
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MicroQR detection error (non-critical): ${e.message}")
                }

                // Schedule next detection
                if (isAdded && viewModel.isScanning.value) {
                    handler.postDelayed(this, 1000)
                }
            }
        }

        handler.postDelayed(microQrRunnable!!, 2000)
    }

    private fun restartScanning() {
        viewModel.resetScanState()
        shutdownCamera()

        handler.postDelayed({
            if (allPermissionsGranted() && isAdded && _binding != null) {
                startCamera()
            }
        }, 500)
    }

    private fun shutdownCamera() {
        Log.d(TAG, "Shutting down camera")
        viewModel.stopScanning()

        microQrRunnable?.let { handler.removeCallbacks(it) }
        microQrRunnable = null

        if (isFlashOn) {
            cameraController?.cameraControl?.enableTorch(false)
            isFlashOn = false
            updateFlashButtonIcon()
        }

        _binding?.viewFinder?.overlay?.clear()

        try {
            mlKitAnalyzer = null
            barcodeScanner?.close()
            barcodeScanner = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing barcode scanner: ${e.message}")
        }

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

    private fun toggleFlash() {
        cameraController?.let { controller ->
            controller.cameraControl?.enableTorch(!isFlashOn)
        } ?: run {
            Toast.makeText(requireContext(), getString(R.string.camera_initialization_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFlashButtonIcon() {
        val iconRes = if (isFlashOn) {
            R.drawable.ic_flash_on
        } else {
            R.drawable.ic_flash_off
        }
        binding.btnToggleFlash.setIconResource(iconRes)

        // Update content description for accessibility
        val contentDesc = if (isFlashOn) {
            getString(R.string.turn_flash_off)
        } else {
            getString(R.string.turn_flash_on)
        }
        binding.btnToggleFlash.contentDescription = contentDesc
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

        // Show success animation
        binding.progressBar.visibility = View.GONE
        binding.successAnimation.visibility = View.VISIBLE

        // Enhanced success animation
        binding.successAnimation.apply {
            scaleX = 0f
            scaleY = 0f
            alpha = 0f
            animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
                .start()
        }

        shutdownCamera()

        Toast.makeText(requireContext(), getString(R.string.meter_verified), Toast.LENGTH_LONG).show()

        handler.postDelayed({
            if (isAdded && _binding != null) {
                exitWithAnimation()
            }
        }, 1500)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        super.onPause()
        shutdownCamera()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && isAdded && _binding != null && !isCameraInitialized) {
            startCamera()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        shutdownCamera()
        stopScanLineAnimation()

        cameraExecutor?.shutdown()
        cameraExecutor = null

        _binding = null
    }
}