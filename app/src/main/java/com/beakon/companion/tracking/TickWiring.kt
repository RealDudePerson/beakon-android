package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import com.beakon.companion.net.userMessage
import kotlin.math.roundToInt

/**
 * The pure wiring inside one tracking tick: what to do with a fix's accuracy,
 * the order in which the state is recorded, and which text the persistent
 * notification carries.
 *
 * Kept Android-free (like [AccuracyGate] and [TickLoop]) so the decisions can be
 * unit-tested on the JVM. The service only performs the side effects.
 */
object TickWiring {

    sealed interface FixAction {
        /**
         * The fix is too coarse: record it (with its numbers) but do not send.
         */
        data class Hold(
            val accuracyM: Int,
            val thresholdM: Int,
            val notification: String,
        ) : FixAction

        /** The fix is good enough (or unjudgeable) and should be reported. */
        data object Report : FixAction
    }

    /**
     * Applies [AccuracyGate] and rounds the recorded accuracy exactly as the
     * Status screen displays it. `acc == null` is a [FixAction.Report].
     */
    fun fixAction(accuracy: Double?, thresholdM: Int): FixAction {
        if (accuracy != null && !AccuracyGate.shouldPush(accuracy, thresholdM)) {
            return FixAction.Hold(
                accuracyM = accuracy.roundToInt(),
                thresholdM = thresholdM,
                notification = AccuracyGate.holdMessage(accuracy, thresholdM),
            )
        }
        return FixAction.Report
    }

    /**
     * Records a held fix: the fix timestamp first, then the hold numbers, so a
     * reader of the state never sees a hold without the fix that caused it.
     * Expressed as callbacks to stay free of the concrete store.
     */
    fun recordHeldFix(recordFix: () -> Unit, recordHold: () -> Unit) {
        recordFix()
        recordHold()
    }

    /** The persistent-notification text for a tick result. */
    fun notificationText(result: SendResult, hadFix: Boolean): String = when (result) {
        SendResult.Success -> "Location reported successfully."
        else -> result.userMessage() ?: if (hadFix) "Reporting…" else "Waiting for a fix…"
    }
}
