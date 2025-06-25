package com.example.microqr

import android.app.Application
import android.util.Log
import com.example.microqr.data.database.MeterDatabase
import com.example.microqr.data.repository.MeterRepository
import com.example.microqr.data.repository.PreferencesManager

class MicroQRApplication : Application() {

    // Lazy initialization of database and repository
    val database by lazy { MeterDatabase.getDatabase(this) }
    val repository by lazy { MeterRepository(this) }
    val preferencesManager by lazy { PreferencesManager(this) }

    override fun onCreate() {
        super.onCreate()
        Log.d("MicroQRApplication", "Application started")

        // Initialize any global settings here
        setupGlobalErrorHandler()

        // Log app version and first run status
        Log.d("MicroQRApplication", "First run: ${preferencesManager.isFirstRun}")
    }

    private fun setupGlobalErrorHandler() {
        // Set up global uncaught exception handler for better debugging
        Thread.setDefaultUncaughtExceptionHandler { _, exception ->
            Log.e("MicroQRApplication", "Uncaught exception: ", exception)
            // In production, you might want to send this to a crash reporting service
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d("MicroQRApplication", "Application terminated")
    }
}

// Add this to your AndroidManifest.xml in the <application> tag:
// android:name=".MicroQRApplication"