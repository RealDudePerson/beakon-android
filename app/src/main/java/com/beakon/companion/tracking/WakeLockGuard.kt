package com.beakon.companion.tracking

/**
 * Runs [block] with a resource acquired by [acquire], releasing it in `finally`
 * so a failed tick cannot leak the resource.
 *
 * Kept Android-free so the acquire/release lifecycle can be unit-tested; the
 * service supplies a partial [android.os.PowerManager.WakeLock].
 */
suspend fun <T> withWakeLock(
    acquire: () -> T?,
    release: (T) -> Unit,
    block: suspend () -> Unit,
) {
    val lock = acquire()
    try {
        block()
    } finally {
        if (lock != null) release(lock)
    }
}
