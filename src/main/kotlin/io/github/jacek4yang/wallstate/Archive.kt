package io.github.jacek4yang.wallstate

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Versioned ZIP archive of exact wallpaper state.
 *
 * Restore archives are treated as untrusted input: entries are read by streaming,
 * never extracted to filesystem paths, and every field, size, and hash is validated.
 */
object Archive {
    const val MANIFEST_ENTRY = "manifest.json"
    const val SYSTEM_ORIGINAL = "system/original"
    const val SYSTEM_CROPPED = "system/cropped"
    const val LOCK_ORIGINAL = "lock/original"
    const val LOCK_CROPPED = "lock/cropped"

    const val MAX_MANIFEST_BYTES: Long = 1L shl 20
    const val MAX_IMAGE_BYTES: Long = 512L shl 20
    const val MAX_ENTRIES = 16

    fun imageEntryName(which: Int, cropped: Boolean): String = when (which) {
        WallpaperFlags.SYSTEM -> if (cropped) SYSTEM_CROPPED else SYSTEM_ORIGINAL
        WallpaperFlags.LOCK -> if (cropped) LOCK_CROPPED else LOCK_ORIGINAL
        else -> throw IllegalArgumentException("invalid wallpaper flag $which")
    }

    /** Entries a valid archive must contain, given its manifest. */
    fun expectedEntries(manifest: BackupManifest): Set<String> {
        val entries = mutableSetOf(MANIFEST_ENTRY, SYSTEM_ORIGINAL)
        if (manifest.system.croppedSha256 != null) entries += SYSTEM_CROPPED
        if (manifest.lock.mode == LockMode.SEPARATE) {
            entries += LOCK_ORIGINAL
            if (manifest.lock.entry?.croppedSha256 != null) entries += LOCK_CROPPED
        }
        return entries
    }
}

/** Wallpaper flag values mirroring WallpaperManager.FLAG_SYSTEM / FLAG_LOCK (1 and 2). */
object WallpaperFlags {
    const val SYSTEM = 1
    const val LOCK = 2
}

/** Streaming reader over an archive with structural and content validation. */
class ArchiveReader(file: File) : Closeable {
    private val zip = ZipFile(file)

    override fun close() = zip.close()

