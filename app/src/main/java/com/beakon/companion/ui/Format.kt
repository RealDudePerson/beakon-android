package com.beakon.companion.ui

import java.text.DateFormat
import java.util.Date

object Format {
    private val dateTime: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)

    /** Formats a millisecond epoch timestamp, or a dash when absent. */
    fun time(millis: Long?): String = if (millis == null) "—" else dateTime.format(Date(millis))

    /**
     * The server sends `timestamp` as a human display string (e.g. "just now",
     * "18 hours ago"), never an epoch number. Show it verbatim, or a dash when
     * absent. Do not try to re-derive a time from it.
     */
    fun serverTime(timestamp: String?): String = timestamp ?: "—"

    /**
     * A real age in whole seconds, computed from the additive `timestamp_unix`
     * epoch when the server sent it, or `null` for an old server that did not
     * (callers then fall back to [serverTime]). Never derived from the display
     * string, which is not parseable.
     */
    fun ageSeconds(
        timestampUnix: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? = timestampUnix?.let { (nowMillis / 1000L) - it }
}
