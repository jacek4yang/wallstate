package io.github.jacek4yang.wallstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestorePlanTest {

    @Test
    fun `separate archive sets lock and never clears`() {
        val plan = RestorePlan.plan(LockMode.SEPARATE)
        assertTrue(plan.setLock)
        assertFalse(plan.clearLock)
    }

    @Test
    fun `inherit archive always clears lock because setting system migrates shared state to lock-only`() {
        val plan = RestorePlan.plan(LockMode.INHERIT_SYSTEM)
        assertFalse(plan.setLock)
        assertTrue(plan.clearLock)
    }

    @Test
    fun `backupProblem rejects live system wallpaper`() {
        val state = DeviceWallpaperState(
            systemStatic = false, systemLiveInfo = "ComponentInfo{com.example.live}",
            lockExists = false, lockStatic = false, lockLiveInfo = null,
        )
        val problem = state.backupProblem()
        assertTrue(problem!!.contains("live"))
        assertTrue(problem.contains("com.example.live"))
    }

    @Test
    fun `backupProblem rejects non-static system without live info`() {
        val state = DeviceWallpaperState(false, null, false, false, null)
        assertTrue(state.backupProblem()!!.contains("not a static image"))
    }

    @Test
    fun `backupProblem rejects live lock wallpaper`() {
        val state = DeviceWallpaperState(true, null, true, false, "ComponentInfo{com.example.locklive}")
        assertTrue(state.backupProblem()!!.contains("lock wallpaper is live"))
    }

    @Test
    fun `backupProblem accepts static separate and static inherited`() {
        val separate = DeviceWallpaperState(true, null, true, true, null)
        assertNull(separate.backupProblem())
        assertEquals(LockMode.SEPARATE, separate.lockMode)

        val inherited = DeviceWallpaperState(true, null, false, false, null)
        assertNull(inherited.backupProblem())
        assertEquals(LockMode.INHERIT_SYSTEM, inherited.lockMode)
    }

    @Test
    fun `matches compares lock mode only`() {
        val manifest = TestManifests.inherit()
        val inherited = DeviceWallpaperState(true, null, false, false, null)
        val separate = DeviceWallpaperState(true, null, true, true, null)
        assertTrue(inherited.matches(manifest))
        assertFalse(separate.matches(manifest))
    }
}

private object TestManifests {
    fun inherit(): BackupManifest = BackupManifest(
        createdAt = "t",
        device = DeviceInfo("OnePlus", "CPH2609", 36, "fp"),
        userId = 0,
        dimSupported = false,
        dimAmount = null,
        system = WallpaperEntry("a".repeat(64), null, emptyMap(), null),
        lock = LockState(LockMode.INHERIT_SYSTEM, null),
        systemWallpaperId = null,
        lockWallpaperId = null,
    )
}
