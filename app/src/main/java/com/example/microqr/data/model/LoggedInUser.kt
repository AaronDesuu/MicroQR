package com.example.microqr.data.model

/**
 * Data class that captures user information for logged in users retrieved from LoginRepository
 */
data class LoggedInUser(
    val id: String,
    val name: String,
    val email: String? = null,
    val profilePictureUrl: String? = null
)