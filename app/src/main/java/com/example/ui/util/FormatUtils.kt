package com.example.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val dateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }

    fun formatTimestamp(ms: Long): String = timeFormat.get()?.format(Date(ms)) ?: ""

    fun formatDateTime(ms: Long): String = dateTimeFormat.get()?.format(Date(ms)) ?: ""

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }
}
