package com.example.microqr.ui.files

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.microqr.R

class FilesFragment : Fragment() {

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var filesAdapter: FilesAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var noFilesContainer: LinearLayout
    private lateinit var uploadedFilesTitleTextView: TextView
    private lateinit var uploadButton: Button
    private lateinit var instructionsTextView: TextView

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

        uploadButton = view.findViewById(R.id.button_upload_csv)
        recyclerView = view.findViewById(R.id.recyclerView_uploaded_files)
        noFilesContainer = view.findViewById(R.id.textView_no_files)
        uploadedFilesTitleTextView = view.findViewById(R.id.textView_uploaded_files_title)
        instructionsTextView = view.findViewById(R.id.textView_instructions)

        uploadButton.setOnClickListener {
            openFilePicker()
        }

        setupRecyclerView()
        setupInstructions()

        return view
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
                    val errorMsg = fileItem?.validationError ?: "File not found"
                    Toast.makeText(context, "Cannot process file: $errorMsg", Toast.LENGTH_LONG).show()
                }
            },
            onProcessForMatch = { fileName ->
                // Only allow processing if file is processable
                val fileItem = filesViewModel.fileItems.value?.find { it.fileName == fileName }
                if (fileItem?.isProcessable() == true) {
                    filesViewModel.processForMatch(fileName)
                } else {
                    val errorMsg = fileItem?.validationError ?: "File not found"
                    Toast.makeText(context, "Cannot process file: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        )
        recyclerView.adapter = filesAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupInstructions() {
        val instructions = """
            📋 Instructions:
            
            1. Upload a CSV file with the following required columns:
               • Number - Meter identification number
               • SerialNumber - Unique serial number
               • Place - Location/address of the meter
               • Registered - Boolean (true/false, yes/no, 1/0)
            
            2. After upload, choose processing destination:
               • MeterCheck - For meter verification workflow
               • Match - For matching operations
               
            3. Only valid CSV files can be processed
            
            Supported file types: CSV, TXT
            Maximum file size: 10MB
        """.trimIndent()

        instructionsTextView.text = instructions
    }

    private fun showDestinationSelectionDialog(uri: Uri) {
        DialogHelper.showDestinationDialog(
            context = requireContext(),
            onMeterCheck = {
                filesViewModel.processCsvFileWithDestination(
                    uri,
                    requireActivity().contentResolver,
                    ProcessingDestination.METER_CHECK
                )
            },
            onMeterMatch = {
                filesViewModel.processCsvFileWithDestination(
                    uri,
                    requireActivity().contentResolver,
                    ProcessingDestination.METER_MATCH
                )
            },
            onCancel = {
                Toast.makeText(context, "Upload cancelled", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showDeleteConfirmationDialog(fileName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete '$fileName'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                filesViewModel.deleteFile(fileName)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
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