package io.github.jacek4yang.wallstate

import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen orientation keys used by WallpaperManagerService for crop maps
 * (mirrors the hidden android.app.WallpaperManager ORIENTATION_* constants).
 */
object ScreenOrientation {
    const val UNKNOWN = -1
    const val PORTRAIT = 0
    const val LANDSCAPE = 1
    const val SQUARE_PORTRAIT = 2
    const val SQUARE_LANDSCAPE = 3
}

/** Result of reading the crop state of one wallpaper destination. */
sealed interface CropRead {
    /** Crop hints relative to the original bitmap; may be empty (default positioning). */
    data class Data(val crops: Map<Int, Rect>) : CropRead

    /** The service has no crop state (e.g. the wallpaper is not an image). */
    data class None(val reason: String) : CropRead
}

/** A writable wallpaper target: write the original bytes, then finish and await completion. */
class WallpaperWrite internal constructor(
    private val out: OutputStream,
    private val completion: WallpaperBridge.Completion,
) {
    fun stream(): OutputStream = out

    /** Closes the stream (triggering server-side processing) and waits for completion. */
    fun finishAndAwait() {
        try {
            out.close()
        } finally {
            completion.await()
        }
    }
}

/**
 * The single place where hidden IWallpaperManager APIs are resolved and called via
 * reflection. Methods are probed against the actual runtime framework; absence and
 * permission failures are surfaced through [Caps] and precise errors so the tool can
 * fail closed instead of guessing.
 */
