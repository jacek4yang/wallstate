package io.github.jacek4yang.wallstate

/**
 * Observed wallpaper state of the current user, as reported by WallpaperManagerService.
 */
data class DeviceWallpaperState(
    val systemStatic: Boolean,
    /** Non-null when the system wallpaper is a live wallpaper component (diagnostic string). */
    val systemLiveInfo: String?,
    val lockExists: Boolean,
    val lockStatic: Boolean,
    /** Non-null when the lock wallpaper exists but is a live wallpaper component. */
    val lockLiveInfo: String?,
) {
    val lockMode: LockMode
        get() = if (lockExists) LockMode.SEPARATE else LockMode.INHERIT_SYSTEM

    /** A precise reason this state is unsupported for exact backup, or null if supported. */
    fun backupProblem(): String? = when {
        !systemStatic && systemLiveInfo != null ->
            "system wallpaper is live ($systemLiveInfo); v1.0.0 exact backup supports static wallpaper only"
        !systemStatic ->
            "system wallpaper is not a static image wallpaper; exact backup is not possible"
        lockExists && !lockStatic && lockLiveInfo != null ->
            "lock wallpaper is live ($lockLiveInfo); v1.0.0 exact backup supports static wallpaper only"
        lockExists && !lockStatic ->
            "lock wallpaper is not a static image wallpaper; exact backup is not possible"
        else -> null
    }

    /** True when this device state is already exactly what the given archive describes. */
    fun matches(manifest: BackupManifest): Boolean =
        lockMode == manifest.lock.mode
}

/**
 * The set of wallpaper mutations a restore must perform.
 *
 * Framework behavior this accounts for (AOSP WallpaperManagerService.setWallpaper):
 * when only the SYSTEM wallpaper is set while lock currently shares it, the service
 * migrates the shared image to lock-only before the new system image is written.
 * Therefore restoring an INHERIT_SYSTEM archive always ends with clearing the lock
 * wallpaper, which returns the lock screen to the shared/inherited state.
 */
data class RestorePlan(
    val setLock: Boolean,
    val clearLock: Boolean,
) {
    companion object {
        fun plan(archiveMode: LockMode): RestorePlan = when (archiveMode) {
            LockMode.SEPARATE -> RestorePlan(setLock = true, clearLock = false)
            LockMode.INHERIT_SYSTEM -> RestorePlan(setLock = false, clearLock = true)
        }
    }
}
