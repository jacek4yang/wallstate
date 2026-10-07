package io.github.jacek4yang.wallstate

/** Base class for all deliberate wallstate failures. */
open class WallstateException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The device wallpaper state is unsupported for exact backup/restore. */
class UnsupportedStateException(message: String) : WallstateException(message)

/** An archive is malformed, unsafe, or fails validation. */
class ArchiveException(message: String) : WallstateException(message)

/** Verification of archive integrity or of device state after restore failed. */
class VerificationException(message: String) : WallstateException(message)

/** A restore failed and its rollback failed too; device state is the rollback target's. */
class FatalRestoreException(message: String, cause: Throwable? = null) : WallstateException(message, cause)