class WallpaperBridge private constructor(
    private val service: Any,
    private val iface: Class<*>,
) {
    data class Caps(
        val wallpaperService: Boolean,
        val getWallpaperWithFeature: Boolean,
        val getCurrentBitmapCrops: Boolean,
        val getBitmapCrops: Boolean,
        val setWallpaper: Boolean,
        val clearWallpaper: Boolean,
        val lockScreenWallpaperExists: Boolean,
        val isStaticWallpaper: Boolean,
        val getWallpaperDimAmount: Boolean,
        val setWallpaperDimAmount: Boolean,
        val isWallpaperBackupEligible: Boolean,
    ) {
        fun missingRequired(): List<String> {
            val missing = mutableListOf<String>()
            if (!getWallpaperWithFeature) missing.add("getWallpaperWithFeature")
            if (!setWallpaper) missing.add("setWallpaper")
            if (!getCurrentBitmapCrops && !getBitmapCrops) missing.add("getCurrentBitmapCrops/getBitmapCrops")
            if (!clearWallpaper) missing.add("clearWallpaper")
            if (!lockScreenWallpaperExists) missing.add("lockScreenWallpaperExists")
            if (!isStaticWallpaper) missing.add("isStaticWallpaper")
            return missing
        }

        fun cropReadApi(): String? = when {
            getCurrentBitmapCrops -> "getCurrentBitmapCrops"
            getBitmapCrops -> "getBitmapCrops"
            else -> null
        }
    }

    private fun find(name: String, predicate: (Array<Class<*>>) -> Boolean = { true }): Method? =
        iface.methods.firstOrNull { it.name == name && predicate(it.parameterTypes) }

    private val mGetWallpaperWithFeature: Method? = find("getWallpaperWithFeature") {
        it.size == 7 && it[0] == String::class.java && it[4] == Bundle::class.java && it[6] == Boolean::class.java
    }
    private val mGetCurrentBitmapCrops: Method? = find("getCurrentBitmapCrops") {
        it.size == 2 && it[0] == Int::class.javaPrimitiveType && it[1] == Int::class.javaPrimitiveType
    }
    private val mGetBitmapCrops: Method? = find("getBitmapCrops") {
        it.size == 4 && it[0] == List::class.java && it[2] == Boolean::class.java
    }
    private val mSetWallpaper: Method? = find("setWallpaper") {
        it.size == 9 && it[2] == IntArray::class.java && it[3] == List::class.java && it[5] == Bundle::class.java
    }
    private val mClearWallpaper: Method? = find("clearWallpaper") { it.size == 3 }
    private val mLockScreenWallpaperExists: Method? = find("lockScreenWallpaperExists") { it.isEmpty() }
    private val mIsStaticWallpaper: Method? = find("isStaticWallpaper") { it.size == 1 }
    private val mGetWallpaperIdForUser: Method? = find("getWallpaperIdForUser") { it.size == 2 }
    private val mGetWallpaperInfo: Method? = find("getWallpaperInfo") { it.size == 1 }
    private val mGetWallpaperInfoWithFlags: Method? = find("getWallpaperInfoWithFlags") { it.size == 2 }
    private val mGetWallpaperDimAmount: Method? = find("getWallpaperDimAmount") { it.isEmpty() }
    private val mSetWallpaperDimAmount: Method? = find("setWallpaperDimAmount") { it.size == 1 }
    private val mIsWallpaperBackupEligible: Method? = find("isWallpaperBackupEligible") { it.size == 2 }
    private val mIsWallpaperSupported: Method? = find("isWallpaperSupported") { it.size == 1 }
    private val mIsSetWallpaperAllowed: Method? = find("isSetWallpaperAllowed") { it.size == 1 }

    private val windowManager: Any? by lazy {
        try {
            val binder = Reflect.callStatic(
                "android.os.ServiceManager", "getService", "window",
            ) as? IBinder ?: return@lazy null
            val iface = Class.forName("android.view.IWindowManager")
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (t: Throwable) {
            null
        }
    }
    private val mGetInitialDisplaySize: Method? by lazy {
        try {
            windowManager?.javaClass?.methods?.firstOrNull {
                it.name == "getInitialDisplaySize" && it.parameterTypes.size == 1
            }
        } catch (t: Throwable) {
            null
        }
    }

    val caps: Caps = Caps(
        wallpaperService = true,
        getWallpaperWithFeature = mGetWallpaperWithFeature != null,
        getCurrentBitmapCrops = mGetCurrentBitmapCrops != null,
        getBitmapCrops = mGetBitmapCrops != null,
        setWallpaper = mSetWallpaper != null,
        clearWallpaper = mClearWallpaper != null,
        lockScreenWallpaperExists = mLockScreenWallpaperExists != null,
        isStaticWallpaper = mIsStaticWallpaper != null,
        getWallpaperDimAmount = mGetWallpaperDimAmount != null,
        setWallpaperDimAmount = mSetWallpaperDimAmount != null,
        isWallpaperBackupEligible = mIsWallpaperBackupEligible != null,
    )

    val displaySizeApiAvailable: Boolean
        get() = mGetInitialDisplaySize != null

    companion object {
        const val SERVICE_NAME = "wallpaper"
        const val COMPLETION_TIMEOUT_MS = 30_000L

        fun create(): WallpaperBridge {
            val binder = Reflect.callStatic(
                "android.os.ServiceManager", "getService", SERVICE_NAME,
            ) as? IBinder
                ?: throw WallstateException("could not obtain '$SERVICE_NAME' service binder")
            if (!binder.isBinderAlive) {
                throw WallstateException("'$SERVICE_NAME' service binder is not alive")
            }
            val iface = try {
                Class.forName("android.app.IWallpaperManager")
            } catch (e: ClassNotFoundException) {
                throw WallstateException("android.app.IWallpaperManager not present on this build")
            }
            val stub = Class.forName("android.app.IWallpaperManager\$Stub")
            val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: throw WallstateException("wallpaper service interface unavailable")
            return WallpaperBridge(service, iface)
        }
    }

    /** Invocation that rethrows the real cause; RemoteExceptions gain call context. */
    private fun call(method: Method, vararg args: Any?): Any? =
        try {
            method.invoke(service, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            when (cause) {
                is SecurityException -> throw cause
                is android.os.RemoteException ->
                    throw WallstateException("wallpaper service call ${method.name} failed: ${cause.message}", cause)
                else -> throw cause
            }
        }

    private fun require(method: Method?, name: String): Method =
        method ?: throw WallstateException("$name unavailable on this Android build")

    // ---- reading ----

    /**
     * Streams the wallpaper file for [which]. [cropped]=false returns the service's
     * original wallpaper file (byte-identical source), true returns the generated crop.
     * Returns null when no wallpaper file exists for the destination.
     */
    fun wallpaperStream(which: Int, userId: Int, cropped: Boolean): InputStream? {
        val method = require(mGetWallpaperWithFeature, "getWallpaperWithFeature")
        val pfd = call(
            method,
            AndroidEnv.SHELL_PACKAGE,   // callingPkg
            null,                       // callingFeatureId
            null,                       // callback (read path; no change notifications needed)
            which,
            Bundle(),                   // outParams
            userId,
            cropped,
        ) as ParcelFileDescriptor? ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    /**
     * Reads the exact crop state relative to the original bitmap.
     * Prefers getCurrentBitmapCrops (Android 16+); falls back to getBitmapCrops for
     * the current display orientations (Android 15).
     */
    fun readCrops(which: Int, userId: Int): CropRead {
        val direct = mGetCurrentBitmapCrops
        if (direct != null) {
            val bundle = call(direct, which, userId) as Bundle?
                ?: return CropRead.None("service reported no image wallpaper for this destination")
            val crops = LinkedHashMap<Int, Rect>()
            for (key in bundle.keySet()) {
                val value = bundle.get(key) as? Rect
                    ?: throw WallstateException("unexpected crop entry type for key '$key'")
                val orientation = key.toIntOrNull()
                    ?: throw WallstateException("invalid crop orientation key '$key'")
                crops[orientation] = value
            }
            return CropRead.Data(crops)
        }
        val fallback = mGetBitmapCrops
            ?: throw WallstateException("getBitmapCrops unavailable on this Android build")
        val sizes = displaySizes()
        val rects = call(fallback, sizes, which, true, userId) as? List<*>
            ?: return CropRead.None("service returned no crop state for this destination")
        if (rects.size != sizes.size) {
            throw WallstateException(
                "getBitmapCrops returned ${rects.size} rects for ${sizes.size} display sizes"
            )
        }
        val crops = LinkedHashMap<Int, Rect>()
        for (i in sizes.indices) {
            val rect = rects[i] as? Rect
                ?: throw WallstateException("getBitmapCrops returned non-Rect element")
            crops[orientationOf(sizes[i])] = rect
        }
        return CropRead.Data(crops)
    }

    fun readDimAmount(): Double? {
        val method = mGetWallpaperDimAmount ?: return null
        return try {
            (call(method) as Float).toDouble()
        } catch (e: SecurityException) {
            null
        }
    }

    fun wallpaperId(which: Int, userId: Int): Int? {
        val method = mGetWallpaperIdForUser ?: return null
        return call(method, which, userId) as Int?
    }

    fun liveWallpaperInfo(userId: Int): String? {
        val method = mGetWallpaperInfo ?: return "(getWallpaperInfo unavailable)"
        return call(method, userId)?.toString()
    }

    fun liveWallpaperInfoWithFlags(which: Int, userId: Int): String? {
        val method = mGetWallpaperInfoWithFlags ?: return "(getWallpaperInfoWithFlags unavailable)"
        return call(method, which, userId)?.toString()
    }

    fun lockScreenWallpaperExists(): Boolean =
        call(require(mLockScreenWallpaperExists, "lockScreenWallpaperExists")) as Boolean

    fun isStaticWallpaper(which: Int): Boolean =
        call(require(mIsStaticWallpaper, "isStaticWallpaper"), which) as Boolean

    fun isWallpaperBackupEligible(which: Int, userId: Int): Boolean? {
        val method = mIsWallpaperBackupEligible ?: return null
        return try {
            call(method, which, userId) as Boolean
        } catch (e: SecurityException) {
            null
        }
    }

    fun isWallpaperSupported(): Boolean {
        val method = require(mIsWallpaperSupported, "isWallpaperSupported")
        return call(method, AndroidEnv.SHELL_PACKAGE) as Boolean
    }

    fun isSetWallpaperAllowed(): Boolean {
        val method = require(mIsSetWallpaperAllowed, "isSetWallpaperAllowed")
        return call(method, AndroidEnv.SHELL_PACKAGE) as Boolean
    }

    fun stateSummary(userId: Int): DeviceWallpaperState {
        val lockExists = lockScreenWallpaperExists()
        val systemStatic = isStaticWallpaper(WallpaperFlags.SYSTEM)
        val lockStatic = if (lockExists) isStaticWallpaper(WallpaperFlags.LOCK) else false
        val systemLive = if (!systemStatic) liveWallpaperInfo(userId) else null
        val lockLive = if (lockExists && !lockStatic) liveWallpaperInfoWithFlags(WallpaperFlags.LOCK, userId) else null
        return DeviceWallpaperState(systemStatic, systemLive, lockExists, lockStatic, lockLive)
    }

    // ---- mutating ----

    /**
     * Opens a writable descriptor for setting the wallpaper of [which]. The caller
     * streams the original wallpaper bytes into it, then calls [WallpaperWrite.finishAndAwait].
     * The exact crop map is applied server-side from [orientations]/[crops].
     */
    fun openWallpaperWrite(
        orientations: IntArray?,
        crops: List<Rect>?,
        allowBackup: Boolean,
        which: Int,
        userId: Int,
    ): WallpaperWrite {
        val method = require(mSetWallpaper, "setWallpaper")
        val completion = Completion()
        val pfd = call(
            method,
            null,           // name
            AndroidEnv.SHELL_PACKAGE,
            orientations,
            crops,
            allowBackup,
            Bundle(),       // out extras
            which,
            completion.callback,
            userId,
        ) as ParcelFileDescriptor?
            ?: throw WallstateException(
                "setWallpaper returned no writable descriptor (wallpaper setting disabled or unsupported)"
            )
        return WallpaperWrite(ParcelFileDescriptor.AutoCloseOutputStream(pfd), completion)
    }

    /** Clears the wallpaper of [which]; for FLAG_LOCK this returns the lock screen to the shared state. */
    fun clearWallpaper(which: Int, userId: Int) {
        call(require(mClearWallpaper, "clearWallpaper"), AndroidEnv.SHELL_PACKAGE, which, userId)
    }

    fun setWallpaperDimAmount(amount: Double) {
        val method = require(mSetWallpaperDimAmount, "setWallpaperDimAmount")
        val f = amount.toFloat()
        if (f.isNaN() || f < 0f || f > 1f) {
            throw WallstateException("dim amount out of range: $amount")
        }
        call(method, f)
    }

    // ---- helpers ----

    private fun displaySizes(): List<Point> {
        val method = mGetInitialDisplaySize
            ?: throw WallstateException("cannot determine display size (IWindowManager.getInitialDisplaySize unavailable)")
        val manager = windowManager
            ?: throw WallstateException("window service unavailable for display size query")
        val point = call(method, manager, 0) as? Point
            ?: throw WallstateException("display size unavailable")
        if (point.x <= 0 || point.y <= 0) {
            throw WallstateException("invalid display size ${point.x}x${point.y}")
        }
        return listOf(point, Point(point.y, point.x))
    }

    class Completion internal constructor() {
        private val latch = CountDownLatch(1)

        val callback: Any =
            Proxy.newProxyInstance(
                WallpaperBridge::class.java.classLoader,
                arrayOf(Class.forName("android.app.IWallpaperManagerCallback")),
                InvocationHandler { _, method, _ ->
                    if (method.name == "onWallpaperChanged") latch.countDown()
                    null
                },
            )

        fun await() {
            if (!latch.await(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw WallstateException(
                    "timed out waiting for wallpaper set completion after ${COMPLETION_TIMEOUT_MS}ms"
                )
            }
        }
    }
}

/** Small helpers for reaching static framework entry points via reflection. */
object Reflect {
    fun callStatic(className: String, methodName: String, arg: String): Any? {
        val cls = Class.forName(className)
        val method = cls.getMethod(methodName, String::class.java)
        return try {
            method.invoke(null, arg)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** Maps a display size to its ScreenOrientation key (mirrors WallpaperManager.getOrientation). */
internal fun orientationOf(point: Point): Int {
    if (point.y <= 0) throw WallstateException("invalid display height ${point.y}")
    val ratio = point.x.toFloat() / point.y.toFloat()
    return when {
        ratio >= 4f / 3f -> ScreenOrientation.LANDSCAPE
        ratio > 1f -> ScreenOrientation.SQUARE_LANDSCAPE
        ratio > 3f / 4f -> ScreenOrientation.SQUARE_PORTRAIT
        else -> ScreenOrientation.PORTRAIT
    }
}
