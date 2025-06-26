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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.microqr.R
import com.example.microqr.databinding.FragmentMeterMatchBinding
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

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
        setupObservers()

        Log.d("MeterMatchFragment", "onViewCreated completed")
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.meter_match_toolbar_title)
    }

    private fun setupRecyclerView() {
        meterAdapter = MeterMatchAdapter(
            onItemClick = { meter ->
                showMeterDetailDialog(meter)
            }
        )

        binding.rvMeters.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = meterAdapter
        }
    }

    private fun setupSearchView() {
        // Search is now inside the search_text_input_layout
        val searchInput = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearch)

        searchInput?.addTextChangedListener(object : TextWatcher {
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
        // Filter buttons are now in the AppBarLayout section
        binding.btnFilterPlace.text = getString(R.string.meter_match_filter_place)
        binding.btnFilterFile.text = getString(R.string.meter_match_filter_file)
        binding.btnSort.text = getString(R.string.meter_match_sort)

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
            clearAllFilters()
        }
    }

    private fun setupObservers() {
        // Observe UI state changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                updateUI(uiState)
            }
        }

        // Observe MeterMatch specific data from FilesViewModel
        filesViewModel.meterMatchMeters.observe(viewLifecycleOwner) { meterMatchMeters ->
            Log.d("MeterMatchFragment", "Received ${meterMatchMeters.size} meters for matching")

            currentCompleteMeterList = meterMatchMeters
            viewModel.setMeters(meterMatchMeters)
            applyCurrentFilters()
        }
    }

    private fun applyCurrentFilters() {
        val uiState = viewModel.uiState.value
        currentFilteredList = filterAndSortMeters(currentCompleteMeterList, uiState)

        meterAdapter.submitList(currentFilteredList.toList())
        updateEmptyState()
        updateStatistics()
    }

    private fun filterAndSortMeters(meters: List<MeterStatus>, uiState: MatchUiState): List<MeterStatus> {
        var filteredMeters = meters

        // Apply search filter
        if (uiState.searchQuery.isNotEmpty()) {
            val query = uiState.searchQuery.lowercase()
            filteredMeters = filteredMeters.filter { meter ->
                meter.serialNumber.lowercase().contains(query) ||
                        meter.number.lowercase().contains(query) ||
                        meter.place.lowercase().contains(query) ||
                        meter.fromFile.lowercase().contains(query)
            }
        }

        // Apply place filter
        if (uiState.filterState.selectedPlaces.isNotEmpty()) {
            filteredMeters = filteredMeters.filter { meter ->
                meter.place in uiState.filterState.selectedPlaces
            }
        }

        // Apply file filter
        if (uiState.filterState.selectedFiles.isNotEmpty()) {
            filteredMeters = filteredMeters.filter { meter ->
                meter.fromFile in uiState.filterState.selectedFiles
            }
        }

        // Apply registration status filter
        if (uiState.filterState.selectedRegistrationStatus.isNotEmpty()) {
            filteredMeters = filteredMeters.filter { meter ->
                meter.registered in uiState.filterState.selectedRegistrationStatus
            }
        }

        // Apply checked status filter
        filteredMeters = when {
            uiState.filterState.showCheckedOnly -> filteredMeters.filter { it.isChecked }
            uiState.filterState.showUncheckedOnly -> filteredMeters.filter { !it.isChecked }
            else -> filteredMeters
        }

        // Apply sorting
        return when (uiState.sortOption) {
            SortOption.SERIAL_NUMBER -> filteredMeters.sortedBy { it.serialNumber }
            SortOption.METER_NUMBER -> filteredMeters.sortedBy { it.number }
            SortOption.PLACE -> filteredMeters.sortedBy { it.place }
            SortOption.SOURCE_FILE -> filteredMeters.sortedBy { it.fromFile }
            SortOption.REGISTRATION_STATUS -> filteredMeters.sortedBy { it.registered }
        }.let { sorted ->
            if (uiState.sortAscending) sorted else sorted.reversed()
        }
    }

    private fun updateUI(uiState: MatchUiState) {
        // Update filter chips
        updateFilterChips(uiState)

        // Update clear filters button visibility
        binding.btnClearFilters.isVisible = hasActiveFilters(uiState.filterState) ||
                uiState.searchQuery.isNotEmpty()

        // Update filter button text
        updateFilterButtonText(uiState)
    }

    private fun updateStatistics() {
        val total = currentCompleteMeterList.size
        val filtered = currentFilteredList.size
        val matched = currentCompleteMeterList.count { it.isChecked }
        val unmatched = total - matched

        binding.tvResultsCount.text = filtered.toString()
        binding.matchedCount.text = matched.toString()
        binding.unmatchedCount.text = unmatched.toString()

        // Update results summary
        binding.tvResultsSummary.text = when {
            filtered == total && total > 0 -> getString(R.string.showing_all_meters, filtered)
            filtered < total -> getString(R.string.showing_filtered_meters, filtered, total)
            total == 0 -> getString(R.string.no_meters_loaded)
            else -> getString(R.string.no_results_match_filters)
        }
    }

    private fun updateEmptyState() {
        val isEmpty = currentFilteredList.isEmpty()
        binding.layoutEmptyState.isVisible = isEmpty
        binding.rvMeters.isVisible = !isEmpty

        if (isEmpty) {
            val uiState = viewModel.uiState.value
            if (uiState.searchQuery.isNotEmpty() || hasActiveFilters(uiState.filterState)) {
                binding.tvEmptyTitle.text = getString(R.string.meter_match_empty_title)
                binding.tvEmptyMessage.text = getString(R.string.meter_match_empty_subtitle)
            } else if (currentCompleteMeterList.isEmpty()) {
                binding.tvEmptyTitle.text = getString(R.string.no_meters_available)
                binding.tvEmptyMessage.text = getString(R.string.process_csv_files_message)
            } else {
                binding.tvEmptyTitle.text = getString(R.string.all_meters_filtered_out)
                binding.tvEmptyMessage.text = getString(R.string.clear_filters_to_see_all)
            }
        }
    }

    private fun updateFilterButtonText(uiState: MatchUiState) {
        // Update place filter button
        val placeCount = uiState.filterState.selectedPlaces.size
        binding.btnFilterPlace.text = if (placeCount > 0) {
            getString(R.string.place_filter_count, placeCount)
        } else {
            getString(R.string.meter_match_filter_place)
        }

        // Update file filter button
        val fileCount = uiState.filterState.selectedFiles.size
        binding.btnFilterFile.text = if (fileCount > 0) {
            getString(R.string.file_filter_count, fileCount)
        } else {
            getString(R.string.meter_match_filter_file)
        }

        // Update sort button
        val sortDisplayName = getString(uiState.sortOption.displayNameRes)
        val sortText = when {
            uiState.sortAscending -> "$sortDisplayName ↑"
            else -> "$sortDisplayName ↓"
        }
        binding.btnSort.text = sortText
    }

    private fun updateFilterChips(uiState: MatchUiState) {
        binding.chipGroupFilters.removeAllViews()

        val hasFilters = hasActiveFilters(uiState.filterState) || uiState.searchQuery.isNotEmpty()
        binding.chipGroupFilters.isVisible = hasFilters

        // Add search chip
        if (uiState.searchQuery.isNotEmpty()) {
            addFilterChip("${getString(R.string.search_meters)}: ${uiState.searchQuery}") {
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
            val text = if (registered) getString(R.string.registered) else getString(R.string.unregistered)
            addFilterChip("✅ $text") {
                viewModel.toggleRegistrationFilter(registered)
                applyCurrentFilters()
            }
        }

        // Add checked status chip
        when {
            uiState.filterState.showCheckedOnly -> {
                addFilterChip("📷 ${getString(R.string.already_scanned)}") {
                    viewModel.toggleCheckedFilter()
                    applyCurrentFilters()
                }
            }
            uiState.filterState.showUncheckedOnly -> {
                addFilterChip("⭕ ${getString(R.string.pending_scan_status)}") {
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

    private fun hasActiveFilters(filterState: FilterState): Boolean {
        return filterState.selectedPlaces.isNotEmpty() ||
                filterState.selectedFiles.isNotEmpty() ||
                filterState.selectedRegistrationStatus.isNotEmpty() ||
                filterState.showCheckedOnly ||
                filterState.showUncheckedOnly
    }

    private fun clearAllFilters() {
        viewModel.clearAllFilters()
        applyCurrentFilters()
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
            .setPositiveButton(getString(R.string.done)) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(getString(R.string.meter_match_clear_all)) { _, _ ->
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
            .setPositiveButton(getString(R.string.done)) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(getString(R.string.meter_match_clear_all)) { _, _ ->
                viewModel.clearFileFilters()
                applyCurrentFilters()
            }
            .show()
    }

    private fun showSortDialog() {
        val uiState = viewModel.uiState.value
        val sortOptions = SortOption.values()
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
                applyCurrentFilters()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showMeterDetailDialog(meter: MeterStatus) {
        val scanStatusText = if (meter.isChecked) getString(R.string.already_scanned) else getString(R.string.pending_scan_status)
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
            .setPositiveButton(getString(R.string.ok)) { dialog, _ -> dialog.dismiss() }

        // Only show scan option if not already scanned
        if (!meter.isChecked) {
            dialogBuilder.setNeutralButton(getString(R.string.scan_meter)) { _, _ ->
                navigateToScanMeter(meter)
            }
        }

        // Option to manually toggle scan status
        dialogBuilder.setNegativeButton(
            if (meter.isChecked) getString(R.string.mark_as_unscanned) else getString(R.string.mark_as_scanned)
        ) { _, _ ->
            filesViewModel.updateMeterCheckedStatus(meter.serialNumber, !meter.isChecked, meter.fromFile)
            Toast.makeText(context,
                if (!meter.isChecked) getString(R.string.mark_as_scanned) else getString(R.string.mark_as_unscanned),
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}