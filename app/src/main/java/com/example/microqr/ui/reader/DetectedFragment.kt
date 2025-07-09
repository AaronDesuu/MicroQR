package com.example.microqr.ui.reader

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.microqr.R
import com.example.microqr.databinding.FragmentDetectedBinding
import com.example.microqr.data.repository.LocationRepository
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class DetectedFragment : Fragment() {

    companion object {
        private const val TAG = "DetectedFragment"
        const val METER_DATA_UPDATED_KEY = "meter_data_updated"
    }

    private var _binding: FragmentDetectedBinding? = null
    private val binding get() = _binding!!

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var locationRepository: LocationRepository

    private var rawQrValue: String = ""
    private var scanContext: String = ""
    private var detectedSerial: String = ""
    private var currentMeter: MeterStatus? = null
    private var currentFlowState: FlowState = FlowState.PROCESSING

    private enum class FlowState {
        PROCESSING,
        NEEDS_METER_DATA,
        READY_FOR_SN_CHECK,
        SN_VERIFIED,
        COMPLETED,  // NEW: Added completed state
        ERROR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            rawQrValue = args.getString("rawQrValue", "")
            scanContext = args.getString("scanContext", "")
            val dataUpdated = args.getBoolean("dataUpdated", false)
            val dataComplete = args.getBoolean("dataComplete", false)

            Log.d(TAG, "DetectedFragment created with:")
            Log.d(TAG, "  rawQrValue: $rawQrValue")
            Log.d(TAG, "  scanContext: $scanContext")
            Log.d(TAG, "  dataUpdated: $dataUpdated")
            Log.d(TAG, "  dataComplete: $dataComplete")
        }

        if (rawQrValue.isBlank()) {
            Log.e(TAG, "ERROR: rawQrValue is null or blank!")
        }

        // FIXED: Setup fragment result listener for MeterDataInputFragment callback
        setFragmentResultListener(METER_DATA_UPDATED_KEY) { _, bundle ->
            val updated = bundle.getBoolean("updated", false)
            if (updated) {
                Log.d(TAG, "📢 Received meter data update callback")
                refreshCurrentMeterData()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            locationRepository = LocationRepository(requireContext())
            setupClickListeners()

            // Check if this is a data update return
            val dataUpdated = arguments?.getBoolean("dataUpdated", false) ?: false

            when {
                dataUpdated -> {
                    Log.d(TAG, "Handling data update scenario")
                    handleDataUpdate()
                }
                rawQrValue.isNotBlank() -> {
                    Log.d(TAG, "Processing normal QR scan")
                    processQrData()
                }
                else -> {
                    Log.w(TAG, "No QR data or update flag - navigating back")
                    navigateBack()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated: ${e.message}", e)
            showErrorState(getString(R.string.error_generic))
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "DetectedFragment onResume called")

        // FIXED: Only auto-refresh if we have a detected serial and current meter
        // This prevents unnecessary refreshes while still catching missed updates
        if (detectedSerial.isNotBlank() && currentMeter != null) {
            Log.d(TAG, "Auto-refreshing meter data on resume")
            refreshCurrentMeterData()
        }
    }

    // FIXED: Enhanced refresh method with better error handling and retry logic
    private fun refreshCurrentMeterData() {
        if (detectedSerial.isBlank()) {
            Log.w(TAG, "Cannot refresh - no detected serial")
            return
        }

        Log.d(TAG, "Refreshing meter data for: $detectedSerial")
        showDatabaseRefreshState()

        lifecycleScope.launch {
            try {
                // Give database time to commit changes
                kotlinx.coroutines.delay(500)

                // Try to refresh data through ViewModel if method exists
                try {
                    filesViewModel.refreshData()
                    Log.d(TAG, "Called refreshData on ViewModel")
                } catch (e: Exception) {
                    Log.w(TAG, "refreshData method not available: ${e.message}")
                }

                // Wait for refresh to complete
                kotlinx.coroutines.delay(800)

                // Multiple attempts to find updated meter
                var refreshedMeter: MeterStatus? = null
                var attempts = 0
                val maxAttempts = 3

                while (refreshedMeter == null && attempts < maxAttempts) {
                    attempts++
                    Log.d(TAG, "Attempt $attempts to find meter: $detectedSerial")

                    refreshedMeter = filesViewModel.findMeterBySerial(detectedSerial)

                    if (refreshedMeter == null && attempts < maxAttempts) {
                        kotlinx.coroutines.delay(500)
                    }
                }

                if (refreshedMeter != null) {
                    Log.d(TAG, "✅ Meter refresh successful: ${refreshedMeter.serialNumber}")
                    Log.d(TAG, "  Location: '${refreshedMeter.place}'")
                    Log.d(TAG, "  Number: '${refreshedMeter.number}'")
                    Log.d(TAG, "  From file: '${refreshedMeter.fromFile}'")

                    currentMeter = refreshedMeter
                    showDataUpdateSuccessState()
                    kotlinx.coroutines.delay(1000)

                    // Re-evaluate meter state based on current data
                    evaluateMeterState(refreshedMeter)
                } else {
                    Log.w(TAG, "⚠️ Could not find meter after refresh: $detectedSerial")
                    // Keep current state if meter not found but don't show error
                    currentMeter?.let { meter ->
                        evaluateMeterState(meter)
                    } ?: run {
                        updateUI()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in refreshCurrentMeterData: ${e.message}", e)
                // Fall back to current state on error
                currentMeter?.let { meter ->
                    evaluateMeterState(meter)
                } ?: run {
                    updateUI()
                }
            }
        }
    }

    // FIXED: Enhanced data update handling
    private fun handleDataUpdate() {
        if (rawQrValue.isBlank()) {
            showErrorState(getString(R.string.error_generic))
            return
        }

        detectedSerial = parseSerialNumber(rawQrValue)
        Log.d(TAG, "Processing data update for serial: $detectedSerial")

        // Show database refresh state immediately
        showDatabaseRefreshState()

        lifecycleScope.launch {
            try {
                // Phase 1: Initial delay for database commit
                Log.d(TAG, "Phase 1: Waiting for database commit...")
                kotlinx.coroutines.delay(800)

                // Phase 2: Force refresh database connections
                Log.d(TAG, "Phase 2: Refreshing database connections...")
                try {
                    filesViewModel.refreshData()
                } catch (e: Exception) {
                    Log.w(TAG, "refreshData method not available: ${e.message}")
                }
                kotlinx.coroutines.delay(1000)

                // Phase 3: Multiple attempts to find updated meter
                Log.d(TAG, "Phase 3: Attempting to find updated meter...")
                var refreshedMeter: MeterStatus? = null
                var attempts = 0
                val maxAttempts = 5

                while (refreshedMeter == null && attempts < maxAttempts) {
                    attempts++
                    Log.d(TAG, "Search attempt $attempts for serial: $detectedSerial")

                    refreshedMeter = filesViewModel.findMeterBySerial(detectedSerial)

                    if (refreshedMeter != null) {
                        Log.d(TAG, "✅ Found meter on attempt $attempts")
                        Log.d(TAG, "  Location: '${refreshedMeter.place}'")
                        Log.d(TAG, "  Number: '${refreshedMeter.number}'")
                    } else if (attempts < maxAttempts) {
                        Log.d(TAG, "Meter not found, waiting before retry...")
                        kotlinx.coroutines.delay(800)
                    }
                }

                if (refreshedMeter != null) {
                    currentMeter = refreshedMeter
                    Log.d(TAG, "✅ Data update successful")

                    // Show success state briefly
                    showDataUpdateSuccessState()
                    kotlinx.coroutines.delay(1500)

                    // Evaluate the meter state
                    evaluateMeterState(refreshedMeter)
                } else {
                    Log.w(TAG, "❌ Could not find updated meter after ${maxAttempts} attempts")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.could_not_refresh_data),
                        Toast.LENGTH_LONG
                    ).show()

                    // Try to continue with existing data or show error
                    if (currentMeter != null) {
                        evaluateMeterState(currentMeter!!)
                    } else {
                        showErrorState(getString(R.string.database_timeout_error))
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in handleDataUpdate: ${e.message}", e)
                showErrorState(getString(R.string.error_generic))
            }
        }
    }

    private fun parseSerialNumber(qrValue: String): String {
        // Extract serial number from QR value - adjust regex as needed
        val serialRegex = """(\d{6,})""".toRegex()
        val match = serialRegex.find(qrValue)
        return match?.value ?: qrValue.trim()
    }

    private fun processQrData() {
        if (rawQrValue.isBlank()) {
            showErrorState(getString(R.string.error_generic))
            return
        }

        // Set initial state and UI
        currentFlowState = FlowState.PROCESSING
        detectedSerial = parseSerialNumber(rawQrValue)
        updateUI()

        // Process the meter data
        lifecycleScope.launch {
            try {
                when (scanContext) {
                    "METER_CHECK" -> {
                        Log.d(TAG, "Handling MeterCheck context")
                        handleMeterCheckContext()
                    }
                    else -> {
                        Log.d(TAG, "Handling default context")
                        handleDefaultContext()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing QR data: ${e.message}", e)
                showErrorState(getString(R.string.error_processing_meter))
            }
        }
    }

    private suspend fun handleMeterCheckContext() {
        Log.d(TAG, "Looking for meter in database: $detectedSerial")

        val foundMeter = filesViewModel.findMeterBySerial(detectedSerial)

        if (foundMeter != null) {
            Log.d(TAG, "✅ Meter found in database")
            currentMeter = foundMeter
            evaluateMeterState(foundMeter)
        } else {
            Log.d(TAG, "❌ Meter not found - creating new entry")
            createNewMeter()
        }
    }

    private suspend fun handleDefaultContext() {
        Log.d(TAG, "Processing default QR scan context")
        val foundMeter = filesViewModel.findMeterBySerial(detectedSerial)

        if (foundMeter != null) {
            currentMeter = foundMeter
            evaluateMeterState(foundMeter)
        } else {
            createNewMeter()
        }
    }

    private suspend fun createNewMeter() {
        Log.d(TAG, "Creating new meter for serial: $detectedSerial")

        try {
            val newMeter = MeterStatus(
                serialNumber = detectedSerial,
                number = getString(R.string.not_set),
                place = getString(R.string.not_set),
                registered = false,
                fromFile = getString(R.string.scanner_input),
                isChecked = false,
                isSelectedForProcessing = false
            )

            currentMeter = newMeter
            Log.d(TAG, "✅ New meter created successfully")
            evaluateMeterState(newMeter)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating new meter: ${e.message}", e)
            showErrorState(getString(R.string.error_processing_meter))
        }
    }

    private fun evaluateMeterState(meter: MeterStatus) {
        Log.d(TAG, "Evaluating meter state for: ${meter.serialNumber}")
        Log.d(TAG, "  Location: '${meter.place}' (valid: ${isValidMeterLocation(meter.place)})")
        Log.d(TAG, "  Number: '${meter.number}' (valid: ${isValidMeterNumber(meter.number)})")
        Log.d(TAG, "  Registered: ${meter.registered}")

        val hasValidLocation = isValidMeterLocation(meter.place)
        val hasValidNumber = isValidMeterNumber(meter.number)

        when {
            !hasValidLocation || !hasValidNumber -> {
                Log.d(TAG, "➤ State: NEEDS_METER_DATA")
                currentFlowState = FlowState.NEEDS_METER_DATA
                showNeedsMeterDataState()
            }
            !meter.registered -> {
                Log.d(TAG, "➤ State: READY_FOR_SN_CHECK")
                currentFlowState = FlowState.READY_FOR_SN_CHECK
                showReadyForCheckState()
            }
            else -> {
                Log.d(TAG, "➤ State: SN_VERIFIED")
                currentFlowState = FlowState.SN_VERIFIED
                showVerifiedState()
            }
        }
    }

    private fun updateUI() {
        when (currentFlowState) {
            FlowState.PROCESSING -> showProcessingState()
            FlowState.NEEDS_METER_DATA -> showNeedsMeterDataState()
            FlowState.READY_FOR_SN_CHECK -> showReadyForCheckState()
            FlowState.SN_VERIFIED -> showVerifiedState()
            FlowState.COMPLETED -> showCompletedState()  // NEW: Added completed state
            FlowState.ERROR -> showErrorState(getString(R.string.error_generic))
        }
    }

    private fun showProcessingState() {
        binding.statusIcon.setImageResource(R.drawable.ic_hourglass_empty)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        binding.titleText.text = getString(R.string.processing_detection)
        binding.nextButton.text = getString(R.string.processing)
        binding.nextButton.isEnabled = false
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)

        // Set serial number if available
        if (detectedSerial.isNotBlank()) {
            binding.detectedSerial.text = detectedSerial
        }

        // Show loading state for status rows
        updateStatusRows(false, false, false)
    }

    private fun showNeedsMeterDataState() {
        binding.statusIcon.setImageResource(R.drawable.ic_warning)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        binding.titleText.text = getString(R.string.verification_completed)
        binding.nextButton.text = getString(R.string.set_meter_data)
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)

        currentMeter?.let { meter ->
            val hasLocation = isValidMeterLocation(meter.place)
            val hasNumber = isValidMeterNumber(meter.number)
            updateStatusRows(hasLocation, hasNumber, false)
            updateMeterInfoDisplay(meter, hasLocation, hasNumber)
        }
    }

    private fun showReadyForCheckState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = getString(R.string.verification_completed)
        binding.nextButton.text = getString(R.string.check_serial_number)
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)

        currentMeter?.let { meter ->
            updateStatusRows(true, true, false)
            updateMeterInfoDisplay(meter, true, true)
        }
    }

    private fun showVerifiedState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = getString(R.string.serial_verification_completed)
        binding.nextButton.text = getString(R.string.done)  // FIXED: Changed to "完了" (Complete)
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)

        currentMeter?.let { meter ->
            updateStatusRows(true, true, true)
            updateMeterInfoDisplay(meter, true, true)
        }
    }

    // NEW: Added completed state for final confirmation
    private fun showCompletedState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = getString(R.string.meter_scan_completed)
        binding.nextButton.text = getString(R.string.back_to_meter_list)
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)

        currentMeter?.let { meter ->
            updateStatusRows(true, true, true)
            updateMeterInfoDisplay(meter, true, true)
        }
    }

    private fun showErrorState(message: String) {
        binding.statusIcon.setImageResource(R.drawable.ic_error)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.error_red))
        binding.titleText.text = getString(R.string.detection_error)
        binding.nextButton.text = getString(R.string.retry_data_refresh)
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.error_red)

        currentFlowState = FlowState.ERROR
        updateStatusRows(false, false, false)

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    // NEW: Show database refresh state
    private fun showDatabaseRefreshState() {
        binding.statusIcon.setImageResource(R.drawable.ic_hourglass_empty)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary_color))
        binding.titleText.text = getString(R.string.refreshing_data)
        binding.nextButton.text = getString(R.string.updating_database)
        binding.nextButton.isEnabled = false
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)

        // Set serial number if available
        if (detectedSerial.isNotBlank()) {
            binding.detectedSerial.text = detectedSerial
        }

        // Show loading state for status rows
        updateStatusRows(false, false, false)
    }

    // NEW: Show data update success transition
    private fun showDataUpdateSuccessState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = getString(R.string.data_refresh_complete)
        binding.nextButton.text = getString(R.string.data_refresh_complete)
        binding.nextButton.isEnabled = false
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)
    }

    // FIXED: Update status rows with correct binding names from layout
    private fun updateStatusRows(hasLocation: Boolean, hasNumber: Boolean, isChecked: Boolean) {
        // Update Location Status Row (binding IDs from layout)
        if (hasLocation) {
            binding.locationStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.locationStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            binding.locationStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.locationStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        }

        // Update Number Status Row
        if (hasNumber) {
            binding.numberStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.numberStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            binding.numberStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.numberStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        }

        // Update Serial Status Row
        if (isChecked) {
            binding.serialStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.serialStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            binding.serialStatusIcon.setImageResource(R.drawable.ic_hourglass_empty)
            binding.serialStatusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        }
    }

    private fun updateMeterInfoDisplay(meter: MeterStatus, hasLocation: Boolean, hasNumber: Boolean) {
        // Update the displayed meter information
        binding.detectedSerial.text = meter.serialNumber

        // Update other display fields as needed based on your layout
        if (_binding != null) {
            try {
                // Update values in the status rows based on actual layout
                if (hasLocation) {
                    binding.locationValue?.text = meter.place
                } else {
                    binding.locationValue?.text = getString(R.string.not_set)
                }

                if (hasNumber) {
                    binding.numberValue?.text = meter.number
                } else {
                    binding.numberValue?.text = getString(R.string.not_set)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Some display fields not available in layout: ${e.message}")
            }
        }
    }

    private fun isValidMeterLocation(location: String?): Boolean {
        return !location.isNullOrBlank() &&
                location.trim() != getString(R.string.not_set) &&
                location.trim() != getString(R.string.unknown)
    }

    private fun isValidMeterNumber(number: String?): Boolean {
        return !number.isNullOrBlank() &&
                number.trim() != getString(R.string.not_set) &&
                number.trim() != getString(R.string.unknown)
    }

    private fun setupClickListeners() {
        binding.nextButton.setOnClickListener {
            when (currentFlowState) {
                FlowState.NEEDS_METER_DATA -> openMeterDataInput()
                FlowState.READY_FOR_SN_CHECK -> performSerialNumberCheck()
                FlowState.SN_VERIFIED -> navigateToMeterCheck()  // FIXED: Navigate to MeterCheck on "完了"
                FlowState.COMPLETED -> navigateToMeterCheck()    // NEW: Also handle completed state
                FlowState.ERROR -> refreshCurrentMeterData()
                else -> {
                    Log.w(TAG, "Button clicked in invalid state: $currentFlowState")
                }
            }
        }

        binding.backButton.setOnClickListener {
            navigateBack()
        }
    }

    private fun openMeterDataInput() {
        try {
            currentMeter?.let { meter ->
                Log.d(TAG, "Opening MeterDataInput for serial: ${meter.serialNumber}")

                val needsLocation = !isValidMeterLocation(meter.place)
                val needsNumber = !isValidMeterNumber(meter.number)
                val isNewMeter = meter.fromFile == getString(R.string.scanner_input)

                Log.d(TAG, "needsLocation: $needsLocation, needsNumber: $needsNumber, isNewMeter: $isNewMeter")

                val fragment = MeterDataInputFragment.newInstance(
                    serialNumber = meter.serialNumber,
                    currentLocation = meter.place,
                    currentNumber = meter.number,
                    needsLocation = needsLocation,
                    needsNumber = needsNumber,
                    isNewMeter = isNewMeter
                )

                // Use DialogFragment.show() instead of Navigation Component
                fragment.show(parentFragmentManager, "MeterDataInput")
            } ?: run {
                Log.e(TAG, "Cannot open MeterDataInput - currentMeter is null")
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_generic),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening MeterDataInput: ${e.message}", e)
            Toast.makeText(
                requireContext(),
                getString(R.string.error_navigation),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ORIGINAL LOGIC PRESERVED: Serial Number Check just updates database, no camera
    private fun performSerialNumberCheck() {
        Log.d(TAG, "Performing S/N check for meter: ${currentMeter?.serialNumber}")

        lifecycleScope.launch {
            try {
                currentMeter?.let { meter ->
                    val updatedMeter = meter.copy(
                        registered = true,
                        isChecked = true
                    )

                    filesViewModel.getMeterRepository().updateMeter(updatedMeter)
                    currentMeter = updatedMeter

                    Log.d(TAG, "S/N check completed successfully")
                    currentFlowState = FlowState.SN_VERIFIED  // FIXED: Go to verified state first
                    updateUI()

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.serial_verification_completed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing S/N check: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_verifying_serial),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // NEW: Navigate to MeterCheckFragment
    private fun navigateToMeterCheck() {
        try {
            Log.d(TAG, "Navigating to MeterCheckFragment")
            findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to MeterCheck: ${e.message}", e)
            // Fallback to popping back
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            Log.d(TAG, "Navigating back from DetectedFragment")
            findNavController().popBackStack()
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating back: ${e.message}", e)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}