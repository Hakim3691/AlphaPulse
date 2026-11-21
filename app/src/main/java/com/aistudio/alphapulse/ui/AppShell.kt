package com.aistudio.alphapulse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphapulse.contract.ModuleInstallState
import com.aistudio.alphapulse.ui.theme.BrandDark
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary

/**
 * Phase 1 §7: the base shell that hosts AlphaPulse and, after the simulated
 * entitlement, the Auto-Trader navigation entry.
 *
 * The core analysis surface is a plain content slot. It is never passed the install
 * state, the download progress or a trader callback, so installing or removing
 * `:proactive_trader` cannot restyle or move anything inside it (D6).
 *
 * There is one bar entry, never two: before entitlement it is the `Enable VIP
 * Features` call to action that opens the emulated entitlement / dynamic-module
 * unlock flow; afterwards the same slot becomes the trader tab with its honest
 * progress label. The old floating auto-trader button and the redundant Analysis
 * tab are deliberately gone — they collided with the bottom cards and duplicated a
 * surface that is already the whole screen.
 *
 * Spec Ref: Phase 1 §7, D5, D6, Directive §3 Bottom Bar
 */
@Composable
fun AppShell(
    installState: ModuleInstallState,
    downloadProgress: Int,
    onUnlockTrader: () -> Unit,
    onTraderTab: () -> Unit,
    modifier: Modifier = Modifier,
    coreContent: @Composable () -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BrandDark,
        // The AlphaPulse screen applies the safe-drawing insets itself; the shell only
        // pays for the bar it adds.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("app_bottom_nav"),
                containerColor = CardBackground,
                contentColor = SlateTextPrimary
            ) {
                if (TraderNavigation.isTraderTabVisible(installState)) {
                    NavigationBarItem(
                        selected = true,
                        onClick = onTraderTab,
                        icon = { Icon(Icons.Default.Bolt, contentDescription = "Auto-Trader") },
                        label = {
                            Text(
                                TraderNavigation.label(installState, downloadProgress),
                                fontSize = 9.sp,
                                color = CyanAccent
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color(0xFF1E293B)
                        ),
                        modifier = Modifier.testTag(TraderNavigation.TRADER_TAB_TAG)
                    )
                } else {
                    NavigationBarItem(
                        selected = false,
                        onClick = onUnlockTrader,
                        icon = {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = "Enable VIP Features"
                            )
                        },
                        label = {
                            Text(
                                "Enable VIP Features",
                                fontSize = 9.sp,
                                color = SlateTextMuted
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color(0xFF1E293B)
                        ),
                        modifier = Modifier.testTag(TraderNavigation.VIP_TAB_TAG)
                    )
                }
            }
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
