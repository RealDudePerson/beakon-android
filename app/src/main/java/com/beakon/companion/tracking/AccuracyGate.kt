package com.beakon.companion.tracking

import kotlin.math.roundToInt

/**
 * The accuracy gate: decides whether a location fix is good enough to push.
 *
 * Deliberately free of Android types so it can be unit-tested on the JVM. The
 * service reads the user's threshold and the fix's reported accuracy, then asks
 * this object; the notification and tracking-state bookkeeping happen in the
 * service (see [LocationTrackingService]).
 */
object AccuracyGate {

    /**
     * True when the fix should be sent to the server.
     *
     * - `acc == null`: the fix reports no accuracy. The app cannot judge it, and
     *   dropping it would silently remove the user from the map entirely, so it
     *   is PUSHED. This is the "never fail silently" rule: an unjudgeable fix is
     *   not a bad fix.
     * - `acc <= thresholdM`: good enough, push. Using `<=` means a fix exactly at
     *   the threshold is pushed.
     * - `acc > thresholdM`: too coarse, hold it back.
     */
    fun shouldPush(acc: Double?, thresholdM: Int): Boolean {
        if (acc == null) return true
        return acc <= thresholdM
    }

    /**
     * The message shown when a fix is held back. It carries the actual numbers so
     * the user can see *why* the map is not moving, rather than a generic
     * "waiting". Pure so it can be unit-tested.
     */
    fun holdMessage(acc: Double, thresholdM: Int): String =
        "Held: fix accurate to ${acc.roundToInt()} m, threshold $thresholdM m"
}
