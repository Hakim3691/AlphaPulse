package com.aistudio.alphapulse.ui

/**
 * The one bottom bar the whole app shares.
 *
 * Why this exists
 * ---------------
 * The base app drew its own one-slot bar (`AppShell`) and the ProactiveMS workspace drew
 * a second, six-slot bar (`ProactiveBottomBar`). That is two navigations for one product:
 * the VIP slot disappeared while the workspace was open, and the workspace tabs did not
 * exist until you were already inside the workspace, so a user could not move from the
 * analysis screen to a specific tab in one tap.
 *
 * There is now one bar, in one place, with two states:
 *
 *  - **before entitlement** — exactly one slot, `VIP Features`, which opens the emulated
 *    unlock flow. The workspace tabs do not exist yet, so they are not rendered at all;
 *    hiding them behind a disabled style would still advertise a surface the build cannot
 *    open.
 *  - **once entitled** — the six canonical tabs from
 *    [ProactiveMsWorkspaceState.TAB_ORDER], with the first slot labelled `Alpha Pulse`.
 *    `TraderNavigation.label` keeps the honest download/install wording while the module
 *    is still arriving.
 *
 * The tab bar itself is [AlphaPulseTabBar], which the ProactiveMS workspace also mounts,
 * so a label or an order can never differ between the two hosts.
 *
 * Spec Ref: Phase 1 §7, Phase 2 §32/§72, C-22, C-33, C-57
 */

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphapulse.contract.ModuleInstallState
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary

/** The six-tab bar, with no entitlement logic: both hosts render the same thing. */
@Composable
fun AlphaPulseTabBar(
    activeTab: ProactiveMsTab,
    onSelectTab: (ProactiveMsTab) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "alpha_pulse_nav_bar"
) {
    NavigationBar(
        modifier = modifier.testTag(tag),
        containerColor = CardBackground,
        contentColor = SlateTextPrimary,
        tonalElevation = 0.dp
    ) {
        ProactiveMsWorkspaceState.TAB_ORDER.forEach { tab ->
            val selected = tab == activeTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = "${tab.navLabel} tab",
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.navLabel,
                        fontSize = 8.sp,
                        maxLines = 1,
                        color = if (selected) CyanAccent else SlateTextMuted
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color(0xFF1E293B),
                    selectedIconColor = CyanAccent,
                    unselectedIconColor = SlateTextMuted
                ),
                modifier = Modifier.testTag(ProactiveWorkspaceTags.tab(tab))
            )
        }
    }
}

/**
 * The lifecycle-aware bar the base shell mounts.
 *
 * It is the only place the entitlement decides what navigation exists, so no host has to
 * duplicate that decision.
 */
@Composable
fun AlphaPulseNavBar(
    installState: ModuleInstallState,
    downloadProgress: Int,
    activeTab: ProactiveMsTab,
    onSelectTab: (ProactiveMsTab) -> Unit,
    onUnlockVip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val action = TraderNavigation.action(installState)

    if (action != TraderTabAction.LAUNCH) {
        NavigationBar(
            modifier = modifier.testTag("app_bottom_nav"),
            containerColor = CardBackground,
            contentColor = SlateTextPrimary
        ) {
            val entitled = TraderNavigation.isTraderTabVisible(installState)
            NavigationBarItem(
                selected = false,
                onClick = onUnlockVip,
                icon = { Icon(Icons.Default.Bolt, contentDescription = "VIP Features") },
                label = {
                    Text(
                        text = if (entitled) {
                            TraderNavigation.label(installState, downloadProgress)
                        } else {
                            "VIP Features"
                        },
                        fontSize = 9.sp,
                        color = if (entitled) CyanAccent else SlateTextMuted
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color(0xFF1E293B)
                ),
                modifier = Modifier.testTag(
                    if (entitled) TraderNavigation.TRADER_TAB_TAG else TraderNavigation.VIP_TAB_TAG
                )
            )
        }
        return
    }

    AlphaPulseTabBar(
        activeTab = activeTab,
        onSelectTab = onSelectTab,
        modifier = modifier.testTag("app_bottom_nav"),
        tag = "alpha_pulse_nav_bar"
    )
}

/** The slot's name in the shared bar: the selector is called `Alpha Pulse` once unlocked. */
val ProactiveMsTab.navLabel: String
    get() = if (this == ProactiveMsTab.ALPHA_PULSE) "Alpha Pulse" else label

internal fun ProactiveMsTab.icon(): ImageVector = when (this) {
    ProactiveMsTab.ALPHA_PULSE -> Icons.Default.Bolt
    ProactiveMsTab.DASHBOARD -> Icons.Default.Dashboard
    ProactiveMsTab.TELEMETRY -> Icons.Default.Sensors
    ProactiveMsTab.GUARDRAILS -> Icons.Default.Security
    ProactiveMsTab.REPLAY -> Icons.Default.Insights
    ProactiveMsTab.AUDIT -> Icons.Default.History
}
