package com.aistudio.alphapulse.engine

import android.app.Activity
import android.app.AlertDialog

/**
 * Phase 1 §3 — the mandatory full-screen intercept shown when the operator leaves the
 * app with live credentials and resting orders.
 *
 * The dialog is intentionally non-cancelable: dismissing it by tapping outside is exactly
 * the behaviour §3 forbids. Both explicit choices are ordered here, and the caller
 * (`TraderHostActivity`) maps them to `MainViewModel.acknowledgeOrphanedOrderWarning()`
 * (wipe keys, credit the §38 minimize evidence) and
 * `MainViewModel.dismissOrphanedOrderWarning()` (leave orders in place, do not credit the
 * gate).
 *
 * Built on the platform dialog because the dynamic feature ships no resources and no
 * Compose dependency.
 *
 * Spec Ref: Phase 1 §3 Orphaned Order OS Intercept, Phase 2 §38
 */
object OrphanInterceptDialog {

    const val TITLE = "ORPHANED ORDERS"
    const val MESSAGE =
        "You are leaving AlphaPulse with live API credentials and resting orders. " +
            "Wiping the keys now cancels nothing on the venue: acknowledge to wipe the " +
            "session from RAM, or stay to manage the boards."
    const val ACKNOWLEDGE_LABEL = "Wipe keys & suspend"
    const val DISMISS_LABEL = "Stay"

    /** Shows the intercept and returns the dialog so the caller can dismiss it. */
    fun show(
        activity: Activity,
        onAcknowledge: () -> Unit,
        onDismiss: () -> Unit
    ): AlertDialog = AlertDialog.Builder(activity)
        .setTitle(TITLE)
        .setMessage(MESSAGE)
        .setCancelable(false)
        .setPositiveButton(ACKNOWLEDGE_LABEL) { dialog, _ ->
            dialog.dismiss()
            onAcknowledge()
        }
        .setNegativeButton(DISMISS_LABEL) { dialog, _ ->
            dialog.dismiss()
            onDismiss()
        }
        .show()
}
