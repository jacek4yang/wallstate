package io.github.jacek4yang.wallstate

import java.io.File

/**
 * Verifies the DEVICE state after a restore against the archive manifest.
 * This is distinct from archive integrity verification (ArchiveReader.verify).
 */
object Verify {
    const val DIM_EPSILON = 1e-4

    data class Check(val ok: Boolean, val detail: String)

    /**
     * Re-reads the actual wallpaper state from WallpaperManagerService and compares it
     * with the manifest: original bytes (SHA-256), exact crop maps, lock relationship,
     * dim amount, and static-ness. Wallpaper ids are diagnostic only and are not compared.
     */
    fun verifyDeviceState(
        bridge: WallpaperBridge,
        manifest: BackupManifest,
        archiveFile: File,
        userId: Int,
    ): List<Check> {
        val checks = mutableListOf<Check>()

        run {
            val static = try {
                bridge.isStaticWallpaper(WallpaperFlags.SYSTEM)
            } catch (t: Throwable) {
                null
            }
            checks += Check(
                static == true,
                "system wallpaper is static: ${if (static == true) "yes" else "no/unverifiable"}",
            )
        }
        run {
            val expected = manifest.system.originalSha256
            val actual = readOriginalSha(bridge, WallpaperFlags.SYSTEM, userId)
            checks += Check(
                actual != null && actual.equals(expected, ignoreCase = true),
                "system original SHA-256: expected $expected, got ${actual ?: "<none>"}",
            )
        }
        run {
            val expected = manifest.system.crops
            val actual = readCropsMap(bridge, WallpaperFlags.SYSTEM, userId)
            checks += Check(
                actual != null && actual == expected,
                "system crops: expected ${describeCrops(expected)}, got ${actual?.let(::describeCrops) ?: "<unavailable>"}",
            )
        }

        when (manifest.lock.mode) {
            LockMode.SEPARATE -> {
                val lockEntry = manifest.lock.entry
                    ?: throw ArchiveException("lock mode SEPARATE requires a lock entry")
                run {
                    val static = runCatching { bridge.isStaticWallpaper(WallpaperFlags.LOCK) }
                    checks += Check(
                        static.getOrDefault(false),
                        "lock wallpaper is static: ${if (static.getOrDefault(false)) "yes" else "no"}",
                    )
                }
                run {
                    val expected = lockEntry.originalSha256
                    val actual = readOriginalSha(bridge, WallpaperFlags.LOCK, userId)
                    checks += Check(
                        actual != null && actual.equals(expected, ignoreCase = true),
                        "lock original SHA-256: expected $expected, got ${actual ?: "<none>"}",
                    )
                }
                run {
                    val expected = lockEntry.crops
                    val actual = readCropsMap(bridge, WallpaperFlags.LOCK, userId)
                    checks += Check(
                        actual != null && actual == expected,
                        "lock crops: expected ${describeCrops(expected)}, got ${actual?.let(::describeCrops) ?: "<unavailable>"}",
                    )
                }
            }
            LockMode.INHERIT_SYSTEM -> {
                run {
                    val exists = try {
                        bridge.lockScreenWallpaperExists()
                    } catch (t: Throwable) {
                        null
                    }
                    checks += Check(
                        exists == false,
                        "lock wallpaper remains inherited: ${if (exists == false) "yes" else "no/unverifiable"}",
                    )
                }
                run {
                    val check = try {
                        val stream = bridge.wallpaperStream(WallpaperFlags.LOCK, userId, cropped = false)
                        Check(
                            stream == null,
                            "no independent lock wallpaper file: ${if (stream == null) "confirmed" else "unexpectedly present"}",
                        )
                    } catch (t: Throwable) {
                        Check(false, "no independent lock wallpaper file: could not verify (${t.message})")
                    }
                    checks += check
                }
            }
        }

        if (manifest.dimSupported && manifest.dimAmount != null) {
            val expected = manifest.dimAmount
            val actual = bridge.readDimAmount()
            checks += Check(
                actual != null && kotlin.math.abs(actual - expected) <= DIM_EPSILON,
                "dim amount: expected $expected, got ${actual ?: "<unavailable>"}",
            )
        }

        return checks
    }

    private fun readOriginalSha(bridge: WallpaperBridge, which: Int, userId: Int): String? =
        bridge.wallpaperStream(which, userId, cropped = false)?.use { stream -> Hashing.sha256(stream) }

    private fun readCropsMap(bridge: WallpaperBridge, which: Int, userId: Int): Map<Int, CropRect>? =
        when (val read = bridge.readCrops(which, userId)) {
            is CropRead.Data -> read.crops.mapValues { (_, rect) -> CropRect(rect.left, rect.top, rect.right, rect.bottom) }
            is CropRead.None -> null
        }

    private fun describeCrops(crops: Map<Int, CropRect>): String =
        if (crops.isEmpty()) "{}"
        else crops.entries.sortedBy { it.key }
            .joinToString(", ", "{", "}") { (k, v) -> "$k:[${v.left},${v.top},${v.right},${v.bottom}]" }
}
