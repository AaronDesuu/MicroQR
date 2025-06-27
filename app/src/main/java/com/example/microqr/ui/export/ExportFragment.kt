package com.example.microqr.ui.export

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.microqr.R
import com.example.microqr.databinding.FragmentExportBinding
import com.example.microqr.ui.files.ProcessingDestination
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExportFragment : Fragment() {

    private var _binding: FragmentExportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExportViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startExport()
        } else {
            showToast(getString(R.string.export_error_no_permission))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        observeViewModel()
        setupFilenameDefault()
    }

    private fun setupUI() {
        setupSpinners()
        setupClickListeners()
        setupDataSourceRadioButtons()
    }

    private fun setupSpinners() {
        // Registration Status Spinner
        val registrationOptions = arrayOf(
            getString(R.string.export_filter_all_status),
            getString(R.string.export_filter_registered_only),
            getString(R.string.export_filter_unregistered_only)
        )
        binding.spinnerRegistrationStatus.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            registrationOptions
        )

        // Check Status Spinner
        val checkOptions = arrayOf(
            getString(R.string.export_filter_all_check_status),
            getString(R.string.export_filter_checked_only),
            getString(R.string.export_filter_unchecked_only)
        )
        binding.spinnerCheckStatus.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            checkOptions
        )
    }

    private fun setupClickListeners() {
        binding.btnSelectFiles.setOnClickListener {
            showFileSelectionDialog()
        }

        binding.btnSelectPlaces.setOnClickListener {
            showPlaceSelectionDialog()
        }

        binding.btnClearFilters.setOnClickListener {
            clearAllFilters()
        }

        binding.btnPreview.setOnClickListener {
            showPreviewDialog()
        }

        binding.btnExport.setOnClickListener {
            checkPermissionAndExport()
        }
    }

    private fun setupDataSourceRadioButtons() {
        binding.rgDataSource.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbAllData -> {
                    viewModel.setDataSource(ExportDataSource.ALL)
                    binding.layoutCheckStatus.visibility = View.VISIBLE
                }
                R.id.rbMeterCheck -> {
                    viewModel.setDataSource(ExportDataSource.METER_CHECK)
                    binding.layoutCheckStatus.visibility = View.VISIBLE
                }
                R.id.rbMeterMatch -> {
                    viewModel.setDataSource(ExportDataSource.METER_MATCH)
                    binding.layoutCheckStatus.visibility = View.GONE
                }
            }
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            updateSummary(state)
            updateFilterButtons(state)
        }

        viewModel.exportProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressExport.visibility = if (progress.isInProgress) View.VISIBLE else View.GONE
            binding.tvProgressMessage.visibility = if (progress.isInProgress) View.VISIBLE else View.GONE
            binding.tvProgressMessage.text = progress.message

            if (!progress.isInProgress && progress.isIndeterminate) {
                binding.progressExport.isIndeterminate = false
            } else {
                binding.progressExport.progress = progress.progress
            }
        }

        viewModel.exportResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is ExportResult.Success -> {
                    showToast(getString(R.string.export_success))
                    showExportSuccessDialog(result.filePath)
                }
                is ExportResult.Error -> {
                    showToast(getString(R.string.export_error_generic, result.message))
                }
                is ExportResult.NoData -> {
                    showToast(getString(R.string.export_error_no_data_selected))
                }
            }
        }
    }

    private fun setupFilenameDefault() {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        binding.etFilename.setText(getString(R.string.export_filename_default, timestamp))
    }

    private fun showFileSelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val availableFiles = viewModel.getAvailableFiles()
            if (availableFiles.isEmpty()) {
                showToast(getString(R.string.export_filter_no_files_selected))
                return@launch
            }

            val selectedFiles = viewModel.getSelectedFiles().toMutableSet()
            val fileNames = availableFiles.toTypedArray()
            val checkedItems = BooleanArray(fileNames.size) { index ->
                fileNames[index] in selectedFiles
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_select_files))
                .setMultiChoiceItems(fileNames, checkedItems) { _, which, isChecked ->
                    if (isChecked) {
                        selectedFiles.add(fileNames[which])
                    } else {
                        selectedFiles.remove(fileNames[which])
                    }
                }
                .setPositiveButton(getString(R.string.btn_select_all)) { _, _ ->
                    selectedFiles.addAll(availableFiles)
                    viewModel.setSelectedFiles(selectedFiles)
                }
                .setNegativeButton(getString(R.string.btn_deselect_all)) { _, _ ->
                    selectedFiles.clear()
                    viewModel.setSelectedFiles(selectedFiles)
                }
                .setNeutralButton(getString(android.R.string.ok)) { _, _ ->
                    viewModel.setSelectedFiles(selectedFiles)
                }
                .show()
        }
    }

    private fun showPlaceSelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val availablePlaces = viewModel.getAvailablePlaces()
            if (availablePlaces.isEmpty()) {
                showToast(getString(R.string.export_filter_no_places_selected))
                return@launch
            }

            val selectedPlaces = viewModel.getSelectedPlaces().toMutableSet()
            val placeNames = availablePlaces.toTypedArray()
            val checkedItems = BooleanArray(placeNames.size) { index ->
                placeNames[index] in selectedPlaces
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_select_places))
                .setMultiChoiceItems(placeNames, checkedItems) { _, which, isChecked ->
                    if (isChecked) {
                        selectedPlaces.add(placeNames[which])
                    } else {
                        selectedPlaces.remove(placeNames[which])
                    }
                }
                .setPositiveButton(getString(R.string.btn_select_all)) { _, _ ->
                    selectedPlaces.addAll(availablePlaces)
                    viewModel.setSelectedPlaces(selectedPlaces)
                }
                .setNegativeButton(getString(R.string.btn_deselect_all)) { _, _ ->
                    selectedPlaces.clear()
                    viewModel.setSelectedPlaces(selectedPlaces)
                }
                .setNeutralButton(getString(android.R.string.ok)) { _, _ ->
                    viewModel.setSelectedPlaces(selectedPlaces)
                }
                .show()
        }
    }

    private fun clearAllFilters() {
        binding.rbAllData.isChecked = true
        binding.spinnerRegistrationStatus.setSelection(0)
        binding.spinnerCheckStatus.setSelection(0)
        viewModel.clearAllFilters()
        updateFilterButtons(viewModel.uiState.value!!)
    }

    private fun showPreviewDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val filteredData = viewModel.getFilteredData()
            if (filteredData.isEmpty()) {
                showToast(getString(R.string.export_error_no_data_selected))
                return@launch
            }

            val previewText = buildString {
                append("${getString(R.string.csv_header_number)},")
                append("${getString(R.string.csv_header_serial_number)},")
                append("${getString(R.string.csv_header_place)},")
                append("${getString(R.string.csv_header_registered)},")
                append("${getString(R.string.csv_header_checked)},")
                append("${getString(R.string.csv_header_source_file)}\n")

                filteredData.take(5).forEach { meter ->
                    append("${meter.number},")
                    append("${meter.serialNumber},")
                    append("\"${meter.place}\",")
                    append("${meter.registered},")
                    append("${meter.isChecked},")
                    append("${meter.fromFile}\n")
                }

                if (filteredData.size > 5) {
                    append("... and ${filteredData.size - 5} more records")
                }
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.export_preview))
                .setMessage(previewText)
                .setPositiveButton(getString(android.R.string.ok), null)
                .show()
        }
    }

    private fun checkPermissionAndExport() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                startExport()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.export_permission_required))
                    .setMessage(getString(R.string.export_permission_required))
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun startExport() {
        val filename = binding.etFilename.text.toString().trim()
        if (filename.isEmpty()) {
            showToast(getString(R.string.export_error_invalid_filename))
            return
        }

        val includeTimestamp = binding.cbIncludeTimestamp.isChecked
        val registrationFilter = getRegistrationFilter()
        val checkFilter = getCheckFilter()

        viewModel.exportToCsv(
            filename = filename,
            includeTimestamp = includeTimestamp,
            registrationFilter = registrationFilter,
            checkFilter = checkFilter
        )
    }

    private fun getRegistrationFilter(): RegistrationFilter {
        return when (binding.spinnerRegistrationStatus.selectedItemPosition) {
            1 -> RegistrationFilter.REGISTERED_ONLY
            2 -> RegistrationFilter.UNREGISTERED_ONLY
            else -> RegistrationFilter.ALL
        }
    }

    private fun getCheckFilter(): CheckFilter {
        return when (binding.spinnerCheckStatus.selectedItemPosition) {
            1 -> CheckFilter.CHECKED_ONLY
            2 -> CheckFilter.UNCHECKED_ONLY
            else -> CheckFilter.ALL
        }
    }

    private fun updateSummary(state: ExportUiState) {
        binding.tvTotalRecords.text = getString(R.string.export_total_records, state.totalRecords)
        binding.tvFilteredRecords.text = getString(R.string.export_filtered_records, state.filteredRecords)
        binding.tvEstimatedSize.text = getString(R.string.export_file_size_estimate, state.estimatedSize)
    }

    private fun updateFilterButtons(state: ExportUiState) {
        // Update file selection button
        binding.btnSelectFiles.text = if (state.selectedFiles.isEmpty()) {
            getString(R.string.export_filter_all_files)
        } else {
            getString(R.string.export_filter_selected_files, state.selectedFiles.size)
        }

        // Update place selection button
        binding.btnSelectPlaces.text = if (state.selectedPlaces.isEmpty()) {
            getString(R.string.export_filter_all_places)
        } else {
            getString(R.string.export_filter_selected_places, state.selectedPlaces.size)
        }
    }

    private fun showExportSuccessDialog(filePath: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.export_success))
            .setMessage(getString(R.string.export_saved_to, filePath))
            .setPositiveButton(getString(R.string.export_share_title)) { _, _ ->
                shareFile(filePath)
            }
            .setNegativeButton(getString(R.string.export_open_with)) { _, _ ->
                openFile(filePath)
            }
            .setNeutralButton(getString(android.R.string.ok), null)
            .show()
    }

    private fun shareFile(filePath: String) {
        try {
            val file = File(filePath)
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "text/csv"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_share_title)))
        } catch (e: Exception) {
            showToast(getString(R.string.export_failed))
        }
    }

    private fun openFile(filePath: String) {
        try {
            val file = File(filePath)
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val openIntent = Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "text/csv")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(openIntent, getString(R.string.export_open_with)))
        } catch (e: Exception) {
            showToast(getString(R.string.export_failed))
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}