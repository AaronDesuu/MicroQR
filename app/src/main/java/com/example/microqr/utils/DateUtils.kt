// Enhanced DateUtils.kt with better Japanese date support
package com.example.microqr.utils

import android.content.Context
import com.example.microqr.R
import java.text.SimpleDateFormat
import java.util.*

object DateUtils {

    /**
     * Formats date according to device locale with automatic input format detection
     */
    fun formatFileDate(context: Context, dateString: String): String {
        return try {
            val date = parseInputDate(dateString)
            date?.let { formatDateByLocale(context, it) } ?: dateString
        } catch (e: Exception) {
            dateString
        }
    }

    /**
     * Parse various input date formats
     */
    private fun parseInputDate(dateString: String): Date? {
        val inputFormats = listOf(
            SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH),          // Jan 15, 2025
            SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.ENGLISH), // Jan 15, 2025 • 2:30 PM
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),            // 2025-01-15
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),   // 2025-01-15 14:30:00
            SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH),            // 15/01/2025
            SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)             // 01/15/2025
        )

        for (format in inputFormats) {
            try {
                return format.parse(dateString)
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    /**
     * Format date according to current device locale
     */
    private fun formatDateByLocale(context: Context, date: Date): String {
        val locale = getCurrentLocale(context)
        val calendar = Calendar.getInstance()
        calendar.time = date

        return when (locale.language) {
            "ja" -> formatJapaneseDate(calendar)
            "ko" -> formatKoreanDate(calendar)
            "zh" -> formatChineseDate(calendar)
            else -> formatWesternDate(date, locale)
        }
    }

    /**
     * Format date in Japanese style: 2025年1月15日
     */
    private fun formatJapaneseDate(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "${year}年${month}月${day}日"
    }

    /**
     * Format date in Korean style: 2025년 1월 15일
     */
    private fun formatKoreanDate(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "${year}년 ${month}월 ${day}일"
    }

    /**
     * Format date in Chinese style: 2025年1月15日
     */
    private fun formatChineseDate(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "${year}年${month}月${day}日"
    }

    /**
     * Format date in Western style
     */
    private fun formatWesternDate(date: Date, locale: Locale): String {
        val format = SimpleDateFormat("MMM dd, yyyy", locale)
        return format.format(date)
    }

    /**
     * Get current locale, handling API differences
     */
    private fun getCurrentLocale(context: Context): Locale {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }

    /**
     * Format date with time according to locale
     */
    fun formatFileDateWithTime(context: Context, dateString: String, timeString: String? = null): String {
        return try {
            val date = parseInputDate(dateString)
            date?.let {
                val dateStr = formatDateByLocale(context, it)
                timeString?.let { time ->
                    val formattedTime = formatTimeByLocale(context, time)
                    "$dateStr • $formattedTime"
                } ?: dateStr
            } ?: dateString
        } catch (e: Exception) {
            dateString
        }
    }

    /**
     * Format time according to locale preferences
     */
    private fun formatTimeByLocale(context: Context, timeString: String): String {
        val locale = getCurrentLocale(context)

        return when (locale.language) {
            "ja", "ko", "zh" -> {
                // Use 24-hour format for East Asian locales
                convertTo24Hour(timeString)
            }
            else -> {
                // Keep original format for other locales
                timeString
            }
        }
    }

    /**
     * Convert 12-hour time to 24-hour format
     */
    private fun convertTo24Hour(timeString: String): String {
        return try {
            val inputFormats = listOf(
                SimpleDateFormat("h:mm a", Locale.ENGLISH),
                SimpleDateFormat("hh:mm a", Locale.ENGLISH),
                SimpleDateFormat("H:mm", Locale.ENGLISH),
                SimpleDateFormat("HH:mm", Locale.ENGLISH)
            )

            for (format in inputFormats) {
                try {
                    val time = format.parse(timeString)
                    time?.let {
                        val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                        return outputFormat.format(it)
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            timeString
        } catch (e: Exception) {
            timeString
        }
    }

    /**
     * Get relative date strings (Today, Yesterday, etc.)
     */
    fun getRelativeDateString(context: Context, date: Date): String {
        val calendar = Calendar.getInstance()
        val today = calendar.time
        calendar.time = date
        val dateCalendar = Calendar.getInstance()
        dateCalendar.time = date

        val todayCalendar = Calendar.getInstance()
        todayCalendar.time = today

        val daysDiff = ((todayCalendar.timeInMillis - dateCalendar.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()

        return when (daysDiff) {
            0 -> context.getString(R.string.date_today)
            1 -> context.getString(R.string.date_yesterday)
            else -> formatDateByLocale(context, date)
        }
    }

    /**
     * Get current timestamp in localized format
     */
    fun getCurrentTimestampLocalized(context: Context): String {
        val now = Date()
        return formatFileDateWithTime(context, SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).format(now))
    }
}