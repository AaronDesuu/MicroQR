package com.example.microqr.ui.data // Or wherever your Result class is

/**
 * A generic class that holds a value with its loading status.
 * @param <T>
 */
sealed class Result<out T : Any> {
    data class Success<out T : Any>(val data: T) : Result<T>()
    data class Error(val exception: Exception) : Result<Nothing>()
    // object Loading : Result<Nothing>() // Optional: if you want a distinct loading state
}