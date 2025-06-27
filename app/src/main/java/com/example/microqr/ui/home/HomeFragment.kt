package com.example.microqr.ui.home

import androidx.fragment.app.viewModels
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.example.microqr.R
import com.example.microqr.databinding.FragmentHomeBinding
import com.google.android.material.snackbar.Snackbar
import android.util.Log

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    // Placeholder for when data is not yet loaded
    private val DATA_LOADING_PLACEHOLDER = "-" // Or "Loading..."

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        setupClickListeners()

        // Initialize stats with a loading placeholder
        setStatsToLoadingState()

        // Load initial data
        viewModel.loadUserData()
        viewModel.loadQuickStats() // This should trigger the LiveData updates
    }

    private fun setStatsToLoadingState() {
        binding.includeQuickStats.tvTotalMeters.text = DATA_LOADING_PLACEHOLDER
        binding.includeQuickStats.tvMatchedMeters.text = DATA_LOADING_PLACEHOLDER
        binding.includeQuickStats.tvUnmatchedMeters.text = DATA_LOADING_PLACEHOLDER
        binding.includeQuickStats.tvScannedMeters.text = DATA_LOADING_PLACEHOLDER
        binding.includeQuickStats.tvUnscannedMeters.text = DATA_LOADING_PLACEHOLDER
    }

    private fun setupObservers() {
        // Observe user greeting
        viewModel.userGreeting.observe(viewLifecycleOwner, Observer { greeting ->
            binding.tvUserGreeting.text = greeting
        })

        // Observe navigation events
        viewModel.navigationEvent.observe(viewLifecycleOwner, Observer { event ->
            when (event) {
                HomeViewModel.NavigationEvent.NAVIGATE_TO_MATCH -> {
                    findNavController().navigate(R.id.action_homeFragment_to_matchFragment)
                }
                HomeViewModel.NavigationEvent.NAVIGATE_TO_METER_CHECK -> {
                    findNavController().navigate(R.id.action_homeFragment_to_meterCheckFragment)
                }
                HomeViewModel.NavigationEvent.NAVIGATE_TO_FILE_UPLOAD -> {
                    findNavController().navigate(R.id.action_homeFragment_to_fileUploadFragment)
                }
                HomeViewModel.NavigationEvent.NAVIGATE_TO_LOGIN -> {
                    findNavController().navigate(R.id.action_homeFragment_to_loginFragment)
                }
                // Clear the event after handling
                null -> { /* Event already handled */ }
            }
        })

        // Observe snackbar messages
//        viewModel.snackbarMessage.observe(viewLifecycleOwner, Observer { message ->
//            message?.let {
//                Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
//                viewModel.onSnackbarShown() // Clear the message
//            }
//        })

        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner, Observer { isLoading ->
            if (isLoading) {
                setStatsToLoadingState()
            }
        })

        // Observe total meters count
        viewModel.totalMeters.observe(viewLifecycleOwner, Observer { total ->
            if (viewModel.isLoading.value == true) {
                binding.includeQuickStats.tvTotalMeters.text = DATA_LOADING_PLACEHOLDER
                return@Observer
            }
            binding.includeQuickStats.tvTotalMeters.text = total.toString()
        })

        // Observe MeterCheck data directly for immediate updates
        viewModel.meterCheckMeters.observe(viewLifecycleOwner) { checkMeters ->
            if (viewModel.isLoading.value == true) { // If globally loading, wait
                binding.includeQuickStats.tvScannedMeters.text = DATA_LOADING_PLACEHOLDER
                binding.includeQuickStats.tvUnscannedMeters.text = DATA_LOADING_PLACEHOLDER
                return@observe
            }
            checkMeters?.let {
                val scanned = it.count { meter -> meter.isChecked }
                val unscanned = it.size - scanned

                binding.includeQuickStats.tvScannedMeters.text = scanned.toString()
                binding.includeQuickStats.tvUnscannedMeters.text = unscanned.toString()

                Log.d("HomeFragment", "MeterCheck data updated: ${it.size} total, $scanned scanned, $unscanned unscanned")
                showWorkSuggestion()
            } ?: run {
                // Handle null case if data is not yet available but not loading
                if (viewModel.isLoading.value == false) {
                    binding.includeQuickStats.tvScannedMeters.text = "0"
                    binding.includeQuickStats.tvUnscannedMeters.text = "0"
                }
            }
        }

        // Observe MeterMatch data directly for immediate updates
        viewModel.meterMatchMeters.observe(viewLifecycleOwner) { matchMeters ->
            if (viewModel.isLoading.value == true) { // If globally loading, wait
                binding.includeQuickStats.tvMatchedMeters.text = DATA_LOADING_PLACEHOLDER
                binding.includeQuickStats.tvUnmatchedMeters.text = DATA_LOADING_PLACEHOLDER
                return@observe
            }
            matchMeters?.let {
                val matched = it.count { meter -> meter.isChecked }
                val unmatched = it.size - matched

                binding.includeQuickStats.tvMatchedMeters.text = matched.toString()
                binding.includeQuickStats.tvUnmatchedMeters.text = unmatched.toString()

                Log.d("HomeFragment", "MeterMatch data updated: ${it.size} total, $matched matched, $unmatched unmatched")
                showWorkSuggestion()
            } ?: run {
                if (viewModel.isLoading.value == false) {
                    binding.includeQuickStats.tvMatchedMeters.text = "0"
                    binding.includeQuickStats.tvUnmatchedMeters.text = "0"
                }
            }
        }
    }

    private fun showWorkSuggestion() {
        val suggestion = viewModel.getWorkPrioritySuggestion()
        suggestion?.let {
            Log.d("HomeFragment", "Work suggestion: $it")
        }
    }

    private fun setupClickListeners() {
        binding.cardMatchMeter.setOnClickListener {
            Log.d("HomeFragment", "Match Meter clicked")
            viewModel.onMatchMeterClicked()
        }
        binding.cardMeterCheck.setOnClickListener {
            Log.d("HomeFragment", "Meter Check clicked")
            viewModel.onMeterCheckClicked()
        }
        binding.cardFileUpload.setOnClickListener {
            Log.d("HomeFragment", "File Upload clicked")
            viewModel.onFileUploadClicked()
        }

        // NEW: Export button click listener
        binding.cardExportData.setOnClickListener {
            Log.d("HomeFragment", "Export Data clicked")
            try {
                findNavController().navigate(R.id.action_homeFragment_to_exportFragment)
            } catch (e: Exception) {
                Log.e("HomeFragment", "Failed to navigate to export", e)
                Snackbar.make(binding.root, "Export feature temporarily unavailable", Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnLogout.setOnClickListener {
            Log.d("HomeFragment", "Logout clicked")
            viewModel.onLogoutClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("HomeFragment", "Fragment resumed - refreshing stats")
        // No need to call loadQuickStats or forceRefreshData here if onViewCreated handles initial load
        // and LiveData handles subsequent updates.
        // viewModel.refreshStats() // Keep if this has a specific purpose beyond just reloading
        // viewModel.forceRefreshData() // Consider if this is always needed onResume
    }

    override fun onPause() {
        super.onPause()
        Log.d("HomeFragment", "Fragment paused")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("HomeFragment", "Fragment view destroyed")
        _binding = null
    }

    fun navigateToSection(destination: String) {
        viewModel.handleDeepLink(destination)
    }

    fun getCurrentStats(): HomeViewModel.DetailedStats {
        return viewModel.getDetailedStats()
    }

    fun forceRefresh() {
        viewModel.forceRefreshData()
    }
}