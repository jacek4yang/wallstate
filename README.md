# wallstate

Exact backup and restore of **static Android wallpaper state** on an unrooted phone,
through ADB shell privileges. Built and tested for OnePlus / OxygenOS devices.

```
wallstate backup original-state.zip
# ... later, or on the same device after a factory reset / OTA experiment ...
wallstate restore original-state.zip
```

## What it does

For supported (static) wallpapers, `wallstate backup` preserves the actual wallpaper
source data and the wallpaper state kept by Android's `WallpaperManagerService`, so that
a later `wallstate restore` returns the wallpaper state to the backed-up state:

- the **original** system wallpaper file, copied byte-for-byte from the framework
  (never decoded, re-encoded, or re-compressed);
- the original lock wallpaper file when the lock wallpaper is separate;
- the **exact crop map** (crop hints relative to the original bitmap, per screen
  orientation) that the service uses to position the wallpaper;
- the system/lock relationship: separate lock wallpaper, or lock **inheriting** the
  system wallpaper;
- the wallpaper **dim amount** where the framework supports it (`getWallpaperDimAmount` /
  `setWallpaperDimAmount`);
- device/build metadata and SHA-256 digests used to verify the restore.

This is a guarantee about wallpaper *state*, not about physical display pixel-output
identity. Wallpaper IDs legitimately change on restore and are recorded as diagnostics
only.

Everything runs on the phone as the Android **shell** uid (2000) through
`app_process`; there is no APK, no root, no GUI, no Shizuku. The tool talks to
`WallpaperManagerService` over Binder and falls back to nothing: if the exact state
required for restoration cannot be captured, backup **fails closed** with a precise
error instead of writing a degraded archive.

## Scope of v1.0.0

- Static image wallpapers only. Live wallpapers, AOD themes, and lock-screen animation
  packages are detected and **rejected with a reason** (non-zero exit), never faked.
- Restore refuses archives created on a different device model/SDK, and refuses to run
  if the required framework methods are missing on the connected build.
- Restore is transactional: it first takes and validates an internal rollback backup of
  the current state, applies the restore, re-reads the device state and verifies it
  against the archive; on any failure it automatically rolls back to the previous state.

## Prerequisites

- A host with Java 17+ and `adb` (Android platform-tools).
- Android SDK platform **android-36** (`android.jar`) for the build.
- A connected OnePlus/OxygenOS device (Android 15 or 16 tested path), USB debugging
  enabled. No root required.

## Build

```sh
./gradlew wallstateJar     # produces build/libs/wallstate.jar
./gradlew build            # jar + host JVM tests
```

`android.jar` is resolved from `local.properties` (`sdk.dir=...`), `ANDROID_HOME`, or
`ANDROID_SDK_ROOT`. Install it e.g. with:

```sh
sdkmanager "platforms;android-36"
```

