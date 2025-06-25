package com.example.microqr.ui.metercheck

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MeterCheckViewModel : ViewModel() {

    private val _screenTitle = MutableLiveData<String>().apply {
        value = "Scan & Register"
    }
    val screenTitle: LiveData<String> = _screenTitle

    private val _searchQuery = MutableLiveData<String>().apply {
        value = ""
    }
    val searchQuery: LiveData<String> = _searchQuery

    private val _isRefreshing = MutableLiveData<Boolean>().apply {
        value = false
    }
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    // Statistics
    private val _totalMetersCount = MutableLiveData<Int>().apply {
        value = 0
    }
    val totalMetersCount: LiveData<Int> = _totalMetersCount

    private val _registeredMetersCount = MutableLiveData<Int>().apply {
        value = 0
    }
    val registeredMetersCount: LiveData<Int> = _registeredMetersCount

    private val _unregisteredMetersCount = MutableLiveData<Int>().apply {
        value = 0
    }
    val unregisteredMetersCount: LiveData<Int> = _unregisteredMetersCount

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun setRefreshing(isRefreshing: Boolean) {
        _isRefreshing.value = isRefreshing
    }

    fun updateStatistics(total: Int, registered: Int, unregistered: Int) {
        _totalMetersCount.value = total
        _registeredMetersCount.value = registered
        _unregisteredMetersCount.value = unregistered
    }

    fun updateScreenTitle(title: String) {
        _screenTitle.value = title
    }
}