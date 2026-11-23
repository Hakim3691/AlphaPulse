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
 * The bar is drawn here rather than with `NavigationBar` because the Material bar reserves
 * 80dp plus its own item padding on every tab. Screenshots of the shipped workspace showed
 * the bar taking a sixth of the screen while the content above it was squeezed; the compact
 * bar is 48dp of content over the navigation-bar inset, with the same order, labels, icons
 * and test tags.
 *
 * The tab bar itself is [AlphaPulseTabBar], which the ProactiveMS workspace also mounts,
 * so a label or an order can never differ between the two hosts.
 *
 * Spec Ref: Phase 1 §7, Phase 2 §32/§72, C-22, C-33, C-57
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
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
    CompactNavBar(modifier = modifier, tag = tag) {
        ProactiveMsWorkspaceState.TAB_ORDER.forEach { tab ->
            NavItem(
                selected = tab == activeTab,
                label = tab.navLabel,
                icon = tab.icon(),
                tag = ProactiveWorkspaceTags.tab(tab),
                onClick = { onSelectTab(tab) }
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
        val entitled = TraderNavigation.isTraderTabVisible(installState)
        CompactNavBar(modifier = modifier, tag = "app_bottom_nav") {
            NavItem(
                selected = false,
                label = if (entitled) {
                    TraderNavigation.label(installState, downloadProgress)
                } else {
                    "VIP Features"
                },
                icon = Icons.Default.Bolt,
                tag = if (entitled) TraderNavigation.TRADER_TAB_TAG else TraderNavigation.VIP_TAB_TAG,
                tone = if (entitled) CyanAccent else SlateTextMuted,
                onClick = onUnlockVip
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

/**
 * The compact bar: one row of slots over the navigation-bar inset, no extra item padding.
 *
 * It carries the same tag it is given so the guard scenarios keep addressing the same
 * node whether the bar is the one-slot unlock bar or the six-tab bar.
 */
@Composable
private fun CompactNavBar(
    modifier: Modifier,
    tag: String,
    content: @Composable RowScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

/** One slot: a 15dp glyph over its label, with a small selected chip behind the glyph. */
@Composable
private fun RowScope.NavItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit,
    tone: Color = if (selected) CyanAccent else SlateTextMuted
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (selected) Color(0xFF1E293B) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "$label tab",
                tint = tone,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = label,
            fontSize = 8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = tone
        )
    }
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
