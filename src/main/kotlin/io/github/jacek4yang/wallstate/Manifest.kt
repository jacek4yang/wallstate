package io.github.jacek4yang.wallstate

import org.json.JSONObject

/** Archive format version understood by this tool. */
const val FORMAT_VERSION = 1

/** Version of the wallstate tool. */
const val TOOL_VERSION = "1.0.0"

/** Upper bound on crop map entries per wallpaper destination. */
internal const val MAX_CROP_ENTRIES = 32

/** How the lock wallpaper relates to the system wallpaper. */
enum class LockMode(val json: String) {
    SEPARATE("SEPARATE"),
    INHERIT_SYSTEM("INHERIT_SYSTEM");

    companion object {
        fun fromJson(value: String): LockMode =
            entries.firstOrNull { it.json == value }
                ?: throw ArchiveException("invalid lock mode: '$value'")
    }
}

/**
 * A wallpaper crop rectangle relative to the wallpaper's original bitmap.
 * Orientation keys use Android's ScreenOrientation constants
 * (ORIENTATION_UNKNOWN = -1, PORTRAIT = 0, LANDSCAPE = 1, SQUARE_PORTRAIT = 2, SQUARE_LANDSCAPE = 3).
 */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        if (left < 0 || top < 0 || right < left || bottom < top) {
            throw ArchiveException(
                "invalid crop rect: left=$left top=$top right=$right bottom=$bottom " +
                    "(expected 0 <= left <= right and 0 <= top <= bottom)"
            )
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toJson(): JSONObject =
        JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)

    companion object {
        fun fromJson(o: JSONObject, where: String): CropRect {
            ManifestJson.expectKeys(
                o, where,
                required = setOf("left", "top", "right", "bottom"),
                optional = emptySet(),
            )
            return CropRect(
                left = ManifestJson.getInt(o, "left", where),
                top = ManifestJson.getInt(o, "top", where),
                right = ManifestJson.getInt(o, "right", where),
                bottom = ManifestJson.getInt(o, "bottom", where),
            )
        }
    }
}

/** Device the archive was created on / is being restored to. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val fingerprint: String,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("manufacturer", manufacturer)
            .put("model", model)
            .put("sdk", sdkInt)
            .put("fingerprint", fingerprint)

    fun sameDevice(other: DeviceInfo): Boolean =
        manufacturer.equals(other.manufacturer, ignoreCase = true) &&
            model.equals(other.model, ignoreCase = true) &&
            sdkInt == other.sdkInt

    override fun toString(): String = "$manufacturer $model (sdk $sdkInt)"
}

/** Captured state of one wallpaper destination (system or lock). */
data class WallpaperEntry(
    val originalSha256: String,
    val croppedSha256: String?,
    /** Crop hints relative to the original bitmap, keyed by screen orientation. */
    val crops: Map<Int, CropRect>,
    /** Whether the source wallpaper allowed system backup, when known. */
    val allowBackup: Boolean?,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("kind", "STATIC")
            .put("originalSha256", originalSha256)
        if (croppedSha256 != null) o.put("croppedSha256", croppedSha256)
        val cropsJson = JSONObject()
        for ((orientation, rect) in crops) cropsJson.put(orientation.toString(), rect.toJson())
        o.put("crops", cropsJson)
        if (allowBackup != null) o.put("allowBackup", allowBackup)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject, where: String): WallpaperEntry {
            ManifestJson.expectKeys(
                o, where,
                required = setOf("kind", "originalSha256", "crops"),
                optional = setOf("croppedSha256", "allowBackup"),
            )
            val kind = ManifestJson.getString(o, "kind", where)
            if (kind != "STATIC") {
                throw ArchiveException("manifest.$where.kind must be \"STATIC\", got '$kind'")
            }
            val originalSha = ManifestJson.getSha256(o, "originalSha256", where)
            val croppedSha = if (o.has("croppedSha256")) {
                ManifestJson.getSha256(o, "croppedSha256", where)
            } else null
            val cropsJson = o.getJSONObject("crops")
            val crops = LinkedHashMap<Int, CropRect>()
            val names = cropsJson.names()
            if (names != null) {
                if (names.length() > MAX_CROP_ENTRIES) {
                    throw ArchiveException("manifest.$where.crops has too many entries")
                }
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    val orientation = key.toIntOrNull()
                        ?: throw ArchiveException("manifest.$where.crops has non-integer key '$key'")
                    crops[orientation] = CropRect.fromJson(cropsJson.getJSONObject(key), "$where.crops[$key]")
                }
            }
            val allowBackup = if (o.has("allowBackup")) ManifestJson.getBoolean(o, "allowBackup", where) else null
            return WallpaperEntry(originalSha, croppedSha, crops, allowBackup)
        }
    }
}

