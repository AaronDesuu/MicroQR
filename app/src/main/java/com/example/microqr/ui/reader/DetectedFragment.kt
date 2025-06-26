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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
                binding.serialNumberText.text = getString(R.string.serial_number_format, extractedSerialNumber)
                binding.detectedInfoText.text = getString(R.string.raw_qr_format, rawQrValue)
                binding.nextButton.text = getString(R.string.check_serial)
                binding.nextButton.visibility = View.VISIBLE
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    checkSerialNumberInTableAsync(extractedSerialNumber!!)
                }
            } else {
                // Parsing failed or rawQrValue was empty
                binding.serialNumberText.text = getString(R.string.error_parsing_qr)
                binding.detectedInfoText.text = getString(R.string.could_not_parse_serial, rawQrValue)
                binding.nextButton.visibility = View.GONE
                Log.w(TAG, "Could not parse serial number from raw QR value: $rawQrValue")
            }
        } else {
            Log.w(TAG, "No rawQrValue found in arguments.")
            binding.serialNumberText.text = getString(R.string.no_data_available)
            binding.detectedInfoText.text = getString(R.string.no_qr_data)
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

    private fun checkSerialNumberInTableAsync(serialNumberToCheck: String) {
        Log.d(TAG, "=== STARTING ASYNC SERIAL NUMBER CHECK ===")
        Log.d(TAG, "Checking serial number: '$serialNumberToCheck'")

        // Disable button to prevent double-clicks
        binding.nextButton.isEnabled = false
        binding.nextButton.text = getString(R.string.checking_serial)

        // 🔍 DEBUG: Check current data state
        val meterCheckCount = filesViewModel.meterCheckMeters.value?.size ?: 0
        val generalMeterCount = filesViewModel.meterStatusList.value?.size ?: 0

        Log.d(TAG, "📊 Data Status:")
        Log.d(TAG, "  - MeterCheck data: $meterCheckCount meters")
        Log.d(TAG, "  - General meter data: $generalMeterCount meters")

        // Use coroutine to handle async database operation
        lifecycleScope.launch {
            try {
                // ✅ NEW: Use the async method for proper database handling
                val (success, foundItemFile) = filesViewModel.updateMeterCheckedStatusBySerialAsync(serialNumberToCheck)

                Log.d(TAG, "📝 Async update result: success=$success, foundFile=$foundItemFile")

                if (success) {
                    // 🎉 SUCCESS CASE - Serial number was found and updated
                    Log.d(TAG, "✅ SUCCESS: Serial number found and processed!")

                    // Wait a bit for the LiveData to refresh
                    delay(1000)

                    // Check the updated status in LiveData
                    val updatedMeter = if (foundItemFile != null) {
                        filesViewModel.meterCheckMeters.value?.find {
                            it.serialNumber == serialNumberToCheck && it.fromFile == foundItemFile
                        } ?: filesViewModel.meterStatusList.value?.find {
                            it.serialNumber == serialNumberToCheck && it.fromFile == foundItemFile
                        }
                    } else {
                        filesViewModel.meterStatusList.value?.find { it.serialNumber == serialNumberToCheck }
                    }

                    Log.d(TAG, "🔍 After update - found meter: ${updatedMeter != null}, checked: ${updatedMeter?.isChecked}")

                    // Show success regardless of LiveData state since database operation succeeded
                    showSuccessResult(serialNumberToCheck, foundItemFile ?: getString(R.string.no_data_available), true)

                } else {
                    // ❌ FAILURE CASE - Serial number was NOT found
                    Log.w(TAG, "❌ FAILURE: Serial number NOT FOUND in database")
                    showNotFoundResult(serialNumberToCheck, meterCheckCount, generalMeterCount)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception during async check: ${e.message}", e)
                showErrorResult(serialNumberToCheck, e.message)
            }
        }
    }

    private fun showSuccessResult(serialNumber: String, fromFile: String, isChecked: Boolean) {
        Log.d(TAG, "🎉 Showing SUCCESS result for $serialNumber")

        val toastMessage = getString(R.string.meter_marked_as_checked, serialNumber, fromFile)
        val statusMessage = "\n\n" + getString(R.string.status_verified_checked, fromFile)

        Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_LONG).show()

        // Clear any "見つかりません" text and add success message
        val currentText = binding.detectedInfoText.text.toString()
        val cleanedText = currentText.replace("見つかりません", "").replace("Not Found", "")
        binding.detectedInfoText.text = cleanedText + statusMessage

        // ✅ SUCCESS: Navigate to MeterCheck to see the updated status
        binding.nextButton.text = getString(R.string.view_in_metercheck)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            findNavController().navigate(R.id.action_detectedFragment_to_meterCheckFragment)
        }
    }

    private fun showNotFoundResult(serialNumber: String, meterCheckCount: Int, generalMeterCount: Int) {
        Log.w(TAG, "❌ Showing NOT FOUND result for $serialNumber")

        val errorMessage = when {
            meterCheckCount > 0 -> getString(R.string.serial_not_found_in_metercheck, serialNumber, meterCheckCount)
            generalMeterCount > 0 -> getString(R.string.serial_not_found_process_first, serialNumber)
            else -> getString(R.string.serial_not_found_no_data, serialNumber)
        }

        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
        binding.detectedInfoText.text = binding.detectedInfoText.text.toString() + "\n\n" + getString(R.string.status_not_found)

        // ✅ HELPFUL: Add suggestion button based on the situation
        when {
            meterCheckCount == 0 && generalMeterCount > 0 -> {
                binding.nextButton.text = getString(R.string.go_to_files)
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().navigate(R.id.navigation_files)
                }
            }
            meterCheckCount == 0 && generalMeterCount == 0 -> {
                binding.nextButton.text = getString(R.string.upload_csv_files)
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().navigate(R.id.navigation_files)
                }
            }
            else -> {
                binding.nextButton.text = getString(R.string.scan_again_button)
                binding.nextButton.isEnabled = true
                binding.nextButton.setOnClickListener {
                    findNavController().popBackStack()
                }
            }
        }
    }

    private fun showErrorResult(serialNumber: String, errorMessage: String?) {
        Log.e(TAG, "💥 Showing ERROR result for $serialNumber: $errorMessage")

        val displayMessage = getString(R.string.failed_to_load_statistics, errorMessage ?: getString(R.string.no_data_available))
        Toast.makeText(requireContext(), displayMessage, Toast.LENGTH_LONG).show()
        binding.detectedInfoText.text = binding.detectedInfoText.text.toString() + "\n\n❌ " + getString(R.string.status_not_found)

        binding.nextButton.text = getString(R.string.scan_again_button)
        binding.nextButton.isEnabled = true
        binding.nextButton.setOnClickListener {
            findNavController().popBackStack()
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