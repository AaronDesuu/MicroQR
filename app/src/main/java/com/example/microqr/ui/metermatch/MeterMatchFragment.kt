package com.example.microqr.ui.metermatch

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        setupBatchReadingButton()

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

    // Add this method to replace the existing showMeterDetailDialog method in MeterMatchFragment.kt

    private fun showMeterDetailDialog(meter: MeterStatus) {
        // Inflate the custom dialog layout
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_meter_info_match, null)

        // Create the dialog
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Set up the dialog views and populate data
        setupDialogViews(dialogView, meter, dialog)

        dialog.show()
    }

    private fun setupDialogViews(dialogView: View, meter: MeterStatus, dialog: androidx.appcompat.app.AlertDialog) {
        // Find views
        val tvMeterNumber = dialogView.findViewById<TextView>(R.id.tvDialogMeterNumber)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvDialogStatus)
        val tvSerialNumber = dialogView.findViewById<TextView>(R.id.tvDialogSerialNumber)
        val tvLocation = dialogView.findViewById<TextView>(R.id.tvDialogLocation)
        val tvSourceFile = dialogView.findViewById<TextView>(R.id.tvDialogSourceFile)
        val btnCloseDialog = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCloseDialog)
        val btnScanMeter = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnScanMeter)
        val btnMarkAsScanned = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMarkAsScanned)

        // Populate meter data
        tvMeterNumber.text = meter.number
        tvSerialNumber.text = meter.serialNumber
        tvLocation.text = meter.place
        tvSourceFile.text = meter.fromFile

        // Set status text and color
        val statusText = if (meter.isChecked) {
            getString(R.string.already_scanned)
        } else {
            getString(R.string.pending_scan_status)
        }
        tvStatus.text = statusText

        // Set status color
        val statusColor = if (meter.isChecked) {
            ContextCompat.getColor(requireContext(), R.color.success_green)
        } else {
            ContextCompat.getColor(requireContext(), R.color.warning_orange)
        }
        tvStatus.setTextColor(statusColor)

        // Set up close button
        btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        // Set up scan button
        btnScanMeter.setOnClickListener {
            dialog.dismiss()
            navigateToScanMeter(meter)
        }

        // Set up mark as scanned button
        setupMarkAsScannedButton(btnMarkAsScanned, meter, dialog)
    }

    private fun setupMarkAsScannedButton(
        btnMarkAsScanned: com.google.android.material.button.MaterialButton,
        meter: MeterStatus,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        // Update button text and behavior based on current scan status
        if (meter.isChecked) {
            // Already scanned - show "Mark as Unscanned" option
            btnMarkAsScanned.text = getString(R.string.mark_as_unscanned)
            btnMarkAsScanned.setIconResource(R.drawable.ic_cancel_24) // You might need to add this icon
            btnMarkAsScanned.setOnClickListener {
                // Show confirmation dialog for unmarking
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.confirm_unmark_scanned))
                    .setMessage(getString(R.string.confirm_unmark_scanned_message, meter.number, meter.serialNumber))
                    .setPositiveButton(getString(R.string.mark_as_unscanned)) { _, _ ->
                        markMeterAsUnscanned(meter)
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { confirmDialog, _ ->
                        confirmDialog.dismiss()
                    }
                    .show()
            }
        } else {
            // Not scanned - show "Mark as Scanned" option
            btnMarkAsScanned.text = getString(R.string.mark_as_scanned_match)
            btnMarkAsScanned.setIconResource(R.drawable.ic_check_circle)
            btnMarkAsScanned.setOnClickListener {
                // Show confirmation dialog for marking as scanned
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.confirm_mark_scanned))
                    .setMessage(getString(R.string.confirm_mark_scanned_message, meter.number, meter.serialNumber))
                    .setPositiveButton(getString(R.string.mark_as_scanned_match)) { _, _ ->
                        markMeterAsScanned(meter)
                        dialog.dismiss()
                    }
                    .setNegativeButton(getString(R.string.cancel)) { confirmDialog, _ ->
                        confirmDialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun markMeterAsScanned(meter: MeterStatus) {
        try {
            // Update the meter status in the database
            filesViewModel.updateMeterCheckedStatus(meter.serialNumber, true, meter.fromFile)

            // Show success message
            Toast.makeText(
                requireContext(),
                getString(R.string.meter_marked_as_scanned, meter.number),
                Toast.LENGTH_SHORT
            ).show()

            // Log the action
            Log.d("MeterMatchFragment", "✅ Meter ${meter.number} (Serial: ${meter.serialNumber}) marked as scanned from ${meter.fromFile}")

        } catch (e: Exception) {
            Log.e("MeterMatchFragment", "❌ Error marking meter as scanned: ${e.message}")
            Toast.makeText(
                requireContext(),
                getString(R.string.error_update_scan_status),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun markMeterAsUnscanned(meter: MeterStatus) {
        try {
            // Update the meter status in the database
            filesViewModel.updateMeterCheckedStatus(meter.serialNumber, false, meter.fromFile)

            // Show success message
            Toast.makeText(
                requireContext(),
                getString(R.string.meter_marked_as_not_scanned, meter.number),
                Toast.LENGTH_SHORT
            ).show()

            // Log the action
            Log.d("MeterMatchFragment", "❌ Meter ${meter.number} (Serial: ${meter.serialNumber}) marked as unscanned from ${meter.fromFile}")

        } catch (e: Exception) {
            Log.e("MeterMatchFragment", "❌ Error marking meter as unscanned: ${e.message}")
            Toast.makeText(
                requireContext(),
                getString(R.string.error_update_scan_status),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Keep the existing navigateToScanMeter method unchanged
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

    // Add this to your existing MeterMatchFragment.kt

    private fun setupBatchReadingButton() {
        // Find the batch reading card/button in your layout
        binding.setupLocationsCard.setOnClickListener {
            startBatchReading()
        }
    }

    private fun startBatchReading() {
        // Get unscanned meters from the current UI state
        val uiState = viewModel.uiState.value
        val unscannedMeters = uiState.filteredMeters.filter { meter -> !meter.isChecked }

        if (unscannedMeters.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.continuous_reading_no_unscanned_meters),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Show confirmation dialog
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.continuous_reading_confirm_title))
            .setMessage(
                getString(
                    R.string.continuous_reading_confirm_message,
                    unscannedMeters.size
                )
            )
            .setPositiveButton(getString(R.string.start)) { _, _ ->
                navigateToContinuousReading(unscannedMeters)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun navigateToContinuousReading(meters: List<MeterStatus>) {
        try {
            val bundle = ContinuousReadingFragment.createBundle(meters)
            findNavController().navigate(
                R.id.action_matchFragment_to_continuousReadingFragment,
                bundle
            )
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.navigation_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}