/** Lock wallpaper relationship captured in an archive. */
data class LockState(val mode: LockMode, val entry: WallpaperEntry?) {
    init {
        if ((mode == LockMode.SEPARATE) != (entry != null)) {
            throw ArchiveException("lock mode ${mode.json} must ${if (mode == LockMode.SEPARATE) "have" else "not have"} an entry")
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject().put("mode", mode.json)
        if (entry != null) o.put("entry", entry.toJson())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject, where: String): LockState {
            ManifestJson.expectKeys(o, where, required = setOf("mode"), optional = setOf("entry"))
            val mode = LockMode.fromJson(ManifestJson.getString(o, "mode", where))
            val entry = if (o.has("entry")) WallpaperEntry.fromJson(o.getJSONObject("entry"), "$where.entry") else null
            return LockState(mode, entry)
        }
    }
}

/**
 * Versioned description of an exact static wallpaper state backup.
 *
 * Everything needed to restore and verify the state is stored as plain JSON plus
 * raw image entries; no Java/Android objects are serialized.
 */
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val toolVersion: String = TOOL_VERSION,
    val createdAt: String,
    val device: DeviceInfo,
    val userId: Int,
    val dimSupported: Boolean,
    /** Effective wallpaper dim amount in [0, 1]; null iff dimSupported is false. */
    val dimAmount: Double?,
    val system: WallpaperEntry,
    val lock: LockState,
    /** Wallpaper ids are diagnostic only; they legitimately change on restore. */
    val systemWallpaperId: Int?,
    val lockWallpaperId: Int?,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("formatVersion", formatVersion)
            .put("toolVersion", toolVersion)
            .put("createdAt", createdAt)
            .put("device", device.toJson())
            .put("userId", userId)
            .put("dimSupported", dimSupported)
        if (dimAmount != null) o.put("dimAmount", dimAmount)
        o.put("system", system.toJson())
        o.put("lock", lock.toJson())
        if (systemWallpaperId != null) o.put("systemWallpaperId", systemWallpaperId)
        if (lockWallpaperId != null) o.put("lockWallpaperId", lockWallpaperId)
        return o
    }

    companion object {
        /** Strictly parses and validates a manifest. Any deviation fails. */
        fun fromJson(text: String): BackupManifest {
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ArchiveException("manifest.json is not valid JSON: ${e.message}")
            }
            ManifestJson.expectKeys(
                o, "",
                required = setOf(
                    "formatVersion", "toolVersion", "createdAt", "device", "userId",
                    "dimSupported", "system", "lock",
                ),
                optional = setOf("dimAmount", "systemWallpaperId", "lockWallpaperId"),
            )
            val formatVersion = ManifestJson.getInt(o, "formatVersion", "")
            if (formatVersion != FORMAT_VERSION) {
                throw ArchiveException(
                    "unsupported manifest formatVersion $formatVersion (this tool supports $FORMAT_VERSION)"
                )
            }
            val toolVersion = ManifestJson.getString(o, "toolVersion", "")
            if (toolVersion.isBlank()) throw ArchiveException("manifest.toolVersion must not be empty")
            val createdAt = ManifestJson.getString(o, "createdAt", "")
            if (createdAt.isBlank() || createdAt.length > 64) {
                throw ArchiveException("manifest.createdAt must be a short timestamp string")
            }
            val device = parseDevice(o.getJSONObject("device"))
            val userId = ManifestJson.getInt(o, "userId", "")
            if (userId < 0 || userId > 99999) throw ArchiveException("manifest.userId out of range: $userId")
            val dimSupported = ManifestJson.getBoolean(o, "dimSupported", "")
            val dimAmount = if (o.has("dimAmount")) {
                val d = ManifestJson.getDouble(o, "dimAmount", "")
                if (d < 0.0 || d > 1.0) throw ArchiveException("manifest.dimAmount out of range: $d")
                d
            } else null
            if (dimSupported != (dimAmount != null)) {
                throw ArchiveException(
                    "manifest.dimSupported=$dimSupported conflicts with dimAmount=${dimAmount ?: "absent"}"
                )
            }
            val system = WallpaperEntry.fromJson(o.getJSONObject("system"), "system")
            val lock = LockState.fromJson(o.getJSONObject("lock"), "lock")
            return BackupManifest(
                formatVersion = formatVersion,
                toolVersion = toolVersion,
                createdAt = createdAt,
                device = device,
                userId = userId,
                dimSupported = dimSupported,
                dimAmount = dimAmount,
                system = system,
                lock = lock,
                systemWallpaperId = if (o.has("systemWallpaperId")) ManifestJson.getInt(o, "systemWallpaperId", "") else null,
                lockWallpaperId = if (o.has("lockWallpaperId")) ManifestJson.getInt(o, "lockWallpaperId", "") else null,
            )
        }

        private fun parseDevice(d: JSONObject): DeviceInfo {
            ManifestJson.expectKeys(
                d, "device",
                required = setOf("manufacturer", "model", "sdk", "fingerprint"),
                optional = emptySet(),
            )
            val sdk = ManifestJson.getInt(d, "sdk", "device")
            if (sdk < 1 || sdk > 10000) throw ArchiveException("manifest.device.sdk out of range: $sdk")
            val manufacturer = ManifestJson.getString(d, "manufacturer", "device")
            val model = ManifestJson.getString(d, "model", "device")
            val fingerprint = ManifestJson.getString(d, "fingerprint", "device")
            if (manufacturer.isBlank() || model.isBlank() || fingerprint.isBlank()) {
                throw ArchiveException("manifest.device fields must not be empty")
            }
            return DeviceInfo(manufacturer, model, sdk, fingerprint)
        }
    }
}

