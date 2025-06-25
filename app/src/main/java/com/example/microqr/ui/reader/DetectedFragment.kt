package com.example.microqr.ui.reader

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.microqr.R
import com.example.microqr.databinding.FragmentDetectedBinding
import com.example.microqr.ui.files.FilesViewModel

class DetectedFragment : Fragment() {

    private var _binding: FragmentDetectedBinding? = null
    private val binding get() = _binding!!

    // Get the shared FilesViewModel instance
    private val filesViewModel: FilesViewModel by activityViewModels()

    companion object {
        private const val TAG = "DetectedFragment"
    }

    // Variables to store the parsed details from the QR code
    private var extractedSerialNumber: String? = null
    private var extractedFromFile: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetectedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rawQrValue = arguments?.getString("rawQrValue")
        Log.d(TAG, "Received arguments: ${argumentsToString(arguments)}")
        Log.i(TAG, "Raw QR Value received: $rawQrValue")
        binding.nextButton.visibility = View.GONE // Start as hidden or disabled

        if (rawQrValue != null) {
            parseRawQrValue(rawQrValue) // Call the simplified parsing function

            if (extractedSerialNumber != null) {
                binding.serialNumberText.text = "S/N: $extractedSerialNumber"
                binding.detectedInfoText.text = "(Raw: $rawQrValue)"
                binding.nextButton.text = getString(R.string.check_serial)
                binding.nextButton.visibility = View.VISIBLE
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    checkSerialNumberInTable(extractedSerialNumber!!)
                }
            } else {
                // Parsing failed or rawQrValue was empty
                binding.serialNumberText.text = "Error Parsing QR"
                binding.detectedInfoText.text = "Could not parse valid Serial Number from QR data.\n\nRaw: $rawQrValue"
                binding.nextButton.visibility = View.GONE
                Log.w(TAG, "Could not parse serial number from raw QR value: $rawQrValue")
            }
        } else {
            Log.w(TAG, "No rawQrValue found in arguments.")
            binding.serialNumberText.text = "N/A"
            binding.detectedInfoText.text = "No QR Code data was received."
            binding.nextButton.visibility = View.GONE
        }

        binding.backButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    /**
     * Parses the raw QR value to extract the serial number and the 'fromFile' identifier.
     * This function needs to be implemented based on YOUR QR code's data format.
     * It should populate `this.extractedSerialNumber` and `this.extractedFromFile`.
     */
    private fun parseRawQrValue(rawValue: String) {
        // Assume the rawValue from the QR code IS the serial number
        this.extractedSerialNumber = rawValue.trim()
        // this.extractedFromFile = null; // No longer explicitly setting/needing this for the check

        if (this.extractedSerialNumber != null && !this.extractedSerialNumber!!.isEmpty()) {
            Log.i(TAG, "Parsed S/N from QR: ${this.extractedSerialNumber}")
        } else {
            Log.w(TAG, "Raw QR value was empty or null: '$rawValue'")
            this.extractedSerialNumber = null // Ensure it's null if empty
        }
    }

    private fun checkSerialNumberInTable(serialNumberToCheck: String) {
        Log.d(TAG, "Checking serial number: $serialNumberToCheck")

        // ✅ FIXED: Use the new method that works with MeterCheck specific data
        val (success, foundItemFile) = filesViewModel.updateMeterCheckedStatusBySerial(serialNumberToCheck)

        if (success) {
            val toastMessage: String
            val statusMessage: String

            // ✅ UPDATED: Check against the correct data source that MeterCheck uses
            if (foundItemFile != null) {
                // Check the actual status from the MeterCheck specific data
                val isActuallyChecked = filesViewModel.meterCheckMeters.value
                    ?.find { it.serialNumber == serialNumberToCheck && it.fromFile == foundItemFile }
                    ?.isChecked ?: false

                // ✅ FALLBACK: If not found in meterCheckMeters, check the general meterStatusList
                val isCheckedInGeneral = if (!isActuallyChecked) {
                    filesViewModel.meterStatusList.value
                        ?.find { it.serialNumber == serialNumberToCheck && it.fromFile == foundItemFile }
                        ?.isChecked ?: false
                } else {
                    isActuallyChecked
                }

                if (isCheckedInGeneral) {
                    toastMessage = "'$serialNumberToCheck' (from $foundItemFile) is now marked as CHECKED!"
                    statusMessage = "\n\nStatus: VERIFIED CHECKED (from $foundItemFile)"
                } else {
                    // This means 'success' was true (item found), but it wasn't updated to 'isChecked = true'.
                    // This implies it was already checked.
                    toastMessage = "'$serialNumberToCheck' (from $foundItemFile) was ALREADY CHECKED."
                    statusMessage = "\n\nStatus: VERIFIED ALREADY CHECKED (from $foundItemFile)"
                }
            } else {
                // This case should ideally not happen if success is true,
                // but as a fallback if foundItemFile wasn't returned by the VM method.
                toastMessage = "'$serialNumberToCheck' status updated (file not specified)."
                statusMessage = "\n\nStatus: CHECKED (file not specified)"
            }

            Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_LONG).show()
            binding.detectedInfoText.append(statusMessage)

            binding.nextButton.text = getString(R.string.serial_checked)
            binding.nextButton.isEnabled = true // Ensure it's enabled if it wasn't
            binding.nextButton.setOnClickListener {
                findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
            }

        } else {
            // ✅ IMPROVED: Provide more helpful error message based on available data sources
            val meterCheckCount = filesViewModel.meterCheckMeters.value?.size ?: 0
            val generalMeterCount = filesViewModel.meterStatusList.value?.size ?: 0

            val errorMessage = when {
                meterCheckCount > 0 -> "'$serialNumberToCheck' NOT FOUND in the loaded MeterCheck data ($meterCheckCount meters)."
                generalMeterCount > 0 -> "'$serialNumberToCheck' NOT FOUND in any meter data. Try processing your files for MeterCheck first."
                else -> "'$serialNumberToCheck' NOT FOUND. No meter data is currently loaded."
            }

            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
            binding.detectedInfoText.append("\n\nStatus: NOT FOUND")

            // ✅ HELPFUL: Add suggestion button if no MeterCheck data is loaded
            if (meterCheckCount == 0 && generalMeterCount > 0) {
                binding.nextButton.text = "Go to Files"
                binding.nextButton.visibility = View.VISIBLE
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().navigate(R.id.navigation_files)
                }
            }
        }
    }

    private fun argumentsToString(args: Bundle?): String {
        if (args == null) return "null"
        val sb = StringBuilder("Bundle[")
        for (key in args.keySet()) {
            sb.append("\n $key = ${args[key]};")
        }
        sb.append("\n]")
        return sb.toString()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}