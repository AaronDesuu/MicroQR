package com.example.microqr.ui.login

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.microqr.R
import com.example.microqr.data.repository.LoginRepository
import com.example.microqr.data.Result
import com.example.microqr.utils.Event
import kotlinx.coroutines.launch

class LoginViewModel(private val loginRepository: LoginRepository) : ViewModel() {

    private val _loginForm = MutableLiveData<LoginFormState>()
    val loginFormState: LiveData<LoginFormState> = _loginForm

    private val _loginResult = MutableLiveData<LoginResult>()
    val loginResult: LiveData<LoginResult> = _loginResult

    // For navigation event
    private val _navigateToHomeEvent = MutableLiveData<Event<Unit>>()
    val navigateToHomeEvent: LiveData<Event<Unit>> = _navigateToHomeEvent

    init {
        // Check if user is already logged in when ViewModel is created
        checkExistingLoginState()
    }

    private fun checkExistingLoginState() {
        val currentUser = loginRepository.getCurrentUser()
        if (currentUser != null) {
            val userView = LoggedInUserView(
                userId = currentUser.id,
                displayName = currentUser.name,
                email = currentUser.email,
                avatarUrl = currentUser.profilePictureUrl
            )
            _loginResult.value = LoginResult(success = userView)
        }
    }

    fun getCurrentLoginState(): LoginResult? {
        return _loginResult.value
    }

    fun login(username: String, password: String) {
        // Validate input (example)
        if (!isUserNameValid(username)) {
            _loginForm.value = LoginFormState(usernameError = R.string.invalid_username)
            return
        }
        if (!isPasswordValid(password)) {
            _loginForm.value = LoginFormState(passwordError = R.string.invalid_password)
            return
        }
        // Can be launched in a separate asynchronous job
        viewModelScope.launch {
            // Show loading state if you have one
            // _loginForm.value = LoginFormState(isDataValid = true, isLoading = true)

            val result = loginRepository.login(username, password)

            // Hide loading state
            // _loginForm.value = LoginFormState(isDataValid = true, isLoading = false)

            if (result is Result.Success) {
                val backendUser = result.data // This is your mock LoggedInUser
                val userView = LoggedInUserView(
                    userId = backendUser.id,
                    displayName = backendUser.name,
                    email = backendUser.email,
                    avatarUrl = backendUser.profilePictureUrl
                )
                _loginResult.value = LoginResult(success = userView)
                _navigateToHomeEvent.value = Event(Unit) // Trigger navigation
            } else if (result is Result.Error) {
                // Example: Using a generic error message from strings.xml
                // You could also extract result.exception.message if it's user-friendly
                _loginResult.value = LoginResult(error = R.string.login_failed)
            }
        }
    }

    fun loginDataChanged(username: String, password: String) {
        if (!isUserNameValid(username)) {
            _loginForm.value = LoginFormState(usernameError = R.string.invalid_username)
        } else if (!isPasswordValid(password)) {
            _loginForm.value = LoginFormState(passwordError = R.string.invalid_password)
        } else {
            _loginForm.value = LoginFormState(isDataValid = true)
        }
    }

    // A placeholder username validation check
    private fun isUserNameValid(username: String): Boolean {
        return if (username.contains('@')) {
            android.util.Patterns.EMAIL_ADDRESS.matcher(username).matches()
        } else {
            username.isNotBlank()
        }
    }

    // A placeholder password validation check
    private fun isPasswordValid(password: String): Boolean {
        return password.length > 5
    }
}