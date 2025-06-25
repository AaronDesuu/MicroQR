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

        // Load initial data
        viewModel.loadUserData()
        viewModel.loadQuickStats()
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
            }
        })

        // Observe real-time statistics from repository
        setupStatsObservers()

        // Observe error messages
        viewModel.errorMessage.observe(viewLifecycleOwner, Observer { message ->
            message?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        })

        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner, Observer { isLoading ->
            // Update UI elements based on loading state
            binding.cardMatchMeter.isEnabled = !isLoading
            binding.cardMeterCheck.isEnabled = !isLoading
            binding.cardFileUpload.isEnabled = !isLoading
            binding.btnLogout.isEnabled = !isLoading

            // You can add a loading indicator here if needed
            // binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        })

        // Observe real-time data changes for immediate updates
        observeRealtimeData()
    }

    private fun setupStatsObservers() {
        // Total meters count
        viewModel.totalMeters.observe(viewLifecycleOwner, Observer { count ->
            binding.includeQuickStats.tvTotalMeters.text = count.toString()
            Log.d("HomeFragment", "Total meters updated: $count")
        })

        // Match-specific stats
        viewModel.matchedMeters.observe(viewLifecycleOwner, Observer { count ->
            binding.includeQuickStats.tvMatchedMeters.text = count.toString()
            Log.d("HomeFragment", "Matched meters updated: $count")
        })

        viewModel.unmatchedMeters.observe(viewLifecycleOwner, Observer { count ->
            binding.includeQuickStats.tvUnmatchedMeters.text = count.toString()
            Log.d("HomeFragment", "Unmatched meters updated: $count")
        })

        // Check-specific stats
        viewModel.scannedMeters.observe(viewLifecycleOwner, Observer { count ->
            binding.includeQuickStats.tvScannedMeters.text = count.toString()
            Log.d("HomeFragment", "Scanned meters updated: $count")
        })

        viewModel.unscannedMeters.observe(viewLifecycleOwner, Observer { count ->
            binding.includeQuickStats.tvUnscannedMeters.text = count.toString()
            Log.d("HomeFragment", "Unscanned meters updated: $count")
        })
    }

    private fun observeRealtimeData() {
        // Observe MeterCheck data directly for immediate updates
        viewModel.meterCheckMeters.observe(viewLifecycleOwner) { checkMeters ->
            checkMeters?.let {
                val scanned = it.count { meter -> meter.isChecked }
                val unscanned = it.size - scanned

                // Update UI immediately
                binding.includeQuickStats.tvScannedMeters.text = scanned.toString()
                binding.includeQuickStats.tvUnscannedMeters.text = unscanned.toString()

                Log.d("HomeFragment", "MeterCheck data updated: ${it.size} total, $scanned scanned, $unscanned unscanned")

                // Show work priority suggestion if needed
                showWorkSuggestion()
            }
        }

        // Observe MeterMatch data directly for immediate updates
        viewModel.meterMatchMeters.observe(viewLifecycleOwner) { matchMeters ->
            matchMeters?.let {
                val matched = it.count { meter -> meter.isChecked }
                val unmatched = it.size - matched

                // Update UI immediately
                binding.includeQuickStats.tvMatchedMeters.text = matched.toString()
                binding.includeQuickStats.tvUnmatchedMeters.text = unmatched.toString()

                Log.d("HomeFragment", "MeterMatch data updated: ${it.size} total, $matched matched, $unmatched unmatched")

                // Show work priority suggestion if needed
                showWorkSuggestion()
            }
        }
    }

    private fun showWorkSuggestion() {
        val suggestion = viewModel.getWorkPrioritySuggestion()
        suggestion?.let {
            // You can show this as a subtle notification or badge
            // For now, just log it
            Log.d("HomeFragment", "Work suggestion: $it")

            // Optionally show as a snackbar for important suggestions
            if (viewModel.hasPendingWork()) {
                // Only show once per session to avoid spam
                // You could use SharedPreferences to track this
            }
        }
    }

    private fun setupClickListeners() {
        // Match Meter card click
        binding.cardMatchMeter.setOnClickListener {
            Log.d("HomeFragment", "Match Meter clicked")
            viewModel.onMatchMeterClicked()
        }

        // Meter Check card click
        binding.cardMeterCheck.setOnClickListener {
            Log.d("HomeFragment", "Meter Check clicked")
            viewModel.onMeterCheckClicked()
        }

        // File Upload card click
        binding.cardFileUpload.setOnClickListener {
            Log.d("HomeFragment", "File Upload clicked")
            viewModel.onFileUploadClicked()
        }

        // Logout button click
        binding.btnLogout.setOnClickListener {
            Log.d("HomeFragment", "Logout clicked")
            viewModel.onLogoutClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("HomeFragment", "Fragment resumed - refreshing stats")

        // Refresh stats when returning to home (e.g., from other fragments)
        viewModel.refreshStats()

        // Force refresh data to ensure we have the latest from repository
        viewModel.forceRefreshData()
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

    // Public method for external navigation (e.g., from notifications)
    fun navigateToSection(destination: String) {
        viewModel.handleDeepLink(destination)
    }

    // Public method to get current stats (useful for debugging)
    fun getCurrentStats(): HomeViewModel.DetailedStats {
        return viewModel.getDetailedStats()
    }

    // Public method to force refresh (useful when data changes elsewhere)
    fun forceRefresh() {
        viewModel.forceRefreshData()
    }
}