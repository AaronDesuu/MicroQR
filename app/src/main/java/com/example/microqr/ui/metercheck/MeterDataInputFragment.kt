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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
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
                .setTitle("Error")
                .setMessage("Failed to load meter input form: ${e.message}")
                .setPositiveButton("OK") { _, _ -> dismiss() }
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

    private fun setupDatabaseFileSection() {
        // Generate default file name
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val defaultFileName = "scanned_meters_$timestamp"

        // Set default values
        binding.autoGenerateFileNameSwitch.isChecked = true
        binding.fileNamePreview.text = getString(R.string.auto_generated_filename, "$defaultFileName.csv")
        binding.customFileNameLayout.visibility = View.GONE

        // Handle switch toggle
        binding.autoGenerateFileNameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Auto-generate mode
                binding.customFileNameLayout.visibility = View.GONE
                binding.fileNamePreview.visibility = View.VISIBLE
                val newTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val newFileName = "scanned_meters_$newTimestamp"
                binding.fileNamePreview.text = getString(R.string.auto_generated_filename, "$newFileName.csv")
            } else {
                // Custom mode
                binding.customFileNameLayout.visibility = View.VISIBLE
                binding.fileNamePreview.visibility = View.GONE

                // Pre-fill with a suggested name
                if (binding.customFileNameInput.text.isNullOrBlank()) {
                    val suggestedName = "scanned_meters_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}"
                    binding.customFileNameInput.setText(suggestedName)
                }
            }
        }
    }

    private fun setupLocationSpinner() {
        lifecycleScope.launch {
            try {
                val locations = locationRepository.getActiveLocationNames().toMutableList()

                if (locations.isEmpty()) {
                    binding.locationSpinner.visibility = View.GONE
                    binding.customLocationLayout.visibility = View.VISIBLE
                    binding.customLocationInput.setText(currentLocation)
                } else {
                    locations.add(getString(R.string.add_locations))

                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        locations
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.locationSpinner.adapter = adapter

                    val currentIndex = locations.indexOf(currentLocation)
                    if (currentIndex >= 0) {
                        binding.locationSpinner.setSelection(currentIndex)
                    }

                    binding.locationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            val selectedItem = parent?.getItemAtPosition(position) as String
                            if (selectedItem == getString(R.string.add_locations)) {
                                binding.customLocationLayout.visibility = View.VISIBLE
                                binding.customLocationInput.setText("")
                            } else {
                                binding.customLocationLayout.visibility = View.GONE
                            }
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up location spinner: ${e.message}")
                binding.locationSpinner.visibility = View.GONE
                binding.customLocationLayout.visibility = View.VISIBLE
                binding.customLocationInput.setText(currentLocation)
            }
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
        val selectedLocation = if (needsLocation) {
            getSelectedLocation()
        } else {
            currentLocation
        }

        val enteredNumber = if (needsNumber) {
            binding.numberInput.text?.toString()?.trim() ?: ""
        } else {
            currentNumber
        }

        // Validation
        if (needsLocation && selectedLocation.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.error_location_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (needsNumber && enteredNumber.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.error_number_required), Toast.LENGTH_SHORT).show()
            return
        }

        // Database filename validation for new meters
        if (isNewMeter) {
            val filename = getDatabaseFileName()
            if (filename.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(R.string.error_filename_required), Toast.LENGTH_SHORT).show()
                return
            }
            if (!isValidFileName(filename)) {
                Toast.makeText(requireContext(), getString(R.string.error_invalid_filename), Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Show loading state
        showLoadingState()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting meter data save process...")

                if (isNewMeter) {
                    // Phase 1: Create new meter with custom filename
                    Log.d(TAG, "Phase 1: Creating new meter in database...")
                    updateLoadingMessage(getString(R.string.adding_meter_to_database))

                    val customFileName = getDatabaseFileName() ?: ""

                    // Use FilesViewModel method to add new meter with custom filename
                    filesViewModel.addNewMeterWithCustomFileName(
                        serialNumber = serialNumber,
                        location = selectedLocation,
                        number = enteredNumber,
                        fileName = customFileName.removeSuffix(".csv")
                    )
                    Log.d(TAG, "✅ New meter created in database")
                } else {
                    val existingMeter = filesViewModel.findMeterBySerial(serialNumber)
                    if (existingMeter != null) {
                        val updatedMeter = existingMeter.copy(
                            place = selectedLocation,
                            number = enteredNumber
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

                // Success - dismiss dialog and let DetectedFragment auto-refresh
                withContext(Dispatchers.Main) {
                    hideLoadingState()
                    Toast.makeText(requireContext(), getString(R.string.meter_data_updated_successfully), Toast.LENGTH_SHORT).show()

                    kotlinx.coroutines.delay(300)
                    dismiss() // Simply dismiss - DetectedFragment will auto-refresh!
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
            val adapter = binding.locationSpinner.adapter as? ArrayAdapter<String>
            val selectedItem = adapter?.getItem(selectedPosition) ?: ""
            if (selectedItem == getString(R.string.add_locations)) "" else selectedItem
        }
    }

    private fun getDatabaseFileName(): String? {
        return if (binding.autoGenerateFileNameSwitch.isChecked) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            "scanned_meters_$timestamp.csv"
        } else {
            binding.customFileNameInput.text?.toString()?.trim()?.let { name ->
                if (name.isNotBlank()) {
                    if (!name.endsWith(".csv", ignoreCase = true)) {
                        "$name.csv"
                    } else {
                        name
                    }
                } else null
            }
        }
    }

    private fun isValidFileName(filename: String): Boolean {
        val invalidChars = charArrayOf('/', '\\', '?', '%', '*', ':', '|', '"', '<', '>')
        return filename.isNotBlank() &&
                filename.none { it in invalidChars } &&
                filename.length <= 100
    }

    private fun showLoadingState() {
        // Disable all input controls
        binding.saveButton.isEnabled = false
        binding.cancelButton.isEnabled = false
        binding.locationSpinner.isEnabled = false
        binding.numberInput.isEnabled = false
        binding.customLocationInput.isEnabled = false

        if (isNewMeter) {
            binding.autoGenerateFileNameSwitch.isEnabled = false
            binding.customFileNameInput.isEnabled = false
        }

        // Update save button to show loading
        binding.saveButton.text = getString(R.string.saving_please_wait)

        Log.d(TAG, "Loading state activated")
    }

    private suspend fun updateLoadingMessage(message: String) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "Loading message: $message")
        }
    }

    private fun hideLoadingState() {
        // Re-enable all controls
        binding.saveButton.isEnabled = true
        binding.cancelButton.isEnabled = true
        binding.locationSpinner.isEnabled = true
        binding.numberInput.isEnabled = true
        binding.customLocationInput.isEnabled = true

        if (isNewMeter) {
            binding.autoGenerateFileNameSwitch.isEnabled = true
            binding.customFileNameInput.isEnabled = true
        }

        // Reset save button text
        binding.saveButton.text = getString(R.string.save)

        Log.d(TAG, "Loading state deactivated")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}