package com.example.microqr.ui.metercheck

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.microqr.R
import com.example.microqr.ui.files.FilesViewModel
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MeterInfoDialogFragment : DialogFragment() {

    private var meter: MeterStatus? = null
    private var onEditClick: ((MeterStatus) -> Unit)? = null
    private var onScanClick: ((MeterStatus) -> Unit)? = null
    private var onDeleteClick: ((MeterStatus) -> Unit)? = null

    // Add FilesViewModel reference for delete functionality
    private val filesViewModel: FilesViewModel by activityViewModels()

    companion object {
        private const val TAG = "MeterInfoDialogFragment"
        private const val ARG_METER_SERIAL = "meter_serial"
        private const val ARG_METER_NUMBER = "meter_number"
        private const val ARG_METER_LOCATION = "meter_location"
        private const val ARG_METER_SOURCE = "meter_source"
        private const val ARG_METER_REGISTERED = "meter_registered"
        private const val ARG_METER_CHECKED = "meter_checked"

        fun newInstance(
            meter: MeterStatus,
            onEditClick: (MeterStatus) -> Unit,
            onScanClick: (MeterStatus) -> Unit,
            onDeleteClick: (MeterStatus) -> Unit
        ): MeterInfoDialogFragment {
            val fragment = MeterInfoDialogFragment()
            val args = Bundle().apply {
                putString(ARG_METER_SERIAL, meter.serialNumber)
                putString(ARG_METER_NUMBER, meter.number)
                putString(ARG_METER_LOCATION, meter.place)
                putString(ARG_METER_SOURCE, meter.fromFile)
                putBoolean(ARG_METER_REGISTERED, meter.registered)
                putBoolean(ARG_METER_CHECKED, meter.isChecked)
            }
            fragment.arguments = args
            fragment.onEditClick = onEditClick
            fragment.onScanClick = onScanClick
            fragment.onDeleteClick = onDeleteClick
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_meter_info_check, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Reconstruct meter from arguments
        arguments?.let { args ->
            meter = MeterStatus(
                serialNumber = args.getString(ARG_METER_SERIAL, ""),
                number = args.getString(ARG_METER_NUMBER, ""),
                place = args.getString(ARG_METER_LOCATION, ""),
                fromFile = args.getString(ARG_METER_SOURCE, ""),
                registered = args.getBoolean(ARG_METER_REGISTERED, false),
                isChecked = args.getBoolean(ARG_METER_CHECKED, false)
            )
        }

        setupViews(view)
        setupClickListeners(view)
    }

    private fun setupViews(view: View) {
        val currentMeter = meter ?: return

        // Setup meter number in the badge (from layout: tvDialogMeterNumber)
        view.findViewById<TextView>(R.id.tvDialogMeterNumber)?.text = formatMeterNumber(currentMeter.number)

        // Setup serial number (from layout: tvDialogSerialNumber)
        view.findViewById<TextView>(R.id.tvDialogSerialNumber)?.text = currentMeter.serialNumber

        // Setup location (from layout: tvDialogLocation)
        val tvLocation = view.findViewById<TextView>(R.id.tvDialogLocation)
        if (isLocationValid(currentMeter.place)) {
            tvLocation?.text = currentMeter.place
            tvLocation?.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary_text_color))
        } else {
            tvLocation?.text = getString(R.string.location_unknown)
            tvLocation?.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        }

        // Setup source file (from layout: tvDialogSourceFile)
        view.findViewById<TextView>(R.id.tvDialogSourceFile)?.text = getShortFileName(currentMeter.fromFile)

        // Setup scan status (from layout: tvDialogScanStatus)
        val tvScanStatus = view.findViewById<TextView>(R.id.tvDialogScanStatus)
        if (currentMeter.isChecked) {
            tvScanStatus?.text = getString(R.string.status_scanned)
            tvScanStatus?.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            tvScanStatus?.text = getString(R.string.status_not_scanned)
            tvScanStatus?.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning_orange))
        }

        // Setup status text (from layout: tvDialogStatus)
        val tvStatus = view.findViewById<TextView>(R.id.tvDialogStatus)
        if (currentMeter.registered) {
            tvStatus?.text = getString(R.string.status_registered)
            tvStatus?.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
        } else {
            tvStatus?.text = getString(R.string.status_not_registered)
            tvStatus?.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
        }
    }

    private fun setupClickListeners(view: View) {
        val currentMeter = meter ?: return

        val btnEditVariables = view.findViewById<MaterialButton>(R.id.btnEditVariables)
        val btnScanMeter = view.findViewById<MaterialButton>(R.id.btnScanMeter)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)
        val btnDeleteMeter = view.findViewById<MaterialButton>(R.id.btnDeleteMeter)

        btnEditVariables?.setOnClickListener {
            onEditClick?.invoke(currentMeter)
            dismiss()
        }

        btnScanMeter?.setOnClickListener {
            onScanClick?.invoke(currentMeter)
            dismiss()
        }

        btnDeleteMeter?.setOnClickListener {
            showDeleteMeterConfirmation(currentMeter)
        }

        btnClose?.setOnClickListener {
            dismiss()
        }

        // Set content descriptions for accessibility
        btnEditVariables?.contentDescription = getString(R.string.cd_edit_meter)
        btnScanMeter?.contentDescription = getString(R.string.cd_scan_meter)
        btnClose?.contentDescription = getString(R.string.cd_close_dialog)
        btnDeleteMeter?.contentDescription = getString(R.string.cd_delete_meter)
    }

    private fun showDeleteMeterConfirmation(meter: MeterStatus) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_meter_title))
            .setMessage(
                getString(
                    R.string.confirm_delete_meter_message,
                    meter.number,
                    meter.serialNumber
                )
            )
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.delete_meter)) { _, _ ->
                deleteMeter(meter)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteMeter(meter: MeterStatus) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get the repository from FilesViewModel
                val repository = filesViewModel.getMeterRepository()

                // Delete the meter using the repository method
                repository.deleteMeter(meter.serialNumber, meter.fromFile)

                // Show success message
                Toast.makeText(
                    requireContext(),
                    getString(R.string.meter_deleted_successfully, meter.number),
                    Toast.LENGTH_SHORT
                ).show()

                // Close the dialog - the LiveData will automatically update the UI
                dismiss()

            } catch (e: Exception) {
                // Show error message
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_deleting_meter, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Make dialog fill most of the screen width
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun formatMeterNumber(number: String): String {
        return when {
            number.length <= 3 -> number
            number.startsWith("M-") || number.startsWith("m-") -> number.substring(2).take(3)
            number.length > 3 -> number.take(3)
            else -> number
        }
    }

    private fun isLocationValid(location: String): Boolean {
        return location.isNotEmpty() &&
                location.lowercase() != "unknown" &&
                location.lowercase() != "n/a" &&
                !location.contains("default", ignoreCase = true)
    }

    private fun isMeterNumberValid(number: String): Boolean {
        return number.isNotEmpty() &&
                number != "0" &&
                !number.contains("default", ignoreCase = true)
    }

    private fun getShortFileName(fileName: String): String {
        return if (fileName.length > 25) {
            fileName.take(22) + "..."
        } else {
            fileName
        }
    }
}