    fun allEntryNames(): List<String> {
        val names = ArrayList<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) names.add(entries.nextElement().name)
        return names
    }

    data class EntryInfo(val name: String, val size: Long, val compressedSize: Long)

    fun entryInfos(): List<EntryInfo> {
        val infos = ArrayList<EntryInfo>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            infos.add(EntryInfo(e.name, e.size, e.compressedSize))
        }
        return infos
    }

    /** Parses and validates manifest.json. */
    fun readManifest(): BackupManifest {
        val entry = zip.getEntry(Archive.MANIFEST_ENTRY)
            ?: throw ArchiveException("archive is missing ${Archive.MANIFEST_ENTRY}")
        if (entry.isDirectory) throw ArchiveException("${Archive.MANIFEST_ENTRY} must be a file")
        val bytes = readCapped(entry, Archive.MAX_MANIFEST_BYTES)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            throw ArchiveException("${Archive.MANIFEST_ENTRY} must not start with a UTF-8 BOM")
        }
        return BackupManifest.fromJson(String(bytes, StandardCharsets.UTF_8))
    }

    /** Opens an image entry for streaming; enforces the size cap while reading. */
    fun openEntry(name: String): InputStream {
        val entry = zip.getEntry(name) ?: throw ArchiveException("archive is missing $name")
        if (entry.isDirectory) throw ArchiveException("$name must be a file")
        if (entry.size > Archive.MAX_IMAGE_BYTES) {
            throw ArchiveException("$name exceeds maximum size ${Archive.MAX_IMAGE_BYTES}")
        }
        return try {
            CappedInputStream(zip.getInputStream(entry), name, Archive.MAX_IMAGE_BYTES)
        } catch (e: ArchiveException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException("cannot read archive entry $name: ${e.message}")
        }
    }

    fun sha256Of(name: String): String = openEntry(name).use { stream ->
        try {
            Hashing.sha256(stream)
        } catch (e: ArchiveException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException("failed reading archive entry $name: ${e.message}")
        }
    }

    /**
     * Full integrity validation: entry names (no duplicates, no unexpected or missing
     * entries), strict manifest schema, and SHA-256 of every recorded payload.
     * Returns the validated manifest.
     */
    fun verify(): BackupManifest {
        val manifest = readManifest()
        checkEntryList(allEntryNames(), manifest)
        checkHash(Archive.SYSTEM_ORIGINAL, manifest.system.originalSha256)
        manifest.system.croppedSha256?.let { checkHash(Archive.SYSTEM_CROPPED, it) }
        if (manifest.lock.mode == LockMode.SEPARATE) {
            val lockEntry = manifest.lock.entry
                ?: throw ArchiveException("lock mode SEPARATE requires a lock entry")
            checkHash(Archive.LOCK_ORIGINAL, lockEntry.originalSha256)
            lockEntry.croppedSha256?.let { checkHash(Archive.LOCK_CROPPED, it) }
        }
        return manifest
    }

    /** Validates a complete entry name list: count, duplicates, unexpected, missing. */
    fun checkEntryList(names: List<String>, manifest: BackupManifest) {
        if (names.size > Archive.MAX_ENTRIES) {
            throw ArchiveException("archive has too many entries: ${names.size}")
        }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ArchiveException("archive contains duplicate entries: ${duplicates.sorted()}")
        }
        val expected = Archive.expectedEntries(manifest)
        val actual = names.toSet()
        val unexpected = (actual - expected).sorted()
        if (unexpected.isNotEmpty()) {
            throw ArchiveException("archive contains unexpected entries: $unexpected")
        }
        val missing = (expected - actual).sorted()
        if (missing.isNotEmpty()) {
            throw ArchiveException("archive is missing entries: $missing")
        }
    }

    /** Streams the entry into [out] without writing anything to the filesystem. */
    fun copyEntryTo(name: String, out: OutputStream) {
        openEntry(name).use { input -> input.copyTo(out) }
    }

    private fun readCapped(entry: ZipEntry, cap: Long): ByteArray {
        if (entry.size > cap) {
            throw ArchiveException("${Archive.MANIFEST_ENTRY} exceeds maximum size $cap")
        }
        zip.getInputStream(entry).use {
            return try {
                CappedInputStream(it, entry.name, cap).readBytes()
            } catch (e: ArchiveException) {
                throw e
            } catch (e: IOException) {
                throw ArchiveException("failed reading ${entry.name}: ${e.message}")
            }
        }
    }

    private fun checkHash(name: String, expected: String) {
        val actual = sha256Of(name)
        if (actual != expected) {
            throw ArchiveException(
                "archived $name SHA-256 mismatch: manifest says $expected, archive contains $actual"
            )
        }
    }
}

/** InputStream that fails closed once more than [cap] bytes are read. */
private class CappedInputStream(
    private val delegate: InputStream,
    private val name: String,
    private val cap: Long,
) : InputStream() {
    private var total = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    override fun close() = delegate.close()

    private fun count(n: Long) {
        total += n
        if (total > cap) {
            throw ArchiveException("archive entry $name exceeds maximum size $cap (decompression bomb?)")
        }
    }
}

/** Streaming archive writer; entries are hashed while being written. */
class ArchiveWriter(private val out: ZipOutputStream) : Closeable {
    override fun close() = out.close()

    /**
     * Adds an entry by streaming from [source] (opened inside, closed by this call),
     * returning its SHA-256. Fails if the source exceeds [maxBytes].
     */
    fun addEntry(name: String, maxBytes: Long, source: () -> InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        out.putNextEntry(ZipEntry(name))
        val buf = ByteArray(128 * 1024)
        var total = 0L
        try {
            source().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw ArchiveException("$name exceeds maximum size $maxBytes")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        } finally {
            out.closeEntry()
        }
        return Hashing.sha256HexOf(digest)
    }

    fun addManifest(manifest: BackupManifest) {
        val bytes = manifest.toJson().toString(2).toByteArray(StandardCharsets.UTF_8)
        out.putNextEntry(ZipEntry(Archive.MANIFEST_ENTRY))
        out.write(bytes)
        out.closeEntry()
    }
}

/**
 * Writes the archive atomically: streams entries into `target.tmp`, fsyncs,
 * reopens and fully validates the temporary archive, and only then renames it
 * onto [target]. On any failure the temporary file is removed and [target] is
 * left untouched.
 */
fun writeArchiveAtomically(target: File, fill: (ArchiveWriter) -> Unit) {
    val parent = target.absoluteFile.parentFile
    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
        throw WallstateException("cannot create directory ${parent.absolutePath}")
    }
    val tmp = File(parent ?: File("."), target.name + ".tmp")
    try {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zos ->
            fill(ArchiveWriter(zos))
        }
        RandomAccessFile(tmp, "rw").use { it.channel.force(true) }
        ArchiveReader(tmp).use { reader -> reader.verify() }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}
