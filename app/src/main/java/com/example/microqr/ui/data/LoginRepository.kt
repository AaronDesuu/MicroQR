package com.example.microqr.ui.data

import com.example.microqr.ui.data.model.LoggedInUser

/**
 * Class that requests authentication and user information from the data source and
 * maintains an in-memory cache of login status and user credentials information.
 */
class LoginRepository(private val dataSource: LoginDataSource) {

    // Example of in-memory cache of the loggedInUser for this session
    @Volatile private var loggedInUser: LoggedInUser? = null

    // Standard function to check if user is logged in (optional, based on your needs)
    val isLoggedIn: Boolean
        get() = loggedInUser != null

    // Expose the current logged in user
    fun getCurrentUser(): LoggedInUser? = loggedInUser

    fun logout() {
        loggedInUser = null
        dataSource.logout() // Call dataSource's logout
    }

    // Make login a suspend function here as well since it calls a suspend function
    suspend fun login(username: String, password: String): Result<LoggedInUser> {
        // Perform the login request
        val result = dataSource.login(username, password)

        // Cache user data on success
        if (result is Result.Success) {
            setLoggedInUser(result.data)
        }

        return result
    }

    private fun setLoggedInUser(loggedInUser: LoggedInUser) {
        this.loggedInUser = loggedInUser
        // If you had other in-memory caching logic, it would go here
    }
}