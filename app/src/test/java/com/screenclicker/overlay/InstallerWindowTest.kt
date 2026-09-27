package com.screenclicker.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerWindowTest {

    @Test
    fun `vendor installer packages are recognized`() {
        // The four the app is likely to meet, plus the naming rule that covers the rest.
        assertTrue(InstallerWindow.isInstaller("com.android.packageinstaller"))
        assertTrue(InstallerWindow.isInstaller("com.google.android.packageinstaller"))
        assertTrue(InstallerWindow.isInstaller("com.samsung.android.packageinstaller"))
        assertTrue(InstallerWindow.isInstaller("com.miui.packageinstaller"))
    }

    @Test
    fun `ordinary packages are not installers`() {
        assertFalse(InstallerWindow.isInstaller("com.screenclicker"))
        assertFalse(InstallerWindow.isInstaller("com.android.settings"))
        assertFalse(InstallerWindow.isInstaller("com.google.android.permissioncontroller"))
        assertFalse(InstallerWindow.isInstaller("com.android.launcher3"))
        assertFalse(InstallerWindow.isInstaller(null))
        assertFalse(InstallerWindow.isInstaller(""))
    }
}
