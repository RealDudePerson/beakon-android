package com.beakon.companion.tracking

/**
 * Freshness filter for the non-fused fallback source's `getLastKnownLocation`
 * results.
 *
 * A last-known fix is only usable when it is no older than twice the configured
 * reporting interval. Otherwise the source falls through to a live fix; if that
 * also yields nothing the caller reports `NoFix`. A stale position is never
 * pushed with a fresh server timestamp.
 */
object LastKnownFix {

    /** Two reporting intervals, in milliseconds. */
    fun maxAgeMs(intervalSeconds: Int): Long = intervalSeconds.coerceAtLeast(1) * 2L * 1000L

    /**
     * Returns the newest fix that is no older than [maxAgeMs], or null when no
     * fix is fresh. [timeOf] extracts each fix's timestamp so this stays free of
     * Android types and testable with a fake clock.
     */
    fun <T> freshest(
        fixes: List<T>,
        nowMs: Long,
        maxAgeMs: Long,
        timeOf: (T) -> Long,
    ): T? = fixes
        .filter { nowMs - timeOf(it) <= maxAgeMs }
        .maxByOrNull(timeOf)
}
