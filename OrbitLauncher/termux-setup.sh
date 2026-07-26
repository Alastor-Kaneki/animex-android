#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_REV="15859902"
CMDLINE_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip"
BUILD_AFTER_SETUP=true

if [[ "${1:-}" == "--no-build" ]]; then
    BUILD_AFTER_SETUP=false
elif [[ $# -gt 0 ]]; then
    echo "Usage: ./termux-setup.sh [--no-build]" >&2
    exit 2
fi

case "$PROJECT_DIR" in
    /sdcard/*|/storage/emulated/*)
        cat >&2 <<'MSG'
Orbit Launcher cannot be built from shared Android storage.
Move the project into Termux home first, for example:

  cp -r /sdcard/Download/OrbitLauncher "$HOME/"
  cd "$HOME/OrbitLauncher"
  ./termux-setup.sh
MSG
        exit 1
        ;;
esac

if [[ -z "${PREFIX:-}" || ! -x "${PREFIX:-/missing}/bin/pkg" ]]; then
    echo "Run this script inside the Termux app." >&2
    exit 1
fi

log() {
    printf '\n\033[1;35m==> %s\033[0m\n' "$*"
}

log "Updating Termux packages"
pkg update -y

log "Installing the ARM64 Android build dependencies"
pkg install -y openjdk-21 aapt2 curl unzip zip

JAVA_BIN="$(command -v java)"
AAPT2_BIN="$(command -v aapt2)"
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
export JAVA_HOME
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

mkdir -p "$SDK_ROOT/cmdline-tools"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
    log "Downloading Android command-line tools"
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT
    TOOLS_ZIP="$TMP_DIR/command-line-tools.zip"

    curl --fail --location --retry 4 --retry-delay 3 \
        "$CMDLINE_TOOLS_URL" -o "$TOOLS_ZIP"

    ACTUAL_SHA256="$(sha256sum "$TOOLS_ZIP" | awk '{print $1}')"
    if [[ "$ACTUAL_SHA256" != "$CMDLINE_TOOLS_SHA256" ]]; then
        echo "Android command-line tools checksum mismatch." >&2
        echo "Expected: $CMDLINE_TOOLS_SHA256" >&2
        echo "Actual:   $ACTUAL_SHA256" >&2
        exit 1
    fi

    rm -rf "$SDK_ROOT/cmdline-tools/latest"
    mkdir -p "$SDK_ROOT/cmdline-tools/latest"
    unzip -q "$TOOLS_ZIP" -d "$TMP_DIR/unpacked"
    mv "$TMP_DIR/unpacked/cmdline-tools/"* \
        "$SDK_ROOT/cmdline-tools/latest/"
fi

export PATH="$JAVA_HOME/bin:$SDK_ROOT/cmdline-tools/latest/bin:$PATH"

log "Accepting Android SDK licenses"
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null
LICENSE_STATUS=$?
set -o pipefail
if [[ $LICENSE_STATUS -ne 0 ]]; then
    echo "Warning: sdkmanager returned $LICENSE_STATUS while accepting licenses." >&2
fi

log "Installing Android API 36 and Build Tools 36.0.0"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
    "platforms;android-36" \
    "build-tools;36.0.0"

printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$PROJECT_DIR/local.properties"
chmod +x "$PROJECT_DIR/gradlew" \
    "$PROJECT_DIR/termux-setup.sh" \
    "$PROJECT_DIR/termux-build.sh"

cat > "$HOME/.orbit-launcher-termux-env" <<EOF_ENV
export JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="\$JAVA_HOME/bin:\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$PATH"
EOF_ENV

log "Termux toolchain is ready"
echo "Java:  $JAVA_HOME"
echo "SDK:   $SDK_ROOT"
echo "AAPT2: $AAPT2_BIN"

if $BUILD_AFTER_SETUP; then
    exec "$PROJECT_DIR/termux-build.sh" debug
fi
