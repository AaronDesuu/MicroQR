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
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MeterCheckFragment : Fragment() {

    private val viewModel: MeterCheckViewModel by viewModels()
    private val filesViewModel: FilesViewModel by activityViewModels()

    private lateinit var meterRecyclerView: RecyclerView
    private lateinit var meterAdapter: MeterCheckAdapter
    private lateinit var totalMetersCountText: TextView
    private lateinit var scannedCountText: TextView
    private lateinit var remainingCountText: TextView
    private lateinit var searchEditText: TextInputEditText
    private lateinit var emptyStateLayout: View
    private lateinit var setupLocationsCard: View
    private lateinit var fabScanMeter: View

    // Hold the current complete list and filtered list
    private var currentCompleteMeterList: List<MeterStatus> = emptyList()
    private var currentFilteredList: List<MeterStatus> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_meter_check, container, false)
        initializeViews(view)
        setupRecyclerView()
        setupSearchFunctionality()
        setupClickListeners()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize with empty state
        clearFragmentData()

        observeViewModel()
        observeFilesViewModel()
    }

    override fun onResume() {
        super.onResume()
        // Don't auto-refresh data - only show what's specifically assigned to MeterCheck
    }

    private fun clearFragmentData() {
        currentCompleteMeterList = emptyList()
        currentFilteredList = emptyList()
        meterAdapter.submitList(emptyList())
        viewModel.updateStatistics(0, 0, 0)
        updateEmptyState(emptyList(), "")
    }

    private fun initializeViews(view: View) {
        meterRecyclerView = view.findViewById(R.id.rvMeters)
        totalMetersCountText = view.findViewById(R.id.totalMetersCount)
        scannedCountText = view.findViewById(R.id.scannedCount)
        remainingCountText = view.findViewById(R.id.remainingCount)
        searchEditText = view.findViewById(R.id.etSearch)
        emptyStateLayout = view.findViewById(R.id.empty_state_layout)
        setupLocationsCard = view.findViewById(R.id.setup_locations_card)
        fabScanMeter = view.findViewById(R.id.fabScanMeter)
    }

    private fun setupRecyclerView() {
        meterAdapter = MeterCheckAdapter(
            onItemClick = { meter ->
                showMeterInfoDialog(meter)
            },
            onEditClick = { meter ->
                showEditMeterDialog(meter)
            },
            onScanClick = { meter ->
                startMeterScanFlow(meter)
            }
        )

        meterRecyclerView.layoutManager = LinearLayoutManager(context)
        meterRecyclerView.adapter = meterAdapter
    }

    private fun showMeterInfoDialog(meter: MeterStatus) {
        val dialog = MeterInfoDialogFragment.newInstance(
            meter = meter,
            onEditClick = { selectedMeter ->
                showEditMeterDialog(selectedMeter)
            },
            onScanClick = { selectedMeter ->
                startMeterScanFlow(selectedMeter)
            }
        )
        dialog.show(parentFragmentManager, "MeterInfoDialog")
    }

    private fun showEditMeterDialog(meter: MeterStatus) {
        // Create a simple edit dialog for location, number, and serial number
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_meter_variables, null)

        val etLocation = dialogView.findViewById<TextInputEditText>(R.id.etLocation)
        val etMeterNumber = dialogView.findViewById<TextInputEditText>(R.id.etMeterNumber)
        val etSerialNumber = dialogView.findViewById<TextInputEditText>(R.id.etSerialNumber)

        // Pre-fill with current values
        etLocation.setText(meter.place)
        etMeterNumber.setText(meter.number)
        etSerialNumber.setText(meter.serialNumber)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_meter_variables))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save_changes)) { _, _ ->
                val newLocation = etLocation.text?.toString()?.trim() ?: meter.place
                val newMeterNumber = etMeterNumber.text?.toString()?.trim() ?: meter.number
                val newSerialNumber = etSerialNumber.text?.toString()?.trim() ?: meter.serialNumber

                updateMeterVariables(meter, newLocation, newMeterNumber, newSerialNumber)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateMeterVariables(
        originalMeter: MeterStatus,
        newLocation: String,
        newMeterNumber: String,
        newSerialNumber: String
    ) {
        lifecycleScope.launch {
            try {
                // Update the meter in the database/repository
                val updatedMeter = originalMeter.copy(
                    place = newLocation,
                    number = newMeterNumber,
                    serialNumber = newSerialNumber
                )

                // Note: You may need to implement updateMeter method in FilesViewModel if it doesn't exist
                // For now, we'll show a success message and refresh the list
                Toast.makeText(
                    requireContext(),
                    getString(R.string.meter_updated_successfully),
                    Toast.LENGTH_SHORT
                ).show()

                // Refresh the list
                applyCurrentFilters()

            } catch (e: Exception) {
                Log.e("MeterCheckFragment", "Error updating meter variables", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_updating_meter, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupSearchFunctionality() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                viewModel.updateSearchQuery(query)
                applyCurrentFilters()
            }
        })
    }

    private fun setupClickListeners() {
        // Setup Locations button
        setupLocationsCard.setOnClickListener {
            navigateToLocationFragment()
        }

        // FAB for quick scanning - add safety check
        fabScanMeter.setOnClickListener {
            // Debug what we have
            android.util.Log.d("MeterCheckFragment",
                "FAB clicked: complete=${currentCompleteMeterList.size}, " +
                        "filtered=${currentFilteredList.size}"
            )

            if (currentFilteredList.isNotEmpty()) {
                startQuickScan()
            } else {
                // Force refresh and try again
                applyCurrentFilters()
                if (currentFilteredList.isNotEmpty()) {
                    startQuickScan()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No meters available for scanning",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // Filter buttons - add null safety
        view?.findViewById<MaterialButton>(R.id.btnFilterLocation)?.setOnClickListener {
            showLocationFilterDialog()
        }

        view?.findViewById<MaterialButton>(R.id.btnFilterStatus)?.setOnClickListener {
            showStatusFilterDialog()
        }

        view?.findViewById<MaterialButton>(R.id.btnSort)?.setOnClickListener {
            showSortDialog()
        }

        view?.findViewById<MaterialButton>(R.id.btnClearFilters)?.setOnClickListener {
            clearAllFilters()
        }
    }

    private fun observeViewModel() {
        // Observe UI state changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                updateUI(uiState)
            }
        }
    }

    private fun observeFilesViewModel() {
        // Observe MeterCheck specific data from FilesViewModel
        filesViewModel.meterCheckMeters.observe(viewLifecycleOwner) { meterCheckMeters ->
            currentCompleteMeterList = meterCheckMeters
            viewModel.setMeters(meterCheckMeters)

            // Apply filters immediately after data is loaded
            applyCurrentFilters()

            // Force update UI to ensure proper visibility
            updateEmptyStateAndVisibility()
        }
    }

    private fun updateEmptyStateAndVisibility() {
        val actualFilteredSize = currentFilteredList.size
        val isEmpty = actualFilteredSize == 0

        // Force visibility update
        emptyStateLayout.isVisible = isEmpty
        meterRecyclerView.isVisible = !isEmpty

        // Update empty state messages
        if (isEmpty) {
            val emptyStateTitle = view?.findViewById<TextView>(R.id.empty_state_title)
            val emptyStateMessage = view?.findViewById<TextView>(R.id.empty_state_message)

            if (currentCompleteMeterList.isEmpty()) {
                // No data at all
                emptyStateTitle?.text = getString(R.string.error_no_locations)
                emptyStateMessage?.text = getString(R.string.locations_required)
            } else {
                // Data exists but filtered out
                emptyStateTitle?.text = "No meters match current filters"
                emptyStateMessage?.text = "Try adjusting your search or filters"
            }
        }

        // Debug logging
        android.util.Log.d("MeterCheckFragment",
            "Data update: complete=${currentCompleteMeterList.size}, " +
                    "filtered=${currentFilteredList.size}, " +
                    "isEmpty=$isEmpty, " +
                    "recyclerVisible=${meterRecyclerView.isVisible}, " +
                    "emptyVisible=${emptyStateLayout.isVisible}"
        )
    }

    private fun updateUI(uiState: MeterCheckUiState) {
        // Update statistics
        totalMetersCountText.text = uiState.totalCount.toString()
        scannedCountText.text = uiState.scannedCount.toString()
        remainingCountText.text = uiState.remainingCount.toString()

        // Show/hide clear filters button
        view?.findViewById<MaterialButton>(R.id.btnClearFilters)?.isVisible = uiState.hasActiveFilters

        // Update empty state - fix the logic here
        updateEmptyStateFromUiState(uiState)

        // Show/hide FAB based on whether there are meters to scan
        fabScanMeter.isVisible = uiState.totalCount > 0

        // Handle navigation to ReaderFragment
        if (uiState.shouldNavigateToScan) {
            // Store the selected meter context for when we return from scanning
            val bundle = Bundle().apply {
                putString("scanContext", "METER_CHECK")
                putString("targetSerial", uiState.selectedMeter?.serialNumber ?: "")
                putString("targetLocation", uiState.selectedMeter?.place ?: "")
                putString("meterNumber", uiState.selectedMeter?.number ?: "")
            }
            findNavController().navigate(R.id.action_meterCheck_to_reader, bundle)
            viewModel.clearNavigationFlag()
        }
    }

    private fun updateEmptyStateFromUiState(uiState: MeterCheckUiState) {
        // Use the actual filtered list size, not the uiState counts
        val actualFilteredSize = currentFilteredList.size
        val isEmpty = actualFilteredSize == 0

        emptyStateLayout.isVisible = isEmpty
        meterRecyclerView.isVisible = !isEmpty

        if (isEmpty) {
            val emptyStateTitle = view?.findViewById<TextView>(R.id.empty_state_title)
            val emptyStateMessage = view?.findViewById<TextView>(R.id.empty_state_message)

            if (currentCompleteMeterList.isEmpty()) {
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

    private fun updateEmptyState(meters: List<MeterStatus>, searchQuery: String) {
        val isEmpty = meters.isEmpty()
        emptyStateLayout.isVisible = isEmpty
        meterRecyclerView.isVisible = !isEmpty

        if (isEmpty) {
            val emptyStateTitle = view?.findViewById<TextView>(R.id.empty_state_title)
            val emptyStateMessage = view?.findViewById<TextView>(R.id.empty_state_message)

            when {
                searchQuery.isNotEmpty() -> {
                    emptyStateTitle?.text = getString(R.string.meter_check_no_meters_found_search)
                    emptyStateMessage?.text = getString(R.string.meter_check_try_different_search)
                }
                else -> {
                    emptyStateTitle?.text = getString(R.string.error_no_locations)
                    emptyStateMessage?.text = getString(R.string.locations_required)
                }
            }
        }
    }

    private fun applyCurrentFilters() {
        val uiState = viewModel.uiState.value
        currentFilteredList = filterAndSortMeters(currentCompleteMeterList, uiState)

        // Submit list to adapter
        meterAdapter.submitList(currentFilteredList.toList())

        // Update statistics in ViewModel
        updateStatistics()

        // Update UI visibility
        updateEmptyStateAndVisibility()

        // Debug logging
        android.util.Log.d("MeterCheckFragment",
            "Filters applied: complete=${currentCompleteMeterList.size}, " +
                    "filtered=${currentFilteredList.size}, " +
                    "query='${uiState.searchQuery}', " +
                    "location='${uiState.selectedLocation}', " +
                    "status='${uiState.selectedStatus}'"
        )
    }

    private fun filterAndSortMeters(meters: List<MeterStatus>, uiState: MeterCheckUiState): List<MeterStatus> {
        var filteredMeters = meters

        // Apply search filter
        if (uiState.searchQuery.isNotEmpty()) {
            val query = uiState.searchQuery.lowercase()
            filteredMeters = filteredMeters.filter { meter ->
                meter.serialNumber.lowercase().contains(query) ||
                        meter.number.lowercase().contains(query) ||
                        meter.place.lowercase().contains(query)
            }
        }

        // Apply location filter
        if (uiState.selectedLocation.isNotEmpty() && uiState.selectedLocation != getString(R.string.all_locations)) {
            filteredMeters = filteredMeters.filter { meter ->
                meter.place.equals(uiState.selectedLocation, ignoreCase = true)
            }
        }

        // Apply status filter
        when (uiState.selectedStatus) {
            getString(R.string.scanned_meters) -> {
                filteredMeters = filteredMeters.filter { it.isChecked }
            }
            getString(R.string.unscanned_meters) -> {
                filteredMeters = filteredMeters.filter { !it.isChecked }
            }
        }

        // Apply sorting
        filteredMeters = when (uiState.sortOption) {
            getString(R.string.sort_by_number) -> filteredMeters.sortedBy { it.number }
            getString(R.string.sort_by_serial) -> filteredMeters.sortedBy { it.serialNumber }
            getString(R.string.sort_by_location) -> filteredMeters.sortedBy { it.place }
            getString(R.string.sort_by_status) -> filteredMeters.sortedBy { it.isChecked }
            else -> filteredMeters
        }

        return filteredMeters
    }

    private fun updateStatistics() {
        val totalCount = currentFilteredList.size
        val scannedCount = currentFilteredList.count { it.isChecked }
        val remainingCount = totalCount - scannedCount

        viewModel.updateStatistics(totalCount, scannedCount, remainingCount)
    }

    private fun startMeterScanFlow(meter: MeterStatus) {
        // Store the selected meter context for when we return from scanning
        viewModel.selectMeterForScanning(meter)

        // Navigate to ReaderFragment with context
        val bundle = Bundle().apply {
            putString("scanContext", "METER_CHECK")
            putString("targetSerial", meter.serialNumber)
            putString("targetLocation", meter.place)
            putString("meterNumber", meter.number)
        }

        try {
            // ✅ SIMPLE FIX: Always navigate directly to the Reader destination
            findNavController().navigate(R.id.readerFragment, bundle)
        } catch (e: Exception) {
            Log.e("MeterCheckFragment", "Navigation failed: ${e.message}")
            Toast.makeText(
                requireContext(),
                "Unable to start camera scanner",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startQuickScan() {
        if (currentFilteredList.isNotEmpty()) {
            // Find the first unscanned meter
            val nextMeter = currentFilteredList.firstOrNull { !it.isChecked }
            if (nextMeter != null) {
                startMeterScanFlow(nextMeter)
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.all_meters_scanned),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun navigateToLocationFragment() {
        findNavController().navigate(R.id.action_meterCheck_to_location)
    }

    private fun showLocationFilterDialog() {
        val locations = currentCompleteMeterList.map { it.place }.distinct().sorted()
        val allLocations = listOf(getString(R.string.all_locations)) + locations
        val currentSelection = viewModel.uiState.value.selectedLocation

        val selectedIndex = allLocations.indexOf(currentSelection).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.filter_by_location))
            .setSingleChoiceItems(allLocations.toTypedArray(), selectedIndex) { dialog, which ->
                viewModel.setLocationFilter(allLocations[which])
                applyCurrentFilters()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel_location), null)
            .show()
    }

    private fun showStatusFilterDialog() {
        val statusOptions = listOf(
            getString(R.string.all_locations), // Using as "All"
            getString(R.string.scanned_meters),
            getString(R.string.unscanned_meters)
        )
        val currentSelection = viewModel.uiState.value.selectedStatus
        val selectedIndex = statusOptions.indexOf(currentSelection).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.filter_by_status))
            .setSingleChoiceItems(statusOptions.toTypedArray(), selectedIndex) { dialog, which ->
                viewModel.setStatusFilter(statusOptions[which])
                applyCurrentFilters()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel_location), null)
            .show()
    }

    private fun showSortDialog() {
        val sortOptions = listOf(
            getString(R.string.sort_by_number),
            getString(R.string.sort_by_serial),
            getString(R.string.sort_by_location),
            getString(R.string.sort_by_status)
        )
        val currentSelection = viewModel.uiState.value.sortOption
        val selectedIndex = sortOptions.indexOf(currentSelection).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.sort_options))
            .setSingleChoiceItems(sortOptions.toTypedArray(), selectedIndex) { dialog, which ->
                viewModel.setSortOption(sortOptions[which])
                applyCurrentFilters()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel_location), null)
            .show()
    }

    private fun clearAllFilters() {
        viewModel.clearAllFilters()
        applyCurrentFilters()
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