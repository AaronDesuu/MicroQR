package com.example.microqr.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.microqr.data.model.LoggedInUser
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Class that handles authentication w/ login credentials and retrieves user information.
 */
class LoginDataSource(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "login_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PROFILE_URL = "user_profile_url"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
    }

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get currently logged in user from persistent storage
     */
    fun getCurrentUser(): LoggedInUser? {
        Log.d("LoginDataSource", "getCurrentUser() called")

        val isLoggedIn = sharedPrefs.getBoolean(KEY_IS_LOGGED_IN, false)
        Log.d("LoginDataSource", "Is logged in flag: $isLoggedIn")

        if (!isLoggedIn) {
            Log.d("LoginDataSource", "User is not logged in")
            return null
        }

        val userId = sharedPrefs.getString(KEY_USER_ID, null)
        val userName = sharedPrefs.getString(KEY_USER_NAME, null)
        val userEmail = sharedPrefs.getString(KEY_USER_EMAIL, null)
        val profileUrl = sharedPrefs.getString(KEY_USER_PROFILE_URL, null)

        Log.d("LoginDataSource", "Retrieved from SharedPrefs - ID: $userId, Name: $userName, Email: $userEmail")

        return if (userId != null && userName != null && userEmail != null) {
            val user = LoggedInUser(
                id = userId,
                name = userName,
                email = userEmail,
                profilePictureUrl = profileUrl
            )
            Log.d("LoginDataSource", "Returning existing user: ${user.name}")
            user
        } else {
            Log.w("LoginDataSource", "Incomplete user data in SharedPrefs, clearing login state")
            clearUserData()
            null
        }
    }

    /**
     * Save user data to persistent storage
     */
    private fun saveUser(user: LoggedInUser) {
        Log.d("LoginDataSource", "saveUser() called for: ${user.name}")

        sharedPrefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_USER_NAME, user.name)
            .putString(KEY_USER_EMAIL, user.email)
            .putString(KEY_USER_PROFILE_URL, user.profilePictureUrl)
            .apply()

        Log.d("LoginDataSource", "User data saved to SharedPreferences")
    }

    /**
     * Clear user data from persistent storage
     */
    private fun clearUserData() {
        Log.d("LoginDataSource", "clearUserData() called")

        sharedPrefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_PROFILE_URL)
            .apply()

        Log.d("LoginDataSource", "User data cleared from SharedPreferences")
    }

    // Make login a suspend function if it involves simulated delays or real network calls later
    suspend fun login(username: String, password: String): Result<LoggedInUser> {
        Log.d("LoginDataSource", "login() called for username: $username")

        // Simulate network delay to mimic a real API call
        delay(1000) // 1 second delay

        try {
            val user = when {
                username == "test" && password == "password" -> {
                    LoggedInUser(
                        id = "mockUserId123",
                        name = "Test User",
                        email = "test@example.com",
                        profilePictureUrl = "https://via.placeholder.com/150"
                    )
                }
                username == "user2" && password == "pass123" -> {
                    LoggedInUser(
                        id = "mockUserId456",
                        name = "Mock User",
                        email = "user2@example.com",
                        profilePictureUrl = null
                    )
                }
                username == "admin" && password == "admin123" -> {
                    LoggedInUser(
                        id = "adminUserId789",
                        name = "Admin User",
                        email = "admin@example.com",
                        profilePictureUrl = null
                    )
                }
                else -> {
                    Log.w("LoginDataSource", "Invalid credentials for username: $username")
                    return Result.Error(IOException("Mock Login Error: Invalid credentials"))
                }
            }

            // Save the user data after successful authentication
            saveUser(user)

            Log.d("LoginDataSource", "Login successful for user: ${user.name}")
            return Result.Success(user)

        } catch (e: Throwable) {
            Log.e("LoginDataSource", "Error during login", e)
            return Result.Error(IOException("Error logging in (mock data source)", e))
        }
    }

    fun logout() {
        Log.d("LoginDataSource", "logout() called")
        clearUserData()
        Log.d("LoginDataSource", "Logout completed - user data cleared")
    }

    // Utility method for debugging
    fun debugPrintStoredData() {
        Log.d("LoginDataSource", "=== DEBUG: Current stored data ===")
        Log.d("LoginDataSource", "Is logged in: ${sharedPrefs.getBoolean(KEY_IS_LOGGED_IN, false)}")
        Log.d("LoginDataSource", "User ID: ${sharedPrefs.getString(KEY_USER_ID, "null")}")
        Log.d("LoginDataSource", "User Name: ${sharedPrefs.getString(KEY_USER_NAME, "null")}")
        Log.d("LoginDataSource", "User Email: ${sharedPrefs.getString(KEY_USER_EMAIL, "null")}")
        Log.d("LoginDataSource", "Profile URL: ${sharedPrefs.getString(KEY_USER_PROFILE_URL, "null")}")
        Log.d("LoginDataSource", "===================================")
    }
}