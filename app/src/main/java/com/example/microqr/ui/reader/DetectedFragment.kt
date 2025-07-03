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

            Log.d(TAG, getString(R.string.log_detected_fragment_created, args.toString()))
            Log.d(TAG, getString(R.string.log_raw_qr_value, rawQrValue))
            Log.d(TAG, getString(R.string.log_scan_context, scanContext))

            if (dataUpdated) {
                Log.d(TAG, "Data was updated, refreshing meter state")
            }
        }

        if (rawQrValue.isBlank()) {
            Log.e(TAG, getString(R.string.log_error_raw_qr_null))
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

        locationRepository = LocationRepository(requireContext())

        setupClickListeners()
        processQrData()
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
                else -> navigateBack()
            }
        }
    }

    private fun processQrData() {
        if (rawQrValue.isBlank()) {
            showErrorState(getString(R.string.error_generic))
            return
        }

        currentFlowState = FlowState.PROCESSING
        updateUI()

        // Parse serial number from QR data
        detectedSerial = parseSerialNumber(rawQrValue)

        lifecycleScope.launch {
            when (scanContext) {
                "METER_CHECK" -> {
                    Log.d(TAG, getString(R.string.log_handling_meter_check_context))
                    handleMeterCheckContext()
                }
                else -> {
                    Log.d(TAG, getString(R.string.log_handling_default_context))
                    handleDefaultContext()
                }
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
                Log.d(TAG, getString(R.string.log_meter_found_in_metercheck, detectedSerial))
                currentMeter = existingMeter
                evaluateMeterState(existingMeter)
            } else {
                Log.d(TAG, getString(R.string.log_meter_not_found_in_metercheck, detectedSerial))
                checkIfLocationsExist()
            }

        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.log_metercheck_error, e.message))
            showErrorState(getString(R.string.error_processing_meter))
        }
    }

    private suspend fun handleDefaultContext() {
        try {
            val existingMeter = filesViewModel.findMeterBySerial(detectedSerial)

            if (existingMeter != null) {
                Log.d(TAG, "Meter found, evaluating state")
                currentMeter = existingMeter
                evaluateMeterState(existingMeter)
            } else {
                Log.d(TAG, "Meter not found, creating new meter entry")
                checkIfLocationsExist()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing meter: ${e.message}")
            showErrorState(getString(R.string.error_processing_meter))
        }
    }

    private suspend fun checkIfLocationsExist() {
        try {
            val locations = locationRepository.getActiveLocationNames()
            if (locations.isEmpty()) {
                showNoLocationsDialog()
            } else {
                // Create new meter with missing data - this is a NEW meter
                currentMeter = MeterStatus(
                    serialNumber = detectedSerial,
                    place = "",
                    number = "",
                    fromFile = getString(R.string.scanner_input),
                    registered = false,
                    isChecked = false
                )
                currentFlowState = FlowState.NEEDS_METER_DATA
                updateUI()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking locations: ${e.message}")
            showErrorState(getString(R.string.error_checking_locations))
        }
    }

    private fun evaluateMeterState(meter: MeterStatus) {
        val needsLocation = !isValidMeterLocation(meter.place)
        val needsNumber = !isValidMeterNumber(meter.number)

        Log.d(TAG, "Evaluating meter state - needsLocation: $needsLocation, needsNumber: $needsNumber, registered: ${meter.registered}")
        Log.d(TAG, "Meter details - place: '${meter.place}', number: '${meter.number}', registered: ${meter.registered}")

        when {
            needsLocation || needsNumber -> {
                Log.d(TAG, "Setting state to NEEDS_METER_DATA - missing data detected")
                currentFlowState = FlowState.NEEDS_METER_DATA
            }
            !meter.registered -> {
                Log.d(TAG, "Setting state to READY_FOR_SN_CHECK - data complete, needs registration")
                currentFlowState = FlowState.READY_FOR_SN_CHECK
            }
            meter.registered -> {
                Log.d(TAG, "Setting state to SN_VERIFIED - meter already registered")
                currentFlowState = FlowState.SN_VERIFIED
            }
            else -> {
                Log.d(TAG, "Setting state to READY_FOR_SN_CHECK - default case")
                currentFlowState = FlowState.READY_FOR_SN_CHECK
            }
        }

        updateUI()
    }

    private fun isValidMeterNumber(number: String?): Boolean {
        return !number.isNullOrBlank() &&
                number != "000" &&
                number != "0" &&
                number != "-" &&
                number != getString(R.string.default_meter_number)
    }

    private fun isValidMeterLocation(place: String?): Boolean {
        return !place.isNullOrBlank() &&
                place.lowercase() != "unknown" &&
                place != getString(R.string.default_meter_place)
    }

    private fun updateUI() {
        // Always set the detected serial number first
        binding.detectedSerial.text = detectedSerial

        when (currentFlowState) {
            FlowState.PROCESSING -> showProcessingState()
            FlowState.NEEDS_METER_DATA -> showNeedsMeterDataState()
            FlowState.READY_FOR_SN_CHECK -> showReadyForSnCheckState()
            FlowState.SN_VERIFIED -> showSnVerifiedState()
            FlowState.ERROR -> showErrorState(getString(R.string.error_generic))
        }
    }

    private fun showProcessingState() {
        binding.statusIcon.setImageResource(R.drawable.ic_hourglass_empty)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        binding.titleText.text = getString(R.string.processing_detection)
        binding.nextButton.text = getString(R.string.processing)
        binding.nextButton.isEnabled = false
        updateStatusRows(false, false, false)
    }

    private fun showNeedsMeterDataState() {
        binding.statusIcon.setImageResource(R.drawable.ic_warning)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        binding.titleText.text = getString(R.string.detection_completed)
        binding.nextButton.text = getString(R.string.set_meter_data)
        binding.nextButton.isEnabled = true

        currentMeter?.let { meter ->
            val hasLocation = isValidMeterLocation(meter.place)
            val hasNumber = isValidMeterNumber(meter.number)

            updateStatusRows(hasLocation, hasNumber, false)
            updateMeterInfoDisplay(meter, hasLocation, hasNumber)
        }
    }

    private fun showReadyForSnCheckState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = getString(R.string.detection_completed)
        binding.nextButton.text = getString(R.string.check_serial_number)
        binding.nextButton.isEnabled = true

        currentMeter?.let { meter ->
            updateStatusRows(true, true, false)
            updateMeterInfoDisplay(meter, true, true)
        }
    }

    private fun showSnVerifiedState() {
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        binding.titleText.text = getString(R.string.verification_completed)
        binding.nextButton.text = getString(R.string.check_meters)
        binding.nextButton.isEnabled = true

        currentMeter?.let { meter ->
            updateStatusRows(true, true, true)
            updateMeterInfoDisplay(meter, true, true)
        }
    }

    private fun showErrorState(message: String) {
        binding.statusIcon.setImageResource(R.drawable.ic_error)
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.error_red))
        binding.titleText.text = getString(R.string.detection_error)
        binding.nextButton.text = getString(R.string.back)
        binding.nextButton.isEnabled = true
        updateStatusRows(false, false, false)

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun updateStatusRows(hasLocation: Boolean, hasNumber: Boolean, snVerified: Boolean) {
        // Location status
        updateStatusIcon(binding.locationStatusIcon, hasLocation)
        binding.locationValue.text = if (hasLocation) {
            currentMeter?.place ?: getString(R.string.unknown)
        } else {
            getString(R.string.not_set)
        }

        // Number status
        updateStatusIcon(binding.numberStatusIcon, hasNumber)
        binding.numberValue.text = if (hasNumber) {
            currentMeter?.number ?: getString(R.string.unknown)
        } else {
            getString(R.string.not_set)
        }

        // Serial verification status
        updateStatusIcon(binding.serialStatusIcon, snVerified)
        binding.serialStatusText.text = if (snVerified) {
            getString(R.string.verified)
        } else {
            getString(R.string.pending_verification)
        }
    }

    private fun updateStatusIcon(imageView: android.widget.ImageView, isComplete: Boolean) {
        if (isComplete) {
            imageView.setImageResource(R.drawable.ic_check_circle)
            imageView.setColorFilter(ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            imageView.setImageResource(R.drawable.ic_warning)
            imageView.setColorFilter(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        }
    }

    private fun updateMeterInfoDisplay(meter: MeterStatus, hasLocation: Boolean, hasNumber: Boolean) {
        // Update the display values in the card
        binding.locationValue.text = if (hasLocation) meter.place else getString(R.string.not_set)
        binding.numberValue.text = if (hasNumber) meter.number else getString(R.string.not_set)
    }

    private fun openMeterDataInput() {
        currentMeter?.let { meter ->
            val needsLocation = !isValidMeterLocation(meter.place)
            val needsNumber = !isValidMeterNumber(meter.number)

            // Determine if this is a new meter (not found in any database)
            val isNewMeter = meter.fromFile == getString(R.string.scanner_input)

            Log.d(TAG, "Opening meter data input - isNewMeter: $isNewMeter")

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
                    // Update meter as registered and checked using the repository
                    val updatedMeter = meter.copy(
                        registered = true,
                        isChecked = true
                    )

                    // Use the repository directly for updating a single meter
                    filesViewModel.getMeterRepository().updateMeter(updatedMeter)
                    currentMeter = updatedMeter

                    Log.d(TAG, "S/N check completed successfully")
                    currentFlowState = FlowState.SN_VERIFIED
                    updateUI()

                    Toast.makeText(requireContext(), getString(R.string.serial_verification_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing S/N check: ${e.message}")
                Toast.makeText(requireContext(), getString(R.string.error_verifying_serial), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNoLocationsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.no_locations_found))
            .setMessage(getString(R.string.no_locations_message_new_meter))
            .setPositiveButton(getString(R.string.add_locations)) { _, _ ->
                navigateToLocationFragment()
            }
            .setNegativeButton(getString(R.string.continue_with_manual_entry)) { _, _ ->
                // Create meter with empty location and proceed - mark as new meter
                currentMeter = MeterStatus(
                    serialNumber = detectedSerial,
                    place = "",
                    number = "",
                    fromFile = getString(R.string.scanner_input), // Special marker for new meters
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
            Log.e(TAG, "Navigation to LocationFragment failed: ${e.message}")
            Toast.makeText(requireContext(), getString(R.string.error_navigation), Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateBack() {
        try {
            findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation back failed: ${e.message}")
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}