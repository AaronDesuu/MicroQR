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
import com.example.microqr.data.repository.LocationRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetectedFragment : Fragment() {

    companion object {
        private const val TAG = "DetectedFragment"
    }

    private var _binding: FragmentDetectedBinding? = null
    private val binding get() = _binding!!

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var locationRepository: LocationRepository

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

        // Initialize location repository
        locationRepository = LocationRepository(requireContext())

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

        binding.serialNumberText.text = scannedSerial
        binding.detectedInfoText.text = getString(R.string.scanned_qr_meter_check)

        // Process the meter check scan with enhanced location/numbering validation
        processMeterCheckScanWithLocationValidation(scannedSerial, targetSerial, targetLocation, meterNumber)
    }

    private fun handleDefaultContext(rawQrValue: String) {
        val serialNumberToCheck = parseSerialNumber(rawQrValue)
        binding.serialNumberText.text = serialNumberToCheck

        // Process with location validation for default context too
        processDetectedSerialWithLocationValidation(serialNumberToCheck)
    }

    private fun processMeterCheckScanWithLocationValidation(
        scannedSerial: String,
        targetSerial: String?,
        targetLocation: String?,
        meterNumber: String?
    ) {
        lifecycleScope.launch {
            try {
                // First check if the meter exists in database
                val existingMeter = findMeterBySerial(scannedSerial)

                if (existingMeter != null) {
                    // Meter exists - validate location and number registration
                    validateMeterRegistration(existingMeter, targetSerial, targetLocation, meterNumber)
                } else {
                    // Meter not found - show registration dialog
                    showMeterRegistrationDialog(scannedSerial, getString(R.string.serial_not_found_in_database))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing meter check scan: ${e.message}", e)
                showErrorResult(scannedSerial, getString(R.string.error_processing_scan, e.message))
            }
        }
    }

    private fun processDetectedSerialWithLocationValidation(serialNumberToCheck: String) {
        lifecycleScope.launch {
            try {
                val existingMeter = findMeterBySerial(serialNumberToCheck)

                if (existingMeter != null) {
                    // Meter exists - validate location and number registration
                    validateMeterRegistration(existingMeter)
                } else {
                    // Meter not found - show registration dialog
                    showMeterRegistrationDialog(serialNumberToCheck, getString(R.string.serial_not_found_in_database))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing detected serial: ${e.message}", e)
                showErrorResult(serialNumberToCheck, getString(R.string.error_processing_serial, e.message))
            }
        }
    }

    private suspend fun validateMeterRegistration(
        meter: MeterStatus,
        targetSerial: String? = null,
        targetLocation: String? = null,
        meterNumber: String? = null
    ) {
        val isLocationRegistered = isLocationValid(meter.place)
        val isMeterNumberRegistered = isMeterNumberValid(meter.number)
        val isLocationInDatabase = if (isLocationRegistered) {
            locationRepository.isLocationActive(meter.place)
        } else false

        Log.d(TAG, "🔍 Validation Results for ${meter.serialNumber}:")
        Log.d(TAG, "  - Location '${meter.place}' registered: $isLocationRegistered, in DB: $isLocationInDatabase")
        Log.d(TAG, "  - Meter number '${meter.number}' registered: $isMeterNumberRegistered")

        when {
            // Case 1: Neither location nor meter number is properly registered
            !isLocationRegistered && !isMeterNumberRegistered -> {
                showRegistrationRequiredDialog(
                    meter,
                    getString(R.string.meter_needs_location_and_number),
                    needsLocation = true,
                    needsNumber = true
                )
            }
            // Case 2: Location not registered but meter number is
            !isLocationRegistered -> {
                showRegistrationRequiredDialog(
                    meter,
                    getString(R.string.meter_needs_location),
                    needsLocation = true,
                    needsNumber = false
                )
            }
            // Case 3: Meter number not registered but location is
            !isMeterNumberRegistered -> {
                showRegistrationRequiredDialog(
                    meter,
                    getString(R.string.meter_needs_number),
                    needsLocation = false,
                    needsNumber = true
                )
            }
            // Case 4: Location exists but not in location database
            !isLocationInDatabase -> {
                showLocationNotInDatabaseDialog(meter)
            }
            // Case 5: Everything is registered - offer to edit
            else -> {
                showEditMeterInfoDialog(meter)
            }
        }
    }

    private fun showRegistrationRequiredDialog(
        meter: MeterStatus,
        message: String,
        needsLocation: Boolean,
        needsNumber: Boolean
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.meter_setup_required))
            .setMessage("$message\n\n${getString(R.string.meter_assignment_info)}")
            .setPositiveButton(getString(R.string.setup_meter)) { _, _ ->
                showMeterSetupDialog(meter, needsLocation, needsNumber)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                navigateBack()
            }
            .setCancelable(false)
            .show()
    }

    private fun showLocationNotInDatabaseDialog(meter: MeterStatus) {
        val message = getString(R.string.location_not_in_database, meter.place)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.location_registration_required))
            .setMessage(message)
            .setPositiveButton(getString(R.string.add_location_to_database)) { _, _ ->
                addLocationToDatabase(meter)
            }
            .setNeutralButton(getString(R.string.change_location)) { _, _ ->
                showMeterSetupDialog(meter, needsLocation = true, needsNumber = false)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                navigateBack()
            }
            .show()
    }

    private fun showEditMeterInfoDialog(meter: MeterStatus) {
        val message = "${getString(R.string.meter_registered_successfully)}\n\n" +
                "${getString(R.string.current_location)}: ${meter.place}\n" +
                "${getString(R.string.current_meter_number)}: ${meter.number}\n\n" +
                getString(R.string.update_meter_info)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.meter_information))
            .setMessage(message)
            .setPositiveButton(getString(R.string.proceed_with_scan)) { _, _ ->
                // Mark as scanned and proceed
                markMeterAsScanned(meter)
            }
            .setNeutralButton(getString(R.string.edit_meter_info)) { _, _ ->
                showMeterSetupDialog(meter, needsLocation = true, needsNumber = true)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                navigateBack()
            }
            .show()
    }

    private fun showMeterRegistrationDialog(serialNumber: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.meter_not_found))
            .setMessage("$message\n\n${getString(R.string.serial_not_found_add_meter, serialNumber)}")
            .setPositiveButton(getString(R.string.add_to_database)) { _, _ ->
                showNewMeterSetupDialog(serialNumber)
            }
            .setNegativeButton(getString(R.string.skip_serial)) { _, _ ->
                navigateBack()
            }
            .setCancelable(false)
            .show()
    }

    private fun showMeterSetupDialog(
        meter: MeterStatus,
        needsLocation: Boolean,
        needsNumber: Boolean
    ) {
        lifecycleScope.launch {
            val dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_meter_setup, null)

            val locationSpinner = dialogView.findViewById<Spinner>(R.id.locationSpinner)
            val locationInputLayout = dialogView.findViewById<TextInputLayout>(R.id.customLocationInputLayout)
            val locationInput = dialogView.findViewById<TextInputEditText>(R.id.customLocationInput)
            val numberInputLayout = dialogView.findViewById<TextInputLayout>(R.id.meterNumberInputLayout)
            val numberInput = dialogView.findViewById<TextInputEditText>(R.id.meterNumberInput)

            // Setup location spinner with database locations
            setupLocationSpinner(locationSpinner, locationInputLayout, locationInput, meter.place)

            // Setup meter number input
            if (needsNumber) {
                numberInput.setText(if (isMeterNumberValid(meter.number)) meter.number else "")
                numberInputLayout.visibility = View.VISIBLE
            } else {
                numberInputLayout.visibility = View.GONE
            }

            // Hide location setup if not needed
            if (!needsLocation) {
                locationSpinner.visibility = View.GONE
                locationInputLayout.visibility = View.GONE
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.update_meter_info))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save_meter_data)) { _, _ ->
                    val selectedLocation = if (needsLocation) {
                        getSelectedLocation(locationSpinner, locationInput)
                    } else {
                        meter.place
                    }

                    val enteredNumber = if (needsNumber) {
                        numberInput.text?.toString()?.trim() ?: ""
                    } else {
                        meter.number
                    }

                    if (selectedLocation.isNotEmpty() && enteredNumber.isNotEmpty()) {
                        updateMeterLocationAndNumber(meter, selectedLocation, enteredNumber)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.error_location_number_required), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                    navigateBack()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showNewMeterSetupDialog(serialNumber: String) {
        lifecycleScope.launch {
            val dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_meter_setup, null)

            val locationSpinner = dialogView.findViewById<Spinner>(R.id.locationSpinner)
            val locationInputLayout = dialogView.findViewById<TextInputLayout>(R.id.customLocationInputLayout)
            val locationInput = dialogView.findViewById<TextInputEditText>(R.id.customLocationInput)
            val numberInputLayout = dialogView.findViewById<TextInputLayout>(R.id.meterNumberInputLayout)
            val numberInput = dialogView.findViewById<TextInputEditText>(R.id.meterNumberInput)

            // Setup location spinner
            setupLocationSpinner(locationSpinner, locationInputLayout, locationInput, "")

            // Show all inputs for new meter
            numberInputLayout.visibility = View.VISIBLE

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.add_new_meter))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.save_meter_data)) { _, _ ->
                    val selectedLocation = getSelectedLocation(locationSpinner, locationInput)
                    val enteredNumber = numberInput.text?.toString()?.trim() ?: ""

                    if (selectedLocation.isNotEmpty() && enteredNumber.isNotEmpty()) {
                        addMeterToDatabase(serialNumber, selectedLocation, enteredNumber)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.error_location_number_required), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                    navigateBack()
                }
                .setCancelable(false)
                .show()
        }
    }

    private suspend fun setupLocationSpinner(
        spinner: Spinner,
        customInputLayout: TextInputLayout,
        customInput: TextInputEditText,
        preselectedLocation: String
    ) {
        try {
            // Get locations from database
            val dbLocations = locationRepository.getActiveLocationNames().sorted()

            val locationList = mutableListOf<String>()
            locationList.add(getString(R.string.select_location))
            locationList.addAll(dbLocations)
            locationList.add(getString(R.string.custom_location))

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, locationList)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            // Handle spinner selection
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    val selectedItem = locationList[position]
                    if (selectedItem == getString(R.string.custom_location)) {
                        customInputLayout.visibility = View.VISIBLE
                    } else {
                        customInputLayout.visibility = View.GONE
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

            // Preselect location if provided and valid
            if (preselectedLocation.isNotEmpty()) {
                val index = locationList.indexOf(preselectedLocation)
                if (index != -1) {
                    spinner.setSelection(index)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up location spinner: ${e.message}", e)
            // Fallback to basic spinner without database locations
            setupBasicLocationSpinner(spinner, customInputLayout, customInput, preselectedLocation)
        }
    }

    private fun setupBasicLocationSpinner(
        spinner: Spinner,
        customInputLayout: TextInputLayout,
        customInput: TextInputEditText,
        preselectedLocation: String
    ) {
        val basicLocations = listOf(
            getString(R.string.select_location),
            getString(R.string.location_lobby),
            getString(R.string.location_basement),
            getString(R.string.location_rooftop),
            getString(R.string.location_office),
            getString(R.string.custom_location)
        )

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, basicLocations)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedItem = basicLocations[position]
                if (selectedItem == getString(R.string.custom_location)) {
                    customInputLayout.visibility = View.VISIBLE
                } else {
                    customInputLayout.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Preselect location if provided
        if (preselectedLocation.isNotEmpty()) {
            val index = basicLocations.indexOf(preselectedLocation)
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

    private fun addLocationToDatabase(meter: MeterStatus) {
        lifecycleScope.launch {
            try {
                val result = locationRepository.addLocation(meter.place)
                if (result.isSuccess) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.location_added_to_database, meter.place),
                        Toast.LENGTH_SHORT
                    ).show()
                    // Now proceed with scanning
                    markMeterAsScanned(meter)
                } else {
                    showErrorResult(
                        meter.serialNumber,
                        getString(R.string.error_adding_location_to_database)
                    )
                }
            } catch (e: Exception) {
                showErrorResult(meter.serialNumber, getString(R.string.error_adding_location_to_database))
            }
        }
    }

    private fun markMeterAsScanned(meter: MeterStatus) {
        lifecycleScope.launch {
            try {
                val (success, fileName) = filesViewModel.updateMeterCheckedStatusBySerialAsync(meter.serialNumber)

                if (success) {
                    showSuccessResult(meter.serialNumber, fileName ?: getString(R.string.unknown_file))
                    setupNavigationButton()
                } else {
                    showErrorResult(meter.serialNumber, getString(R.string.error_update_scan_status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error marking meter as scanned: ${e.message}", e)
                showErrorResult(meter.serialNumber, getString(R.string.error_update_scan_status))
            }
        }
    }

    private fun updateMeterLocationAndNumber(meter: MeterStatus, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // Update meter details
                filesViewModel.updateMeterLocationAndNumber(meter.serialNumber, location, number)

                // If it's a custom location, add it to the database
                if (!location.isEmpty()) {
                    val isLocationInDb = locationRepository.isLocationActive(location)
                    if (!isLocationInDb) {
                        locationRepository.addLocation(location)
                    }
                }

                // Mark as scanned
                val (success, fileName) = filesViewModel.updateMeterCheckedStatusBySerialAsync(meter.serialNumber)

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

    private fun addMeterToDatabase(serialNumber: String, location: String, number: String) {
        lifecycleScope.launch {
            try {
                // Add meter to database
                filesViewModel.addNewMeter(serialNumber, location, number)

                // If it's a custom location, add it to the location database
                val isLocationInDb = locationRepository.isLocationActive(location)
                if (!isLocationInDb) {
                    locationRepository.addLocation(location)
                }

                // Give a moment for database update
                delay(500)

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

    // Utility functions
    private suspend fun findMeterBySerial(serialNumber: String): MeterStatus? {
        return try {
            filesViewModel.findMeterBySerial(serialNumber)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding meter by serial: ${e.message}", e)
            null
        }
    }

    private fun isLocationValid(location: String): Boolean {
        return location.isNotEmpty() &&
                location != getString(R.string.default_meter_place) &&
                location.lowercase() != "unknown" &&
                location.lowercase() != "n/a"
    }

    private fun isMeterNumberValid(number: String): Boolean {
        return number.isNotEmpty() &&
                number != getString(R.string.default_meter_number) &&
                number != "0"
    }

    private fun navigateBack() {
        findNavController().navigateUp()
    }

    private fun setupNavigationButton() {
        binding.nextButton.text = getString(R.string.back_to_meter_list)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            navigateBack()
        }
    }

    // Existing utility functions (parseSerialNumber, showSuccessResult, showErrorResult, etc.)
    private fun parseSerialNumber(rawData: String): String {
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

        binding.serialNumberText.text = serialNumber
    }

    private fun showErrorResult(serialNumber: String, errorMessage: String) {
        Log.e(TAG, "❌ Showing ERROR result for $serialNumber: $errorMessage")

        binding.detectedInfoText.text = "❌ $errorMessage"
        binding.serialNumberText.text = serialNumber

        binding.nextButton.text = getString(R.string.back_to_meter_list)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            navigateBack()
        }
    }

    private fun argumentsToString(args: Bundle?): String {
        if (args == null) return "null"
        return args.keySet().joinToString(", ") { key ->
            "$key=${args.get(key)}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}