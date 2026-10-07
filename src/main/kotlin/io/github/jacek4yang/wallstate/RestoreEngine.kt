package io.github.jacek4yang.wallstate

import android.graphics.Rect
import java.io.File

/**
 * Transactional exact restore.
 *
 * Sequence (spec order): validate the archive completely, check device compatibility and
 * capabilities, take and validate an internal rollback backup of the CURRENT state, apply
 * the restore, re-read and verify the device state; on any failure roll the device back
 * automatically and verify the rollback.
 */
class RestoreEngine(private val bridge: WallpaperBridge) {

    fun restore(archiveFile: File, out: (String) -> Unit = {}): RestoreResult {
        // 1. Full archive validation before touching anything.
        val manifest = ArchiveReader(archiveFile).use { reader -> reader.verify() }

        // 2. Compatibility and capability prechecks (before mutation).
        checkCompatible(manifest)
        checkCapabilities(manifest, out)

        val userId = AndroidEnv.currentUser()
        if (manifest.userId != userId) {
            out("note: archive was created for user ${manifest.userId}, restoring onto current user $userId")
        }

        // 3. Internal exact backup of the current state, used for rollback.
        val rollbackFile = File(AndroidEnv.tmpDir(), "rollback-${System.currentTimeMillis()}.zip")
        var restoreFailed: Throwable? = null
        var success = false
        try {
            out("creating rollback backup of current state")
            val rollbackManifest = try {
                BackupEngine(bridge).backup(rollbackFile)
            } catch (e: Throwable) {
                throw WallstateException(
                    "could not create a validated rollback backup of the current wallpaper state; " +
                        "refusing to restore (${e.message})", e,
                )
            }
            out("rollback backup validated: ${rollbackFile.name}")

            // 4-5. Apply the restore.
            try {
                apply(manifest, archiveFile, userId, out)
            } catch (e: Throwable) {
                restoreFailed = e
            }

            // 6. Verify device state (only meaningful if apply did not fail outright).
            var failures: List<Verify.Check> = emptyList()
            if (restoreFailed == null) {
                settle(out)
                failures = Verify.verifyDeviceState(bridge, manifest, archiveFile, userId).filter { !it.ok }
                if (failures.isEmpty()) {
                    success = true
                    out("restore verified: original bytes, crop maps, lock relationship" +
                        (if (manifest.dimSupported) ", dim amount" else "") + " all match")
                    return RestoreResult(manifest, userId)
                }
            }

            // 7. Failure path: roll back automatically and verify the rollback.
            val reason = restoreFailed?.message ?: failures.joinToString("; ") { it.detail }
            out("restore failed: $reason")
            out("rolling back to previous wallpaper state")
            try {
                apply(rollbackManifest, rollbackFile, userId, out)
                settle(out)
                val rollbackFailures = Verify.verifyDeviceState(bridge, rollbackManifest, rollbackFile, userId)
                    .filter { !it.ok }
                if (rollbackFailures.isNotEmpty()) {
                    throw FatalRestoreException(
                        "restore failed and rollback also failed to verify: " +
                            rollbackFailures.joinToString("; ") { it.detail } +
                            "; rollback backup kept at $rollbackFile"
                    )
                }
            } catch (e: FatalRestoreException) {
                throw e
            } catch (e: Throwable) {
                throw FatalRestoreException(
                    "restore failed and rollback errored: ${e.message}; " +
                        "rollback backup kept at $rollbackFile", e,
                )
            }
            throw VerificationException(
                "restore verification failed; rollback succeeded (previous state restored). " +
                    "Rollback backup kept at $rollbackFile. Reason: $reason"
            )
        } finally {
            // Only remove the rollback backup when everything succeeded; on failure it is
            // the user's manual recovery copy.
            if (success && rollbackFile.exists()) rollbackFile.delete()
        }
    }

    /** Applies an archive's wallpaper state (used for both restore and rollback). */
    private fun apply(manifest: BackupManifest, archiveFile: File, userId: Int, out: (String) -> Unit) {
        val plan = RestorePlan.plan(manifest.lock.mode)
        ArchiveReader(archiveFile).use { reader ->
            out("restoring system wallpaper")
            setDestination(reader, Archive.SYSTEM_ORIGINAL, manifest.system, WallpaperFlags.SYSTEM, userId)

            if (plan.setLock) {
                val lockEntry = manifest.lock.entry
                    ?: throw ArchiveException("lock mode SEPARATE requires a lock entry")
                out("restoring separate lock wallpaper")
                setDestination(reader, Archive.LOCK_ORIGINAL, lockEntry, WallpaperFlags.LOCK, userId)
            }
        }
        if (plan.clearLock) {
            out("clearing independent lock wallpaper (returning lock to shared state)")
            bridge.clearWallpaper(WallpaperFlags.LOCK, userId)
        }
        if (manifest.dimSupported && manifest.dimAmount != null) {
            out("restoring dim amount ${manifest.dimAmount}")
            bridge.setWallpaperDimAmount(manifest.dimAmount)
        }
    }

    /**
     * Writes the archived original bytes into a fresh wallpaper file while instructing
     * the service to apply the archived exact crop map. The cropped image entry is never
     * used as restore input.
     */
    private fun setDestination(
        reader: ArchiveReader,
        entryName: String,
        entry: WallpaperEntry,
        which: Int,
        userId: Int,
    ) {
        val empty = entry.crops.isEmpty()
        val orientations = if (empty) null else entry.crops.keys.toIntArray()
        val crops = if (empty) null else entry.crops.values.map { rect ->
            Rect(rect.left, rect.top, rect.right, rect.bottom)
        }
        val write = bridge.openWallpaperWrite(
            orientations, crops, entry.allowBackup ?: true, which, userId,
        )
        try {
            reader.copyEntryTo(entryName, write.stream())
        } finally {
            write.finishAndAwait()
        }
    }

    /** Gives the service a short grace period to settle after mutations. */
    private fun settle(out: (String) -> Unit) {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun checkCompatible(manifest: BackupManifest) {
        val device = AndroidEnv.deviceInfo()
        if (!manifest.device.sameDevice(device)) {
            throw WallstateException(
                "incompatible restore target: archive was created on " +
                    "${manifest.device.manufacturer} ${manifest.device.model} (sdk ${manifest.device.sdkInt}), " +
                    "this device is ${device.manufacturer} ${device.model} (sdk ${device.sdkInt}); " +
                    "crop semantics may differ across devices/builds"
            )
        }
    }

    private fun checkCapabilities(manifest: BackupManifest, out: (String) -> Unit) {
        val missing = bridge.caps.missingRequired()
        if (missing.isNotEmpty()) {
            throw WallstateException(
                "this Android build does not provide required wallpaper APIs: $missing"
            )
        }
        if (manifest.dimSupported && manifest.dimAmount != null) {
            if (!bridge.caps.getWallpaperDimAmount || !bridge.caps.setWallpaperDimAmount) {
                throw WallstateException(
                    "archive contains dim state but this build lacks getWallpaperDimAmount/" +
                        "setWallpaperDimAmount; refusing partial restore"
                )
            }
        }
        val fingerprint = AndroidEnv.deviceInfo().fingerprint
        if (!manifest.device.fingerprint.equals(fingerprint, ignoreCase = true)) {
            out(
                "warning: build fingerprint differs (archive: ${manifest.device.fingerprint}, " +
                    "device: $fingerprint); same device model and sdk, continuing"
            )
        }
    }
}

data class RestoreResult(val manifest: BackupManifest, val userId: Int)
