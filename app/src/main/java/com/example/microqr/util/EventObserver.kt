package com.example.microqr.util
import androidx.lifecycle.Observer // Make sure this is imported

/**
 * An [Observer] for [Event]s, simplifying the pattern of checking if the [Event]'s content has
 * already been handled.
 *
 * [onEventUnhandledContent] is *only* called if the [Event]'s contents has not been handled.
 */
class EventObserver<T>(private val onEventUnhandledContent: (T) -> Unit) : Observer<Event<T>> {
    override fun onChanged(value: Event<T>) { // Parameter name 'value' is conventional
        value.getContentIfNotHandled()?.let { content ->
            onEventUnhandledContent(content)
        }
    }
}