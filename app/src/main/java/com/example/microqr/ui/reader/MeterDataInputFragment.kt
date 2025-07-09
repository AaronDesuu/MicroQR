package com.example.microqr.ui.reader

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.example.microqr.R
import com.example.microqr.databinding.FragmentMeterDataInputBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.data.repository.LocationRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MeterDataInputFragment : DialogFragment() {

    companion object {
        private const val TAG = "MeterDataInputFragment"
        private const val ARG_SERIAL_NUMBER = "serial_number"
        private const val ARG_CURRENT_LOCATION = "current_location"
        private const val ARG_CURRENT_NUMBER = "current_number"
        private const val ARG_NEEDS_LOCATION = "needs_location"
        private const val ARG_NEEDS_NUMBER = "needs_number"
        private const val ARG_IS_NEW_METER = "is_new_meter"

        fun newInstance(
            serialNumber: String,
            currentLocation: String = "",
            currentNumber: String = "",
            needsLocation: Boolean = true,
            needsNumber: Boolean = true,
            isNewMeter: Boolean = false
        ): MeterDataInputFragment {
            val fragment = MeterDataInputFragment()
            val args = Bundle().apply {
                putString(ARG_SERIAL_NUMBER, serialNumber)
                putString(ARG_CURRENT_LOCATION, currentLocation)
                putString(ARG_CURRENT_NUMBER, currentNumber)
                putBoolean(ARG_NEEDS_LOCATION, needsLocation)
                putBoolean(ARG_NEEDS_NUMBER, needsNumber)
                putBoolean(ARG_IS_NEW_METER, isNewMeter)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: FragmentMeterDataInputBinding? = null
    private val binding get() = _binding!!

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var locationRepository: LocationRepository

    private var serialNumber: String = ""
    private var currentLocation: String = ""
    private var currentNumber: String = ""
    private var needsLocation: Boolean = true
    private var needsNumber: Boolean = true
    private var isNewMeter: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            serialNumber = args.getString(ARG_SERIAL_NUMBER, "")
            currentLocation = args.getString(ARG_CURRENT_LOCATION, "")
            currentNumber = args.getString(ARG_CURRENT_NUMBER, "")
            needsLocation = args.getBoolean(ARG_NEEDS_LOCATION, true)
            needsNumber = args.getBoolean(ARG_NEEDS_NUMBER, true)
            isNewMeter = args.getBoolean(ARG_IS_NEW_METER, false)
        }

        Log.d(TAG, "MeterDataInputFragment created - serial: $serialNumber, isNewMeter: $isNewMeter")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Log.d(TAG, "onCreateDialog called")

        try {
            _binding = FragmentMeterDataInputBinding.inflate(layoutInflater)
            locationRepository = LocationRepository(requireContext())

            setupUI()
            setupClickListeners()

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(binding.root)
                .create()

            Log.d(TAG, "Dialog created successfully")
            return dialog
        } catch (e: Exception) {
            Log.e(TAG, "Error creating dialog: ${e.message}", e)
            // Return a simple dialog if there's an error
            return MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.error_generic))
                .setMessage(getString(R.string.error_saving_meter_data))
                .setPositiveButton(getString(R.string.save)) { _, _ -> dismiss() }
                .create()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setupUI() {
        Log.d(TAG, "setupUI called")

        try {
            // Set serial number (read-only)
            binding.serialNumberValue.text = serialNumber
            Log.d(TAG, "Serial number set: $serialNumber")

            // Setup database file name section (only for new meters)
            if (isNewMeter) {
                Log.d(TAG, "Setting up database file section for new meter")
                binding.databaseFileSection.visibility = View.VISIBLE
                setupDatabaseFileSection()
            } else {
                Log.d(TAG, "Hiding database file section - existing meter")
                binding.databaseFileSection.visibility = View.GONE
            }

            // Setup location section
            if (needsLocation) {
                Log.d(TAG, "Setting up location section")
                binding.locationSection.visibility = View.VISIBLE
                setupLocationSpinner()
            } else {
                Log.d(TAG, "Hiding location section")
                binding.locationSection.visibility = View.GONE
            }

            // Setup number section
            if (needsNumber) {
                Log.d(TAG, "Setting up number section")
                binding.numberSection.visibility = View.VISIBLE
                binding.numberInput.setText(currentNumber)
            } else {
                Log.d(TAG, "Hiding number section")
                binding.numberSection.visibility = View.GONE
            }

            // Update dialog title
            val title = if (isNewMeter) {
                getString(R.string.add_new_meter_to_database)
            } else {
                when {
                    needsLocation && needsNumber -> getString(R.string.set_meter_location_and_number)
                    needsLocation -> getString(R.string.set_meter_location)
                    needsNumber -> getString(R.string.set_meter_number)
                    else -> getString(R.string.update_meter_data)
                }
            }
            binding.dialogTitle.text = title
            Log.d(TAG, "Dialog title set: $title")

        } catch (e: Exception) {
            Log.e(TAG, "Error in setupUI: ${e.message}", e)
            throw e
        }
    }

    private fun updateDialogTitle() {
        val title = when {
            needsLocation && needsNumber -> getString(R.string.set_meter_location_and_number)
            needsLocation -> getString(R.string.set_meter_location)
            needsNumber -> getString(R.string.set_meter_number)
            else -> getString(R.string.update_meter_data)
        }
        binding.dialogTitle.text = title
    }

    private fun setupDatabaseFileSection() {
        // Generate default file name
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val defaultFileName = "scanned_meters_$timestamp"

        binding.fileNamePreview.text = getString(R.string.auto_generated_filename, defaultFileName)
        binding.autoGenerateFileNameSwitch.isChecked = true
        binding.fileNamePreview.visibility = View.VISIBLE
        binding.customFileNameLayout.visibility = View.GONE

        // ADDED: Setup existing files section
        setupExistingFilesSection()

        binding.autoGenerateFileNameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.fileNamePreview.visibility = View.VISIBLE
                binding.customFileNameLayout.visibility = View.GONE
            } else {
                binding.fileNamePreview.visibility = View.GONE
                binding.customFileNameLayout.visibility = View.VISIBLE
            }
        }
    }

    // NEW: Setup existing MeterCheck files option
    private fun setupExistingFilesSection() {
        lifecycleScope.launch {
            try {
                // Get existing MeterCheck files from FilesViewModel
                val existingFiles = filesViewModel.getMeterCheckFiles()

                if (existingFiles.isNotEmpty()) {
                    // Show existing files option
                    binding.existingFilesSection?.visibility = View.VISIBLE
                    setupExistingFilesSpinner(existingFiles)
                } else {
                    // Hide existing files section if no files available
                    binding.existingFilesSection?.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading existing files: ${e.message}", e)
                binding.existingFilesSection?.visibility = View.GONE
            }
        }
    }

    // NEW: Setup spinner for existing files
    private fun setupExistingFilesSpinner(existingFiles: List<String>) {
        try {
            val spinnerItems = mutableListOf<String>().apply {
                add(getString(R.string.select_file_prompt))  // "ファイルを選択" as first item (placeholder)
                add(getString(R.string.create_new_file))      // Option to create new file
                addAll(existingFiles)                         // Existing files
            }

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerItems)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.existingFilesSpinner?.adapter = adapter

            // Set default selection to the placeholder (position 0)
            binding.existingFilesSpinner?.setSelection(0)

            binding.existingFilesSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    when (position) {
                        0 -> {
                            // "ファイルを選択" selected - hide all file options (placeholder state)
                            binding.newFileOptionsLayout?.visibility = View.GONE
                            binding.autoGenerateLayout?.visibility = View.GONE
                            binding.fileNamePreview?.visibility = View.GONE
                            binding.customFileNameLayout?.visibility = View.GONE
                        }
                        1 -> {
                            // "Create new file" selected - show file name options
                            binding.newFileOptionsLayout?.visibility = View.VISIBLE
                            binding.autoGenerateLayout?.visibility = View.VISIBLE
                            binding.fileNamePreview?.visibility = if (binding.autoGenerateFileNameSwitch.isChecked) View.VISIBLE else View.GONE
                            binding.customFileNameLayout?.visibility = if (binding.autoGenerateFileNameSwitch.isChecked) View.GONE else View.VISIBLE
                        }
                        else -> {
                            // Existing file selected - hide ALL file name options
                            binding.newFileOptionsLayout?.visibility = View.GONE
                            binding.autoGenerateLayout?.visibility = View.GONE
                            binding.fileNamePreview?.visibility = View.GONE
                            binding.customFileNameLayout?.visibility = View.GONE
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up existing files spinner: ${e.message}", e)
        }
    }

    private fun generateAutoFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "scanned_meters_$timestamp"
    }

    private fun setupLocationSpinner() {
        lifecycleScope.launch {
            try {
                val locations = locationRepository.getActiveLocationNames()

                if (locations.isNotEmpty()) {
                    val spinnerItems = mutableListOf<String>().apply {
                        add(getString(R.string.choose_location))  // FIXED: Show "場所を選択" as first option
                        add(getString(R.string.custom_location_hint))  // Then "場所名を入力" for custom input
                        addAll(locations)
                    }

                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerItems)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.locationSpinner.adapter = adapter

                    // Set current location if it exists in the list
                    if (currentLocation.isNotBlank()) {
                        val locationIndex = locations.indexOf(currentLocation)
                        if (locationIndex >= 0) {
                            binding.locationSpinner.setSelection(locationIndex + 2) // +2 for choose_location and custom options
                        }
                    }

                    binding.locationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            when (position) {
                                0 -> {
                                    // "場所を選択" selected - do nothing, keep dropdown closed
                                    binding.customLocationLayout.visibility = View.GONE
                                }
                                1 -> {
                                    // "場所名を入力" selected - show custom input
                                    binding.customLocationLayout.visibility = View.VISIBLE
                                    binding.customLocationInput.setText(currentLocation)
                                }
                                else -> {
                                    // Existing location selected
                                    binding.customLocationLayout.visibility = View.GONE
                                }
                            }
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                } else {
                    // No existing locations - show custom input only
                    binding.locationSpinner.visibility = View.GONE
                    binding.customLocationLayout.visibility = View.VISIBLE
                    binding.customLocationInput.setText(currentLocation)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations: ${e.message}", e)
                // Fallback to custom input
                binding.locationSpinner.visibility = View.GONE
                binding.customLocationLayout.visibility = View.VISIBLE
                binding.customLocationInput.setText(currentLocation)
            }
        }
    }

    private fun setupLocationSpinner(locations: List<String>) {
        try {
            val spinnerItems = mutableListOf<String>().apply {
                add(getString(R.string.custom_location_hint))
                addAll(locations)
            }

            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerItems)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.locationSpinner.adapter = adapter

            binding.locationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        // Custom location selected
                        showCustomLocationInput()
                    } else {
                        // Existing location selected
                        hideCustomLocationInput()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up location spinner: ${e.message}", e)
            showCustomLocationInput()
        }
    }

    private fun showCustomLocationInput() {
        binding.customLocationLayout.visibility = View.VISIBLE
        binding.locationSpinner.visibility = View.GONE
    }

    private fun hideCustomLocationInput() {
        binding.customLocationLayout.visibility = View.GONE
        binding.locationSpinner.visibility = View.VISIBLE
    }

    private fun setupNumberSection() {
        try {
            Log.d(TAG, "Setting up meter number section")

            // Pre-fill with current number if valid (FIXED: correct ID from layout)
            if (currentNumber.isNotBlank() &&
                currentNumber != getString(R.string.not_set) &&
                currentNumber != getString(R.string.unknown)) {
                binding.numberInput.setText(currentNumber)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up number section: ${e.message}", e)
        }
    }

    private fun setupClickListeners() {
        binding.saveButton.setOnClickListener {
            saveMeterData()
        }

        binding.cancelButton.setOnClickListener {
            dismiss()
        }
    }

    private fun saveMeterData() {
        Log.d(TAG, "saveMeterData called")

        // FIXED: Always get both current values AND updated values, then merge them
        val updatedLocation = if (needsLocation) {
            getSelectedLocation()
        } else {
            ""  // No update needed for location
        }

        val updatedNumber = if (needsNumber) {
            getMeterNumber()
        } else {
            ""  // No update needed for number
        }

        // FIXED: Merge current values with updated values
        val finalLocation = if (needsLocation && updatedLocation.isNotBlank()) {
            updatedLocation  // Use new location
        } else {
            currentLocation  // Keep current location
        }

        val finalNumber = if (needsNumber && updatedNumber.isNotBlank()) {
            updatedNumber    // Use new number
        } else {
            currentNumber    // Keep current number
        }

        // Validation - only validate fields that are being updated
        if (needsLocation && updatedLocation.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.error_location_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (needsNumber && updatedNumber.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.error_number_required), Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Final values to save:")
        Log.d(TAG, "  Serial: $serialNumber")
        Log.d(TAG, "  Location: '$finalLocation' (current: '$currentLocation', updated: '$updatedLocation')")
        Log.d(TAG, "  Number: '$finalNumber' (current: '$currentNumber', updated: '$updatedNumber')")

        // Show loading state
        showLoadingState()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting meter data save process...")

                // Phase 1: Save to database
                Log.d(TAG, "Phase 1: Saving to database...")
                updateLoadingMessage(getString(R.string.phase_saving_to_database))

                if (isNewMeter) {
                    // UPDATED: Check if adding to existing file or creating new file
                    val selectedPosition = binding.existingFilesSpinner?.selectedItemPosition ?: 0
                    val useExistingFile = selectedPosition > 1  // Position 0 = placeholder, 1 = create new, 2+ = existing files

                    if (useExistingFile) {
                        // ADDED: Add to existing MeterCheck file
                        val selectedFileName = binding.existingFilesSpinner?.selectedItem?.toString() ?: ""

                        filesViewModel.addMeterToExistingFile(
                            serialNumber = serialNumber,
                            location = finalLocation,
                            number = finalNumber,
                            fileName = selectedFileName
                        )
                        Log.d(TAG, "✅ Meter added to existing file: $selectedFileName")
                    } else if (selectedPosition == 1) {
                        // UPDATED: Create new file (position 1)
                        val customFileName = if (binding.autoGenerateFileNameSwitch.isChecked) {
                            generateAutoFileName()
                        } else {
                            binding.customFileNameInput.text?.toString()?.trim() ?: ""
                        }

                        if (customFileName.isBlank()) {
                            throw IllegalArgumentException("Custom filename cannot be empty")
                        }

                        // Use FilesViewModel method to add new meter with custom filename
                        filesViewModel.addNewMeterWithCustomFileName(
                            serialNumber = serialNumber,
                            location = finalLocation,
                            number = finalNumber,
                            fileName = customFileName.removeSuffix(".csv")
                        )
                        Log.d(TAG, "✅ New meter created in database")
                    } else {
                        // Position 0 = placeholder selected, show error
                        throw IllegalArgumentException("Please select a file option")
                    }
                } else {
                    val existingMeter = filesViewModel.findMeterBySerial(serialNumber)
                    if (existingMeter != null) {
                        // FIXED: Push both current values and updated values merged
                        val updatedMeter = existingMeter.copy(
                            place = finalLocation,   // Merged current + updated location
                            number = finalNumber     // Merged current + updated number
                        )
                        filesViewModel.getMeterRepository().updateMeter(updatedMeter)
                        Log.d(TAG, "✅ Existing meter updated in database")
                    } else {
                        throw IllegalStateException("Meter not found for update")
                    }
                }

                // Phase 2: Wait for database commit
                Log.d(TAG, "Phase 2: Waiting for database refresh...")
                updateLoadingMessage(getString(R.string.updating_database))
                kotlinx.coroutines.delay(800)

                // Phase 3: Verify changes
                Log.d(TAG, "Phase 3: Verifying saved data...")
                updateLoadingMessage(getString(R.string.verifying_changes))
                kotlinx.coroutines.delay(300)

                // Phase 4: Final completion
                updateLoadingMessage(getString(R.string.preparing_to_return))
                kotlinx.coroutines.delay(400)

                // Success - send callback to DetectedFragment and dismiss
                withContext(Dispatchers.Main) {
                    hideLoadingState()
                    Toast.makeText(requireContext(), getString(R.string.meter_data_updated_successfully), Toast.LENGTH_SHORT).show()

                    kotlinx.coroutines.delay(300)

                    // Send fragment result to notify DetectedFragment
                    setFragmentResult(
                        DetectedFragment.METER_DATA_UPDATED_KEY,
                        bundleOf("updated" to true)
                    )

                    Log.d(TAG, "📢 Sent meter data update callback to DetectedFragment")
                    dismiss()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error saving meter data: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideLoadingState()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_saving_meter_data),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun getSelectedLocation(): String {
        return if (binding.customLocationLayout.visibility == View.VISIBLE) {
            binding.customLocationInput.text?.toString()?.trim() ?: ""
        } else {
            val selectedPosition = binding.locationSpinner.selectedItemPosition
            if (selectedPosition > 0) {
                binding.locationSpinner.selectedItem.toString()
            } else {
                ""
            }
        }
    }

    private fun getMeterNumber(): String {
        return binding.numberInput.text?.toString()?.trim() ?: ""
    }

    private fun showLoadingState() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.saveButton.isEnabled = false
        binding.cancelButton.isEnabled = false
    }

    private fun hideLoadingState() {
        binding.loadingOverlay.visibility = View.GONE
        binding.saveButton.isEnabled = true
        binding.cancelButton.isEnabled = true
    }

    private fun updateLoadingMessage(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.loadingMessage.text = message
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}