package com.example.microqr.ui.metercheck

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.example.microqr.ui.files.ProcessingDestination
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
    private lateinit var registeredMetersCountText: TextView
    private lateinit var unregisteredMetersCountText: TextView
    private lateinit var searchEditText: TextInputEditText
    private lateinit var refreshButton: MaterialButton
    private lateinit var emptyStateLayout: View
    private lateinit var currentFilesTextView: TextView

    // Hold the current complete list and filtered list
    private var currentCompleteMeterList: List<MeterStatus> = emptyList()
    private var currentFilteredList: List<MeterStatus> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
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
        viewModel.updateScreenTitle(getString(R.string.meter_check_default_title))
        updateEmptyState(emptyList(), "")
    }

    private fun initializeViews(view: View) {
        meterRecyclerView = view.findViewById(R.id.meter_recycler_view)
        totalMetersCountText = view.findViewById(R.id.total_meters_count)
        registeredMetersCountText = view.findViewById(R.id.active_meters_count)
        unregisteredMetersCountText = view.findViewById(R.id.alerts_count)
        searchEditText = view.findViewById(R.id.search_edit_text)
        refreshButton = view.findViewById(R.id.refresh_button)
        emptyStateLayout = view.findViewById(R.id.empty_state_layout)
        currentFilesTextView = view.findViewById(R.id.current_files_text_view)
    }

    private fun setupRecyclerView() {
        meterAdapter = MeterCheckAdapter { meterStatus, isChecked ->
            filesViewModel.updateMeterCheckedStatus(
                meterStatus.serialNumber,
                isChecked,
                meterStatus.fromFile
            )

            val statusText = if (isChecked) {
                getString(R.string.meter_marked_as_scanned, meterStatus.number)
            } else {
                getString(R.string.meter_marked_as_not_scanned, meterStatus.number)
            }
            Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
        }
        meterRecyclerView.layoutManager = LinearLayoutManager(context)
        meterRecyclerView.adapter = meterAdapter
    }

    private fun setupSearchFunctionality() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                viewModel.updateSearchQuery(query)
                applySearchFilter(query)
            }
        })
    }

    private fun setupClickListeners() {
        refreshButton.setOnClickListener {
            viewModel.clearSearch()
            searchEditText.text?.clear()

            // Show refresh feedback
            viewModel.setRefreshing(true)
            Toast.makeText(context, getString(R.string.refreshing_meter_data), Toast.LENGTH_SHORT).show()

            // Simulate refresh delay
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                viewModel.setRefreshing(false)
                Toast.makeText(context, getString(R.string.meter_data_refreshed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        // Observe screen title
        viewModel.screenTitle.observe(viewLifecycleOwner) { title ->
            activity?.title = title
        }

        // Observe refresh state
        viewModel.isRefreshing.observe(viewLifecycleOwner) { isRefreshing ->
            refreshButton.isEnabled = !isRefreshing
            refreshButton.text = if (isRefreshing) {
                getString(R.string.refresh_button_refreshing)
            } else {
                getString(R.string.refresh_button_refresh)
            }
        }

        // Observe statistics
        viewModel.totalMetersCount.observe(viewLifecycleOwner) { count ->
            totalMetersCountText.text = count.toString()
        }

        viewModel.registeredMetersCount.observe(viewLifecycleOwner) { count ->
            registeredMetersCountText.text = count.toString()
        }

        viewModel.unregisteredMetersCount.observe(viewLifecycleOwner) { count ->
            unregisteredMetersCountText.text = count.toString()
        }
    }

    private fun observeFilesViewModel() {
        // ✅ FIXED: ONLY observe meterCheckMeters - this is the ONLY source of data for MeterCheck
        filesViewModel.meterCheckMeters.observe(viewLifecycleOwner) { meterCheckMeters ->
            if (meterCheckMeters.isNotEmpty()) {
                currentCompleteMeterList = meterCheckMeters
                val screenTitle = getString(R.string.meter_check_screen_title, meterCheckMeters.size)
                viewModel.updateScreenTitle(screenTitle)
                updateStatisticsAndApplyFilter()
                updateCurrentFilesDisplay()

                val toastMessage = getString(R.string.loaded_meters_for_checking, meterCheckMeters.size)
                Toast.makeText(
                    context,
                    toastMessage,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // No MeterCheck specific data, clear display
                clearFragmentData()
            }
        }


        // ✅ ONLY observe file changes for cleanup when files are deleted
        filesViewModel.fileItems.observe(viewLifecycleOwner) { fileItems ->
            // Only handle file deletions - check if our current files still exist
            if (currentCompleteMeterList.isNotEmpty()) {
                val currentFileNames = currentCompleteMeterList.map { it.fromFile }.distinct()
                val existingFileNames = fileItems.filter { it.isValid }.map { it.fileName }

                val filesStillExist = currentFileNames.all { fileName ->
                    existingFileNames.contains(fileName)
                }

                if (!filesStillExist) {
                    // Some files were deleted, but let meterCheckMeters observer handle the update
                    // Don't clear data here, let the observer do it
                }
            }
        }
    }

    private fun updateCurrentFilesDisplay() {
        if (currentCompleteMeterList.isNotEmpty()) {
            val relevantFiles = currentCompleteMeterList.map { it.fromFile }.distinct()
            if (relevantFiles.isNotEmpty()) {
                val fileListString = relevantFiles.joinToString(", ")
                currentFilesTextView.text = getString(R.string.meter_check_data_from, fileListString)
                currentFilesTextView.isVisible = true
            } else {
                currentFilesTextView.isVisible = false
            }
        } else {
            currentFilesTextView.text = getString(R.string.no_meter_check_data_loaded)
            currentFilesTextView.isVisible = false
        }
    }

    private fun updateStatisticsAndApplyFilter() {
        updateStatistics(currentCompleteMeterList)
        applySearchFilter(viewModel.searchQuery.value ?: "")
    }

    private fun updateStatistics(meters: List<MeterStatus>) {
        val total = meters.size
        val registered = meters.count { it.isChecked }
        val unregistered = total - registered

        viewModel.updateStatistics(total, registered, unregistered)
    }

    private fun applySearchFilter(query: String) {
        currentFilteredList = if (query.isEmpty()) {
            currentCompleteMeterList
        } else {
            currentCompleteMeterList.filter { meter ->
                meter.serialNumber.contains(query, ignoreCase = true) ||
                        meter.number.contains(query, ignoreCase = true) ||
                        meter.place.contains(query, ignoreCase = true) ||
                        meter.fromFile.contains(query, ignoreCase = true)
            }
        }

        meterAdapter.submitList(currentFilteredList.toList()) // Ensure new list instance
        updateEmptyState(currentFilteredList, query)
        updateCurrentFilesDisplay()
    }

    private fun updateEmptyState(meters: List<MeterStatus>, searchQuery: String) {
        val isEmpty = meters.isEmpty()
        val hasSearchQuery = searchQuery.isNotBlank()
        val hasAnyMeters = currentCompleteMeterList.isNotEmpty()

        emptyStateLayout.isVisible = isEmpty
        meterRecyclerView.isVisible = !isEmpty

        if (isEmpty) {
            val emptyTextView = emptyStateLayout.findViewById<TextView>(R.id.empty_state_text)
            emptyTextView?.text = when {
                !hasAnyMeters -> getString(R.string.meter_check_no_meters_available)
                hasSearchQuery -> getString(R.string.meter_check_no_meters_found_search)
                else -> getString(R.string.meter_check_no_meter_data)
            }
        }
    }

    // Public method for QR scanner integration
    fun onMeterScanned(serialNumber: String) {
        val result = filesViewModel.updateMeterCheckedStatusBySerial(serialNumber)
        if (result.first) {
            val fileName = result.second
            val message = getString(R.string.meter_scanned_successfully, serialNumber, fileName) // Use getString with parameters
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
            .setNeutralButton(getString(R.string.dialog_button_show_all_meters)) { _, _ ->
                viewModel.clearSearch()
                searchEditText.text?.clear()
            }
            .show()
    }

    // Public method for QR scanner integration
    fun processQrResult(qrContent: String) {
        // Extract serial number from QR content
        val serialNumber = extractSerialNumber(qrContent)
        if (serialNumber.isNotEmpty()) {
            onMeterScanned(serialNumber)
        } else {
            Toast.makeText(context, "❌ Invalid QR code format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractSerialNumber(qrContent: String): String {
        // Implement your QR code parsing logic here
        return when {
            qrContent.startsWith("SN:") -> qrContent.substring(3).trim()
            qrContent.startsWith("Serial:") -> qrContent.substring(7).trim()
            qrContent.contains("SerialNumber:") -> {
                val parts = qrContent.split("SerialNumber:")
                if (parts.size > 1) parts[1].split(",")[0].trim() else ""
            }
            else -> qrContent.trim() // Assume whole content is serial number
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
}