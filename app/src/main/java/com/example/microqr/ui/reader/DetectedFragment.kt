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

            if (dataUpdated) {
                Log.d(TAG, "Data was updated, processing refreshed data...")
                handleDataUpdate()
            } else {
                Log.d(TAG, "Processing new QR data")
                processQrData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated: ${e.message}", e)
            showErrorState("Initialization error: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        binding.backButton.setOnClickListener {
            navigateBack()
        }

        binding.nextButton.setOnClickListener {
            when (currentFlowState) {
                FlowState.NEEDS_METER_DATA -> openMeterDataInput()
                FlowState.READY_FOR_SN_CHECK -> performSnCheck()
                FlowState.SN_VERIFIED -> navigateBack()
                FlowState.ERROR -> {
                    // Retry functionality
                    val dataUpdated = arguments?.getBoolean("dataUpdated", false) ?: false
                    if (dataUpdated) {
                        handleDataUpdate()
                    } else {
                        processQrData()
                    }
                }
                else -> navigateBack()
            }
        }
    }

    private fun processQrData() {
        if (rawQrValue.isBlank()) {
            showErrorState("No QR data received")
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
                showErrorState("Error processing meter: ${e.message}")
            }
        }
    }

    // ENHANCED: Handle data update with proper refresh timing
    private fun handleDataUpdate() {
        if (rawQrValue.isBlank()) {
            showErrorState("No QR data received")
            return
        }

        detectedSerial = parseSerialNumber(rawQrValue)

        // Show processing with database refresh message
        showDatabaseRefreshState()

        lifecycleScope.launch {
            try {
                // Phase 1: Initial delay to ensure database commit
                Log.d(TAG, "Phase 1: Waiting for database commit...")
                kotlinx.coroutines.delay(500)

                // Phase 2: Force refresh database connections (if method exists)
                Log.d(TAG, "Phase 2: Refreshing database connections...")
                try {
                    filesViewModel.refreshData()
                } catch (e: Exception) {
                    Log.w(TAG, "refreshData method not available: ${e.message}")
                }
                kotlinx.coroutines.delay(800)

                // Phase 3: Multiple attempts to find updated meter
                Log.d(TAG, "Phase 3: Attempting to find updated meter...")
                var refreshedMeter: MeterStatus? = null
                var attempts = 0
                val maxAttempts = 3

                while (refreshedMeter == null && attempts < maxAttempts) {
                    attempts++
                    Log.d(TAG, "Database query attempt $attempts/$maxAttempts")

                    refreshedMeter = filesViewModel.findMeterBySerial(detectedSerial)

                    if (refreshedMeter == null) {
                        Log.w(TAG, "Meter not found on attempt $attempts, retrying...")
                        kotlinx.coroutines.delay(500) // Wait before retry
                    } else {
                        Log.d(TAG, "✅ Found meter on attempt $attempts")
                    }
                }

                // Phase 4: Process the refreshed meter
                if (refreshedMeter != null) {
                    currentMeter = refreshedMeter
                    Log.d(TAG, "✅ Found refreshed meter: location='${refreshedMeter.place}', number='${refreshedMeter.number}'")

                    // Get completion status from navigation arguments
                    val dataComplete = arguments?.getBoolean("dataComplete", false) ?: false
                    val updateTimestamp = arguments?.getLong("updateTimestamp", 0L) ?: 0L

                    Log.d(TAG, "Data complete: $dataComplete, timestamp: $updateTimestamp")

                    // Show transition animation before final state
                    showDataUpdateSuccessState()

                    // Brief delay for user to see success, then evaluate final state
                    kotlinx.coroutines.delay(1200)

                    if (dataComplete && isValidMeterLocation(refreshedMeter.place) && isValidMeterNumber(refreshedMeter.number)) {
                        Log.d(TAG, "✅ Data is complete! Auto-progressing to READY_FOR_SN_CHECK")
                        currentFlowState = FlowState.READY_FOR_SN_CHECK
                        updateUI()

                        // Show success toast
                        Toast.makeText(requireContext(), "✅ Meter data updated! Ready for verification.", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Data incomplete, re-evaluating state")
                        evaluateMeterState(refreshedMeter)
                    }
                } else {
                    Log.e(TAG, "❌ Could not find meter after $maxAttempts attempts: $detectedSerial")
                    showErrorState("Could not refresh meter data. Please try again.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling data update: ${e.message}", e)
                showErrorState("Error refreshing data: ${e.message}")
            }
        }
    }

    private fun parseSerialNumber(qrValue: String): String {
        return qrValue.trim()
    }

    private suspend fun handleMeterCheckContext() {
        try {
            val existingMeter = filesViewModel.findMeterBySerial(detectedSerial)

            if (existingMeter != null) {
                Log.d(TAG, "Found existing meter: $detectedSerial")
                currentMeter = existingMeter
                evaluateMeterState(existingMeter)
            } else {
                Log.d(TAG, "Meter not found, creating new entry")
                checkIfLocationsExist()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in MeterCheck context: ${e.message}", e)
            showErrorState("Error processing meter")
        }
    }

    private suspend fun handleDefaultContext() {
        try {
            val existingMeter = filesViewModel.findMeterBySerial(detectedSerial)

            if (existingMeter != null) {
                Log.d(TAG, "Found existing meter")
                currentMeter = existingMeter
                evaluateMeterState(existingMeter)
            } else {
                Log.d(TAG, "Meter not found, creating new entry")
                checkIfLocationsExist()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in default context: ${e.message}", e)
            showErrorState("Error processing meter")
        }
    }

    private suspend fun checkIfLocationsExist() {
        try {
            val locations = locationRepository.getActiveLocationNames()
            if (locations.isEmpty()) {
                showNoLocationsDialog()
            } else {
                // Create new meter entry - mark as new meter with special fromFile
                currentMeter = MeterStatus(
                    serialNumber = detectedSerial,
                    place = "",
                    number = "",
                    fromFile = "Scanner Input",
                    registered = false,
                    isChecked = false
                )
                currentFlowState = FlowState.NEEDS_METER_DATA
                updateUI()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking locations: ${e.message}", e)
            showErrorState("Error checking locations")
        }
    }

    private fun evaluateMeterState(meter: MeterStatus) {
        val needsLocation = !isValidMeterLocation(meter.place)
        val needsNumber = !isValidMeterNumber(meter.number)

        Log.d(TAG, "Evaluating meter state:")
        Log.d(TAG, "  needsLocation: $needsLocation (place: '${meter.place}')")
        Log.d(TAG, "  needsNumber: $needsNumber (number: '${meter.number}')")
        Log.d(TAG, "  registered: ${meter.registered}")

        when {
            needsLocation || needsNumber -> {
                Log.d(TAG, "Setting state to NEEDS_METER_DATA")
                currentFlowState = FlowState.NEEDS_METER_DATA
            }
            !meter.registered -> {
                Log.d(TAG, "Setting state to READY_FOR_SN_CHECK")
                currentFlowState = FlowState.READY_FOR_SN_CHECK
            }
            meter.registered -> {
                Log.d(TAG, "Setting state to SN_VERIFIED")
                currentFlowState = FlowState.SN_VERIFIED
            }
            else -> {
                Log.d(TAG, "Setting state to READY_FOR_SN_CHECK (default)")
                currentFlowState = FlowState.READY_FOR_SN_CHECK
            }
        }

        updateUI()
    }

    private fun isValidMeterNumber(number: String?): Boolean {
        val isValid = !number.isNullOrBlank() &&
                number != "000" &&
                number != "0" &&
                number != "-"
        Log.d(TAG, "Number validation: '$number' -> $isValid")
        return isValid
    }

    private fun isValidMeterLocation(place: String?): Boolean {
        val isValid = !place.isNullOrBlank() &&
                place.lowercase() != "unknown"
        Log.d(TAG, "Location validation: '$place' -> $isValid")
        return isValid
    }

    private fun updateUI() {
        try {
            // Always set the detected serial number
            binding.detectedSerial.text = detectedSerial

            when (currentFlowState) {
                FlowState.PROCESSING -> showProcessingState()
                FlowState.NEEDS_METER_DATA -> showNeedsMeterDataState()
                FlowState.READY_FOR_SN_CHECK -> showReadyForSnCheckState()
                FlowState.SN_VERIFIED -> showSnVerifiedState()
                FlowState.ERROR -> showErrorState("Error state")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI: ${e.message}", e)
            showErrorState("UI update error")
        }
    }

    private fun showProcessingState() {
        binding.statusIcon.setImageResource(R.drawable.ic_hourglass_empty)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        binding.titleText.text = "Processing Detection..."
        binding.nextButton.text = "Processing..."
        binding.nextButton.isEnabled = false
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)
        updateStatusRows(false, false, false)
    }

    private fun showNeedsMeterDataState() {
        binding.statusIcon.setImageResource(R.drawable.ic_warning)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        binding.titleText.text = "Detection Completed"
        binding.nextButton.text = "Set Meter Data"
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)

        currentMeter?.let { meter ->
            val hasLocation = isValidMeterLocation(meter.place)
            val hasNumber = isValidMeterNumber(meter.number)
            updateStatusRows(hasLocation, hasNumber, false)
            updateMeterInfoDisplay(meter, hasLocation, hasNumber)
        }
    }

    // NEW: Show database refresh state
    private fun showDatabaseRefreshState() {
        binding.statusIcon.setImageResource(R.drawable.ic_hourglass_empty)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.primary_color))
        binding.titleText.text = "Refreshing Data..."
        binding.nextButton.text = "Updating..."
        binding.nextButton.isEnabled = false
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)

        // Set serial number
        binding.detectedSerial.text = detectedSerial

        // Show loading state for status rows
        updateStatusRows(false, false, false)
    }

    // NEW: Show data update success transition
    private fun showDataUpdateSuccessState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = "Data Updated Successfully!"
        binding.nextButton.text = "Processing..."
        binding.nextButton.isEnabled = false
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)

        // Update status rows to show the new data
        currentMeter?.let { meter ->
            val hasLocation = isValidMeterLocation(meter.place)
            val hasNumber = isValidMeterNumber(meter.number)
            updateStatusRows(hasLocation, hasNumber, false)
            updateMeterInfoDisplay(meter, hasLocation, hasNumber)
        }
    }

    // ENHANCED: Ready state with celebration
    private fun showReadyForSnCheckState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = "Ready for Verification! 🎉"
        binding.nextButton.text = "Check S/N"
        binding.nextButton.isEnabled = true

        // Highlight the button with success color
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)

        currentMeter?.let { meter ->
            updateStatusRows(true, true, false)
            updateMeterInfoDisplay(meter, true, true)
        }

        // Add a subtle animation to draw attention to the button
        binding.nextButton.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(200)
            .withEndAction {
                binding.nextButton.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun showSnVerifiedState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = "Verification Completed"
        binding.nextButton.text = "Back to List"
        binding.nextButton.isEnabled = true
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.success_green)

        currentMeter?.let { meter ->
            updateStatusRows(true, true, true)
            updateMeterInfoDisplay(meter, true, true)
        }
    }

    // ENHANCED: Error state with retry option
    private fun showErrorState(message: String) {
        binding.statusIcon.setImageResource(R.drawable.ic_error)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.error_red))
        binding.titleText.text = "Error Occurred"
        binding.nextButton.text = "Retry"
        binding.nextButton.isEnabled = true

        // Reset button color
        binding.nextButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.primary_color)

        updateStatusRows(false, false, false)
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    // ENHANCED: Update status rows with animations
    private fun updateStatusRows(hasLocation: Boolean, hasNumber: Boolean, snVerified: Boolean) {
        // Location status with animation
        updateStatusIconWithAnimation(binding.locationStatusIcon, hasLocation)
        binding.locationValue.text = if (hasLocation) {
            currentMeter?.place ?: "Unknown"
        } else {
            "Not Set"
        }

        // Number status with animation
        updateStatusIconWithAnimation(binding.numberStatusIcon, hasNumber)
        binding.numberValue.text = if (hasNumber) {
            currentMeter?.number ?: "Unknown"
        } else {
            "Not Set"
        }

        // Serial verification status with animation
        updateStatusIconWithAnimation(binding.serialStatusIcon, snVerified)
        binding.serialStatusText.text = if (snVerified) {
            "Verified"
        } else {
            "Pending"
        }
    }

    // NEW: Update status icon with smooth animation
    private fun updateStatusIconWithAnimation(imageView: android.widget.ImageView, isComplete: Boolean) {
        // Fade out, change icon, fade in
        imageView.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                if (isComplete) {
                    imageView.setImageResource(R.drawable.ic_check_circle)
                    imageView.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
                } else {
                    imageView.setImageResource(R.drawable.ic_warning)
                    imageView.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
                }

                imageView.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun updateMeterInfoDisplay(meter: MeterStatus, hasLocation: Boolean, hasNumber: Boolean) {
        binding.locationValue.text = if (hasLocation) meter.place else "Not Set"
        binding.numberValue.text = if (hasNumber) meter.number else "Not Set"
    }

    private fun openMeterDataInput() {
        currentMeter?.let { meter ->
            val needsLocation = !isValidMeterLocation(meter.place)
            val needsNumber = !isValidMeterNumber(meter.number)
            val isNewMeter = meter.fromFile == "Scanner Input"

            // Mark the time when opening dialog
            arguments?.putLong("lastDataUpdate", System.currentTimeMillis())

            val fragment = MeterDataInputFragment.newInstance(
                serialNumber = detectedSerial,
                currentLocation = meter.place,
                currentNumber = meter.number,
                needsLocation = needsLocation,
                needsNumber = needsNumber,
                isNewMeter = isNewMeter
            )
            fragment.show(parentFragmentManager, "MeterDataInput")
        }
    }

    private fun performSnCheck() {
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
                    currentFlowState = FlowState.SN_VERIFIED
                    updateUI()

                    Toast.makeText(requireContext(), "✅ Serial number verified successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing S/N check: ${e.message}", e)
                Toast.makeText(requireContext(), "Error verifying serial number", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNoLocationsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("No Locations Found")
            .setMessage("This meter is not in any database. No locations are configured yet. Would you like to add some locations first, or continue with manual entry?")
            .setPositiveButton("Add Locations") { _, _ ->
                navigateToLocationFragment()
            }
            .setNegativeButton("Continue with Manual Entry") { _, _ ->
                currentMeter = MeterStatus(
                    serialNumber = detectedSerial,
                    place = "",
                    number = "",
                    fromFile = "Scanner Input", // Special marker for new meters
                    registered = false,
                    isChecked = false
                )
                currentFlowState = FlowState.NEEDS_METER_DATA
                updateUI()
            }
            .setCancelable(false)
            .show()
    }

    private fun navigateToLocationFragment() {
        try {
            findNavController().navigate(R.id.action_detectedFragment_to_locationFragment)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation to LocationFragment failed: ${e.message}", e)
            Toast.makeText(requireContext(), "Navigation error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateBack() {
        try {
            findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation back failed: ${e.message}", e)
            findNavController().popBackStack()
        }
    }

    // ADD this to DetectedFragment - override onResume to refresh data
    override fun onResume() {
        super.onResume()

        // Check if we should refresh data (when coming back from dialog)
        val lastUpdate = arguments?.getLong("lastDataUpdate", 0L) ?: 0L
        val currentTime = System.currentTimeMillis()

        // If dialog was recently dismissed (within last 5 seconds), refresh
        if (currentTime - lastUpdate < 5000 && lastUpdate > 0) {
            Log.d(TAG, "Auto-refreshing after dialog dismiss")
            refreshCurrentMeterData()
        }
    }

    // ADD this method to DetectedFragment
    private fun refreshCurrentMeterData() {
        if (detectedSerial.isNotBlank()) {
            showDatabaseRefreshState()

            lifecycleScope.launch {
                try {
                    kotlinx.coroutines.delay(500)

                    val refreshedMeter = filesViewModel.findMeterBySerial(detectedSerial)

                    if (refreshedMeter != null) {
                        currentMeter = refreshedMeter
                        Log.d(TAG, "✅ Auto-refresh successful")

                        showDataUpdateSuccessState()
                        kotlinx.coroutines.delay(1000)

                        // Check if data is now complete
                        if (isValidMeterLocation(refreshedMeter.place) && isValidMeterNumber(refreshedMeter.number)) {
                            currentFlowState = FlowState.READY_FOR_SN_CHECK
                            updateUI()
                            Toast.makeText(requireContext(), "✅ Ready for verification!", Toast.LENGTH_SHORT).show()
                        } else {
                            evaluateMeterState(refreshedMeter)
                        }
                    } else {
                        // If still not found, keep current state
                        evaluateMeterState(currentMeter ?: return@launch)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in auto-refresh: ${e.message}", e)
                    // Just continue with current state
                    updateUI()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}