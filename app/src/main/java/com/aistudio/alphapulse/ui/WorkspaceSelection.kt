package com.aistudio.alphapulse.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * C-62 — the workspace's one coin selection.
 *
 * AlphaPulse's institutional screen is the only selector (C-22). It used to publish its
 * pick into its own view model while the campaign engine booted and re-chose the first
 * row of its own universe, so the Dashboard could show `SOLUSDT` while AlphaPulse showed
 * another coin. The selection now lives in exactly one process-wide store: AlphaPulse
 * writes it, the engine reads it on boot and on every refresh, and the workspace mirrors
 * it. Nothing else may choose a coin.
 *
 * The selection is persisted (`workspace_selection_prefs`) so a process restart reopens
 * the desk on the coin the operator left it on. Only a known-good, non-blank symbol is
 * restored; anything else falls back to "no selection yet", which the resolve rule below
 * turns into the leading admitted row.
 *
 * [resolveSymbol] is the one rule for turning that selection into a coin to inspect:
 * the selected coin when it is admitted, the first admitted row only when nothing has
 * been selected, and `null` when the selected coin is not in the universe — never a
 * silent substitution.
 *
 * Spec Ref: §72, C-22, C-58, C-62
 */
object WorkspaceSelection {

    /**
     * @param selected the symbol the operator selected, or null before the first pick
     * @param available the symbols the current universe admits, in rank order
     * @return the symbol to inspect, or null when [selected] names a coin the universe
     *   does not admit (the caller then reloads rather than repointing the desk)
     */
    fun resolveSymbol(selected: String?, available: List<String>): String? {
        val wanted = selected?.takeIf { it.isNotBlank() }
        if (wanted != null) return wanted.takeIf { symbol -> available.any { it == symbol } }
        return available.firstOrNull()
    }

    /**
     * Keeps the inspected board across a universe re-plan: by id when it survived, the
     * leading board when it did not, and nothing when there are no boards.
     */
    fun keepCampaign(currentId: String?, nextIds: List<String>): String? =
        currentId?.takeIf { id -> nextIds.any { it == id } } ?: nextIds.firstOrNull()
}

/** Where a selection is remembered between processes. Deliberately tiny and fakeable. */
interface SelectionPersistence {
    fun load(): String?
    fun save(symbol: String)
}

/** The persisted payload. Only a non-blank symbol is ever written or read back. */
private class PreferencesSelectionPersistence(
    private val prefs: SharedPreferences
) : SelectionPersistence {

    override fun load(): String? = prefs.getString(KEY_SYMBOL, null)?.takeIf { it.isNotBlank() }

    override fun save(symbol: String) {
        prefs.edit().putString(KEY_SYMBOL, symbol).apply()
    }

    companion object {
        const val PREFS = "workspace_selection_prefs"
        const val KEY_SYMBOL = "selected_symbol"
    }
}

/**
 * The process-wide selected symbol.
 *
 * @param persistence optional backing store; the shared instance is attached to
 *   SharedPreferences by the first view model that owns an `Application`
 */
class WorkspaceSelectionStore(
    private var persistence: SelectionPersistence? = null
) {

    private val _selectedSymbol = MutableStateFlow(
        persistence?.load()?.takeIf { it.isNotBlank() }
    )
    val selectedSymbol: StateFlow<String?> = _selectedSymbol.asStateFlow()

    /** Records a selection. A blank value is not a selection and changes nothing. */
    fun select(symbol: String) {
        if (symbol.isBlank()) return
        if (_selectedSymbol.value == symbol) return
        _selectedSymbol.value = symbol
        persistence?.save(symbol)
    }

    /**
     * Backs [shared] with the app's preferences once, keeping any in-memory selection
     * that is newer than the stored one.
     */
    fun attach(context: Context) {
        if (persistence != null) return
        val stored = PreferencesSelectionPersistence(
            context.getSharedPreferences(
                PreferencesSelectionPersistence.PREFS,
                Context.MODE_PRIVATE
            )
        )
        persistence = stored
        if (_selectedSymbol.value == null) {
            _selectedSymbol.value = stored.load()
        }
    }

    companion object {
        /** The one instance the app's hosts read and write. */
        val shared: WorkspaceSelectionStore by lazy { WorkspaceSelectionStore() }
    }
}
