package com.example.microqr.ui.login

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.microqr.data.repository.LoginRepository
import com.example.microqr.data.LoginDataSource

class LoginViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        Log.d("LoginViewModelFactory", "Creating ViewModel with context: $context")

        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            // Pass context to LoginDataSource so it can access SharedPreferences/DataStore
            val dataSource = LoginDataSource(context) // Add context parameter
            val repository = LoginRepository(dataSource)

            Log.d("LoginViewModelFactory", "Created LoginRepository and LoginDataSource with context")
            return LoginViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}