package com.example.microqr.ui.files

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton // Added
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
import com.example.microqr.databinding.FragmentFilesBinding // Import ViewBinding

class FilesFragment : Fragment() {

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>

    // ViewBinding
    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                showDestinationSelectionDialog(it)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonUploadCsv.setOnClickListener {
            openFilePicker()
        }

        setupRecyclerView()
        setupInstructionsToggle() // Call the new setup method
        setInstructionsText() // Set the actual instruction text

        // Observe toast messages
        filesViewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                filesViewModel.clearToastMessage()
            }
        }

        // Observe file items
        filesViewModel.fileItems.observe(viewLifecycleOwner) { fileItems ->
            (binding.recyclerViewUploadedFiles.adapter as FilesAdapter).submitList(fileItems)
            if (fileItems.isNullOrEmpty()) {
                binding.textViewNoFiles.visibility = View.VISIBLE
                binding.recyclerViewUploadedFiles.visibility = View.GONE
                binding.textViewUploadedFilesTitle.visibility = View.GONE
            } else {
                binding.textViewNoFiles.visibility = View.GONE
                binding.recyclerViewUploadedFiles.visibility = View.VISIBLE
                binding.textViewUploadedFilesTitle.visibility = View.VISIBLE
            }
        }
    }

    private fun setupRecyclerView() {
        val filesAdapter = FilesAdapter( // filesAdapter is local now, accessed via binding.recyclerViewUploadedFiles.adapter
            onDeleteClicked = { fileName ->
                showDeleteConfirmationDialog(fileName)
            },
            onProcessForMeterCheck = { fileName ->
                val fileItem = filesViewModel.fileItems.value?.find { it.fileName == fileName }
                if (fileItem?.isProcessable() == true) {
                    filesViewModel.processForMeterCheck(fileName)
                } else {
                    val errorMsg = fileItem?.validationError ?: "File not found"
                    Toast.makeText(context, "Cannot process file: $errorMsg", Toast.LENGTH_LONG).show()
                }
            },
            onProcessForMatch = { fileName ->
                val fileItem = filesViewModel.fileItems.value?.find { it.fileName == fileName }
                if (fileItem?.isProcessable() == true) {
                    filesViewModel.processForMatch(fileName)
                } else {
                    val errorMsg = fileItem?.validationError ?: "File not found"
                    Toast.makeText(context, "Cannot process file: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        )
        binding.recyclerViewUploadedFiles.adapter = filesAdapter
        binding.recyclerViewUploadedFiles.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupInstructionsToggle() {
        binding.buttonToggleInstructions.setOnClickListener {
            if (binding.textViewInstructions.visibility == View.VISIBLE) {
                binding.textViewInstructions.visibility = View.GONE
                binding.buttonToggleInstructions.setImageResource(R.drawable.ic_arrow_drop_down) // Ensure you have this drawable
            } else {
                binding.textViewInstructions.visibility = View.VISIBLE
                binding.buttonToggleInstructions.setImageResource(R.drawable.ic_arrow_drop_up) // Ensure you have this drawable
            }
        }
        // Ensure initial state is collapsed
        binding.textViewInstructions.visibility = View.GONE
        binding.buttonToggleInstructions.setImageResource(R.drawable.ic_arrow_drop_down)
    }

    private fun setInstructionsText() {
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

        binding.textViewInstructions.text = instructions
    }

    private fun showDestinationSelectionDialog(uri: Uri) {
        // Assuming DialogHelper is correctly implemented
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

    private fun openFilePicker() {
        filePickerLauncher.launch(FileConstants.SUPPORTED_MIME_TYPES)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important for ViewBinding to avoid memory leaks
    }

    companion object {
        fun newInstance() = FilesFragment() // This is fine if you don't pass arguments
    }
}