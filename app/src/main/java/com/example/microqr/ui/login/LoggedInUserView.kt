package com.example.microqr.ui.login

/**
 * User details post authentication that is exposed to the UI
 */
data class LoggedInUserView(
    val userId: String, // Essential for identifying the user for further operations
    val displayName: String,
    val email: String? = null,      // Optional: if you need to display the email
    val avatarUrl: String? = null   // Optional: URL for user's profile picture
    // Add other simple data fields that might be useful for immediate UI updates
    // For example:
    // val userRole: String? = null // If you have simple roles affecting initial UI
)