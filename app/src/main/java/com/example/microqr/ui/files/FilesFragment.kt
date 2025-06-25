package com.example.microqr.ui.files

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
// import android.widget.Button // No longer needed directly if using MaterialButton from XML
// import android.widget.ImageButton // No longer needed directly if using ImageButton from XML
// import android.widget.LinearLayout // No longer needed directly
// import android.widget.TextView // No longer needed directly
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
// import androidx.navigation.fragment.findNavController // Not used in this snippet
import androidx.recyclerview.widget.LinearLayoutManager
// import androidx.recyclerview.widget.RecyclerView // No longer needed directly
import com.example.microqr.R
import com.example.microqr.databinding.FragmentFilesBinding
import androidx.core.view.isVisible
import androidx.transition.TransitionManager // Import for animations
import androidx.transition.Fade // Import for Fade transition
import androidx.transition.ChangeBounds // Import for ChangeBounds transition
import android.view.animation.AccelerateDecelerateInterpolator // For smoother animation timing

class FilesFragment : Fragment() {

    private val filesViewModel: FilesViewModel by activityViewModels()
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>

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
        setupInstructionsToggle()
        setInstructionsText()

        filesViewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                filesViewModel.clearToastMessage()
            }
        }

        filesViewModel.fileItems.observe(viewLifecycleOwner) { fileItems ->
            (binding.recyclerViewUploadedFiles.adapter as FilesAdapter).submitList(fileItems)
            binding.textViewNoFiles.isVisible = fileItems.isNullOrEmpty()
            binding.recyclerViewUploadedFiles.isVisible = !fileItems.isNullOrEmpty()
            binding.textViewUploadedFilesTitle.isVisible = !fileItems.isNullOrEmpty()
        }
    }

    private fun setupRecyclerView() {
        val filesAdapter = FilesAdapter(
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
        // Ensure initial state is collapsed without animation
        binding.textViewInstructions.visibility = View.GONE
        binding.buttonToggleInstructions.setImageResource(R.drawable.ic_arrow_drop_down)

        binding.buttonToggleInstructions.setOnClickListener {
            // Define the scene root for the transition. This is usually the parent layout
            // that contains the views being animated.
            val sceneRoot = binding.root.parent as? ViewGroup ?: binding.root as ViewGroup

            // Create a transition set for a smoother effect
            val transition = androidx.transition.TransitionSet()
                .addTransition(Fade()) // For fading in/out
                .addTransition(ChangeBounds()) // For animating layout changes (size, position)
                .setInterpolator(AccelerateDecelerateInterpolator()) // Smoother timing
                .setDuration(300) // Animation duration in milliseconds

            TransitionManager.beginDelayedTransition(sceneRoot, transition)

            if (binding.textViewInstructions.isVisible) {
                binding.textViewInstructions.visibility = View.GONE
                binding.buttonToggleInstructions.setImageResource(R.drawable.ic_arrow_drop_down)
            } else {
                binding.textViewInstructions.visibility = View.VISIBLE
                binding.buttonToggleInstructions.setImageResource(R.drawable.ic_arrow_drop_up)
            }
        }
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
        _binding = null
    }

    companion object {
        fun newInstance() = FilesFragment()
    }
}