package com.beakon.companion.ui

import java.text.DateFormat
import java.util.Date

object Format {
    private val dateTime: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)

    /** Formats a millisecond epoch timestamp, or a dash when absent. */
    fun time(millis: Long?): String = if (millis == null) "—" else dateTime.format(Date(millis))

    /** The server sends `timestamp` in seconds; converts to millis. */
    fun serverTime(seconds: Long?): String = time(seconds?.times(1000))
}
