package com.example.microqr.ui.files

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RadioGroup
import android.widget.RadioButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FilesFragment : Fragment() {

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var filesAdapter: FilesAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var noFilesContainer: LinearLayout
    private lateinit var uploadedFilesTitleTextView: TextView
    private lateinit var uploadButton: Button
    private lateinit var instructionsTextView: TextView

    // Dropdown UI elements
    private lateinit var dropdownHeader: LinearLayout
    private lateinit var dropdownIcon: ImageView
    private lateinit var dropdownContent: LinearLayout
    private var isDropdownExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                // Process the file first, then show destination selection
                showDestinationSelectionDialog(it)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_files, container, false)

        // Initialize views
        uploadButton = view.findViewById(R.id.button_upload_csv)
        recyclerView = view.findViewById(R.id.recyclerView_uploaded_files)
        noFilesContainer = view.findViewById(R.id.textView_no_files)
        uploadedFilesTitleTextView = view.findViewById(R.id.textView_uploaded_files_title)
        instructionsTextView = view.findViewById(R.id.textView_instructions)

        // Initialize dropdown views
        dropdownHeader = view.findViewById(R.id.dropdown_header)
        dropdownIcon = view.findViewById(R.id.dropdown_icon)
        dropdownContent = view.findViewById(R.id.dropdown_content)

        setupClickListeners()
        setupRecyclerView()
        setupDropdown()

        return view
    }

    private fun setupClickListeners() {
        uploadButton.setOnClickListener {
            openFilePicker()
        }
    }

    private fun setupDropdown() {
        // Set initial state
        dropdownContent.isVisible = isDropdownExpanded
        updateDropdownIcon()

        // Set up click listener for dropdown header with improved animation
        dropdownHeader.setOnClickListener {
            toggleDropdownWithAnimation()
        }

        // Set up the instructions content
        setupInstructions()
    }

    private fun toggleDropdownWithAnimation() {
        isDropdownExpanded = !isDropdownExpanded

        // Animate the dropdown content
        if (isDropdownExpanded) {
            // Expand animation
            dropdownContent.isVisible = true
            dropdownContent.alpha = 0f
            dropdownContent.animate()
                .alpha(1f)
                .setDuration(250)
                .start()
        } else {
            // Collapse animation
            dropdownContent.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    dropdownContent.isVisible = false
                }
                .start()
        }

        // Animate the dropdown icon
        updateDropdownIcon()
    }

    private fun updateDropdownIcon() {
        // Smooth rotation animation for the dropdown arrow
        val targetRotation = if (isDropdownExpanded) 180f else 0f
        dropdownIcon.animate()
            .rotation(targetRotation)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun setupRecyclerView() {
        filesAdapter = FilesAdapter(
            onDeleteClicked = { fileName ->
                showDeleteConfirmationDialog(fileName)
            },
            onProcessForMeterCheck = { fileName ->
                // Only allow processing if file is processable
                val fileItem = filesViewModel.fileItems.value?.find { it.fileName == fileName }
                if (fileItem?.isProcessable() == true) {
                    filesViewModel.processForMeterCheck(fileName)
                } else {
                    val errorMsg = fileItem?.validationError ?: getString(R.string.files_validation_no_data)
                    Toast.makeText(context, getString(R.string.files_processing_error) + ": $errorMsg", Toast.LENGTH_LONG).show()
                }
            },
            onProcessForMatch = { fileName ->
                // Only allow processing if file is processable
                val fileItem = filesViewModel.fileItems.value?.find { it.fileName == fileName }
                if (fileItem?.isProcessable() == true) {
                    filesViewModel.processForMatch(fileName)
                } else {
                    val errorMsg = fileItem?.validationError ?: getString(R.string.files_validation_no_data)
                    Toast.makeText(context, getString(R.string.files_processing_error) + ": $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        )
        recyclerView.adapter = filesAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupInstructions() {
        val instructions = getString(R.string.files_upload_instructions)
        instructionsTextView.text = instructions
    }

    private fun showDestinationSelectionDialog(uri: Uri) {
        // Create a LinearLayout with RadioButtons
        val linearLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val radioGroup = RadioGroup(requireContext())

        val meterCheckRadio = RadioButton(requireContext()).apply {
            text = getString(R.string.files_destination_meter_check)
            id = 1
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }

        val meterMatchRadio = RadioButton(requireContext()).apply {
            text = getString(R.string.files_destination_meter_match)
            id = 2
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }

        radioGroup.addView(meterCheckRadio)
        radioGroup.addView(meterMatchRadio)
        linearLayout.addView(radioGroup)

        var selectedDestination: ProcessingDestination? = null

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedDestination = when (checkedId) {
                1 -> ProcessingDestination.METER_CHECK
                2 -> ProcessingDestination.METER_MATCH
                else -> null
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.files_choose_destination))
            .setMessage(getString(R.string.files_destination_dialog_subtitle))
            .setView(linearLayout)
            .setPositiveButton(getString(R.string.files_select_destination)) { _, _ ->
                selectedDestination?.let { destination ->
                    Toast.makeText(
                        context,
                        getString(R.string.files_processing_file),
                        Toast.LENGTH_SHORT
                    ).show()

                    filesViewModel.processCsvFileWithDestination(
                        uri,
                        requireActivity().contentResolver,
                        destination
                    )

                    Handler(Looper.getMainLooper()).postDelayed({
                        val destinationName = when (destination) {
                            ProcessingDestination.METER_CHECK -> getString(R.string.files_destination_meter_check)
                            ProcessingDestination.METER_MATCH -> getString(R.string.files_destination_meter_match)
                        }
                        Toast.makeText(
                            context,
                            getString(R.string.files_processed_success, destinationName),
                            Toast.LENGTH_SHORT
                        ).show()
                    }, 1000)
                } ?: run {
                    Toast.makeText(
                        context,
                        getString(R.string.files_select_destination_first),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    context,
                    getString(R.string.files_upload_cancelled),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .create()

        dialog.show()

        // Initially disable the positive button
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.isEnabled = false

        // Enable button when selection is made
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedDestination = when (checkedId) {
                1 -> ProcessingDestination.METER_CHECK
                2 -> ProcessingDestination.METER_MATCH
                else -> null
            }
            positiveButton?.isEnabled = (selectedDestination != null)
        }
    }

    private fun showDeleteConfirmationDialog(fileName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_file))
            .setMessage(getString(R.string.delete_file_confirmation))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                filesViewModel.deleteFile(fileName)
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe toast messages
        filesViewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                filesViewModel.clearToastMessage()
            }
        }

        // Observe file items
        filesViewModel.fileItems.observe(viewLifecycleOwner) { fileItems ->
            filesAdapter.submitList(fileItems)
            if (fileItems.isNullOrEmpty()) {
                noFilesContainer.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                uploadedFilesTitleTextView.visibility = View.GONE
            } else {
                noFilesContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                uploadedFilesTitleTextView.visibility = View.VISIBLE
            }
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(FileConstants.SUPPORTED_MIME_TYPES)
    }

    companion object {
        fun newInstance() = FilesFragment()
    }
}