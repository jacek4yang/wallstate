package io.github.jacek4yang.wallstate

import java.io.File
import java.time.Instant

/** Captured state of a single wallpaper destination during backup. */
data class CapturedWallpaper(
    val originalSha256: String,
    val croppedSha256: String?,
    val crops: Map<Int, CropRect>,
    val allowBackup: Boolean?,
) {
    fun toEntry(): WallpaperEntry =
        WallpaperEntry(originalSha256, croppedSha256, crops, allowBackup)
}

/**
 * Performs an exact static wallpaper backup.
 *
 * Guarantees enforced here:
 *  - only static wallpapers are accepted (live/dynamic state is rejected with a reason);
 *  - the original wallpaper data is streamed byte-for-byte from the service (never
 *    decoded or recompressed) and SHA-256 is computed while copying;
 *  - the archive is written to a temporary file, fully re-read and verified, and only
 *    then atomically renamed onto the requested target.
 */
class BackupEngine(private val bridge: WallpaperBridge) {

    fun backup(target: File): BackupManifest {
        val userId = AndroidEnv.currentUser()
        val state = bridge.stateSummary(userId)
        val problem = state.backupProblem()
        if (problem != null) throw UnsupportedStateException(problem)

        var manifest: BackupManifest? = null
        writeArchiveAtomically(target) { writer ->
            val system = capture(writer, WallpaperFlags.SYSTEM, userId, "system")
            val lock = if (state.lockExists) capture(writer, WallpaperFlags.LOCK, userId, "lock") else null
            val dim = bridge.readDimAmount()
            manifest = BackupManifest(
                createdAt = Instant.now().toString(),
                device = AndroidEnv.deviceInfo(),
                userId = userId,
                dimSupported = dim != null,
                dimAmount = dim,
                system = system.toEntry(),
                lock = if (lock != null) LockState(LockMode.SEPARATE, lock.toEntry())
                else LockState(LockMode.INHERIT_SYSTEM, null),
                systemWallpaperId = bridge.wallpaperId(WallpaperFlags.SYSTEM, userId),
                lockWallpaperId = if (state.lockExists) bridge.wallpaperId(WallpaperFlags.LOCK, userId) else null,
            )
            writer.addManifest(manifest!!)
        }
        return manifest ?: throw WallstateException("backup produced no manifest")
    }

    /** Streams one destination's original (and cropped, if any) data into the archive. */
    private fun capture(writer: ArchiveWriter, which: Int, userId: Int, label: String): CapturedWallpaper {
        val originalSha = writer.addEntry(Archive.imageEntryName(which, cropped = false), Archive.MAX_IMAGE_BYTES) {
            bridge.wallpaperStream(which, userId, cropped = false)
                ?: throw WallstateException("original $label wallpaper missing from wallpaper service")
        }

        val croppedSha = bridge.wallpaperStream(which, userId, cropped = true)?.let { cropped ->
            writer.addEntry(Archive.imageEntryName(which, cropped = true), Archive.MAX_IMAGE_BYTES) { cropped }
        }

        val crops = when (val read = bridge.readCrops(which, userId)) {
            is CropRead.Data -> read.crops.mapValues { (_, rect) ->
                CropRect(rect.left, rect.top, rect.right, rect.bottom)
            }
            is CropRead.None -> throw WallstateException(
                "exact crop state of $label wallpaper unavailable: ${read.reason}"
            )
        }
        if (crops.size > MAX_CROP_ENTRIES) {
            throw WallstateException("crop map of $label wallpaper too large: ${crops.size} entries")
        }

        return CapturedWallpaper(
            originalSha256 = originalSha,
            croppedSha256 = croppedSha,
            crops = crops,
            allowBackup = bridge.isWallpaperBackupEligible(which, userId),
        )
    }
}
