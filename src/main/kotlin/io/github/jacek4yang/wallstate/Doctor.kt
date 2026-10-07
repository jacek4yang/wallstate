package io.github.jacek4yang.wallstate

/**
 * Reports environment, framework access, and whether exact backup/restore is
 * possible right now on this device. Exits non-zero if any check fails.
 */
object Doctor {

    fun run(): Int {
        var allOk = true
        fun line(label: String, value: String, ok: Boolean? = null) {
            println("$label: $value")
            if (ok == false) allOk = false
        }

        println("wallstate $TOOL_VERSION")

        val uid = AndroidEnv.uid()
        val shell = uid == AndroidEnv.SHELL_UID
        line("uid", "$uid (${if (shell) "shell" else "NOT shell"})", shell)

        val device = AndroidEnv.deviceInfo()
        line("sdk", device.sdkInt.toString(), true)
        line("device", "${device.manufacturer} ${device.model}", true)
        line("fingerprint", device.fingerprint, true)
        val userId = try {
            AndroidEnv.currentUser().also { line("user", it.toString(), true) }
        } catch (t: Throwable) {
            allOk = false
            line("user", "unavailable (${t.message})", false)
            0
        }

        val bridge = try {
            WallpaperBridge.create()
        } catch (t: Throwable) {
            line("wallpaper service", "UNAVAILABLE (${t.message})", false)
            line("framework methods", "unknown (service unavailable)", false)
            line("exact backup", "NO (wallpaper service unavailable)", false)
            line("exact restore", "NO (wallpaper service unavailable)", false)
            return if (allOk) Cli.EXIT_OK else Cli.EXIT_ERROR
        }
        line("wallpaper service", "OK", true)

        val caps = bridge.caps
        val missing = caps.missingRequired()
        if (missing.isEmpty()) {
            line("framework methods", "OK", true)
        } else {
            line("framework methods", "MISSING: ${missing.joinToString(", ")}", false)
        }
        line("crop state read", caps.cropReadApi()?.let { "OK ($it)" } ?: "NO API FOUND", caps.cropReadApi() != null)

        val state = try {
            bridge.stateSummary(userId)
        } catch (t: Throwable) {
            line("system", "UNKNOWN (${t.message})", false)
            line("lock", "UNKNOWN (${t.message})", false)
            line("exact backup", "NO (state unreadable)", false)
            line("exact restore", "NO (state unreadable)", false)
            return if (allOk) Cli.EXIT_OK else Cli.EXIT_ERROR
        }

        // Original read probe: non-mutating; also exercises READ_WALLPAPER_INTERNAL privilege.
        val originalRead = if (state.systemStatic) {
            try {
                bridge.wallpaperStream(WallpaperFlags.SYSTEM, userId, cropped = false)?.use { "OK" }
                    ?: "NO (system wallpaper file missing)"
            } catch (t: Throwable) {
                "NO (${t.message})"
            }
        } else {
            "NOT TESTED (system wallpaper not static)"
        }
        val originalReadOk = originalRead == "OK"
        line("original read", originalRead, if (state.systemStatic) originalReadOk else null)

        val cropRead = if (state.systemStatic) {
            try {
                when (val read = bridge.readCrops(WallpaperFlags.SYSTEM, userId)) {
                    is CropRead.Data -> "OK (${read.crops.size} crop entries)"
                    is CropRead.None -> "NO (${read.reason})"
                }
            } catch (t: Throwable) {
                "NO (${t.message})"
            }
        } else {
            "NOT TESTED (system wallpaper not static)"
        }
        val cropReadOk = cropRead.startsWith("OK")
        if (state.systemStatic) line("crop state read", cropRead, cropReadOk)

        val dim = bridge.readDimAmount()
        line(
            "dim state",
            if (dim != null) "OK (%.4f)".format(dim)
            else if (!caps.getWallpaperDimAmount) "UNSUPPORTED (API missing)"
            else "UNSUPPORTED (permission denied)",
            null, // dim is an optional fidelity feature; its absence is recorded, not fatal
        )

        val problem = state.backupProblem()
        line("system", if (state.systemStatic) "STATIC" else "LIVE/NOT-STATIC (${state.systemLiveInfo ?: "not an image"})", state.systemStatic)
        line(
            "lock",
            when {
                !state.lockExists -> "INHERIT_SYSTEM"
                state.lockStatic -> "SEPARATE (STATIC)"
                else -> "SEPARATE (LIVE/NOT-STATIC: ${state.lockLiveInfo ?: "not an image"})"
            },
            if (state.lockExists) state.lockStatic else true,
        )

        val backupPossible = shell && missing.isEmpty() && problem == null && originalReadOk && cropReadOk
        line(
            "exact backup",
            if (backupPossible) "YES" else "NO (${reason(problem, shell, missing, originalReadOk, cropReadOk)})",
            backupPossible,
        )

        val restorePossible = shell && missing.isEmpty() && caps.cropReadApi() != null
        line(
            "exact restore",
            if (restorePossible) "YES" else "NO (missing APIs or privileges)",
            restorePossible,
        )

        return if (allOk) Cli.EXIT_OK else Cli.EXIT_ERROR
    }

    private fun reason(
        problem: String?,
        shell: Boolean,
        missing: List<String>,
        originalReadOk: Boolean,
        cropReadOk: Boolean,
    ): String = when {
        !shell -> "not running as shell"
        missing.isNotEmpty() -> "missing framework methods: ${missing.joinToString(", ")}"
        problem != null -> problem
        !originalReadOk -> "original wallpaper not readable"
        !cropReadOk -> "crop state not readable"
        else -> "unknown"
    }
}
