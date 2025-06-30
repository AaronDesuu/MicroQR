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
            showErrorResult("", "No QR data received")
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

        binding.detectedSerialText.text = scannedSerial
        binding.detectedInfoText.text = "Scanned QR Code for MeterCheck workflow"

        // Process the meter check scan with location/numbering validation
        processMeterCheckScan(scannedSerial, targetSerial, targetLocation, meterNumber)
    }

    private fun handleDefaultContext(rawQrValue: String) {
        // Your existing default handling logic
        val serialNumberToCheck = parseSerialNumber(rawQrValue)
        binding.detectedSerialText.text = serialNumberToCheck

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

                    Log.d(TAG, "🔍 After update - found meter: ${updatedMeter != null}, checked: ${updatedMeter?.isChecked}")

                    // Show success regardless of LiveData state since database operation succeeded
                    showSuccessResult(serialNumberToCheck, foundItemFile ?: "Unknown File")

                } else {
                    // FAILURE CASE - Serial number was not found
                    Log.w(TAG, "⚠️ Serial number '$serialNumberToCheck' not found in database")
                    showNotFoundResult(serialNumberToCheck)
                }

            } catch (e: Exception) {
                Log.e(TAG, "💥 Exception during database update: ${e.message}", e)
                showErrorResult(serialNumberToCheck, "Database error: ${e.message}")
            }
        }
    }

    private fun processMeterCheckScan(scannedSerial: String, targetSerial: String?, targetLocation: String?, meterNumber: String?) {
        lifecycleScope.launch {
            try {
                // Check if the scanned meter exists in database
                val currentMeters = filesViewModel.meterStatusList.value ?: emptyList()
                val existingMeter = currentMeters.find { it.serialNumber == scannedSerial }

                if (existingMeter != null) {
                    // Meter exists - check if it has valid location and number
                    val hasValidLocation = isValidLocation(existingMeter.place)
                    val hasValidNumber = isValidNumber(existingMeter.number)

                    if (!hasValidLocation || !hasValidNumber) {
                        // Force user to set location and/or number
                        showLocationNumberDialog(existingMeter, hasValidLocation, hasValidNumber)
                    } else {
                        // Meter has valid data, proceed with scan
                        markMeterAsScanned(scannedSerial, existingMeter.place, existingMeter.number)
                    }
                } else {
                    // Meter not found - show option to add with location and number
                    showAddMeterDialog(scannedSerial)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ MeterCheck ERROR: ${e.message}", e)
                showErrorResult(scannedSerial, "Error processing scan: ${e.message}")
            }
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
            !hasValidLocation && !hasValidNumber -> "This meter needs both location and number assignment:"
            !hasValidLocation -> "This meter needs location assignment:"
            !hasValidNumber -> "This meter needs number assignment:"
            else -> "Update meter information:"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Meter Setup Required")
            .setMessage(message)
            .setView(dialogView)
            .setPositiveButton("Save & Continue") { _, _ ->
                val selectedLocation = getSelectedLocation(locationSpinner, customLocationInput)
                val enteredNumber = numberInput.text?.toString()?.trim() ?: ""

                if (selectedLocation.isNotEmpty() && enteredNumber.isNotEmpty()) {
                    updateMeterLocationAndNumber(meter, selectedLocation, enteredNumber)
                } else {
                    Toast.makeText(requireContext(), "Both location and number are required", Toast.LENGTH_SHORT).show()
                    showLocationNumberDialog(meter, hasValidLocation, hasValidNumber) // Show again
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                // Go back to meter list
                findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
            }
            .setCancelable(false)
            .show()
    }

    private fun showAddMeterDialog(scannedSerial: String) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_meter_location_number, null)

        val locationSpinner = dialogView.findViewById<Spinner>(R.id.location_spinner)
        val customLocationInput = dialogView.findViewById<TextInputEditText>(R.id.custom_location_input)
        val numberInput = dialogView.findViewById<TextInputEditText>(R.id.meter_number_input)

        setupLocationSpinner(locationSpinner, customLocationInput, "")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add New Meter")
            .setMessage("Serial number '$scannedSerial' not found in database. Add it with location and number:")
            .setView(dialogView)
            .setPositiveButton("Add Meter") { _, _ ->
                val selectedLocation = getSelectedLocation(locationSpinner, customLocationInput)
                val enteredNumber = numberInput.text?.toString()?.trim() ?: ""

                if (selectedLocation.isNotEmpty() && enteredNumber.isNotEmpty()) {
                    addNewMeterToDatabase(scannedSerial, selectedLocation, enteredNumber)
                } else {
                    Toast.makeText(requireContext(), "Both location and number are required", Toast.LENGTH_SHORT).show()
                    showAddMeterDialog(scannedSerial) // Show again
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
            }
            .setCancelable(false)
            .show()
    }

    private fun setupLocationSpinner(spinner: Spinner, customInput: TextInputEditText, currentLocation: String) {
        // Get locations from LocationViewModel (you'll need to access this)
        val availableLocations = getAvailableLocations()

        val locations = mutableListOf<String>().apply {
            add("Choose Location")
            addAll(availableLocations)
            add("Enter Custom Location")
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, locations)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Set current location if it exists
        if (currentLocation.isNotEmpty() && availableLocations.contains(currentLocation)) {
            val index = locations.indexOf(currentLocation)
            if (index > 0) spinner.setSelection(index)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustomSelected = locations[position] == "Enter Custom Location"
                customInput.visibility = if (isCustomSelected) View.VISIBLE else View.GONE

                if (isCustomSelected) {
                    customInput.requestFocus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun getSelectedLocation(spinner: Spinner, customInput: TextInputEditText): String {
        val selectedItem = spinner.selectedItem.toString()
        return when (selectedItem) {
            "Enter Custom Location" -> customInput.text?.toString()?.trim() ?: ""
            "Choose Location" -> ""
            else -> selectedItem
        }
    }

    private fun getAvailableLocations(): List<String> {
        // In a real implementation, you'd get this from LocationViewModel or SharedPreferences
        // For now, return some default locations.
        // TODO: Integrate with LocationViewModel or shared storage
        return listOf(
            "Building A - Floor 1",
            "Building A - Floor 2",
            "Building B - Basement",
            "Outdoor Area",
            "Parking Garage",
            "Utility Room",
            "Main Entrance",
            "Loading Dock",
            "Server Room",
            "Conference Room"
        )
    }

    // TODO: Add this method to FilesViewModel for proper implementation
    private fun updateMeterLocationAndNumber(meter: MeterStatus, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // TODO: Implement this method in FilesViewModel:
                // filesViewModel.updateMeterLocationAndNumber(meter.serialNumber, meter.fromFile, location, number)

                // For now, just mark as scanned
                markMeterAsScanned(meter.serialNumber, location, number)

                Toast.makeText(
                    requireContext(),
                    "Meter updated: #$number at $location",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error updating meter: ${e.message}", e)
                showErrorResult(meter.serialNumber, "Failed to update meter")
            }
        }
    }

    // TODO: Add this method to FilesViewModel for proper implementation
    private fun addNewMeterToDatabase(serialNumber: String, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // TODO: Implement this method in FilesViewModel:
                // filesViewModel.addNewMeter(serialNumber, number, location)

                // For now, just mark as scanned
                markMeterAsScanned(serialNumber, location, number)

                Toast.makeText(
                    requireContext(),
                    "New meter added: #$number at $location",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error adding meter: ${e.message}", e)
                showErrorResult(serialNumber, "Failed to add meter")
            }
        }
    }

    private fun markMeterAsScanned(serialNumber: String, location: String, number: String) {
        lifecycleScope.launch {
            try {
                val (success, foundFile) = filesViewModel.updateMeterCheckedStatusBySerialAsync(serialNumber)

                if (success) {
                    Log.d(TAG, "✅ MeterCheck SUCCESS: Serial $serialNumber marked as scanned")

                    binding.detectedInfoText.text = "✅ Meter #$number at $location\n" +
                            "Serial: $serialNumber\n" +
                            "Status: Successfully scanned!"

                    binding.nextButton.text = getString(R.string.back_to_meter_list)
                    binding.nextButton.isEnabled = true
                    binding.nextButton.setOnClickListener {
                        findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
                    }

                } else {
                    showErrorResult(serialNumber, "Failed to update scan status")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error marking meter as scanned: ${e.message}", e)
                showErrorResult(serialNumber, "Error updating scan status: ${e.message}")
            }
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

        Log.d(TAG, "📊 Data Status:")
        Log.d(TAG, "  - MeterCheck data: $meterCheckCount meters")
        Log.d(TAG, "  - General meter data: $generalMeterCount meters")

        // Show helpful button based on the situation
        when {
            meterCheckCount == 0 && generalMeterCount > 0 -> {
                binding.nextButton.text = getString(R.string.go_to_files)
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().navigate(R.id.navigation_files)
                }
            }
            meterCheckCount == 0 && generalMeterCount == 0 -> {
                binding.nextButton.text = getString(R.string.upload_csv_files)
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().navigate(R.id.navigation_files)
                }
            }
            else -> {
                binding.nextButton.text = getString(R.string.scan_again_button)
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().popBackStack()
                }
            }
        }
    }

    private fun showNotFoundResult(serialNumber: String) {
        Log.w(TAG, "⚠️ Showing NOT FOUND result for $serialNumber")

        binding.detectedInfoText.text = binding.detectedInfoText.text.toString() +
                "\n\n⚠️ " + getString(R.string.status_not_found)

        binding.nextButton.text = getString(R.string.scan_again_button)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun showErrorResult(serialNumber: String, errorMessage: String?) {
        Log.e(TAG, "💥 Showing ERROR result for $serialNumber: $errorMessage")

        val displayMessage = getString(R.string.failed_to_load_statistics, errorMessage ?: getString(R.string.no_data_available))
        Toast.makeText(requireContext(), displayMessage, Toast.LENGTH_LONG).show()
        binding.detectedInfoText.text = binding.detectedInfoText.text.toString() + "\n\n❌ " + getString(R.string.status_not_found)

        binding.nextButton.text = getString(R.string.scan_again_button)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun argumentsToString(args: Bundle?): String {
        if (args == null) return "null"
        val sb = StringBuilder("Bundle[")
        for (key in args.keySet()) {
            sb.append("\n $key = ${args[key]};")
        }
        sb.append("\n]")
        return sb.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}