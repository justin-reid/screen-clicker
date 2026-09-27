package com.screenclicker.overlay

/**
 * Recognition of the system's package installer.
 *
 * Our overlay windows are `TYPE_APPLICATION_OVERLAY`, so they draw *above* a normal app's
 * dialog — and the rule editor's rectangles and the floating panel also take touches there.
 * A rectangle can cover most of the screen, so while the installer is showing, the pair of
 * them can swallow every tap meant for its Update button: the button does nothing and the
 * only way out is force-stopping the app (which is exactly the report that prompted this).
 *
 * The test is deliberately vendor-agnostic — AOSP, Google, Samsung and the various OEM
 * installers all name the package `…packageinstaller` — and a wrong answer is harmless:
 * the only thing that happens on a false positive is that our own overlays step aside.
 */
object InstallerWindow {

    /** True when [packageName] is the system's installer/update dialog. */
    fun isInstaller(packageName: String?): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        return pkg.endsWith("packageinstaller")
    }
}
