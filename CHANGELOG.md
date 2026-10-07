# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-07

Initial release.

### Added

- Device-side CLI (`version`, `doctor`, `info`, `backup`, `inspect`, `verify`,
  `restore`) executed as Android shell uid (2000) through
  `CLASSPATH=... app_process /system/bin io.github.jacek4yang.wallstate.Main`.
- Exact static wallpaper backup: byte-identical original wallpaper data streamed
  from WallpaperManagerService (`getWallpaperWithFeature(..., getCropped=false)`),
  SHA-256 computed during copy, exact crop maps captured via
  `getCurrentBitmapCrops` (Android 16+) or `getBitmapCrops` (Android 15 fallback),
  lock separation/inheritance detected via `lockScreenWallpaperExists()` and
  `isStaticWallpaper()`, dim amount captured via `getWallpaperDimAmount()`,
  `allowBackup` flags recorded via `isWallpaperBackupEligible()`.
- Versioned ZIP archive (`formatVersion 1`) with a strict JSON manifest; archives
  are written to a temporary file, fully re-read and verified, then atomically
  renamed.
- Transactional restore: full archive validation, device compatibility and
  capability prechecks, automatic rollback backup of the current state, restore of
  original + crop map through `setWallpaper(..., screenOrientations, crops, ...)`,
  lock relationship restored (separate or inherited), dim amount restored, device
  state re-read and verified; automatic rollback on any failure.
- Fail-closed behavior throughout: live wallpapers are rejected with a reason,
  unreadable originals or crop state abort the backup, untrusted archives are
  validated against duplicate/unexpected/oversized entries, traversal names,
  malformed manifests, and hash mismatches.
- Host-side POSIX wrapper `scripts/wallstate` (device selection, jar push/pull,
  temporary file cleanup, exit status propagation).
- Gradle build producing `wallstate.jar` containing `classes.dex` (D8), no Android
  Studio required; host JVM test suite (42 tests) covering manifest schema strictness,
  archive validation, hashing, lock-mode modeling, and restore planning.

### Target

- OnePlus / OxygenOS, current Android builds with Android 15/16 wallpaper service
  APIs; no root; no Shizuku; no APK.
