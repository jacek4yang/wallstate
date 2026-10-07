package io.github.jacek4yang.wallstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestTest {

    private val device = DeviceInfo("OnePlus", "CPH2609", 36, "OnePlus/CPH2609/OP5D25L1:16/BP2A.250605.014")

    private val systemEntry = WallpaperEntry(
        originalSha256 = "a".repeat(64),
        croppedSha256 = "b".repeat(64),
        crops = mapOf(
            0 to CropRect(0, 0, 1080, 2400),
            1 to CropRect(0, 0, 2400, 1080),
        ),
        allowBackup = true,
    )

    private val lockEntry = WallpaperEntry(
        originalSha256 = "c".repeat(64),
        croppedSha256 = null,
        crops = emptyMap(),
        allowBackup = null,
    )

    private fun manifest(lock: LockState = LockState(LockMode.SEPARATE, lockEntry)) = BackupManifest(
        createdAt = "2026-10-07T00:00:00Z",
        device = device,
        userId = 0,
        dimSupported = true,
        dimAmount = 0.25,
        system = systemEntry,
        lock = lock,
        systemWallpaperId = 42,
        lockWallpaperId = 43,
    )

    @Test
    fun `round trip preserves all fields`() {
        val original = manifest()
        val parsed = BackupManifest.fromJson(original.toJson().toString())
        assertEquals(original, parsed)
    }

    @Test
    fun `round trip without optional fields`() {
        val original = BackupManifest(
            createdAt = "t", device = device, userId = 0,
            dimSupported = false, dimAmount = null,
            system = WallpaperEntry("a".repeat(64), null, emptyMap(), null),
            lock = LockState(LockMode.INHERIT_SYSTEM, null),
            systemWallpaperId = null, lockWallpaperId = null,
        )
        val parsed = BackupManifest.fromJson(original.toJson().toString())
        assertEquals(original, parsed)
    }

    @Test
    fun `rejects unsupported formatVersion`() {
        val json = manifest().toJson().put("formatVersion", 2).toString()
        val e = assertFailsWith<ArchiveException> { BackupManifest.fromJson(json) }
        assertTrue(e.message!!.contains("formatVersion"))
    }

    @Test
    fun `rejects unknown top level field`() {
        val json = manifest().toJson().put("surprise", 1).toString()
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json) }
    }

    @Test
    fun `rejects missing required field`() {
        val json = manifest().toJson()
        json.remove("userId")
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects wrong value types`() {
        val json = manifest().toJson().put("userId", "zero").toString()
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json) }
        val json2 = manifest().toJson().put("dimSupported", "yes").toString()
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json2) }
        val json3 = manifest().toJson().put("dimAmount", "0.5").toString()
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json3) }
    }

    @Test
    fun `rejects bad sha256`() {
        val json = manifest().toJson()
        json.getJSONObject("system").put("originalSha256", "deadbeef")
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects out of range dim amount`() {
        val json = manifest().toJson().put("dimAmount", 1.5).toString()
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json) }
    }

    @Test
    fun `rejects dimSupported dimAmount conflict`() {
        val json = manifest().toJson()
        json.put("dimAmount", 0.5)
        json.put("dimSupported", false)
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects invalid crop rect`() {
        val json = manifest().toJson()
        json.getJSONObject("system").getJSONObject("crops").getJSONObject("0").put("right", -1)
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects crop rect with right before left`() {
        assertFailsWith<ArchiveException> { CropRect(10, 0, 5, 100) }
        assertFailsWith<ArchiveException> { CropRect(-1, 0, 5, 100) }
        assertFailsWith<ArchiveException> { CropRect(0, -5, 5, 100) }
    }

    @Test
    fun `rejects non integer crop keys`() {
        val json = manifest().toJson()
        val crops = json.getJSONObject("system").getJSONObject("crops")
        val rect = crops.getJSONObject("0")
        crops.remove("0")
        crops.put("portrait", rect)
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects unknown lock mode`() {
        val json = manifest().toJson()
        json.getJSONObject("lock").put("mode", "SHARED_MAYBE")
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects SEPARATE without entry`() {
        val json = manifest().toJson()
        json.getJSONObject("lock").remove("entry")
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects INHERIT_SYSTEM with entry`() {
        val json = manifest().toJson()
        json.getJSONObject("lock").put("mode", "INHERIT_SYSTEM")
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects non static kind`() {
        val json = manifest().toJson()
        json.getJSONObject("system").put("kind", "LIVE")
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `rejects out of range userId and sdk`() {
        assertFailsWith<ArchiveException> {
            BackupManifest.fromJson(manifest().toJson().put("userId", 123456).toString())
        }
        val json = manifest().toJson()
        json.getJSONObject("device").put("sdk", 0)
        assertFailsWith<ArchiveException> { BackupManifest.fromJson(json.toString()) }
    }

    @Test
    fun `device comparison requires same manufacturer model sdk`() {
        val d = device
        assertTrue(d.sameDevice(DeviceInfo("oneplus", "cph2609", 36, "other-fingerprint")))
        assertTrue(!d.sameDevice(DeviceInfo("OnePlus", "CPH2607", 36, "x")))
        assertTrue(!d.sameDevice(DeviceInfo("OnePlus", "CPH2609", 35, "x")))
        assertTrue(!d.sameDevice(DeviceInfo("Google", "CPH2609", 36, "x")))
    }
}
