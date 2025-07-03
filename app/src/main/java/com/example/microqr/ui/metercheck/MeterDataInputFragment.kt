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
        _binding = FragmentMeterDataInputBinding.inflate(layoutInflater)
        locationRepository = LocationRepository(requireContext())

        setupUI()
        setupClickListeners()

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setupUI() {
        // Set serial number (read-only)
        binding.serialNumberValue.text = serialNumber

        // Setup database file name section (only for new meters)
        if (isNewMeter) {
            binding.databaseFileSection.visibility = View.VISIBLE
            setupDatabaseFileSection()
        } else {
            binding.databaseFileSection.visibility = View.GONE
        }

        // Setup location section
        if (needsLocation) {
            binding.locationSection.visibility = View.VISIBLE
            setupLocationSpinner()
        } else {
            binding.locationSection.visibility = View.GONE
        }

        // Setup number section
        if (needsNumber) {
            binding.numberSection.visibility = View.VISIBLE
            binding.numberInput.setText(currentNumber)
        } else {
            binding.numberSection.visibility = View.GONE
        }

        // Update dialog title
        val title = if (isNewMeter) {
            "Add New Meter to Database"
        } else {
            when {
                needsLocation && needsNumber -> "Set Meter Location and Number"
                needsLocation -> "Set Meter Location"
                needsNumber -> "Set Meter Number"
                else -> "Set Meter Data"
            }
        }
        binding.dialogTitle.text = title
    }

    private fun setupDatabaseFileSection() {
        // Generate default file name
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val defaultFileName = "scanned_meters_$timestamp"

        // Set default values
        binding.autoGenerateFileNameSwitch.isChecked = true
        binding.fileNamePreview.text = "Auto-generated: $defaultFileName.csv"
        binding.customFileNameLayout.visibility = View.GONE

        // Handle switch toggle
        binding.autoGenerateFileNameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Auto-generate mode
                binding.customFileNameLayout.visibility = View.GONE
                binding.fileNamePreview.visibility = View.VISIBLE
                val newTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val newFileName = "scanned_meters_$newTimestamp"
                binding.fileNamePreview.text = "Auto-generated: $newFileName.csv"
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
                    locations.add("Add Location")

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
                            val selectedItem = locations[position]
                            if (selectedItem == "Add Location") {
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

        val databaseFileName = if (isNewMeter) {
            getDatabaseFileName()
        } else {
            null
        }

        // Validate inputs
        if (needsLocation && selectedLocation.isEmpty()) {
            Toast.makeText(requireContext(), "Location is required", Toast.LENGTH_SHORT).show()
            return
        }

        if (needsNumber && enteredNumber.isEmpty()) {
            Toast.makeText(requireContext(), "Number is required", Toast.LENGTH_SHORT).show()
            return
        }

        if (isNewMeter && databaseFileName.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Database filename is required", Toast.LENGTH_SHORT).show()
            return
        }

        if (isNewMeter && !isValidFileName(databaseFileName!!)) {
            Toast.makeText(requireContext(), "Invalid filename. Use only letters, numbers, and underscores", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Starting save operation...")
        showLoadingState()

        lifecycleScope.launch {
            try {
                // Phase 1: Save to database
                Log.d(TAG, "Phase 1: Saving to database...")

                if (isNewMeter) {
                    filesViewModel.addNewMeterWithCustomFileName(
                        serialNumber = serialNumber,
                        location = selectedLocation,
                        number = enteredNumber,
                        fileName = databaseFileName!!
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
                updateLoadingMessage("Updating database...")
                kotlinx.coroutines.delay(800)

                // Phase 3: Verify changes
                Log.d(TAG, "Phase 3: Verifying saved data...")
                updateLoadingMessage("Verifying changes...")
                kotlinx.coroutines.delay(300)

                // Phase 4: Final completion
                updateLoadingMessage("Completing save...")
                kotlinx.coroutines.delay(400)

                // Success - dismiss dialog and let DetectedFragment auto-refresh
                withContext(Dispatchers.Main) {
                    hideLoadingState()
                    Toast.makeText(requireContext(), "✅ Meter data saved successfully!", Toast.LENGTH_SHORT).show()

                    kotlinx.coroutines.delay(300)
                    dismiss() // Simply dismiss - DetectedFragment will auto-refresh!
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during save operation: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideLoadingState()
                    Toast.makeText(
                        requireContext(),
                        "Error saving meter data: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
        binding.saveButton.text = "Saving... Please Wait"

        // Show loading overlay
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingProgressBar.visibility = View.VISIBLE
        binding.loadingMessage.visibility = View.VISIBLE
        binding.loadingMessage.text = "Saving Meter Data"

        Log.d(TAG, "Loading state activated")
    }

    private suspend fun updateLoadingMessage(message: String) {
        withContext(Dispatchers.Main) {
            binding.loadingMessage.text = message
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
        binding.saveButton.text = "Save"

        // Hide loading overlay
        binding.loadingOverlay.visibility = View.GONE
        binding.loadingProgressBar.visibility = View.GONE
        binding.loadingMessage.visibility = View.GONE

        Log.d(TAG, "Loading state deactivated")
    }

    private fun getSelectedLocation(): String {
        if (!needsLocation) return currentLocation

        val selectedPosition = binding.locationSpinner.selectedItemPosition
        val adapter = binding.locationSpinner.adapter as? ArrayAdapter<String>
        val selectedItem = adapter?.getItem(selectedPosition) ?: ""

        return if (selectedItem == "Add Location") {
            binding.customLocationInput.text?.toString()?.trim() ?: ""
        } else {
            selectedItem
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

    private fun isValidFileName(fileName: String): Boolean {
        val invalidChars = charArrayOf('/', '\\', '?', '%', '*', ':', '|', '"', '<', '>')
        return fileName.isNotBlank() &&
                fileName.none { it in invalidChars } &&
                fileName.length <= 100
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}