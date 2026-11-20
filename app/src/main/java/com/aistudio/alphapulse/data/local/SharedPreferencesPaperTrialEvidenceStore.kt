package com.aistudio.alphapulse.data.local

import android.content.Context
import com.aistudio.alphapulse.engine.proactive.PaperTrialEvidenceStore

/**
 * Durable storage for the §38 paper-trial evidence.
 *
 * A checksummed payload is written with `apply()`; the tracker validates it with
 * [com.aistudio.alphapulse.engine.proactive.PaperTrialEvidenceCodec] on read, so an
 * asynchronous or interrupted write that leaves a partial string is rejected and the
 * gate stays locked instead of reading a corrupted streak.
 *
 * Spec Ref: Phase 2 §38
 */
class SharedPreferencesPaperTrialEvidenceStore(context: Context) : PaperTrialEvidenceStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_EVIDENCE, null)

    override fun write(payload: String) {
        prefs.edit().putString(KEY_EVIDENCE, payload).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_EVIDENCE).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "paper_trial_evidence_prefs"
        const val KEY_EVIDENCE = "paper_trial_evidence_v1"
    }
}