/** Strict JSON field access helpers; every deviation fails closed. */
internal object ManifestJson {
    fun expectKeys(o: JSONObject, where: String, required: Set<String>, optional: Set<String>) {
        val names = o.names() ?: throw ArchiveException("manifest${whereOf(where)} must be a JSON object")
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            if (key !in required && key !in optional) {
                throw ArchiveException("unexpected field '$key' in manifest${whereOf(where)}")
            }
        }
        for (key in required) {
            if (!o.has(key) || o.isNull(key)) {
                throw ArchiveException("missing field '$key' in manifest${whereOf(where)}")
            }
        }
    }

    fun getInt(o: JSONObject, key: String, where: String): Int {
        val v = o.get(key)
        return when (v) {
            is Int -> v
            is Long ->
                if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt()
                else throw ArchiveException("manifest${whereOf(where)}.$key out of integer range: $v")
            else -> throw ArchiveException("manifest${whereOf(where)}.$key must be an integer, got ${typeName(v)}")
        }
    }

    fun getDouble(o: JSONObject, key: String, where: String): Double {
        val v = o.get(key)
        return when (v) {
            is Double -> v
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            else -> throw ArchiveException("manifest${whereOf(where)}.$key must be a number, got ${typeName(v)}")
        }
    }

    fun getBoolean(o: JSONObject, key: String, where: String): Boolean {
        val v = o.get(key)
        if (v !is Boolean) {
            throw ArchiveException("manifest${whereOf(where)}.$key must be a boolean, got ${typeName(v)}")
        }
        return v
    }

    fun getString(o: JSONObject, key: String, where: String): String {
        val v = o.get(key)
        if (v !is String) {
            throw ArchiveException("manifest${whereOf(where)}.$key must be a string, got ${typeName(v)}")
        }
        return v
    }

    fun getSha256(o: JSONObject, key: String, where: String): String {
        val v = getString(o, key, where)
        if (!Hashing.isSha256Hex(v)) {
            throw ArchiveException("manifest${whereOf(where)}.$key is not a SHA-256 hex digest")
        }
        return v.lowercase()
    }

    private fun whereOf(where: String) = if (where.isBlank()) "" else ".$where"

    private fun typeName(v: Any?): String =
        when (v) {
            null -> "null"
            is String -> "string"
            is Int, is Long -> "integer"
            is Double -> "number"
            is Boolean -> "boolean"
            is JSONObject -> "object"
            is org.json.JSONArray -> "array"
            else -> v.javaClass.simpleName
        }
}
