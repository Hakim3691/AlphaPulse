package com.aistudio.alphapulse.ui

/**
 * Phase 1 §7: the base shell that hosts AlphaPulse and, after the simulated
 * entitlement, the shared navigation.
 *
 * The core analysis surface is a plain content slot. It is never passed the install
 * state, the download progress or a trader callback, so installing or removing
 * `:proactive_trader` cannot restyle or move anything inside it (D6).
 *
 * The bar itself is [AlphaPulseNavBar], the same composable the workspace's host uses:
 * before entitlement it is one `VIP Features` slot, afterwards the six canonical tabs
 * with `Alpha Pulse` first. The old floating auto-trader button and the redundant
 * Analysis tab are deliberately gone — they collided with the bottom cards and
 * duplicated a surface that is already the whole screen.
 *
 * Spec Ref: Phase 1 §7, D5, D6, Directive §3 Bottom Bar, C-57
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.alphapulse.contract.ModuleInstallState
import com.aistudio.alphapulse.ui.theme.BrandDark

@Composable
fun AppShell(
    installState: ModuleInstallState,
    downloadProgress: Int,
    onUnlockTrader: () -> Unit,
    onTraderTab: () -> Unit,
    onSelectTab: (ProactiveMsTab) -> Unit = { if (it != ProactiveMsTab.ALPHA_PULSE) onTraderTab() },
    modifier: Modifier = Modifier,
    coreContent: @Composable () -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BrandDark,
        // The AlphaPulse screen applies the status-bar inset itself; the shell only
        // pays for the bar it adds.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AlphaPulseNavBar(
                installState = installState,
                downloadProgress = downloadProgress,
                activeTab = ProactiveMsTab.ALPHA_PULSE,
                onSelectTab = onSelectTab,
                onUnlockVip = onUnlockTrader
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(TraderNavigation.CORE_SURFACE_TAG)
        ) {
            coreContent()
        }
    }
}
