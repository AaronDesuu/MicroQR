package com.example.microqr.ui.metermatch

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.microqr.databinding.FragmentMeterMatchBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.ui.files.ProcessingDestination
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.example.microqr.R

class MeterMatchFragment : Fragment() {

    private var _binding: FragmentMeterMatchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MatchViewModel by viewModels()
    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var meterAdapter: MeterMatchAdapter

    // Hold the current complete list and filtered list
    private var currentCompleteMeterList: List<MeterStatus> = emptyList()
    private var currentFilteredList: List<MeterStatus> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeterMatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupSearchView()
        setupFilterButtons()

        // Initialize with empty state
        clearFragmentData()

        observeUiState()
        observeFilesViewModel()

        // DO NOT call loadDataForFragment() here - let observers handle data loading

        Log.d("MeterMatchFragment", "onViewCreated completed - fragment initialized empty")
    }

    override fun onResume() {
        super.onResume()
        // DO NOT refresh data automatically - only show what's in the dedicated LiveData
        Log.d("MeterMatchFragment", "onResume - checking meterMatchMeters: ${filesViewModel.meterMatchMeters.value?.size ?: 0}")
    }

    private fun loadDataForFragment() {
        // Only load data if we have MeterMatch specific data in LiveData
        val meterMatchMeters = filesViewModel.meterMatchMeters.value
        if (!meterMatchMeters.isNullOrEmpty()) {
            currentCompleteMeterList = meterMatchMeters
            viewModel.setMeters(meterMatchMeters)
            updateStatisticsAndApplyFilter()
            Toast.makeText(
                context,
                "Loaded ${meterMatchMeters.size} meters for matching",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // No MeterMatch specific data, clear display
            clearFragmentData()
        }
    }

    private fun clearFragmentData() {
        Log.d("MeterMatchFragment", "🧹 Clearing fragment data")
        currentCompleteMeterList = emptyList()
        currentFilteredList = emptyList()
        viewModel.setMeters(emptyList())
        updateEmptyState(emptyList(), "")
        Log.d("MeterMatchFragment", "✅ Fragment data cleared - showing empty state")
    }

    private fun updateStatisticsAndApplyFilter() {
        applyCurrentFilters()
    }

    private fun applyCurrentFilters() {
        // Apply the current state filters to the complete list
        val uiState = viewModel.uiState.value
        currentFilteredList = filterMeters(currentCompleteMeterList, uiState)

        Log.d("MeterMatchFragment", "Submitting ${currentFilteredList.size} filtered meters to adapter")
        meterAdapter.submitList(currentFilteredList.toList()) // Ensure new list instance
        updateEmptyState(currentFilteredList, uiState.searchQuery)
    }

    private fun filterMeters(meters: List<MeterStatus>, uiState: MatchUiState): List<MeterStatus> {
        return meters.filter { meter ->
            // Search filter
            val matchesSearch = uiState.searchQuery.isEmpty() ||
                    meter.serialNumber.contains(uiState.searchQuery, ignoreCase = true) ||
                    meter.number.contains(uiState.searchQuery, ignoreCase = true) ||
                    meter.place.contains(uiState.searchQuery, ignoreCase = true) ||
                    meter.fromFile.contains(uiState.searchQuery, ignoreCase = true)

            // Place filter
            val matchesPlaceFilter = uiState.filterState.selectedPlaces.isEmpty() ||
                    meter.place in uiState.filterState.selectedPlaces

            // File filter
            val matchesFileFilter = uiState.filterState.selectedFiles.isEmpty() ||
                    meter.fromFile in uiState.filterState.selectedFiles

            // Registration status filter
            val matchesRegistrationFilter = uiState.filterState.selectedRegistrationStatus.isEmpty() ||
                    meter.registered in uiState.filterState.selectedRegistrationStatus

            // Checked status filter
            val matchesCheckedFilter = when {
                uiState.filterState.showCheckedOnly -> meter.isChecked
                uiState.filterState.showUncheckedOnly -> !meter.isChecked
                else -> true
            }

            matchesSearch && matchesPlaceFilter && matchesFileFilter && matchesRegistrationFilter && matchesCheckedFilter
        }.let { filteredList ->
            // Apply sorting
            when (uiState.sortOption) {
                SortOption.SERIAL_NUMBER -> filteredList.sortedBy { it.serialNumber }
                SortOption.METER_NUMBER -> filteredList.sortedBy { it.number }
                SortOption.PLACE -> filteredList.sortedBy { it.place }
                SortOption.SOURCE_FILE -> filteredList.sortedBy { it.fromFile }
                SortOption.REGISTRATION_STATUS -> filteredList.sortedBy { it.registered }
            }.let { sorted ->
                if (uiState.sortAscending) sorted else sorted.reversed()
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.title = "Match Meters"
        // You can add toolbar menu items here if needed
    }

    private fun setupRecyclerView() {
        meterAdapter = MeterMatchAdapter(
            onItemClick = { meter ->
                // Handle meter item click - show detail dialog with scan option
                showMeterDetailDialog(meter)
            }
        )

        binding.rvMeters.apply {
            layoutManager = LinearLayoutManager(requireContext())
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
                applyCurrentFilters()
            }
        })
    }

    private fun setupFilterButtons() {
        binding.btnFilterPlace.setOnClickListener {
            showPlaceFilterDialog()
        }

        binding.btnFilterFile.setOnClickListener {
            showFileFilterDialog()
        }

        binding.btnSort.setOnClickListener {
            showSortDialog()
        }

        binding.btnClearFilters.setOnClickListener {
            viewModel.clearAllFilters()
            applyCurrentFilters()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                updateUI(uiState)
            }
        }
    }

    private fun observeFilesViewModel() {
        // ONLY observe MeterMatch specific data - this is the ONLY source of data for this fragment
        filesViewModel.meterMatchMeters.observe(viewLifecycleOwner) { meterMatchMeters ->
            Log.d("MeterMatchFragment", "🔍 meterMatchMeters observer triggered: ${meterMatchMeters.size} meters")

            if (meterMatchMeters.isNotEmpty()) {
                Log.d("MeterMatchFragment", "✅ Loading ${meterMatchMeters.size} meters for MeterMatch")
                currentCompleteMeterList = meterMatchMeters
                viewModel.setMeters(meterMatchMeters)
                updateStatisticsAndApplyFilter()
            } else {
                Log.d("MeterMatchFragment", "❌ No MeterMatch meters, clearing fragment")
                clearFragmentData()
            }
        }

        // DO NOT observe meterStatusList or selectedMetersForProcessing or fileItems
        // DO NOT observe anything that could trigger automatic data loading

        Log.d("MeterMatchFragment", "🔧 Observer setup complete - only observing meterMatchMeters")
    }

    private fun updateUI(uiState: MatchUiState) {
        // Update loading state
        binding.layoutLoading.isVisible = uiState.isLoading

        // Update results count and stats
        val count = currentFilteredList.size
        val total = currentCompleteMeterList.size

        // Update main results count
        binding.tvResultsCount.text = count.toString()

        // Update stats
        val matchedCount = currentCompleteMeterList.count { it.isChecked }
        val unmatchedCount = total - matchedCount
        binding.matchedCount.text = matchedCount.toString()
        binding.unmatchedCount.text = unmatchedCount.toString()

        // Update results summary
        binding.tvResultsSummary.text = when {
            count == total && total > 0 -> getString(R.string.showing_all_meters, count)
            count < total -> getString(R.string.showing_filtered_meters, count, total)
            total == 0 -> getString(R.string.no_meters_loaded)
            else -> getString(R.string.no_results_match_filters)
        }

        // Update empty state
        val isEmpty = currentFilteredList.isEmpty() && !uiState.isLoading
        binding.layoutEmptyState.isVisible = isEmpty
        binding.rvMeters.isVisible = !isEmpty && !uiState.isLoading

        if (isEmpty) {
            if (uiState.searchQuery.isNotEmpty() || hasActiveFilters(uiState.filterState)) {
                binding.tvEmptyTitle.text = getString(R.string.no_meters_found_search)
                binding.tvEmptyMessage.text = getString(R.string.try_adjusting_search)
            } else if (currentCompleteMeterList.isEmpty()) {
                binding.tvEmptyTitle.text = getString(R.string.no_meters_available)
                binding.tvEmptyMessage.text = getString(R.string.process_csv_files_message)
            } else {
                binding.tvEmptyTitle.text = getString(R.string.all_meters_filtered_out)
                binding.tvEmptyMessage.text = getString(R.string.clear_filters_to_see_all)
            }
        }

        // Update filter chips
        updateFilterChips(uiState)

        // Update clear filters button
        binding.btnClearFilters.isVisible = hasActiveFilters(uiState.filterState) ||
                uiState.searchQuery.isNotEmpty()

        // Update search field
        if (binding.etSearch.text.toString() != uiState.searchQuery) {
            binding.etSearch.setText(uiState.searchQuery)
            binding.etSearch.setSelection(uiState.searchQuery.length)
        }

        // Update filter button text to show active filters count
        updateFilterButtonText(uiState)
    }

    private fun updateFilterButtonText(uiState: MatchUiState) {
        // Update place filter button
        val placeCount = uiState.filterState.selectedPlaces.size
        binding.btnFilterPlace.text = if (placeCount > 0) {
            getString(R.string.place_filter_count, placeCount)
        } else {
            getString(R.string.filter_place)
        }

        // Update file filter button
        val fileCount = uiState.filterState.selectedFiles.size
        binding.btnFilterFile.text = if (fileCount > 0) {
            getString(R.string.file_filter_count, fileCount)
        } else {
            "Filter File"
        }

        // Update sort button to show current sort
        val sortText = when {
            uiState.sortAscending -> "${uiState.sortOption.displayName} ↑"
            else -> "${uiState.sortOption.displayName} ↓"
        }
        binding.btnSort.text = sortText
    }

    private fun updateEmptyState(meters: List<MeterStatus>, searchQuery: String) {
        val isEmpty = meters.isEmpty()
        val hasSearchQuery = searchQuery.isNotBlank()
        val hasAnyMeters = currentCompleteMeterList.isNotEmpty()

        binding.layoutEmptyState.isVisible = isEmpty
        binding.rvMeters.isVisible = !isEmpty

        if (isEmpty) {
            if (hasSearchQuery || hasActiveFilters(viewModel.uiState.value.filterState)) {
                binding.tvEmptyTitle.text = getString(R.string.no_meters_found_search)
                binding.tvEmptyMessage.text = getString(R.string.try_adjusting_search)
            } else if (!hasAnyMeters) {
                binding.tvEmptyTitle.text = getString(R.string.no_meters_available)
                binding.tvEmptyMessage.text = getString(R.string.process_csv_files_message)
            } else {
                binding.tvEmptyTitle.text = getString(R.string.no_meters_available)
            }
        }
    }

    private fun hasActiveFilters(filterState: FilterState): Boolean {
        return filterState.selectedPlaces.isNotEmpty() ||
                filterState.selectedFiles.isNotEmpty() ||
                filterState.selectedRegistrationStatus.isNotEmpty() ||
                filterState.showCheckedOnly ||
                filterState.showUncheckedOnly
    }

    private fun updateFilterChips(uiState: MatchUiState) {
        binding.chipGroupFilters.removeAllViews()

        val hasFilters = hasActiveFilters(uiState.filterState) || uiState.searchQuery.isNotEmpty()
        binding.chipGroupFilters.isVisible = hasFilters

        // Add search chip
        if (uiState.searchQuery.isNotEmpty()) {
            addFilterChip("Search: ${uiState.searchQuery}") {
                viewModel.updateSearchQuery("")
                applyCurrentFilters()
            }
        }

        // Add place filter chips
        uiState.filterState.selectedPlaces.forEach { place ->
            addFilterChip("📍 ${getShortText(place, 15)}") {
                viewModel.togglePlaceFilter(place)
                applyCurrentFilters()
            }
        }

        // Add file filter chips
        uiState.filterState.selectedFiles.forEach { file ->
            addFilterChip("📁 ${getShortFileName(file)}") {
                viewModel.toggleFileFilter(file)
                applyCurrentFilters()
            }
        }

        // Add registration filter chips
        uiState.filterState.selectedRegistrationStatus.forEach { registered ->
            val text = if (registered) "✅ Registered" else "❌ Unregistered"
            addFilterChip(text) {
                viewModel.toggleRegistrationFilter(registered)
                applyCurrentFilters()
            }
        }

        // Add checked status chip
        when {
            uiState.filterState.showCheckedOnly -> {
                addFilterChip("📷 Scanned Only") {
                    viewModel.toggleCheckedFilter()
                    applyCurrentFilters()
                }
            }
            uiState.filterState.showUncheckedOnly -> {
                addFilterChip("⭕ Not Scanned Only") {
                    viewModel.toggleCheckedFilter()
                    applyCurrentFilters()
                }
            }
        }
    }

    private fun addFilterChip(text: String, onRemove: () -> Unit) {
        val chip = Chip(requireContext()).apply {
            this.text = text
            isCloseIconVisible = true
            setOnCloseIconClickListener { onRemove() }
        }
        binding.chipGroupFilters.addView(chip)
    }

    private fun getShortFileName(fileName: String): String {
        return if (fileName.length > 15) {
            fileName.take(12) + "..."
        } else {
            fileName
        }
    }

    private fun getShortText(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 3) + "..."
        } else {
            text
        }
    }

    private fun showPlaceFilterDialog() {
        val availablePlaces = currentCompleteMeterList.map { it.place }.distinct().sorted()
        val uiState = viewModel.uiState.value
        val selectedPlaces = uiState.filterState.selectedPlaces
        val checkedItems = availablePlaces.map { it in selectedPlaces }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.filter_by_place))
            .setMultiChoiceItems(availablePlaces.toTypedArray(), checkedItems) { _, which, _ ->
                val place = availablePlaces[which]
                viewModel.togglePlaceFilter(place)
                applyCurrentFilters()
            }
            .setPositiveButton("Done") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Clear All") { _, _ ->
                viewModel.clearPlaceFilters()
                applyCurrentFilters()
            }
            .show()
    }

    private fun showFileFilterDialog() {
        val availableFiles = currentCompleteMeterList.map { it.fromFile }.distinct().sorted()
        val uiState = viewModel.uiState.value
        val selectedFiles = uiState.filterState.selectedFiles
        val checkedItems = availableFiles.map { it in selectedFiles }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.filter_by_source_file))
            .setMultiChoiceItems(availableFiles.toTypedArray(), checkedItems) { _, which, _ ->
                val file = availableFiles[which]
                viewModel.toggleFileFilter(file)
                applyCurrentFilters()
            }
            .setPositiveButton("Done") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Clear All") { _, _ ->
                viewModel.clearFileFilters()
                applyCurrentFilters()
            }
            .show()
    }

    private fun showSortDialog() {
        val uiState = viewModel.uiState.value
        val sortOptions = SortOption.values()
        val optionNames = sortOptions.map {
            val arrow = if (uiState.sortOption == it) {
                if (uiState.sortAscending) " ↑" else " ↓"
            } else ""
            it.displayName + arrow
        }.toTypedArray()

        val selectedIndex = sortOptions.indexOf(uiState.sortOption)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.sort_by))
            .setSingleChoiceItems(optionNames, selectedIndex) { dialog, which ->
                viewModel.setSortOption(sortOptions[which])
                applyCurrentFilters()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showMeterDetailDialog(meter: MeterStatus) {
        val scanStatusText = if (meter.isChecked) "✅ Scanned & Registered" else "❌ Pending Scan"
        val message = getString(R.string.meter_information) + "\n\n" +
                getString(R.string.meter_number_label, meter.number) + "\n" +
                getString(R.string.meter_serial_label, meter.serialNumber) + "\n" +
                getString(R.string.meter_location_label, meter.place) + "\n" +
                getString(R.string.meter_source_label, meter.fromFile) + "\n\n" +
                getString(R.string.status_summary) + "\n" +
                getString(R.string.qr_scan_status, scanStatusText)

        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.meter_details))
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        // Only show scan option if not already scanned
        if (!meter.isChecked) {
            dialogBuilder.setNeutralButton("Scan Meter") { _, _ ->
                // Navigate to the scan fragment with meter details
                navigateToScanMeter(meter)
            }
        }

        // Option to manually toggle scan status (for testing/correction)
        dialogBuilder.setNegativeButton(
            if (meter.isChecked) getString(R.string.marked_as_not_scanned) else getString(R.string.marked_as_scanned)
        ) { _, _ ->
            filesViewModel.updateMeterCheckedStatus(meter.serialNumber, !meter.isChecked, meter.fromFile)
            Toast.makeText(context,
                if (!meter.isChecked) getString(R.string.marked_as_scanned) else getString(R.string.marked_as_not_scanned),
                Toast.LENGTH_SHORT).show()
        }

        dialogBuilder.show()
    }

    private fun navigateToScanMeter(meter: MeterStatus) {
        try {
            val bundle = MeterDetailScanFragment.createBundle(meter)
            findNavController().navigate(
                R.id.action_matchFragment_to_meterDetailScanFragment,
                bundle
            )
        } catch (e: Exception) {
            Log.e("MeterMatchFragment", "Navigation error: ${e.message}")
            Toast.makeText(context, getString(R.string.unable_start_camera), Toast.LENGTH_SHORT).show()
        }
    }

    // Helper method to get current statistics for external use
    fun getCurrentStats(): Triple<Int, Int, Int> {
        val total = currentCompleteMeterList.size
        val registered = currentCompleteMeterList.count { it.isChecked }
        val unregistered = total - registered
        return Triple(total, registered, unregistered)
    }

    // Helper method to get filtered meters count
    fun getFilteredCount(): Int = currentFilteredList.size

    // Debug method - you can call this from anywhere to check current state

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}