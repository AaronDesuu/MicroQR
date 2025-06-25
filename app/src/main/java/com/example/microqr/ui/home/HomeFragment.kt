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

        // Observe quick stats
        viewModel.totalMeters.observe(viewLifecycleOwner, Observer { count ->
            binding.tvTotalMeters.text = count.toString()
        })

        viewModel.recentScans.observe(viewLifecycleOwner, Observer { count ->
            binding.tvRecentScans.text = count.toString()
        })

        // Observe error messages
        viewModel.errorMessage.observe(viewLifecycleOwner, Observer { message ->
            message?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        })

        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner, Observer { isLoading ->
            // You can add loading indicators here if needed
            // For now, we'll just disable buttons during loading
            binding.cardMatchMeter.isEnabled = !isLoading
            binding.cardMeterCheck.isEnabled = !isLoading
            binding.cardFileUpload.isEnabled = !isLoading
            binding.btnLogout.isEnabled = !isLoading
        })
    }

    private fun setupClickListeners() {
        // Match Meter card click
        binding.cardMatchMeter.setOnClickListener {
            viewModel.onMatchMeterClicked()
        }

        // Meter Check card click
        binding.cardMeterCheck.setOnClickListener {
            viewModel.onMeterCheckClicked()
        }

        // File Upload card click
        binding.cardFileUpload.setOnClickListener {
            viewModel.onFileUploadClicked()
        }

        // Logout button click
        binding.btnLogout.setOnClickListener {
            viewModel.onLogoutClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh stats when returning to home
        viewModel.loadQuickStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}