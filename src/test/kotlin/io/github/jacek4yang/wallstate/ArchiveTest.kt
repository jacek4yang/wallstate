package io.github.jacek4yang.wallstate

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = createTempDir("wallstate-archive-test")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val systemBytes = ByteArray(4096) { (it * 7 % 256).toByte() }
    private val lockBytes = "lock-image-bytes".toByteArray()
    private val croppedBytes = "cropped-image-bytes".toByteArray()

    private val device = DeviceInfo("OnePlus", "CPH2609", 36, "fp")

    /** Builds a consistent archive; the manifest is derived from the actual written bytes. */
    private fun write(
        target: File,
        separateLock: Boolean,
        includeSystemCropped: Boolean = false,
        includeLockCropped: Boolean = false,
    ): BackupManifest {
        var written: BackupManifest? = null
        writeArchiveAtomically(target) { writer ->
            val systemCroppedSha = if (includeSystemCropped) {
                writer.addEntry(Archive.SYSTEM_CROPPED, Archive.MAX_IMAGE_BYTES) { croppedBytes.inputStream() }
            } else null
            val lockEntry = if (separateLock) {
                val lockCroppedSha = if (includeLockCropped) {
                    writer.addEntry(Archive.LOCK_CROPPED, Archive.MAX_IMAGE_BYTES) { "lock-crop".toByteArray().inputStream() }
                } else null
                WallpaperEntry(
                    writer.addEntry(Archive.LOCK_ORIGINAL, Archive.MAX_IMAGE_BYTES) { lockBytes.inputStream() },
                    lockCroppedSha, emptyMap(), false,
                )
            } else null
            val systemEntry = WallpaperEntry(
                writer.addEntry(Archive.SYSTEM_ORIGINAL, Archive.MAX_IMAGE_BYTES) { systemBytes.inputStream() },
                systemCroppedSha, emptyMap(), true,
            )
            val manifest = BackupManifest(
                createdAt = "2026-10-07T00:00:00Z",
                device = device,
                userId = 0,
                dimSupported = false,
                dimAmount = null,
                system = systemEntry,
                lock = if (lockEntry != null) LockState(LockMode.SEPARATE, lockEntry)
                else LockState(LockMode.INHERIT_SYSTEM, null),
                systemWallpaperId = null,
                lockWallpaperId = null,
            )
            writer.addManifest(manifest)
            written = manifest
        }
        return written!!
    }

    @Test
    fun `atomic write produces a verifiable archive`() {
        val target = File(dir, "backup.zip")
        val manifest = write(target, separateLock = false)
        assertEquals(Hashing.sha256(systemBytes), manifest.system.originalSha256)
        ArchiveReader(target).use { reader ->
            val verified = reader.verify()
            assertEquals(manifest, verified)
            assertEquals(
                setOf(Archive.MANIFEST_ENTRY, Archive.SYSTEM_ORIGINAL),
                reader.allEntryNames().toSet(),
            )
        }
    }

    @Test
    fun `separate lock archive contains all four entries`() {
        val target = File(dir, "separate.zip")
        val manifest = write(
            target,
            separateLock = true,
            includeSystemCropped = true,
            includeLockCropped = true,
        )
        assertEquals(LockMode.SEPARATE, manifest.lock.mode)
        assertEquals(Hashing.sha256(lockBytes), manifest.lock.entry?.originalSha256)
        assertEquals(Hashing.sha256(croppedBytes), manifest.system.croppedSha256)
        ArchiveReader(target).use {
            it.verify()
            assertEquals(
                setOf(
                    Archive.MANIFEST_ENTRY, Archive.SYSTEM_ORIGINAL, Archive.SYSTEM_CROPPED,
                    Archive.LOCK_ORIGINAL, Archive.LOCK_CROPPED,
                ),
                it.allEntryNames().toSet(),
            )
        }
    }

    @Test
    fun `target is untouched and no temp left when fill fails`() {
        val target = File(dir, "backup.zip")
        target.writeText("preexisting")
        try {
            writeArchiveAtomically(target) { throw WallstateException("boom") }
        } catch (expected: WallstateException) {
            // expected
        }
        assertEquals("preexisting", target.readText())
        val leftovers = dir.listFiles { f -> f.name.endsWith(".tmp") }?.toList() ?: emptyList()
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `verify detects hash mismatch`() {
        val target = File(dir, "backup.zip")
        write(target, separateLock = false)
        rewriteZip(target) { name, data ->
            if (name == Archive.SYSTEM_ORIGINAL) {
                val copy = data.copyOf()
                copy[0] = (copy[0].toInt() xor 0x41).toByte()
                copy
            } else data
        }
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(e.message!!.contains("SHA-256 mismatch"), e.message)
    }

    @Test
    fun `verify rejects unexpected entries including traversal names`() {
        for (evil in listOf("evil.txt", "../evil", "system/extra", "manifest.json/")) {
            val safe = evil.replace(Regex("[^a-zA-Z0-9]"), "_")
            val target = File(dir, "evil-$safe.zip")
            write(target, separateLock = false)
            appendEntry(target, evil, "malicious".toByteArray())
            val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
            assertTrue(e.message!!.contains("unexpected"), "$evil -> ${e.message}")
        }
    }

    @Test
    fun `duplicate entry names are rejected`() {
        val target = File(dir, "dup.zip")
        write(target, separateLock = false)
        // ZipOutputStream refuses to write duplicate names, so exercise the validator
        // with the name list a hand-crafted archive would produce.
        ArchiveReader(target).use { reader ->
            val manifest = reader.readManifest()
            val names = listOf(Archive.MANIFEST_ENTRY, Archive.SYSTEM_ORIGINAL, Archive.SYSTEM_ORIGINAL)
            val e = assertFailsWith<ArchiveException> { reader.checkEntryList(names, manifest) }
            assertTrue(e.message!!.contains("duplicate"), e.message)
        }
    }

    @Test
    fun `verify rejects SEPARATE manifest without lock entry`() {
        val target = File(dir, "nolock.zip")
        write(target, separateLock = false)
        replaceManifest(target) { json -> json.getJSONObject("lock").put("mode", "SEPARATE") }
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(
            e.message!!.contains("SEPARATE") || e.message!!.contains("missing"),
            e.message,
        )
    }

    @Test
    fun `verify rejects lock entry for INHERIT_SYSTEM`() {
        val target = File(dir, "boguslock.zip")
        write(target, separateLock = true)
        replaceManifest(target) { json ->
            json.getJSONObject("lock").put("mode", "INHERIT_SYSTEM")
            json.getJSONObject("lock").remove("entry")
        }
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(e.message!!.contains("unexpected"), e.message)
    }

    @Test
    fun `verify rejects croppedSha without cropped entry`() {
        val target = File(dir, "cropsha.zip")
        write(target, separateLock = false)
        replaceManifest(target) { json ->
            json.getJSONObject("system").put("croppedSha256", "a".repeat(64))
        }
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(e.message!!.contains("missing"), e.message)
    }

    @Test
    fun `verify rejects oversized manifest`() {
        val target = File(dir, "bigmanifest.zip")
        write(target, separateLock = false)
        replaceEntry(target, Archive.MANIFEST_ENTRY, ByteArray(2 shl 20))
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(e.message!!.contains("exceeds maximum size"), e.message)
    }

    @Test
    fun `verify rejects UTF-8 BOM manifest`() {
        val target = File(dir, "bom.zip")
        write(target, separateLock = false)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + readAllEntries(target)
            .first { it.first == Archive.MANIFEST_ENTRY }.second
        replaceEntry(target, Archive.MANIFEST_ENTRY, bom)
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(e.message!!.contains("BOM"), e.message)
    }

    @Test
    fun `verify rejects malformed manifest json`() {
        val target = File(dir, "badjson.zip")
        write(target, separateLock = false)
        replaceEntry(target, Archive.MANIFEST_ENTRY, "{ not json".toByteArray())
        assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
    }

    @Test
    fun `verify rejects unsupported formatVersion inside archive`() {
        val target = File(dir, "future.zip")
        write(target, separateLock = false)
        replaceManifest(target) { json -> json.put("formatVersion", 99) }
        val e = assertFailsWith<ArchiveException> { ArchiveReader(target).use { it.verify() } }
        assertTrue(e.message!!.contains("formatVersion"), e.message)
    }

    // ---- zip manipulation helpers ----

    private fun readAllEntries(zip: File): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                result.add(e.name to zf.getInputStream(e).readBytes())
            }
        }
        return result
    }

    private fun writeEntries(zip: File, entries: List<Pair<String, ByteArray>>) {
        val out = File(zip.parentFile, zip.name + ".rewrite")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        zip.delete()
        out.renameTo(zip)
    }

    private fun rewriteZip(zip: File, transform: (String, ByteArray) -> Any?) {
        writeEntries(zip, readAllEntries(zip).map { (name, data) ->
            when (val t = transform(name, data)) {
                null -> name to data
                is ByteArray -> name to t
                else -> name to data
            }
        })
    }

    private fun replaceEntry(zip: File, entryName: String, data: ByteArray) {
        writeEntries(zip, readAllEntries(zip).map { (name, old) ->
            if (name == entryName) name to data else name to old
        })
    }

    private fun appendEntry(zip: File, entryName: String, data: ByteArray) {
        writeEntries(zip, readAllEntries(zip) + (entryName to data))
    }

    private fun replaceManifest(zip: File, transform: (org.json.JSONObject) -> Unit) {
        val entries = readAllEntries(zip)
        val json = org.json.JSONObject(
            String(entries.first { it.first == Archive.MANIFEST_ENTRY }.second, Charsets.UTF_8),
        )
        transform(json)
        writeEntries(zip, entries.map { (name, data) ->
            if (name == Archive.MANIFEST_ENTRY) name to json.toString().toByteArray() else name to data
        })
    }
}
