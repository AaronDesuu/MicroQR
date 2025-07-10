package com.example.microqr.ui.metercheck

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.databinding.FragmentMeterCheckBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import android.widget.ArrayAdapter
import com.example.microqr.data.repository.LocationRepository
import com.google.android.material.textfield.MaterialAutoCompleteTextView


class MeterCheckFragment : Fragment() {

    private var _binding: FragmentMeterCheckBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MeterCheckViewModel by viewModels()
    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var meterAdapter: MeterCheckAdapter

    // Hold the current complete list and filtered list
    private var currentCompleteMeterList: List<MeterStatus> = emptyList()
    private var currentFilteredList: List<MeterStatus> = emptyList()

    // View references
    private lateinit var meterRecyclerView: RecyclerView
    private lateinit var emptyStateLayout: View
    private lateinit var fabScanMeter: com.google.android.material.floatingactionbutton.FloatingActionButton

    companion object {
        private const val TAG = "MeterCheckFragment"
    }
    private lateinit var locationRepository: LocationRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeterCheckBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews()
        setupRecyclerView()
        setupSearchView()
        setupFilterButtons()
        setupObservers()
        locationRepository = LocationRepository(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initializeViews() {
        meterRecyclerView = binding.rvMeters
        emptyStateLayout = binding.emptyStateLayout
        fabScanMeter = binding.fabScanMeter

        // Setup FAB click listener
        fabScanMeter.setOnClickListener {
            handleFabScanClick()
        }

        // Setup locations card click listener
        binding.setupLocationsCard.setOnClickListener {
            navigateToLocationFragment()
        }

        // Always show the FAB
        fabScanMeter.show()
    }

    private fun setupRecyclerView() {
        meterAdapter = MeterCheckAdapter(
            onItemClick = { meter ->
                showMeterDetailDialog(meter)
            },
            onEditClick = { meter ->
                showEditMeterDialog(meter)
            },
            onScanClick = { meter ->
                startMeterScanFlow(meter)
            }
        )

        meterRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = meterAdapter
        }
    }

