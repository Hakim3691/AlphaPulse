package com.aistudio.alphapulse.ui

import com.alphapulse.contract.ModuleInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 §7 / D5: the VIP Features navigation entry is injected only from the
 * simulated entitlement lifecycle, and never before it.
 *
 * The tab is a function of state, not a boolean scattered through the UI, so these
 * scenarios pin every lifecycle transition the user can observe.
 *
 * Spec Ref: Phase 1 §7 Post-Unlock Bottom Tab Navigation, D5, D6
 */
class TraderTabInjectionBddSpec {

    @Test
    fun `given a fresh install with no entitlement when navigation is built then only the core analysis tab exists`() {
        assertFalse(TraderNavigation.isTraderTabVisible(ModuleInstallState.NOT_INSTALLED))
        assertFalse(TraderNavigation.isTraderTabVisible(ModuleInstallState.ENTITLEMENT_REQUIRED))
        assertTrue(TraderNavigation.showsUnlockButton(ModuleInstallState.NOT_INSTALLED))
        assertEquals(TraderTabAction.PURCHASE, TraderNavigation.action(ModuleInstallState.NOT_INSTALLED))
    }

    @Test
    fun `given a simulated purchase when the download is running then the trader tab appears with honest progress and does not launch`() {
        assertTrue(TraderNavigation.isTraderTabVisible(ModuleInstallState.DOWNLOADING))
        assertEquals("Trader 40%", TraderNavigation.label(ModuleInstallState.DOWNLOADING, 40))
        assertEquals(TraderTabAction.WAITING, TraderNavigation.action(ModuleInstallState.DOWNLOADING))

        assertTrue(TraderNavigation.isTraderTabVisible(ModuleInstallState.INSTALLING))
        assertEquals("Installing", TraderNavigation.label(ModuleInstallState.INSTALLING, 100))
        assertEquals(TraderTabAction.WAITING, TraderNavigation.action(ModuleInstallState.INSTALLING))
    }

    @Test
    fun `given the module is installed when the tab is tapped then it launches the feature host`() {
        assertTrue(TraderNavigation.isTraderTabVisible(ModuleInstallState.INSTALLED))
        assertEquals("VIP Features", TraderNavigation.label(ModuleInstallState.INSTALLED, 100))
        assertEquals(TraderTabAction.LAUNCH, TraderNavigation.action(ModuleInstallState.INSTALLED))
        assertFalse(TraderNavigation.showsUnlockButton(ModuleInstallState.INSTALLED))
    }

    @Test
    fun `given progress outside the valid range when the label is rendered then it is clamped`() {
        assertEquals("Trader 0%", TraderNavigation.label(ModuleInstallState.DOWNLOADING, -5))
        assertEquals("Trader 100%", TraderNavigation.label(ModuleInstallState.DOWNLOADING, 250))
    }

    @Test
    fun `given the base screen when read then it takes no trader state and cannot be restyled by the module`() {
        // D6 structural guarantee: the core screen is a content slot. If it ever
        // gained an install-state parameter the module could alter its layout, which
        // the immutable core rule forbids.
        val screen = repoFile("app/src/main/java/com/aistudio/alphapulse/ui/AlphaPulseScreen.kt").readText()
        listOf(
            "ModuleInstallState",
            "SimulatedTraderManager",
            "TraderNavigation",
            "downloadProgress"
        ).forEach { marker ->
            assertFalse(
                "AlphaPulseScreen must not depend on the VIP Features module ($marker)",
                screen.contains(marker)
            )
        }

        val shell = repoFile("app/src/main/java/com/aistudio/alphapulse/ui/AppShell.kt").readText()
        assertTrue("The shell must render the trader entry", shell.contains("VIP_TAB_TAG"))
        assertTrue(
            "The core surface must be a plain content slot",
            shell.contains("coreContent()")
        )
    }

    @Test
    fun `given no entitlement when the bottom bar is built then it offers one VIP call to action and no floating trader button`() {
        val shell = repoFile("app/src/main/java/com/aistudio/alphapulse/ui/AppShell.kt").readText()

        // Directive §3: one bar entry before entitlement, then the same slot becomes
        // the trader tab. The floating button and the redundant Analysis tab are gone.
        assertTrue("The bar must offer the VIP unlock", shell.contains("VIP_TAB_TAG"))
        assertTrue(shell.contains("Enable VIP Features"))
        assertFalse(
            "The floating ENABLE VIP FEATURES button collided with the bottom cards",
            shell.contains("ENABLE VIP FEATURES")
        )
        assertFalse(
            "The redundant Analysis tab duplicated the whole core surface",
            shell.contains("\"Analysis\"")
        )
    }

    private fun repoFile(relativePath: String): java.io.File {
        var directory: java.io.File? = java.io.File("").absoluteFile
        while (directory != null) {
            if (java.io.File(directory, "settings.gradle.kts").isFile) {
                val file = java.io.File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