or download
[platform-36_r02.zip](https://dl.google.com/android/repository/platform-36_r02.zip) and
unpack `android-36/` into `<sdk>/platforms/android-36/`.

The build compiles Kotlin against the public SDK, dexes the classes (plus the Kotlin
stdlib) with **D8**, and packages a single `classes.dex` into `wallstate.jar`.

## Installation / raw usage

Push the jar once (the wrapper does this automatically):

```sh
adb push build/libs/wallstate.jar /data/local/tmp/wallstate.jar
```

Run the device CLI directly:

```sh
adb shell '
  CLASSPATH=/data/local/tmp/wallstate.jar \
  app_process /system/bin io.github.jacek4yang.wallstate.Main doctor
'
```

The process must run as `uid=2000 (shell)`; privileged commands verify this at runtime
and fail immediately otherwise.

## Host wrapper usage

`scripts/wallstate` (POSIX sh) is a thin convenience wrapper: it checks for exactly one
attached device (or `--serial`), pushes the jar, runs the device command, and for
backup/restore moves the archive to/from the host and cleans up temporary files.

```sh
scripts/wallstate doctor
scripts/wallstate info
scripts/wallstate backup original-state.zip
scripts/wallstate verify original-state.zip
scripts/wallstate inspect original-state.zip
scripts/wallstate restore original-state.zip
```

Options: `--serial SERIAL` (or `-s`) to pick a device, `--jar PATH` to override the jar
location. Exit status is propagated from the device command.

## Commands

| Command | Purpose |
| --- | --- |
| `version` | print the tool version |
| `doctor` | uid, SDK, device, wallpaper service, framework methods, privileges, current wallpaper types, exact backup/restore capability |
| `info` | current wallpaper state: types, ids, original SHA-256, crop maps, dim |
| `backup <output.zip>` | create an exact backup (atomically, verified before success) |
| `inspect <backup.zip>` | show the archive manifest and entries |
| `verify <backup.zip>` | verify archive integrity: schema, entry set, all SHA-256 digests |
| `restore <backup.zip>` | transactionally restore the wallpaper state |
| `set <img> <system\|lock\|both> [dim]` | test helper: set a wallpaper through the framework write path (used by `scripts/acceptance.sh`) |

`wallstate verify` verifies the **archive**; verification after `restore` verifies the
**device state** (original bytes, crop maps, lock relationship, dim amount).

## Backup guarantees

- Original wallpaper bytes are streamed straight from the framework-provided
  `ParcelFileDescriptor` into the archive; SHA-256 is computed during the copy.
- After writing, the archive is re-opened and every recorded digest is re-verified;
  only then is the temporary file atomically renamed to the requested path.
- If the original stream or the exact crop state cannot be retrieved, backup fails.
  The cropped wallpaper image is included in the archive for diagnostics only and is
  never used as a restore input.

## Restore and rollback behavior

1. the archive is fully validated (schema, entries, hashes, crop sanity, device
   compatibility) before anything is changed;
2. an internal exact backup of the **current** state is created and validated;
3. the system wallpaper is restored from the archived original plus the archived crop
   map through the framework's `setWallpaper(..., screenOrientations, crops, ...)`
   mechanism;
4. the lock wallpaper is restored according to the archived relationship: a separate
   lock image is written; an inherited lock is re-established by clearing the lock
   wallpaper (the framework migrates a shared image to lock-only when the system
   wallpaper is overwritten, so the clear always follows);
5. the dim amount is restored when supported;
6. the device state is re-read and verified against the archive;
7. on success the rollback backup is deleted; on any failure the rollback is applied
   automatically and verified. A failed restore never quietly leaves the phone
   half-restored. If rollback itself fails, the command prints `FATAL:` and exits 3,
   keeping the rollback archive for manual recovery.

## Archive format

Versioned ZIP (`formatVersion: 1`), plain JSON manifest plus raw entries:

```
backup.zip
├── manifest.json          versioned, strict schema
├── system/
│   ├── original           byte-identical wallpaper source
│   └── cropped            (optional) generated crop, diagnostics only
└── lock/                  only when lock is SEPARATE
    ├── original
    └── cropped            (optional)
```

The manifest records `toolVersion`, creation time, device (manufacturer, model, sdk,
fingerprint), user id, dim state, per-destination `originalSha256` / optional
`croppedSha256` / crop map (orientation → rect, relative to the original bitmap) /
`allowBackup`, and the lock mode (`SEPARATE` or `INHERIT_SYSTEM`). No Java or Android
objects are serialized. Crop orientations use Android ScreenOrientation constants:
-1 unknown, 0 portrait, 1 landscape, 2 square-portrait, 3 square-landscape; an empty
crop map means the wallpaper uses the framework's default positioning.

Restore treats archives as untrusted input: entries are streamed (never extracted to
filesystem paths), entry names are matched against an exact allowlist, duplicates,
oversized entries, and malformed manifests are rejected, and every digest is checked
before any mutation.

## Known limitations

- Static wallpapers only; live/dynamic state is rejected, not preserved.
- v1.0.0 targets the same-device case: restore refuses a different manufacturer/model/SDK
  and warns on a different build fingerprint of the same device.
- The dim amount is per-calling-uid in the framework; wallstate restores the effective
  dim amount it observed via the shell uid. A higher dim set by another app remains in
  effect (framework semantics).
- Crop-state reading prefers `getCurrentBitmapCrops` (Android 16+); on Android 15 it
  falls back to `getBitmapCrops` for the current display orientations, which cannot
  distinguish "no custom crop hints" from "hints equal to the defaults".
- OEM-only wallpaper surfaces that are not represented by WallpaperManagerService
  static wallpaper state (theme-store assets, AOD, lock animations) are out of scope.

## Compatibility

v1.0.0 was validated end-to-end on Android 16 (API 36, AOSP `google/emu64xa:16/
BE2A.250530.026.F3`) running in an emulator on the development machine, including the
real-device acceptance sequence: seed backup, framework-path mutation, restore,
device-state verification for both lock relationships (separate and inherited), a
second backup/restore cycle, and a deliberate failure-injection of the automatic
rollback path.

The tool probes the actual framework at runtime (`doctor` reports every method it
found or missed) rather than hard-coding assumptions or binder transaction numbers,
so OxygenOS builds that differ from AOSP produce precise diagnostics instead of
undefined behavior. Restore to the same device model/SDK is friction-free; anything
else is rejected up front.

## Troubleshooting

- `process is not running as shell uid` — the jar was executed outside `adb shell`
  (e.g. as root or via `su`); run it through adb as-is.
- `NO (missing framework methods: ...)` in `doctor` — the connected build lacks a
  required hidden API; paste the doctor output in a bug report.
- `wallpaper service: UNAVAILABLE` — the system is mid-boot or the service crashed;
  wait and retry.
- Restore refuses with *incompatible restore target* — the archive was created on a
  different device/build; wallstate refuses rather than risk wrong crop semantics.
- `FATAL:` on restore — automatic rollback also failed; the phone keeps the last
  applied state; the rollback archive path is printed, re-run `restore` with it.
- On an emulator (or after an unclean device shutdown), `app_process` can abort at
  startup with `ClassNotFoundException: io.github.jacek4yang.wallstate.Main` even
  though the jar is intact: the runtime's dex cache is keyed to the old file state.
  Re-pushing the jar (the wrapper does this on every run) or `adb shell touch
  /data/local/tmp/wallstate.jar` clears it.
- `scripts/acceptance.sh` runs the full backup/mutate/restore/verify acceptance
  sequence (both lock-relationship transitions plus a deliberate failure-injection
  of the rollback path) and always ends by restoring the user's original state.

## License

Apache-2.0. See [LICENSE](LICENSE) and [CHANGELOG](CHANGELOG.md).
