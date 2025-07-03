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
import androidx.navigation.fragment.findNavController
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
            getString(R.string.add_new_meter_to_database)
        } else {
            when {
                needsLocation && needsNumber -> getString(R.string.set_meter_location_and_number)
                needsLocation -> getString(R.string.set_meter_location)
                needsNumber -> getString(R.string.set_meter_number)
                else -> getString(R.string.set_meter_data)
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
        binding.fileNamePreview.text = getString(R.string.auto_generated_filename, defaultFileName)
        binding.customFileNameLayout.visibility = View.GONE

        // Handle switch toggle
        binding.autoGenerateFileNameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Auto-generate mode
                binding.customFileNameLayout.visibility = View.GONE
                binding.fileNamePreview.visibility = View.VISIBLE
                val newTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val newFileName = "scanned_meters_$newTimestamp"
                binding.fileNamePreview.text = getString(R.string.auto_generated_filename, newFileName)
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
                    locations.add(getString(R.string.add_location))

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
                            if (selectedItem == getString(R.string.add_location)) {
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

        // Get database file name (if new meter)
        val databaseFileName = if (isNewMeter) {
            getDatabaseFileName()
        } else {
            null
        }

        // Validate inputs
        if (needsLocation && selectedLocation.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.error_location_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (needsNumber && enteredNumber.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.error_number_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (isNewMeter && databaseFileName.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.error_filename_required), Toast.LENGTH_SHORT).show()
            return
        }

        if (isNewMeter && !isValidFileName(databaseFileName!!)) {
            Toast.makeText(requireContext(), getString(R.string.error_invalid_filename), Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Saving meter data: serial=$serialNumber, location=$selectedLocation, number=$enteredNumber, fileName=$databaseFileName")

        binding.saveButton.isEnabled = false

        lifecycleScope.launch {
            try {
                if (isNewMeter) {
                    // Create new meter with custom file name
                    filesViewModel.addNewMeterWithCustomFileName(
                        serialNumber = serialNumber,
                        location = selectedLocation,
                        number = enteredNumber,
                        fileName = databaseFileName!!
                    )
                    Log.d(TAG, "✅ Successfully created new meter in file: $databaseFileName")
                } else {
                    // Update existing meter
                    val existingMeter = filesViewModel.findMeterBySerial(serialNumber)
                    if (existingMeter != null) {
                        val updatedMeter = existingMeter.copy(
                            place = selectedLocation,
                            number = enteredNumber
                        )
                        filesViewModel.getMeterRepository().updateMeter(updatedMeter)
                        Log.d(TAG, "✅ Successfully updated existing meter")
                    } else {
                        throw IllegalStateException("Meter not found for update")
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.meter_data_saved), Toast.LENGTH_SHORT).show()
                    dismiss()

                    val bundle = Bundle().apply {
                        putString("rawQrValue", serialNumber)
                        putString("scanContext", "METER_CHECK")
                        putBoolean("dataUpdated", true)
                    }
                    findNavController().navigate(R.id.action_meterDataInputFragment_to_detectedFragment, bundle)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error saving meter data: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.saveButton.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_saving_meter_data) + ": ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun getSelectedLocation(): String {
        if (!needsLocation) return currentLocation

        val selectedPosition = binding.locationSpinner.selectedItemPosition
        val adapter = binding.locationSpinner.adapter as? ArrayAdapter<String>
        val selectedItem = adapter?.getItem(selectedPosition) ?: ""

        return if (selectedItem == getString(R.string.add_location)) {
            binding.customLocationInput.text?.toString()?.trim() ?: ""
        } else {
            selectedItem
        }
    }

    private fun getDatabaseFileName(): String? {
        return if (binding.autoGenerateFileNameSwitch.isChecked) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            "scanned_meters_$timestamp"
        } else {
            binding.customFileNameInput.text?.toString()?.trim()?.let { name ->
                if (name.isNotBlank()) {
                    // Add .csv extension if not present
                    if (!name.endsWith(".csv", ignoreCase = true)) {
                        "${name}.csv"
                    } else {
                        name
                    }
                } else null
            }
        }
    }

    private fun isValidFileName(fileName: String): Boolean {
        // Check for invalid characters
        val invalidChars = charArrayOf('/', '\\', '?', '%', '*', ':', '|', '"', '<', '>')
        return fileName.isNotBlank() &&
                fileName.none { it in invalidChars } &&
                fileName.length <= 100 // Reasonable length limit
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}