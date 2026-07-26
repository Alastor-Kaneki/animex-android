#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
MODE="${1:-debug}"

case "$PROJECT_DIR" in
    /sdcard/*|/storage/emulated/*)
        cat >&2 <<'MSG'
Do not build Gradle projects from /sdcard or /storage/emulated/0.
Move the project into Termux home and run the command again.
MSG
        exit 1
        ;;
esac

if [[ -z "${PREFIX:-}" || ! -x "${PREFIX:-/missing}/bin/pkg" ]]; then
    echo "Run this script inside Termux." >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1 || \
   ! command -v aapt2 >/dev/null 2>&1 || \
   [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]] || \
   [[ ! -f "$SDK_ROOT/platforms/android-36/android.jar" ]]; then
    echo "The Termux Android toolchain is not installed yet; setting it up now."
    "$PROJECT_DIR/termux-setup.sh" --no-build
fi

JAVA_BIN="$(command -v java)"
AAPT2_BIN="$(command -v aapt2)"
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
export JAVA_HOME
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$JAVA_HOME/bin:$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$PROJECT_DIR/local.properties"
cd "$PROJECT_DIR"

TASK=""
APK_SOURCE=""
APK_DEST=""
OPEN_INSTALLER=false

case "$MODE" in
    debug)
        TASK="assembleDebug"
        APK_SOURCE="app/build/outputs/apk/debug/app-debug.apk"
        APK_DEST="OrbitLauncher-debug.apk"
        ;;
    install)
        TASK="assembleDebug"
        APK_SOURCE="app/build/outputs/apk/debug/app-debug.apk"
        APK_DEST="OrbitLauncher-debug.apk"
        OPEN_INSTALLER=true
        ;;
    release)
        TASK="assembleRelease"
        APK_SOURCE="app/build/outputs/apk/release/app-release-unsigned.apk"
        APK_DEST="OrbitLauncher-release-unsigned.apk"
        ;;
    test)
        TASK="testDebugUnitTest"
        ;;
    clean)
        TASK="clean"
        ;;
    *)
        cat >&2 <<'MSG'
Usage: ./termux-build.sh [debug|install|release|test|clean]

  debug    Build an installable debug APK (default)
  install  Build the debug APK and open Android's package installer
  release  Build an unsigned optimized release APK
  test     Run local unit tests
  clean    Remove Gradle build outputs
MSG
        exit 2
        ;;
esac

printf '\n\033[1;35m==> Running Gradle task: %s\033[0m\n' "$TASK"

# Google's Maven AAPT2 executable is built for desktop x86_64 Linux.
# This override forces AGP to use Termux's native Android/ARM64 binary.
./gradlew \
    --no-daemon \
    --console=plain \
    -Pandroid.aapt2FromMavenOverride="$AAPT2_BIN" \
    "$TASK"

if [[ -n "$APK_SOURCE" ]]; then
    if [[ ! -f "$APK_SOURCE" ]]; then
        echo "Gradle finished, but the expected APK was not found: $APK_SOURCE" >&2
        exit 1
    fi

    cp -f "$APK_SOURCE" "$APK_DEST"
    printf '\n\033[1;32mAPK created:\033[0m %s/%s\n' "$PROJECT_DIR" "$APK_DEST"

    if $OPEN_INSTALLER; then
        if command -v termux-open >/dev/null 2>&1; then
            termux-open --view "$PROJECT_DIR/$APK_DEST"
        else
            echo "termux-open is unavailable. Open $APK_DEST manually to install it."
        fi
    fi
fi
