package com.aistudio.alphapulse.data.local

import android.content.Context
import android.content.SharedPreferences
import com.aistudio.alphapulse.data.model.ExecutionMode

/**
 * C-63 — the execution mode is a persisted setting, not a process variable.
 *
 * The mode used to reset to Paper on every launch, so an operator who had chosen Shadow
 * or Capped Live silently got Paper's behaviour back. Only a known enum name is
 * restored; anything else falls back to Paper rather than inventing a mode.
 *
 * Spec Ref: §36, C-63
 */
class ExecutionModeStore(private val prefs: SharedPreferences) {

    fun load(): ExecutionMode {
        val stored = prefs.getString(KEY_MODE, null) ?: return ExecutionMode.PAPER
        return ExecutionMode.entries.firstOrNull { it.name == stored } ?: ExecutionMode.PAPER
    }

    fun save(mode: ExecutionMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        const val PREFS = "execution_mode_prefs"
        const val KEY_MODE = "execution_mode"

        fun from(context: Context): ExecutionModeStore =
            ExecutionModeStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
