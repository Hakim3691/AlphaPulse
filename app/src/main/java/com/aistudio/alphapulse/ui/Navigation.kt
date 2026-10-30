package com.aistudio.alphapulse.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ListAlt
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
import androidx.compose.ui.unit.sp
import com.aistudio.alphapulse.ui.theme.CardBackground
import com.aistudio.alphapulse.ui.theme.CyanAccent
import com.aistudio.alphapulse.ui.theme.SlateTextMuted
import com.aistudio.alphapulse.ui.theme.SlateTextPrimary

enum class AppDestination(val route: String, val title: String, val icon: ImageVector) {
    SCREENER("screener", "Screener", Icons.Default.ListAlt),
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Dashboard),
    TELEMETRY("telemetry", "Telemetry", Icons.Default.Sensors),
    RISK("risk", "Guardrails", Icons.Default.Security),
    SIMULATION("simulation", "Replay", Icons.Default.Analytics),
    AUDIT("audit", "Audit", Icons.Default.History)
}

@Composable
fun AppBottomNavigationBar(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.testTag("app_bottom_nav"),
        containerColor = CardBackground,
        contentColor = SlateTextPrimary
    ) {
        AppDestination.values().forEach { destination ->
            val isSelected = destination == currentDestination
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.title,
                        tint = if (isSelected) CyanAccent else SlateTextMuted
                    )
                },
                label = {
                    Text(
                        text = destination.title,
                        fontSize = 10.sp,
                        color = if (isSelected) CyanAccent else SlateTextMuted
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color(0xFF1E293B)
                ),
                modifier = Modifier.testTag("nav_item_${destination.route}")
            )
        }
    }
}
