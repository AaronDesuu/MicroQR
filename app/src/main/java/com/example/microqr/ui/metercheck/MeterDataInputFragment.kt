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
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.microqr.R
import com.example.microqr.databinding.FragmentMeterDataInputBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.data.repository.LocationRepository
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class MeterDataInputFragment : DialogFragment() {

    companion object {
        private const val TAG = "MeterDataInputFragment"
        private const val ARG_SERIAL_NUMBER = "serial_number"
        private const val ARG_CURRENT_LOCATION = "current_location"
        private const val ARG_CURRENT_NUMBER = "current_number"
        private const val ARG_NEEDS_LOCATION = "needs_location"
        private const val ARG_NEEDS_NUMBER = "needs_number"

        fun newInstance(
            serialNumber: String,
            currentLocation: String = "",
            currentNumber: String = "",
            needsLocation: Boolean = true,
            needsNumber: Boolean = true
        ): MeterDataInputFragment {
            val fragment = MeterDataInputFragment()
            val args = Bundle().apply {
                putString(ARG_SERIAL_NUMBER, serialNumber)
                putString(ARG_CURRENT_LOCATION, currentLocation)
                putString(ARG_CURRENT_NUMBER, currentNumber)
                putBoolean(ARG_NEEDS_LOCATION, needsLocation)
                putBoolean(ARG_NEEDS_NUMBER, needsNumber)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.let { args ->
            serialNumber = args.getString(ARG_SERIAL_NUMBER, "")
            currentLocation = args.getString(ARG_CURRENT_LOCATION, "")
            currentNumber = args.getString(ARG_CURRENT_NUMBER, "")
            needsLocation = args.getBoolean(ARG_NEEDS_LOCATION, true)
            needsNumber = args.getBoolean(ARG_NEEDS_NUMBER, true)
        }

        Log.d(TAG, "MeterDataInputFragment created for serial: $serialNumber, needsLocation: $needsLocation, needsNumber: $needsNumber")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeterDataInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        locationRepository = LocationRepository(requireContext())

        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        // Set serial number (read-only)
        binding.serialNumberValue.text = serialNumber

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

        // Update dialog title based on what's needed
        val title = when {
            needsLocation && needsNumber -> getString(R.string.set_meter_location_and_number)
            needsLocation -> getString(R.string.set_meter_location)
            needsNumber -> getString(R.string.set_meter_number)
            else -> getString(R.string.update_meter_data)
        }
        binding.dialogTitle.text = title
    }

    private fun setupLocationSpinner() {
        lifecycleScope.launch {
            try {
                val locations = locationRepository.getActiveLocationNames()
                val locationNames = mutableListOf<String>()

                // Add current location if it exists and is not in the list
                if (currentLocation.isNotEmpty() && !locations.contains(currentLocation)) {
                    locationNames.add(currentLocation)
                }

                locationNames.addAll(locations)
                locationNames.add(getString(R.string.add_location))

                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    locationNames
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.locationSpinner.adapter = adapter

                // Select current location if it exists
                if (currentLocation.isNotEmpty()) {
                    val index = locationNames.indexOf(currentLocation)
                    if (index >= 0) {
                        binding.locationSpinner.setSelection(index)
                    }
                }

                binding.locationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selectedItem = locationNames[position]
                        if (selectedItem == getString(R.string.add_location)) {
                            // Show custom location input
                            binding.customLocationLayout.visibility = View.VISIBLE
                            binding.customLocationInput.requestFocus()
                        } else {
                            binding.customLocationLayout.visibility = View.GONE
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading locations: ${e.message}")
                Toast.makeText(requireContext(), getString(R.string.error_loading_locations), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupClickListeners() {
        binding.cancelButton.setOnClickListener {
            dismiss()
        }

        binding.saveButton.setOnClickListener {
            saveMeterData()
        }

        binding.addLocationButton.setOnClickListener {
            // Navigate to LocationFragment to add new location
            dismiss()
            findNavController().navigate(R.id.action_detectedFragment_to_locationFragment)
        }
    }

    private fun saveMeterData() {
        val selectedLocation = getSelectedLocation()
        val enteredNumber = if (needsNumber) {
            binding.numberInput.text?.toString()?.trim() ?: ""
        } else {
            currentNumber
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

        Log.d(TAG, "Saving meter data: serial=$serialNumber, location=$selectedLocation, number=$enteredNumber")

        lifecycleScope.launch {
            try {
                // Check if meter already exists
                val existingMeter = filesViewModel.findMeterBySerial(serialNumber)

                if (existingMeter != null) {
                    // Update existing meter using the repository method
                    filesViewModel.updateMeterLocationAndNumber(serialNumber, selectedLocation, enteredNumber)
                    Log.d(TAG, "Updated existing meter with serial: $serialNumber")
                } else {
                    // Create new meter using the repository method
                    filesViewModel.addNewMeter(serialNumber, selectedLocation, enteredNumber)
                    Log.d(TAG, "Created new meter with serial: $serialNumber")
                }

                Toast.makeText(requireContext(), getString(R.string.meter_data_saved), Toast.LENGTH_SHORT).show()
                dismiss()

                // Navigate back to DetectedFragment with updated data
                val bundle = Bundle().apply {
                    putString("rawQrValue", serialNumber)
                    putString("scanContext", "METER_CHECK")
                    putBoolean("dataUpdated", true)
                }
                findNavController().navigate(R.id.action_meterDataInputFragment_to_detectedFragment, bundle)

            } catch (e: Exception) {
                Log.e(TAG, "Error saving meter data: ${e.message}")
                Toast.makeText(requireContext(), getString(R.string.error_saving_meter_data), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getSelectedLocation(): String {
        if (!needsLocation) return currentLocation

        val selectedPosition = binding.locationSpinner.selectedItemPosition
        val adapter = binding.locationSpinner.adapter as ArrayAdapter<String>
        val selectedItem = adapter.getItem(selectedPosition) ?: ""

        return if (selectedItem == getString(R.string.add_location)) {
            binding.customLocationInput.text?.toString()?.trim() ?: ""
        } else {
            selectedItem
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}