#!/bin/sh
# wallstate real-device acceptance test.
#
# Implements the v1.0.0 acceptance sequence:
#   A. preflight (doctor, info)          B. original backup + verify
#   C. record reference state            D. mutate wallpaper state (framework path)
#   E. confirm state changed             F. restore original
#   G. verify post-restore state         second cycle (incl. lock-relationship change)
#   H. final safety: original state restored and verified, no matter what.
#
# Usage: scripts/acceptance.sh [SERIAL]
#   (or set WALLSTATE_SERIAL)
#
# The script never leaves the device in a mutated state: the last step always
# restores the original archive and verifies it.
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
WRAP="$ROOT/scripts/wallstate"
WORK="${WALLSTATE_ACCEPTANCE_DIR:-$ROOT/build/acceptance}"
SERIAL="${1:-${WALLSTATE_SERIAL:-}}"
SERIAL_OPT=""
[ -n "$SERIAL" ] && SERIAL_OPT="--serial $SERIAL"

DEVICE_TMP=/data/local/tmp/wallstate
FINAL_STATUS=0

say() { echo "[acceptance] $*"; }
pass() { echo "[acceptance] PASS: $*"; }
fail() { echo "[acceptance] FAIL: $*" >&2; FINAL_STATUS=1; }

wrap() {
    # wrap <cmd...> with serial option inserted
    if [ -n "$SERIAL_OPT" ]; then
        "$WRAP" $SERIAL_OPT "$@"
    else
        "$WRAP" "$@"
    fi
}

adb_serial() {
    if [ -n "$SERIAL" ]; then
        adb -s "$SERIAL" "$@"
    else
        adb "$@"
    fi
}

strip_ids() {
    # wallpaper ids legitimately change on restore; remove them before comparing state
    sed 's/ (id=[0-9]*)//'
}

make_png() {
    # make_png <file> <r> <g> <b> — solid color PNG, big enough to be a realistic wallpaper
    python - "$1" "$2" "$3" "$4" <<'PY'
import struct, sys, zlib
path, r, g, b = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
w, h = 1080, 2400
row = b'\x00' + bytes([r, g, b]) * w
raw = row * h
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(raw, 9))
png += chunk(b'IEND', b'')
open(path, 'wb').write(png)
PY
}

manifest_field() {
    # manifest_field <zip> <python-expr on json m>
    python - "$1" "$2" <<'PY'
import json, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    m = json.loads(z.read("manifest.json"))
print(eval(sys.argv[2], {"m": m}))
PY
}

snapshot() {
    # snapshot <output-file>
    wrap info | strip_ids > "$1"
}

require_changed() {
    # require_changed <before> <after> <label>
    if cmp -s "$1" "$2"; then
        fail "$3: device state did not change"
    else
        pass "$3: state changed"
    fi
}

require_same() {
    # require_same <expected> <actual> <label>
    if cmp -s "$1" "$2"; then
        pass "$3"
    else
        fail "$3: state differs"
        diff "$1" "$2" | sed 's/^/[acceptance]     /' >&2 || true
    fi
}

dim_supported() {
    # 1 when the device reports a numeric dim state
    info_line=$(wrap info | grep '^dim:' || true)
    case "$info_line" in
        dim:\ unsupported) return 1 ;;
        dim:*) return 0 ;;
        *) return 1 ;;
    esac
}

