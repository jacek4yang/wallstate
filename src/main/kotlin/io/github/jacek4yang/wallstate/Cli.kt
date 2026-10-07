package io.github.jacek4yang.wallstate

import java.io.File

/** Command line interface: dispatch, exit codes, and compact human-readable output. */
object Cli {
    const val EXIT_OK = 0
    const val EXIT_ERROR = 1
    const val EXIT_USAGE = 2
    const val EXIT_FATAL = 3

    fun run(args: Array<String>): Int = try {
        dispatch(args)
    } catch (e: FatalRestoreException) {
        System.err.println("FATAL: ${e.message}")
        EXIT_FATAL
    } catch (e: WallstateException) {
        System.err.println("ERROR: ${e.message}")
        EXIT_ERROR
    } catch (e: SecurityException) {
        System.err.println("ERROR: permission denied by wallpaper service: ${e.message}")
        EXIT_ERROR
    } catch (e: Throwable) {
        System.err.println("ERROR: unexpected failure: $e")
        EXIT_ERROR
    }

    private fun dispatch(args: Array<String>): Int {
        val command = args.getOrNull(0)
        val rest = args.drop(1)
        return try {
            when (command) {
                "version" -> {
                    println("wallstate $TOOL_VERSION")
                    EXIT_OK
                }
                "doctor" -> Doctor.run()
                "info" -> info()
                "backup" -> backup(requireArg(command, rest))
                "inspect" -> inspect(requireArg(command, rest))
                "verify" -> verify(requireArg(command, rest))
                "restore" -> restore(requireArg(command, rest))
                null, "-h", "--help", "help" -> usage()
                else -> {
                    System.err.println("ERROR: unknown command '$command'")
                    usage()
                }
            }
        } catch (e: UsageException) {
            System.err.println("ERROR: ${e.message}")
            usage()
        }
    }

    private class UsageException(message: String) : WallstateException(message)

    private fun requireArg(command: String, rest: List<String>): String =
        rest.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw UsageException("command '$command' requires an argument")

    private fun usage(): Int {
        println("usage: wallstate <command> [args]")
        println("  version               print tool version")
        println("  doctor                check uid, framework access, and exact backup/restore capability")
        println("  info                  show current wallpaper state of the current user")
        println("  backup <output.zip>   create an exact static wallpaper backup")
        println("  inspect <backup.zip>  show archive manifest and entries")
        println("  verify <backup.zip>   verify archive integrity (schema, entries, SHA-256)")
        println("  restore <backup.zip>  transactionally restore wallpaper state from an archive")
        return EXIT_USAGE
    }

    // ---- commands ----

    private fun info(): Int {
        AndroidEnv.requireShellUid()
        val bridge = WallpaperBridge.create()
        val userId = AndroidEnv.currentUser()
        val state = bridge.stateSummary(userId)
        println("user: $userId")
        println("system: ${if (state.systemStatic) "STATIC" else "NOT-STATIC"}${idSuffix(bridge, WallpaperFlags.SYSTEM, userId)}")
        if (state.systemLiveInfo != null) println("system info: ${state.systemLiveInfo}")
        if (state.systemStatic) printWallpaperDetails(bridge, WallpaperFlags.SYSTEM, userId)
        println(
            "lock: ${if (state.lockExists) "SEPARATE" else "INHERIT_SYSTEM"}" +
                (if (state.lockExists) idSuffix(bridge, WallpaperFlags.LOCK, userId) else "")
        )
        if (state.lockExists && state.lockStatic) printWallpaperDetails(bridge, WallpaperFlags.LOCK, userId)
        if (state.lockExists && !state.lockStatic) println("lock info: ${state.lockLiveInfo ?: "not static"}")
        val dim = bridge.readDimAmount()
        println("dim: ${if (dim != null) "%.4f".format(dim) else "unsupported"}")
        return EXIT_OK
    }

