package com.example.microqr.ui.reader

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.microqr.R
import com.example.microqr.databinding.FragmentDetectedBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetectedFragment : Fragment() {

    companion object {
        private const val TAG = "DetectedFragment"
    }

    private var _binding: FragmentDetectedBinding? = null
    private val binding get() = _binding!!

    private val filesViewModel: FilesViewModel by activityViewModels()

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

        val args = arguments
        val rawQrValue = args?.getString("rawQrValue")
        val scanContext = args?.getString("scanContext")

        Log.d(TAG, "📱 DetectedFragment created with args: ${argumentsToString(args)}")
        Log.d(TAG, "🔍 Raw QR Value: $rawQrValue")
        Log.d(TAG, "📋 Scan Context: $scanContext")

        if (rawQrValue.isNullOrBlank()) {
            Log.e(TAG, "❌ ERROR: rawQrValue is null or blank!")
            showErrorResult("", getString(R.string.error_no_qr_data))
            return
        }

        // Handle different contexts
        when (scanContext) {
            "METER_CHECK" -> handleMeterCheckContext(rawQrValue, args)
            else -> handleDefaultContext(rawQrValue)
        }
    }

    private fun handleMeterCheckContext(rawQrValue: String, args: Bundle?) {
        val targetSerial = args?.getString("targetSerial")
        val targetLocation = args?.getString("targetLocation")
        val meterNumber = args?.getString("meterNumber")

        Log.d(TAG, "🎯 MeterCheck Context - Target: $targetSerial, Location: $targetLocation, Number: $meterNumber")

        // Parse the scanned QR to get serial number
        val scannedSerial = parseSerialNumber(rawQrValue)

        // Fixed: Use the correct view ID from layout
        binding.serialNumberText.text = scannedSerial
        binding.detectedInfoText.text = getString(R.string.scanned_qr_meter_check)

        // Process the meter check scan with location/numbering validation
        processMeterCheckScan(scannedSerial, targetSerial, targetLocation, meterNumber)
    }

    private fun handleDefaultContext(rawQrValue: String) {
        // Your existing default handling logic
        val serialNumberToCheck = parseSerialNumber(rawQrValue)
        // Fixed: Use the correct view ID from layout
        binding.serialNumberText.text = serialNumberToCheck

        // Continue with existing logic...
        processDetectedSerial(serialNumberToCheck)
    }

    private fun processDetectedSerial(serialNumberToCheck: String) {
        // Your existing implementation for default context
        // This would include your current DetectedFragment logic

        val meterCheckCount = filesViewModel.meterCheckMeters.value?.size ?: 0
        val generalMeterCount = filesViewModel.meterStatusList.value?.size ?: 0

        Log.d(TAG, "📊 Data Status:")
        Log.d(TAG, "  - MeterCheck data: $meterCheckCount meters")
        Log.d(TAG, "  - General meter data: $generalMeterCount meters")

        // Use coroutine to handle async database operation
        lifecycleScope.launch {
            try {
                // Use the async method for proper database handling
                val (success, foundItemFile) = filesViewModel.updateMeterCheckedStatusBySerialAsync(serialNumberToCheck)

                Log.d(TAG, "📝 Async update result: success=$success, foundFile=$foundItemFile")

                if (success) {
                    // SUCCESS CASE - Serial number was found and updated
                    Log.d(TAG, "✅ SUCCESS: Serial number found and processed!")

                    // Wait a bit for the LiveData to refresh
                    delay(1000)

                    // Check the updated status in LiveData
                    val updatedMeter = if (foundItemFile != null) {
                        filesViewModel.meterCheckMeters.value?.find {
                            it.serialNumber == serialNumberToCheck && it.fromFile == foundItemFile
                        } ?: filesViewModel.meterStatusList.value?.find {
                            it.serialNumber == serialNumberToCheck && it.fromFile == foundItemFile
                        }
                    } else {
                        filesViewModel.meterStatusList.value?.find { it.serialNumber == serialNumberToCheck }
                    }

                    showSuccessResult(serialNumberToCheck, foundItemFile ?: getString(R.string.unknown_file))

                } else {
                    // FAILURE CASE - Serial number not found
                    Log.d(TAG, "❌ FAILURE: Serial number not found!")
                    showErrorResult(serialNumberToCheck, getString(R.string.error_serial_not_found))
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing serial: ${e.message}", e)
                showErrorResult(serialNumberToCheck, getString(R.string.error_processing_serial, e.message))
            }
        }
    }

    private fun processMeterCheckScan(scannedSerial: String, targetSerial: String?, targetLocation: String?, meterNumber: String?) {
        lifecycleScope.launch {
            try {
                // ✅ FIXED: Only search in MeterCheck data, not all meters
                val foundMeter = filesViewModel.meterCheckMeters.value?.find { meter ->
                    meter.serialNumber == scannedSerial
                }

                Log.d(TAG, "🔍 Searching in MeterCheck data only: ${filesViewModel.meterCheckMeters.value?.size} meters")
                Log.d(TAG, "🎯 Looking for serial: $scannedSerial")
                Log.d(TAG, "📝 Found meter: ${foundMeter?.serialNumber}")

                if (foundMeter != null) {
                    Log.d(TAG, "✅ Found meter in MeterCheck data: ${foundMeter.serialNumber}")

                    // Check if location and number are valid
                    val hasValidLocation = isValidLocation(foundMeter.place)
                    val hasValidNumber = isValidNumber(foundMeter.number)

                    if (!hasValidLocation || !hasValidNumber) {
                        // Show dialog to collect missing information
                        showLocationNumberDialog(foundMeter, hasValidLocation, hasValidNumber)
                        return@launch
                    }

                    // Mark as scanned using MeterCheck specific method
                    val (success, fileName) = updateMeterCheckStatusAsync(scannedSerial)

                    if (success) {
                        showSuccessResult(scannedSerial, fileName ?: getString(R.string.unknown_file))
                        setupNavigationButton()
                    } else {
                        showErrorResult(scannedSerial, getString(R.string.error_update_scan_status))
                    }

                } else {
                    // ✅ FIXED: Meter not found in MeterCheck data - show option to add it to MeterCheck
                    Log.d(TAG, "❌ Meter not found in MeterCheck data: $scannedSerial")
                    showAddMeterToMeterCheckDialog(scannedSerial)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ MeterCheck ERROR: ${e.message}", e)
                showErrorResult(scannedSerial, getString(R.string.error_processing_scan, e.message))
            }
        }
    }
    private suspend fun updateMeterCheckStatusAsync(serialNumber: String): Pair<Boolean, String?> {
        return try {
            // Only look in MeterCheck meters
            val meterCheckMeters = filesViewModel.meterCheckMeters.value ?: emptyList()
            val targetMeter = meterCheckMeters.find { it.serialNumber == serialNumber }

            if (targetMeter != null) {
                filesViewModel.updateMeterCheckedStatus(serialNumber, true, targetMeter.fromFile)
                Pair(true, targetMeter.fromFile)
            } else {
                Log.w(TAG, "❌ Serial $serialNumber not found in MeterCheck data")
                Pair(false, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating MeterCheck status: ${e.message}", e)
            Pair(false, null)
        }
    }

    private fun isValidLocation(location: String): Boolean {
        return location.isNotBlank() &&
                location.lowercase() !in listOf("unknown", "n/a", "tbd", "pending", "-", "null")
    }

    private fun isValidNumber(number: String): Boolean {
        return number.isNotBlank() &&
                number != "-" &&
                number.lowercase() != "unknown" &&
                number.lowercase() != "pending"
    }

    private fun showLocationNumberDialog(meter: MeterStatus, hasValidLocation: Boolean, hasValidNumber: Boolean) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_meter_location_number, null)

        val locationSpinner = dialogView.findViewById<Spinner>(R.id.location_spinner)
        val customLocationInput = dialogView.findViewById<TextInputEditText>(R.id.custom_location_input)
        val numberInput = dialogView.findViewById<TextInputEditText>(R.id.meter_number_input)

        // Setup location spinner with data from LocationFragment
        setupLocationSpinner(locationSpinner, customLocationInput, if (hasValidLocation) meter.place else "")

        // Pre-fill number if valid
        if (hasValidNumber) {
            numberInput.setText(meter.number)
        }

        val message = when {
            !hasValidLocation && !hasValidNumber -> getString(R.string.meter_needs_location_and_number)
            !hasValidLocation -> getString(R.string.meter_needs_location)
            !hasValidNumber -> getString(R.string.meter_needs_number)
            else -> getString(R.string.update_meter_info)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.meter_setup_required))
            .setMessage(message)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save_and_continue)) { _, _ ->
                val selectedLocation = getSelectedLocation(locationSpinner, customLocationInput)
                val enteredNumber = numberInput.text?.toString()?.trim() ?: ""

                if (selectedLocation.isNotEmpty() && enteredNumber.isNotEmpty()) {
                    updateMeterLocationAndNumber(meter, selectedLocation, enteredNumber)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.error_location_number_required), Toast.LENGTH_SHORT).show()
                    showLocationNumberDialog(meter, hasValidLocation, hasValidNumber) // Show again
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                // Go back to meter list
                findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
            }
            .setCancelable(false)
            .show()
    }

    private fun showAddMeterToMeterCheckDialog(scannedSerial: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_meter_location_number, null)

        val locationSpinner = dialogView.findViewById<Spinner>(R.id.location_spinner)
        val customLocationInput = dialogView.findViewById<TextInputEditText>(R.id.custom_location_input)
        val numberInput = dialogView.findViewById<TextInputEditText>(R.id.meter_number_input)

        setupLocationSpinner(locationSpinner, customLocationInput, "")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_meter_to_metercheck))
            .setMessage(getString(R.string.serial_not_found_in_metercheck, scannedSerial))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.add_to_metercheck)) { _, _ ->
                val selectedLocation = getSelectedLocation(locationSpinner, customLocationInput)
                val enteredNumber = numberInput.text?.toString()?.trim() ?: ""

                if (selectedLocation.isNotEmpty() && enteredNumber.isNotEmpty()) {
                    addMeterToMeterCheck(scannedSerial, selectedLocation, enteredNumber)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.error_location_number_required), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
            }
            .setCancelable(false)
            .show()
    }

    private fun addMeterToMeterCheck(serialNumber: String, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // ✅ FIXED: Use only 3 parameters (no fromFile parameter)
                filesViewModel.addNewMeter(serialNumber, location, number)

                // After adding the meter, we need to process it for MeterCheck destination
                // Since the meter was just added, it should be in the most recent "manual_entry" type file
                delay(500) // Give a moment for the database to update

                // Mark as scanned using MeterCheck-specific method
                val (success, fileName) = updateMeterCheckStatusOnly(serialNumber)

                if (success) {
                    showSuccessResult(serialNumber, fileName ?: getString(R.string.unknown_file))
                    setupNavigationButton()
                } else {
                    showErrorResult(serialNumber, getString(R.string.error_update_scan_status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding meter to MeterCheck: ${e.message}", e)
                showErrorResult(serialNumber, getString(R.string.error_adding_meter, e.message))
            }
        }
    }

    private fun addNewMeter(serialNumber: String, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // ✅ FIXED: Use only 3 parameters
                filesViewModel.addNewMeter(serialNumber, location, number)

                // Mark as scanned
                val (success, fileName) = filesViewModel.updateMeterCheckedStatusBySerialAsync(serialNumber)

                if (success) {
                    showSuccessResult(serialNumber, fileName ?: getString(R.string.unknown_file))
                    setupNavigationButton()
                } else {
                    showErrorResult(serialNumber, getString(R.string.error_update_scan_status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding meter: ${e.message}", e)
                showErrorResult(serialNumber, getString(R.string.error_adding_meter, e.message))
            }
        }
    }

    private suspend fun updateMeterCheckStatusOnly(serialNumber: String): Pair<Boolean, String?> {
        return try {
            // Only look in MeterCheck meters
            val meterCheckMeters = filesViewModel.meterCheckMeters.value ?: emptyList()
            val targetMeter = meterCheckMeters.find { it.serialNumber == serialNumber }

            if (targetMeter != null) {
                filesViewModel.updateMeterCheckedStatus(serialNumber, true, targetMeter.fromFile)
                Pair(true, targetMeter.fromFile)
            } else {
                // If not found in MeterCheck data, try to find in all meters and mark as scanned
                // This handles the case where meter was just added
                val allMeters = filesViewModel.meterStatusList.value ?: emptyList()
                val newMeter = allMeters.find { it.serialNumber == serialNumber }

                if (newMeter != null) {
                    filesViewModel.updateMeterCheckedStatus(serialNumber, true, newMeter.fromFile)

                    // Also try to process the file for MeterCheck if it's a manual entry
                    if (newMeter.fromFile.contains("manual")) {
                        try {
                            filesViewModel.processForMeterCheck(newMeter.fromFile)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not process file for MeterCheck: ${e.message}")
                        }
                    }

                    Pair(true, newMeter.fromFile)
                } else {
                    Log.w(TAG, "❌ Serial $serialNumber not found even after adding")
                    Pair(false, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating MeterCheck status: ${e.message}", e)
            Pair(false, null)
        }
    }


    private fun setupLocationSpinner(spinner: Spinner, customInput: TextInputEditText, preselectedLocation: String) {
        // Get unique locations from the data
        val locations = mutableSetOf<String>()

        // Add locations from meter data
        filesViewModel.meterCheckMeters.value?.forEach { meter ->
            if (isValidLocation(meter.place)) {
                locations.add(meter.place)
            }
        }

        // Add common locations if needed
        val commonLocations = listOf(
            getString(R.string.location_lobby),
            getString(R.string.location_basement),
            getString(R.string.location_garage),
            getString(R.string.location_rooftop),
            getString(R.string.location_office)
        )
        locations.addAll(commonLocations)

        val locationList = mutableListOf<String>()
        locationList.add(getString(R.string.select_location))
        locationList.addAll(locations.sorted())
        locationList.add(getString(R.string.custom_location))

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, locationList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Handle spinner selection
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedItem = locationList[position]
                if (selectedItem == getString(R.string.custom_location)) {
                    customInput.visibility = View.VISIBLE
                } else {
                    customInput.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Preselect location if provided
        if (preselectedLocation.isNotEmpty()) {
            val index = locationList.indexOf(preselectedLocation)
            if (index != -1) {
                spinner.setSelection(index)
            }
        }
    }

    private fun getSelectedLocation(spinner: Spinner, customInput: TextInputEditText): String {
        val selectedLocation = spinner.selectedItem.toString()
        return if (selectedLocation == getString(R.string.custom_location)) {
            customInput.text?.toString()?.trim() ?: ""
        } else if (selectedLocation == getString(R.string.select_location)) {
            ""
        } else {
            selectedLocation
        }
    }

    private fun updateMeterLocationAndNumber(meter: MeterStatus, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // Update meter details
                filesViewModel.updateMeterLocationAndNumber(meter.serialNumber, location, number)

                // Mark as scanned in MeterCheck context
                val (success, fileName) = updateMeterCheckStatusAsync(meter.serialNumber)

                if (success) {
                    showSuccessResult(meter.serialNumber, fileName ?: getString(R.string.unknown_file))
                    setupNavigationButton()
                } else {
                    showErrorResult(meter.serialNumber, getString(R.string.error_update_scan_status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating meter: ${e.message}", e)
                showErrorResult(meter.serialNumber, getString(R.string.error_updating_meter, e.message))
            }
        }
    }

    private fun setupNavigationButton() {
        binding.nextButton.text = getString(R.string.back_to_meter_list)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
        }
    }

    private fun parseSerialNumber(rawData: String): String {
        // Use the same parsing logic as in your existing code
        return when {
            rawData.contains("F6") || rawData.contains("F7") -> {
                "${rawData.substring(7, 8)}${rawData.substring(3, 5)}${rawData.substring(8, 15)}"
            }
            rawData.contains("FMCBLE") -> {
                rawData.substring(7, 24)
            }
            rawData.length >= 10 -> {
                rawData.take(15)
            }
            else -> {
                rawData
            }
        }
    }

    private fun showSuccessResult(serialNumber: String, fileName: String) {
        Log.d(TAG, "✅ Showing SUCCESS result for $serialNumber from $fileName")

        binding.detectedInfoText.text = binding.detectedInfoText.text.toString() +
                "\n\n✅ " + getString(R.string.meter_verified)

        val meterCheckCount = filesViewModel.meterCheckMeters.value?.size ?: 0
        val generalMeterCount = filesViewModel.meterStatusList.value?.size ?: 0

        val messageBuilder = StringBuilder()
        messageBuilder.append(getString(R.string.success_meter_found, serialNumber))
        messageBuilder.append("\n")
        messageBuilder.append(getString(R.string.file_source, fileName))

        if (meterCheckCount > 0) {
            messageBuilder.append("\n")
            messageBuilder.append(getString(R.string.meter_check_data, meterCheckCount))
        }

        if (generalMeterCount > 0) {
            messageBuilder.append("\n")
            messageBuilder.append(getString(R.string.general_meter_data, generalMeterCount))
        }

        binding.detectedInfoText.text = messageBuilder.toString()

        // Enable next button
        binding.nextButton.isEnabled = true
        binding.nextButton.text = getString(R.string.next)
    }

    private fun showErrorResult(serialNumber: String, errorMessage: String) {
        Log.d(TAG, "❌ Showing ERROR result for $serialNumber: $errorMessage")

        val messageBuilder = StringBuilder()
        if (serialNumber.isNotEmpty()) {
            messageBuilder.append(getString(R.string.scanned_serial, serialNumber))
            messageBuilder.append("\n\n")
        }
        messageBuilder.append("❌ ")
        messageBuilder.append(errorMessage)

        binding.detectedInfoText.text = messageBuilder.toString()

        // Enable back button to try again
        binding.backButton.isEnabled = true
        binding.nextButton.isEnabled = false
    }

    private fun argumentsToString(args: Bundle?): String {
        if (args == null) return "null"
        val sb = StringBuilder("{")
        for (key in args.keySet()) {
            sb.append("$key=${args.get(key)}, ")
        }
        if (sb.length > 1) sb.setLength(sb.length - 2) // Remove last ", "
        sb.append("}")
        return sb.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}