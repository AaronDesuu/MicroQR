package com.example.microqr.ui.metercheck

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.example.microqr.R
import com.example.microqr.ui.files.MeterStatus
import com.google.android.material.button.MaterialButton

class MeterInfoDialogFragment : DialogFragment() {

    private var meter: MeterStatus? = null
    private var onEditClick: ((MeterStatus) -> Unit)? = null
    private var onScanClick: ((MeterStatus) -> Unit)? = null
    private var onDeleteClick: ((MeterStatus) -> Unit)? = null

    companion object {
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
        return inflater.inflate(R.layout.dialog_meter_info, container, false)
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

        // Header information
        val tvDialogMeterNumber = view.findViewById<TextView>(R.id.tvDialogMeterNumber)
        val tvDialogStatus = view.findViewById<TextView>(R.id.tvDialogStatus)

        // Detail information
        val tvDialogSerialNumber = view.findViewById<TextView>(R.id.tvDialogSerialNumber)
        val tvDialogLocation = view.findViewById<TextView>(R.id.tvDialogLocation)
        val tvDialogSourceFile = view.findViewById<TextView>(R.id.tvDialogSourceFile)
        val tvDialogScanStatus = view.findViewById<TextView>(R.id.tvDialogScanStatus)

        // Set meter number badge
        tvDialogMeterNumber.text = formatMeterNumber(currentMeter.number)

        // Set status summary
        val registrationStatus = if (currentMeter.registered) {
            getString(R.string.status_registered)
        } else {
            getString(R.string.status_not_registered)
        }
        val scanStatus = if (currentMeter.isChecked) {
            getString(R.string.status_scanned)
        } else {
            getString(R.string.status_not_scanned)
        }
        tvDialogStatus.text = "$registrationStatus • $scanStatus"

        // Set detail information
        tvDialogSerialNumber.text = currentMeter.serialNumber
        tvDialogLocation.text = currentMeter.place
        tvDialogSourceFile.text = getShortFileName(currentMeter.fromFile)

        // Set scan status with color
        tvDialogScanStatus.text = scanStatus
        if (currentMeter.isChecked) {
            tvDialogScanStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_green)
            )
        } else {
            tvDialogScanStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.warning_orange)
            )
        }

        // Highlight incomplete information
        val isLocationValid = isLocationValid(currentMeter.place)
        val isMeterNumberValid = isMeterNumberValid(currentMeter.number)

        if (!isLocationValid) {
            tvDialogLocation.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.warning_orange)
            )
        }

        if (!isMeterNumberValid) {
            tvDialogMeterNumber.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.warning_orange)
            )
        }
    }

    private fun setupClickListeners(view: View) {
        val currentMeter = meter ?: return

        val btnEditVariables = view.findViewById<MaterialButton>(R.id.btnEditVariables)
        val btnScanMeter = view.findViewById<MaterialButton>(R.id.btnScanMeter)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)
        val btnDeleteMeter = view.findViewById<MaterialButton>(R.id.btnDeleteMeter)

        btnEditVariables.setOnClickListener {
            onEditClick?.invoke(currentMeter)
            dismiss()
        }

        btnScanMeter.setOnClickListener {
            onScanClick?.invoke(currentMeter)
            dismiss()
        }

        btnDeleteMeter.setOnClickListener {
            onDeleteClick?.invoke(currentMeter)
            dismiss()
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        // Set content descriptions for accessibility
        btnEditVariables.contentDescription = getString(R.string.cd_edit_meter)
        btnScanMeter.contentDescription = getString(R.string.cd_scan_meter)
        btnClose.contentDescription = getString(R.string.cd_close_dialog)
        btnDeleteMeter.contentDescription = getString(R.string.cd_delete_meter)
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