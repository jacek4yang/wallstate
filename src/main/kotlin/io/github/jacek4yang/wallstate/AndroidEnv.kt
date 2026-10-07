package io.github.jacek4yang.wallstate

import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

/**
 * Runtime environment of the app_process process: uid, device identity, current user,
 * and the temporary directory under /data/local/tmp.
 */
object AndroidEnv {
    const val SHELL_UID = 2000

    /**
     * Package identity used for Binder calls requiring a calling package. It belongs to
     * the shell uid on every Android build; `doctor` exercises a permission-checked call
     * to confirm the pairing works on the actual device.
     */
    const val SHELL_PACKAGE = "com.android.shell"

    const val TMP_DIR = "/data/local/tmp/wallstate"

    fun uid(): Int = Process.myUid()

    fun requireShellUid() {
        val uid = uid()
        if (uid != SHELL_UID) {
            throw WallstateException("process is not running as shell uid (expected $SHELL_UID, got $uid)")
        }
    }

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER ?: "",
        model = Build.MODEL ?: "",
        sdkInt = Build.VERSION.SDK_INT,
        fingerprint = Build.FINGERPRINT ?: "",
    )

    /**
     * The foreground user whose wallpaper state is being operated on. Uses
     * IActivityManager.getCurrentUser() via reflection; falls back to the user derived
     * from the shell uid (always user 0) if the activity service cannot be reached.
     */
    fun currentUser(): Int = try {
        CurrentUserReader.get()
    } catch (t: Throwable) {
        // uid 2000 belongs to user 0 (uid/100000); only a fallback for a broken activity service.
        uid() / 100000
    }

    /** Temporary directory, created on demand with restrictive permissions. */
    fun tmpDir(): File {
        val dir = File(TMP_DIR)
        if (dir.exists() && !dir.isDirectory) {
            throw WallstateException("$TMP_DIR exists and is not a directory")
        }
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw WallstateException("cannot create $TMP_DIR")
        }
        try {
            android.system.Os.chmod(TMP_DIR, 448) // 0700
        } catch (_: Exception) {
            // Best effort only; /data/local/tmp is already shell-private.
        }
        return dir
    }

    fun newTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, tmpDir())
}

/** Resolves the foreground user through IActivityManager (hidden API, reflection only). */
internal object CurrentUserReader {
    fun get(): Int {
        val binder = Reflect.callStatic(
            "android.os.ServiceManager", "getService", "activity",
        ) as? IBinder ?: throw WallstateException("activity service binder unavailable")
        val amClass = Class.forName("android.app.IActivityManager")
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        val am = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw WallstateException("activity service interface unavailable")
        val method = amClass.getMethod("getCurrentUser")
        return method.invoke(am) as Int
    }
}

/** ParcelFileDescriptor → streams convenience. */
object Pfd {
    fun input(pfd: ParcelFileDescriptor): java.io.InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(pfd)
}
