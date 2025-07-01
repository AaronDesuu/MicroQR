package com.example.microqr.ui.location

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.microqr.R
import com.example.microqr.databinding.FragmentLocationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class LocationFragment : Fragment() {

    private var _binding: FragmentLocationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LocationViewModel by viewModels {
        LocationViewModelFactory(requireContext())
    }
    private lateinit var locationAdapter: LocationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupAddLocationInput()
        setupObservers()
        setupClickListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(R.string.location_fragment_title)
    }

    private fun setupRecyclerView() {
        locationAdapter = LocationAdapter(
            onEditClick = { location ->
                showEditLocationDialog(location)
            },
            onDeleteClick = { location ->
                showDeleteLocationDialog(location)
            }
        )

        binding.locationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = locationAdapter
        }
    }

    private fun setupAddLocationInput() {
        binding.locationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                binding.addLocationButton.isEnabled = text.isNotEmpty()
            }
        })

        // Set initial state
        binding.addLocationButton.isEnabled = false
    }

    private fun setupClickListeners() {
        binding.addLocationButton.setOnClickListener {
            addLocation()
        }

        // Handle enter key in input field
        binding.locationInput.setOnEditorActionListener { _, _, _ ->
            if (binding.addLocationButton.isEnabled) {
                addLocation()
            }
            true
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                updateUI(uiState)
            }
        }
    }

    private fun updateUI(uiState: LocationUiState) {
        // Update locations list
        locationAdapter.submitList(uiState.locations.toList())

        // Update empty state
        binding.emptyStateLayout.isVisible = uiState.locations.isEmpty()

        // Show loading state
        binding.addLocationButton.isEnabled = !uiState.isLoading &&
                binding.locationInput.text?.toString()?.trim()?.isNotEmpty() == true

        // Clear input field after successful addition
        if (uiState.clearInput) {
            binding.locationInput.setText("")
            viewModel.clearInputFlag()
        }

        // Show messages
        uiState.message?.let { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }

        // Handle errors
        uiState.error?.let { error ->
            binding.locationInputLayout.error = error
            viewModel.clearError()
        } ?: run {
            binding.locationInputLayout.error = null
        }
    }

    private fun addLocation() {
        val locationName = binding.locationInput.text?.toString()?.trim() ?: ""
        if (locationName.isNotEmpty()) {
            viewModel.addLocation(locationName)
        }
    }

    private fun showEditLocationDialog(location: String) {
        val editText = TextInputEditText(requireContext()).apply {
            setText(location)
            setSelectAllOnFocus(true)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.edit_location))
            .setView(editText)
            .setPositiveButton(getString(R.string.save_location)) { _, _ ->
                val newName = editText.text?.toString()?.trim() ?: ""
                if (newName.isNotEmpty()) {
                    viewModel.updateLocation(location, newName)
                }
            }
            .setNegativeButton(getString(R.string.cancel_location), null)
            .show()
    }

    private fun showDeleteLocationDialog(location: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_location))
            .setMessage(getString(R.string.confirm_delete_location_message))
            .setPositiveButton(getString(R.string.delete_location)) { _, _ ->
                viewModel.deleteLocation(location)
            }
            .setNegativeButton(getString(R.string.cancel_location), null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}