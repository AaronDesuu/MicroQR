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
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.data.repository.LocationRepository
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

    private var detectedSerial: String = ""
    private var currentMeter: MeterStatus? = null
    private var currentFlowState: FlowState = FlowState.PROCESSING

    private enum class FlowState {
        PROCESSING,           // Initial processing state
        NEEDS_METER_DATA,     // Missing location/number data
        READY_FOR_SN_CHECK,   // All data complete, ready to verify S/N
        SN_VERIFIED,          // S/N verified, ready to go back
        ERROR                 // Error state
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

        val args = arguments
        val rawQrValue = args?.getString("rawQrValue")
        val scanContext = args?.getString("scanContext")
        val dataUpdated = args?.getBoolean("dataUpdated", false) ?: false

        Log.d(TAG, "DetectedFragment created with rawQrValue: $rawQrValue, scanContext: $scanContext, dataUpdated: $dataUpdated")

        if (rawQrValue.isNullOrBlank()) {
            Log.e(TAG, "ERROR: rawQrValue is null or blank!")
            showErrorState(getString(R.string.error_no_qr_data))
            return
        }

        detectedSerial = rawQrValue
        binding.detectedSerial.text = detectedSerial

        // If data was just updated, refresh the flow
        if (dataUpdated) {
            processDetectedMeter()
        } else {
            // Handle different contexts
            when (scanContext) {
                "METER_CHECK" -> handleMeterCheckContext()
                else -> handleDefaultContext()
            }
        }

        setupClickListeners()
    }

    private fun handleMeterCheckContext() {
        Log.d(TAG, "Handling METER_CHECK context")
        processDetectedMeter()
    }

    private fun handleDefaultContext() {
        Log.d(TAG, "Handling default context")
        processDetectedMeter()
    }

    private fun processDetectedMeter() {
        Log.d(TAG, "Processing detected meter with serial: $detectedSerial")

        lifecycleScope.launch {
            try {
                // Find meter by serial number
                currentMeter = filesViewModel.findMeterBySerial(detectedSerial)

                if (currentMeter != null) {
                    Log.d(TAG, "Found existing meter: $currentMeter")
                    evaluateMeterState(currentMeter!!)
                } else {
                    Log.d(TAG, "Meter not found, creating new meter entry")
                    checkIfLocationsExist()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing meter: ${e.message}")
                showErrorState(getString(R.string.error_processing_meter))
            }
        }
    }

    private suspend fun checkIfLocationsExist() {
        try {
            val locations = locationRepository.getActiveLocationNames()
            if (locations.isEmpty()) {
                showNoLocationsDialog()
            } else {
                // Create new meter with missing data
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
        val needsLocation = meter.place.isBlank() || meter.place == "Unknown" || meter.place == getString(R.string.default_meter_place)
        val needsNumber = meter.number.isBlank() || meter.number == "000" || meter.number == getString(R.string.default_meter_number)

        Log.d(TAG, "Evaluating meter state - needsLocation: $needsLocation, needsNumber: $needsNumber, registered: ${meter.registered}")

        when {
            needsLocation || needsNumber -> {
                currentFlowState = FlowState.NEEDS_METER_DATA
            }
            !meter.registered -> {
                currentFlowState = FlowState.READY_FOR_SN_CHECK
            }
            meter.registered -> {
                currentFlowState = FlowState.SN_VERIFIED
            }
            else -> {
                currentFlowState = FlowState.READY_FOR_SN_CHECK
            }
        }

        updateUI()
    }

    private fun updateUI() {
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
            val hasLocation = meter.place.isNotBlank() && meter.place != "Unknown" && meter.place != getString(R.string.default_meter_place)
            val hasNumber = meter.number.isNotBlank() && meter.number != "000" && meter.number != getString(R.string.default_meter_number)

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

    private fun openMeterDataInput() {
        currentMeter?.let { meter ->
            val needsLocation = meter.place.isBlank() || meter.place == "Unknown" || meter.place == getString(R.string.default_meter_place)
            val needsNumber = meter.number.isBlank() || meter.number == "000" || meter.number == getString(R.string.default_meter_number)

            val fragment = MeterDataInputFragment.newInstance(
                serialNumber = detectedSerial,
                currentLocation = meter.place,
                currentNumber = meter.number,
                needsLocation = needsLocation,
                needsNumber = needsNumber
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
            .setMessage(getString(R.string.no_locations_message))
            .setPositiveButton(getString(R.string.add_locations)) { _, _ ->
                navigateToLocationFragment()
            }
            .setNegativeButton(getString(R.string.continue_anyway)) { _, _ ->
                // Create meter with empty location and proceed
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