    private fun backup(outputPath: String): Int {
        AndroidEnv.requireShellUid()
        val bridge = WallpaperBridge.create()
        val engine = BackupEngine(bridge)
        val manifest = engine.backup(File(outputPath))
        val file = File(outputPath)
        println("user: ${manifest.userId}")
        println("system: STATIC sha256=${manifest.system.originalSha256} crops=${manifest.system.crops.size} entries")
        when (manifest.lock.mode) {
            LockMode.SEPARATE -> println(
                "lock: SEPARATE sha256=${manifest.lock.entry?.originalSha256} " +
                    "crops=${manifest.lock.entry?.crops?.size ?: 0} entries"
            )
            LockMode.INHERIT_SYSTEM -> println("lock: INHERIT_SYSTEM")
        }
        println("dim: ${if (manifest.dimSupported) "%.4f".format(manifest.dimAmount!!) else "unsupported"}")
        println("wrote ${file.absolutePath} (${file.length()} bytes)")
        println("integrity: OK (archive re-opened and fully verified)")
        return EXIT_OK
    }

    private fun inspect(archivePath: String): Int {
        ArchiveReader(File(archivePath)).use { reader ->
            val manifest = reader.readManifest()
            println("archive: ${File(archivePath).absolutePath}")
            println("formatVersion: ${manifest.formatVersion}")
            println("toolVersion: ${manifest.toolVersion}")
            println("createdAt: ${manifest.createdAt}")
            println("device: ${manifest.device} fingerprint=${manifest.device.fingerprint}")
            println("userId: ${manifest.userId}")
            println("dim: ${if (manifest.dimSupported) "%.4f".format(manifest.dimAmount!!) else "unsupported"}")
            println(
                "system: STATIC sha256=${manifest.system.originalSha256} " +
                    "crops=${describeCrops(manifest.system.crops)}" +
                    (manifest.system.allowBackup?.let { " allowBackup=$it" } ?: "")
            )
            when (manifest.lock.mode) {
                LockMode.SEPARATE -> {
                    val e = manifest.lock.entry
                    println(
                        "lock: SEPARATE sha256=${e?.originalSha256} " +
                            "crops=${describeCrops(e?.crops ?: emptyMap())}" +
                            (e?.allowBackup?.let { " allowBackup=$it" } ?: "")
                    )
                }
                LockMode.INHERIT_SYSTEM -> println("lock: INHERIT_SYSTEM")
            }
            println("systemWallpaperId: ${manifest.systemWallpaperId ?: "n/a"} (diagnostic)")
            println("lockWallpaperId: ${manifest.lockWallpaperId ?: "n/a"} (diagnostic)")
            println("entries:")
            for (entry in reader.entryInfos()) {
                println("  ${entry.name} (${entry.size} bytes)")
            }
            checkStructureOnly(reader)
            println("structure: OK (run 'wallstate verify' for full integrity check)")
        }
        return EXIT_OK
    }

    private fun verify(archivePath: String): Int {
        ArchiveReader(File(archivePath)).use { reader ->
            val manifest = reader.verify()
            println("OK: ${File(archivePath).name}")
            println("formatVersion: ${manifest.formatVersion}, device: ${manifest.device}")
            println("all recorded SHA-256 digests match archived bytes")
        }
        return EXIT_OK
    }

    private fun restore(archivePath: String): Int {
        AndroidEnv.requireShellUid()
        val bridge = WallpaperBridge.create()
        val result = RestoreEngine(bridge).restore(File(archivePath)) { line -> println(line) }
        println("restored wallpaper state for user ${result.userId}")
        return EXIT_OK
    }

    // ---- helpers ----

    internal fun checkStructureOnly(reader: ArchiveReader) {
        reader.checkEntryList(reader.allEntryNames(), reader.readManifest())
    }

    private fun idSuffix(bridge: WallpaperBridge, which: Int, userId: Int): String {
        val id = bridge.wallpaperId(which, userId) ?: return ""
        return " (id=$id)"
    }

    private fun printWallpaperDetails(bridge: WallpaperBridge, which: Int, userId: Int) {
        val sha = bridge.wallpaperStream(which, userId, cropped = false)?.use { Hashing.sha256(it) }
        println("  original sha256: ${sha ?: "<unavailable>"}")
        when (val crops = bridge.readCrops(which, userId)) {
            is CropRead.Data -> println(
                "  crops: " + crops.crops.entries.sortedBy { it.key }
                    .joinToString(", ") { (k, v) -> "$k=[${v.left},${v.top},${v.right},${v.bottom}]" }
            )
            is CropRead.None -> println("  crops: unavailable (${crops.reason})")
        }
    }

    private fun describeCrops(crops: Map<Int, CropRect>): String =
        if (crops.isEmpty()) "{}"
        else crops.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (k, v) -> "$k:[${v.left},${v.top},${v.right},${v.bottom}]" }
}
