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
        Log.d("MeterCheckFragment", "onCreateView completed")
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize with empty state
        clearFragmentData()

        observeViewModel()
        observeFilesViewModel()

        // DO NOT call loadDataForFragment() here - let observers handle data loading

        Log.d("MeterCheckFragment", "onViewCreated completed - fragment initialized empty")
    }

    override fun onResume() {
        super.onResume()
        // DO NOT refresh data automatically - only show what's in the dedicated LiveData
        Log.d("MeterCheckFragment", "onResume - checking meterCheckMeters: ${filesViewModel.meterCheckMeters.value?.size ?: 0}")
    }

    private fun loadDataForFragment() {
        // Load meters specifically processed for MeterCheck
        val meterCheckMeters = filesViewModel.getMetersByDestination(ProcessingDestination.METER_CHECK)
        if (meterCheckMeters.isNotEmpty()) {
            currentCompleteMeterList = meterCheckMeters
            viewModel.updateScreenTitle("MeterCheck - ${meterCheckMeters.size} meters")
            updateStatisticsAndApplyFilter()
        } else {
            // Fallback to selected meters if no MeterCheck specific data
            val selectedMeters = filesViewModel.selectedMetersForProcessing.value
            if (!selectedMeters.isNullOrEmpty()) {
                currentCompleteMeterList = selectedMeters
                viewModel.updateScreenTitle("MeterCheck - ${selectedMeters.size} meters")
                updateStatisticsAndApplyFilter()
            } else {
                // Clear data if no relevant meters
                clearFragmentData()
            }
        }
    }

    private fun clearFragmentData() {
        Log.d("MeterCheckFragment", "🧹 Clearing fragment data")
        currentCompleteMeterList = emptyList()
        currentFilteredList = emptyList()
        meterAdapter.submitList(emptyList())
        viewModel.updateStatistics(0, 0, 0)
        viewModel.updateScreenTitle("MeterCheck")
        updateEmptyState(emptyList(), "")
        Log.d("MeterCheckFragment", "✅ Fragment data cleared - showing empty state")
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
            // Callback when a checkbox in the adapter changes
            filesViewModel.updateMeterCheckedStatus(
                meterStatus.serialNumber,
                isChecked,
                meterStatus.fromFile
            )

            // Show feedback to user
            val statusText = if (isChecked) "marked as scanned ✅" else "marked as not scanned ❌"
            Toast.makeText(context, "Meter ${meterStatus.number} $statusText", Toast.LENGTH_SHORT).show()
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
            Log.d("MeterCheckFragment", "Refresh button clicked")
            viewModel.clearSearch()
            searchEditText.text?.clear()

            // Show refresh feedback
            viewModel.setRefreshing(true)
            Toast.makeText(context, "Refreshing meter data...", Toast.LENGTH_SHORT).show()

            // Simulate refresh delay
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                viewModel.setRefreshing(false)
                Toast.makeText(context, "Meter data refreshed", Toast.LENGTH_SHORT).show()
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
            refreshButton.text = if (isRefreshing) "Refreshing..." else "Refresh"
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
        // Observe selected meters for processing (priority)
        filesViewModel.selectedMetersForProcessing.observe(viewLifecycleOwner) { selectedMeters ->
            Log.d("MeterCheckFragment", "Selected meters changed: ${selectedMeters.size}")

            // Only update if this is for MeterCheck or if no destination-specific data exists
            val meterCheckMeters = filesViewModel.getMetersByDestination(ProcessingDestination.METER_CHECK)

            if (meterCheckMeters.isNotEmpty()) {
                // Use MeterCheck specific data
                currentCompleteMeterList = meterCheckMeters
                viewModel.updateScreenTitle("MeterCheck - ${meterCheckMeters.size} meters")
            } else if (selectedMeters.isNotEmpty()) {
                // Fallback to selected meters
                currentCompleteMeterList = selectedMeters
                viewModel.updateScreenTitle("MeterCheck - ${selectedMeters.size} meters")
                Toast.makeText(
                    context,
                    "Loaded ${selectedMeters.size} meters for checking",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Clear data
                clearFragmentData()
                return@observe
            }

            updateStatisticsAndApplyFilter()
        }

        // Observe all meters to detect changes/deletions
        filesViewModel.meterStatusList.observe(viewLifecycleOwner) { allMeters ->
            Log.i("MeterCheckFragment", "All meters changed: ${allMeters?.size ?: 0}")

            // Check if our current data is still valid
            if (currentCompleteMeterList.isNotEmpty()) {
                val currentFileNames = currentCompleteMeterList.map { it.fromFile }.distinct()
                val stillValidMeters = allMeters?.filter { it.fromFile in currentFileNames } ?: emptyList()

                if (stillValidMeters.size != currentCompleteMeterList.size) {
                    // Some meters were removed, update our data
                    Log.d("MeterCheckFragment", "Detected meter removal, updating data")
                    loadDataForFragment()
                }
            } else {
                // Try to load data if we have none
                loadDataForFragment()
            }
        }

        // Observe file changes to detect deletions
        filesViewModel.fileItems.observe(viewLifecycleOwner) { fileItems ->
            val fileNames = fileItems.filter { it.isValid }.map { it.fileName }
            updateCurrentFilesDisplay(fileNames)

            // Check if files we depend on were deleted
            if (currentCompleteMeterList.isNotEmpty()) {
                val currentFileNames = currentCompleteMeterList.map { it.fromFile }.distinct()
                val filesStillExist = currentFileNames.all { fileName ->
                    fileNames.contains(fileName)
                }

                if (!filesStillExist) {
                    Log.d("MeterCheckFragment", "Source files were deleted, clearing data")
                    clearFragmentData()
                }
            }
        }
    }

    private fun updateCurrentFilesDisplay(fileNames: List<String>) {
        // Temporarily disable this to prevent any side effects
        // Only show files if we actually have MeterCheck data
        if (currentCompleteMeterList.isNotEmpty()) {
            val relevantFiles = currentCompleteMeterList.map { it.fromFile }.distinct()
            if (relevantFiles.isNotEmpty()) {
                currentFilesTextView.text = "MeterCheck data from: ${relevantFiles.joinToString(", ")}"
                currentFilesTextView.isVisible = true
            } else {
                currentFilesTextView.isVisible = false
            }
        } else {
            currentFilesTextView.text = "No MeterCheck data loaded"
            currentFilesTextView.isVisible = false
        }
    }

    private fun updateStatisticsAndApplyFilter() {
        Log.d("MeterCheckFragment", "📊 updateStatisticsAndApplyFilter called with ${currentCompleteMeterList.size} meters")
        updateStatistics(currentCompleteMeterList)
        applySearchFilter(viewModel.searchQuery.value ?: "")
    }

    private fun updateStatistics(meters: List<MeterStatus>) {
        Log.d("MeterCheckFragment", "📈 Updating statistics: ${meters.size} total meters")
        val total = meters.size
        val registered = meters.count { it.isChecked }
        val unregistered = total - registered

        viewModel.updateStatistics(total, registered, unregistered)
        Log.d("MeterCheckFragment", "   - Total: $total, Registered: $registered, Unregistered: $unregistered")
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

        Log.d("MeterCheckFragment", "🔍 Applied search filter '$query': ${currentCompleteMeterList.size} → ${currentFilteredList.size} meters")
        meterAdapter.submitList(currentFilteredList.toList()) // Ensure new list instance
        updateEmptyState(currentFilteredList, query)

        // Update file display based on current data
        updateCurrentFilesDisplay(emptyList()) // Pass empty to force recalculation
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
                !hasAnyMeters -> "No meters available\nProcess CSV files to see meters here"
                hasSearchQuery -> "No meters found\nTry adjusting your search query"
                else -> "No meter data available"
            }
        }
    }

    // Public method for QR scanner integration
    fun onMeterScanned(serialNumber: String) {
        val result = filesViewModel.updateMeterCheckedStatusBySerial(serialNumber)
        if (result.first) {
            val fileName = result.second
            Toast.makeText(
                context,
                "✅ Meter scanned successfully!\nSerial: $serialNumber\nFile: $fileName",
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
            .setTitle("Meter Not Found")
            .setMessage("Serial number '$serialNumber' was not found in the loaded meter data.\n\nThis could mean:\n• The meter is from a different batch\n• The serial number was misread\n• The meter data hasn't been loaded")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Show All Meters") { _, _ ->
                // Clear search to show all meters
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