    private fun setupSearchView() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                viewModel.updateSearchQuery(query)
            }
        })
    }

    private fun setupFilterButtons() {
        // Filter buttons setup using same strings as MeterMatch
        binding.btnFilterLocation.text = getString(R.string.meter_match_filter_place)
        binding.btnFilterStatus.text = getString(R.string.meter_match_filter_file)
        binding.btnSort.text = getString(R.string.meter_match_sort)

        binding.btnFilterLocation.setOnClickListener {
            showPlaceFilterDialog()
        }

        binding.btnFilterStatus.setOnClickListener {
            showFileFilterDialog()
        }

        binding.btnSort.setOnClickListener {
            showSortDialog()
        }

        binding.btnClearFilters.setOnClickListener {
            clearAllFilters()
        }
    }

    private fun setupObservers() {
        // Observe UI state changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                updateUI(uiState)
                updateEmptyStateFromUiState(uiState)

                // Handle navigation to scan
                if (uiState.shouldNavigateToScan && uiState.selectedMeter != null) {
                    val bundle = Bundle().apply {
                        putString("targetSerial", uiState.selectedMeter.serialNumber)
                        putString("targetLocation", uiState.selectedMeter.place)
                        putString("meterNumber", uiState.selectedMeter.number)
                    }
                    findNavController().navigate(R.id.action_meterCheck_to_reader, bundle)
                    viewModel.clearNavigationFlag()
                }
            }
        }

        // Observe MeterCheck specific data from FilesViewModel
        filesViewModel.meterCheckMeters.observe(viewLifecycleOwner) { meterCheckMeters ->
            Log.d("MeterCheckFragment", "Received ${meterCheckMeters.size} meters for checking")

            currentCompleteMeterList = meterCheckMeters
            viewModel.setMeters(meterCheckMeters)
        }
    }

    private fun updateUI(uiState: MeterCheckUiState) {
        // Update filtered list
        currentFilteredList = uiState.filteredMeters
        meterAdapter.submitList(currentFilteredList.toList())

        // Update filter chips
        updateFilterChips(uiState)

        // Update clear filters button visibility
        binding.btnClearFilters.isVisible = uiState.hasActiveFilters

        // Update filter button text
        updateFilterButtonText(uiState)

        // Update statistics
        updateStatistics(uiState)

        // Update FAB icon and text based on state
        updateFabState(uiState)
    }

    private fun updateFabState(uiState: MeterCheckUiState) {
        if (uiState.filteredMeters.isEmpty()) {
            // No meters - FAB for adding new meter
            fabScanMeter.setImageResource(R.drawable.ic_add_24)
            fabScanMeter.contentDescription = getString(R.string.add_meter)
        } else {
            // Meters available - FAB for scanning
            fabScanMeter.setImageResource(R.drawable.ic_qr_code_scanner_24)
            fabScanMeter.contentDescription = getString(R.string.scan_qr_code)
        }

        // Always show the FAB
        fabScanMeter.show()
    }

    private fun updateFilterChips(uiState: MeterCheckUiState) {
        // MeterCheck layout doesn't have chipGroupFilters like MeterMatch
        // We'll show active filters in the button text instead
        // This method is kept for consistency but doesn't create actual chips
    }

    private fun updateFilterButtonText(uiState: MeterCheckUiState) {
        // Update place filter button
        val placeCount = uiState.filterState.selectedPlaces.size
        binding.btnFilterLocation.text = if (placeCount > 0) {
            getString(R.string.place_filter_count, placeCount)
        } else {
            getString(R.string.meter_match_filter_place)
        }

        // Update file filter button
        val fileCount = uiState.filterState.selectedFiles.size
        binding.btnFilterStatus.text = if (fileCount > 0) {
            getString(R.string.file_filter_count, fileCount)
        } else {
            getString(R.string.meter_match_filter_file)
        }

        // Update sort button with arrow indicator
        val sortDisplayName = getString(uiState.sortOption.displayNameRes)
        val arrow = if (uiState.sortAscending) " ↑" else " ↓"
        binding.btnSort.text = sortDisplayName + arrow
    }

    private fun updateStatistics(uiState: MeterCheckUiState) {
        binding.totalMetersCount.text = uiState.totalCount.toString()
        binding.scannedCount.text = uiState.scannedCount.toString()
        binding.remainingCount.text = uiState.remainingCount.toString()
    }

    private fun updateEmptyStateFromUiState(uiState: MeterCheckUiState) {
        val actualFilteredSize = uiState.filteredMeters.size
        val isEmpty = actualFilteredSize == 0

        emptyStateLayout.isVisible = isEmpty
        meterRecyclerView.isVisible = !isEmpty

        if (isEmpty) {
            val emptyStateTitle = view?.findViewById<TextView>(R.id.empty_state_title)
            val emptyStateMessage = view?.findViewById<TextView>(R.id.empty_state_message)

            if (uiState.allMeters.isEmpty()) {
                // No data at all
                emptyStateTitle?.text = getString(R.string.error_no_locations)
                emptyStateMessage?.text = getString(R.string.locations_required)
            } else {
                // Data exists but filtered out
                emptyStateTitle?.text = getString(R.string.meter_check_no_meters_found_search)
                emptyStateMessage?.text = getString(R.string.meter_check_try_different_search)
            }
        }
    }

    private fun showPlaceFilterDialog() {
        val uiState = viewModel.uiState.value
        val availablePlaces = uiState.availablePlaces
        val selectedPlaces = uiState.filterState.selectedPlaces
        val checkedItems = availablePlaces.map { it in selectedPlaces }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.filter_by_place))
            .setMultiChoiceItems(availablePlaces.toTypedArray(), checkedItems) { _, which, _ ->
                val place = availablePlaces[which]
                viewModel.togglePlaceFilter(place)
            }
            .setPositiveButton(getString(R.string.done)) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(getString(R.string.meter_match_clear_all)) { _, _ ->
                viewModel.clearPlaceFilters()
            }
            .show()
    }

    private fun showFileFilterDialog() {
        val uiState = viewModel.uiState.value
        val availableFiles = uiState.availableFiles
        val selectedFiles = uiState.filterState.selectedFiles
        val checkedItems = availableFiles.map { it in selectedFiles }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.filter_by_source_file))
            .setMultiChoiceItems(availableFiles.toTypedArray(), checkedItems) { _, which, _ ->
                val file = availableFiles[which]
                viewModel.toggleFileFilter(file)
            }
            .setPositiveButton(getString(R.string.done)) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(getString(R.string.meter_match_clear_all)) { _, _ ->
                viewModel.clearFileFilters()
            }
            .show()
    }

    private fun handleFabScanClick() {
        if (currentFilteredList.isNotEmpty()) {
            // If there are meters available, start quick scan for the first unscanned meter
            val unscannedMeter = currentFilteredList.find { !it.isChecked }
            if (unscannedMeter != null) {
                startMeterScanFlow(unscannedMeter)
            } else {
                // All meters are scanned, scan the first one anyway or show completion message
                Toast.makeText(
                    requireContext(),
                    getString(R.string.all_meters_scanned),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            // No meters available, navigate to scanner to add new meter
            startScanForNewMeter()
        }
    }

    private fun startScanForNewMeter() {
        // Navigate to scanner with flag to add new meter
        val bundle = Bundle().apply {
            putString("scanContext", "ADD_NEW_METER")
            putString("targetSerial", "")
            putString("targetLocation", "")
            putString("meterNumber", "")
        }
        findNavController().navigate(R.id.action_meterCheck_to_reader, bundle)
    }

    private fun navigateToLocationFragment() {
        try {
            findNavController().navigate(R.id.action_meterCheck_to_location)
        } catch (e: Exception) {
            Log.e("MeterCheckFragment", "Navigation to LocationFragment failed: ${e.message}")
            Toast.makeText(
                requireContext(),
                "Unable to open location settings",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showSortDialog() {
        val uiState = viewModel.uiState.value
        val sortOptions = MeterCheckSortOption.values()
        val optionNames = sortOptions.map {
            val displayName = getString(it.displayNameRes)
            val arrow = if (uiState.sortOption == it) {
                if (uiState.sortAscending) " ↑" else " ↓"
            } else ""
            displayName + arrow
        }.toTypedArray()

        val selectedIndex = sortOptions.indexOf(uiState.sortOption)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.sort_by))
            .setSingleChoiceItems(optionNames, selectedIndex) { dialog, which ->
                viewModel.setSortOption(sortOptions[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel_location), null)
            .show()
    }

    private fun showDeleteMeterConfirmation(meter: MeterStatus) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_meter_title))
            .setMessage(
                getString(
                    R.string.confirm_delete_meter_message,
                    meter.number,
                    meter.serialNumber
                )
            )
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.delete_meter)) { _, _ ->
                deleteMeter(meter)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteMeter(meter: MeterStatus) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // For now, we'll call the repository method that needs to be added
                // Since we don't have a direct deleteMeter method, we'll use a workaround
                // by getting the repository and implementing the deletion
                val repository = filesViewModel.getMeterRepository()

                // We need to add this method to the repository
                // For now, let's show a message that this feature will be implemented
                Toast.makeText(
                    requireContext(),
                    "Delete functionality will be implemented - meter ${meter.number} would be deleted",
                    Toast.LENGTH_LONG
                ).show()

                /*
                // This is what the code should look like once the repository method is added:
                repository.deleteMeter(meter.serialNumber, meter.fromFile)

                Toast.makeText(
                    requireContext(),
                    getString(R.string.meter_deleted_successfully, meter.number),
                    Toast.LENGTH_SHORT
                ).show()
                */

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_deleting_meter, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun clearAllFilters() {
        viewModel.clearAllFilters()
    }

    private fun startMeterScanFlow(meter: MeterStatus) {
        // Store the selected meter context for when we return from scanning
        viewModel.selectMeterForScanning(meter)
    }

    private fun showMeterDetailDialog(meter: MeterStatus) {
        val dialog = MeterInfoDialogFragment.newInstance(
            meter = meter,
            onEditClick = { selectedMeter ->
                showEditMeterDialog(selectedMeter)
            },
            onScanClick = { selectedMeter ->
                startMeterScanFlow(selectedMeter)
            },
            onDeleteClick = { selectedMeter ->
                showDeleteMeterConfirmation(selectedMeter)
            }
        )
        dialog.show(parentFragmentManager, "MeterInfoDialog")
    }

    private fun showEditMeterDialog(meter: MeterStatus) {
        // Create the edit dialog with LocationFragment integration
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_meter_variables, null)

        // Get references to the new location-related views
        val locationDropdown = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.locationDropdown)
        val customLocationLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.customLocationLayout)
        val etCustomLocation = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCustomLocation)
        val btnAddLocation = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddLocation)

        // Get references to existing views
        val etMeterNumber = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMeterNumber)
        val etSerialNumber = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSerialNumber)

        // Pre-fill with current values
        etMeterNumber.setText(meter.number)
        etSerialNumber.setText(meter.serialNumber)

        // Setup location dropdown with data from LocationRepository
        var currentSelectedLocation = meter.place
        setupLocationDropdown(locationDropdown, customLocationLayout, etCustomLocation, btnAddLocation, currentSelectedLocation) { selectedLocation ->
            currentSelectedLocation = selectedLocation
        }

        // Handle add location button
        btnAddLocation.setOnClickListener {
            val customLocationName = etCustomLocation.text?.toString()?.trim()
            if (!customLocationName.isNullOrEmpty()) {
                addLocationToDatabase(customLocationName) { success ->
                    if (success) {
                        currentSelectedLocation = customLocationName
                        setupLocationDropdown(locationDropdown, customLocationLayout, etCustomLocation, btnAddLocation, currentSelectedLocation) { selectedLocation ->
                            currentSelectedLocation = selectedLocation
                        }
                        // Hide custom location input after adding
                        customLocationLayout.isVisible = false
                        btnAddLocation.isVisible = false
                        etCustomLocation.setText("")
                    }
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_meter_variables))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save_changes)) { _, _ ->
                val newLocation = currentSelectedLocation
                val newMeterNumber = etMeterNumber.text?.toString()?.trim() ?: meter.number
                val newSerialNumber = etSerialNumber.text?.toString()?.trim() ?: meter.serialNumber

                // Update meter in database
                updateMeterInDatabase(meter, newLocation, newMeterNumber, newSerialNumber)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Add these new methods to your MeterCheckFragment class
    private fun setupLocationDropdown(
        dropdown: MaterialAutoCompleteTextView,
        customLocationLayout: com.google.android.material.textfield.TextInputLayout,
        customLocationEditText: com.google.android.material.textfield.TextInputEditText,
        addLocationButton: com.google.android.material.button.MaterialButton,
        currentLocation: String,
        onLocationSelected: (String) -> Unit
    ) {
        lifecycleScope.launch {
            try {
                // Get location names from repository (returns Flow<List<String>>)
                locationRepository.getAllActiveLocations().collect { locationNames ->
                    val locationList = locationNames.toMutableList()

                    // Add custom location option using localized string
                    val customLocationOption = getString(R.string.enter_custom_location)
                    locationList.add(customLocationOption)

                    val adapter = ArrayAdapter<String>(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        locationList
                    )
                    dropdown.setAdapter(adapter)

                    // Set current location if it exists in the list
                    if (locationNames.contains(currentLocation)) {
                        dropdown.setText(currentLocation, false)
                    } else if (currentLocation.isNotEmpty()) {
                        // Current location not in list, show as custom
                        dropdown.setText(currentLocation, false)
                    }

                    dropdown.setOnItemClickListener { _, _, position, _ ->
                        val selectedLocation = locationList[position]

                        if (selectedLocation == customLocationOption) {
                            // Show custom location input
                            customLocationLayout.isVisible = true
                            addLocationButton.isVisible = true
                            customLocationEditText.requestFocus()
                        } else {
                            // Hide custom location input
                            customLocationLayout.isVisible = false
                            addLocationButton.isVisible = false
                            onLocationSelected(selectedLocation)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up location dropdown: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.error_loading_locations), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addLocationToDatabase(locationName: String, onComplete: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                val result = locationRepository.addLocation(locationName)
                if (result.isSuccess) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.location_added, locationName),
                        Toast.LENGTH_SHORT
                    ).show()
                    onComplete(true)
                } else {
                    val errorMessage = when (result.exceptionOrNull()?.message) {
                        "Location already exists" -> getString(R.string.location_already_exists)
                        "Location name cannot be empty" -> getString(R.string.location_name_required)
                        else -> getString(R.string.error_adding_location)
                    }
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding location: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.error_adding_location), Toast.LENGTH_SHORT).show()
                onComplete(false)
            }
        }
    }

    private fun updateMeterInDatabase(
        originalMeter: MeterStatus,
        newLocation: String,
        newMeterNumber: String,
        newSerialNumber: String
    ) {
        lifecycleScope.launch {
            try {
                // Create updated meter object following FilesViewModel pattern
                val updatedMeter = originalMeter.copy(
                    place = newLocation,
                    number = newMeterNumber,
                    serialNumber = newSerialNumber
                )

                // Use FilesViewModel's MeterRepository to update the meter
                filesViewModel.getMeterRepository().updateMeter(updatedMeter)

                Toast.makeText(
                    requireContext(),
                    getString(R.string.meter_updated_successfully),
                    Toast.LENGTH_SHORT
                ).show()

                Log.d(TAG, "✅ Meter updated successfully: ${updatedMeter.serialNumber}")

            } catch (e: Exception) {
                Log.e(TAG, "Error updating meter: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_updating_meter, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Public method for QR scanner integration (maintaining compatibility)
    fun onMeterScanned(serialNumber: String) {
        val result = filesViewModel.updateMeterCheckedStatusBySerial(serialNumber)
        if (result.first) {
            val fileName = result.second
            val message = getString(R.string.meter_scanned_successfully, serialNumber, fileName)
            Toast.makeText(
                context,
                message,
                Toast.LENGTH_LONG
            ).show()

            // Scroll to the scanned meter if it's visible in current filter
            scrollToMeter(serialNumber)
        } else {
            showMeterNotFoundDialog(serialNumber)
        }
    }

    private fun scrollToMeter(serialNumber: String) {
        val position = currentFilteredList.indexOfFirst { it.serialNumber == serialNumber }
        if (position != -1) {
            meterRecyclerView.smoothScrollToPosition(position)
        }
    }

    private fun showMeterNotFoundDialog(serialNumber: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_meter_not_found_title))
            .setMessage(getString(R.string.dialog_meter_not_found_message, serialNumber))
            .setPositiveButton(getString(R.string.dialog_button_ok)) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}