set_image() {
    # set_image <png> <system|lock|both> [dim]
    adb_serial push "$1" "$DEVICE_TMP/mutate.png" >/dev/null || { fail "push image"; return 1; }
    if [ $# -ge 3 ]; then
        wrap set "$DEVICE_TMP/mutate.png" "$2" "$3"
    else
        wrap set "$DEVICE_TMP/mutate.png" "$2"
    fi
}

lock_mode() {
    wrap info | grep '^lock:' | cut -d' ' -f2
}

mkdir -p "$WORK"

# ---------- A. Preflight ----------
say "A: preflight"
adb_serial wait-for-device
wrap doctor || fail "doctor"
wrap info || fail "info"
say "lock mode at start: $(lock_mode)"

# ---------- B/C. Original backup + reference state ----------
say "B: original backup"
ORIGINAL="$WORK/original-state.zip"
wrap backup "$ORIGINAL" || { fail "backup original"; exit 1; }
wrap verify "$ORIGINAL" || { fail "verify original archive"; exit 1; }
pass "original archive verified"
snapshot "$WORK/info-original.txt"
MODE0=$(lock_mode)
if dim_supported; then DIM0=$(wrap info | grep '^dim:' | cut -d' ' -f2); else DIM0="unsupported"; fi
say "reference: lock=$MODE0 dim=$DIM0"

# Test images
make_png "$WORK/red.png" 200 30 30 || { fail "generate red.png"; exit 1; }
make_png "$WORK/blue.png" 30 60 200 || { fail "generate blue.png"; exit 1; }

run_cycle1() {
    say "D: mutate (cycle 1)"
    case "$MODE0" in
        SEPARATE)
            set_image "$WORK/red.png" system || return 1
            set_image "$WORK/red.png" lock || return 1
            ;;
        INHERIT_SYSTEM)
            set_image "$WORK/red.png" system || return 1
            ;;
    esac
    if dim_supported; then
        wrap set "$DEVICE_TMP/mutate.png" system 0.35 || return 1
    fi
    snapshot "$WORK/info-mutated.txt"
    require_changed "$WORK/info-original.txt" "$WORK/info-mutated.txt" "mutate (cycle 1)"

    say "F: restore original (cycle 1)"
    wrap restore "$ORIGINAL" || { fail "restore (cycle 1)"; return 1; }

    say "G: verify post-restore (cycle 1)"
    snapshot "$WORK/info-restored1.txt"
    require_same "$WORK/info-original.txt" "$WORK/info-restored1.txt" "device state matches original (cycle 1)"

    say "G: archive can be re-created and compared semantically"
    wrap backup "$WORK/second.zip" || { fail "second backup"; return 1; }
    SHA_A=$(manifest_field "$ORIGINAL" "m['system']['originalSha256']")
    SHA_B=$(manifest_field "$WORK/second.zip" "m['system']['originalSha256']")
    [ "$SHA_A" = "$SHA_B" ] || { fail "system sha differs between archives"; return 1; }
    MODE_A=$(manifest_field "$ORIGINAL" "m['lock']['mode']")
    MODE_B=$(manifest_field "$WORK/second.zip" "m['lock']['mode']")
    [ "$MODE_A" = "$MODE_B" ] || { fail "lock mode differs between archives"; return 1; }
    pass "second backup matches original semantically"
}

# Cycle 2: exercise the other lock-relationship transition where safe.
run_cycle2() {
    say "D: mutate (cycle 2)"
    case "$MODE0" in
        SEPARATE)
            # Collapse lock into the system wallpaper, then exercise an INHERIT restore.
            set_image "$WORK/blue.png" both || return 1
            snapshot "$WORK/info-inherit.txt"
            INHERIT_ZIP="$WORK/inherit-state.zip"
            wrap backup "$INHERIT_ZIP" || { fail "inherit-state backup"; return 1; }
            M=$(manifest_field "$INHERIT_ZIP" "m['lock']['mode']")
            [ "$M" = "INHERIT_SYSTEM" ] || { fail "expected INHERIT_SYSTEM archive, got $M"; return 1; }
            set_image "$WORK/red.png" lock || return 1
            wrap restore "$INHERIT_ZIP" || { fail "restore inherit-state"; return 1; }
            snapshot "$WORK/info-restored-inherit.txt"
            require_same "$WORK/info-inherit.txt" "$WORK/info-restored-inherit.txt" "inherit-state restore"
            ;;
        INHERIT_SYSTEM)
            # Create a separate lock wallpaper, then restore the INHERIT archive:
            # exercises migration of the shared image to lock-only + clearWallpaper.
            set_image "$WORK/blue.png" lock || return 1
            ;;
    esac
    if [ "$MODE0" = "INHERIT_SYSTEM" ] && dim_supported; then
        wrap set "$DEVICE_TMP/mutate.png" system 0.45 || return 1
    fi

    say "F: restore original (cycle 2)"
    wrap restore "$ORIGINAL" || { fail "restore (cycle 2)"; return 1; }

    say "G: verify post-restore (cycle 2)"
    snapshot "$WORK/info-restored2.txt"
    require_same "$WORK/info-original.txt" "$WORK/info-restored2.txt" "device state matches original (cycle 2)"
}

run_cycle1
run_cycle2

# ---------- H. Final safety ----------
say "H: final safety restore of the user's original state"
wrap restore "$ORIGINAL" || fail "final restore"
snapshot "$WORK/info-final.txt"
require_same "$WORK/info-original.txt" "$WORK/info-final.txt" "final device state == original pre-test state"
wrap verify "$ORIGINAL" >/dev/null && pass "original archive still verifies" || fail "final archive verify"
adb_serial shell "rm -f $DEVICE_TMP/mutate.png" >/dev/null 2>&1 || true

if [ "$FINAL_STATUS" -eq 0 ]; then
    say "ALL ACCEPTANCE CHECKS PASSED"
else
    say "ACCEPTANCE FAILURES PRESENT (original state still restored)"
fi
exit "$FINAL_STATUS"
