# Orbit Launcher

A gesture-driven Android home launcher where installed apps are distributed over a virtual sphere rather than placed on pages.

## Features

- Swipe in any direction to rotate the app sphere
- Momentum/inertial rotation after a fling
- Perspective depth: front apps are larger and brighter
- Tap an icon to launch it
- Long-press an icon to add or remove it from favorites
- Search by app name or package name
- Automatically refreshes when apps are installed, removed, or updated
- Requests the Android Home role so it can become the default launcher
- Edge-to-edge dark interface

## Project configuration

- Package: `com.alastor.orbitlauncher`
- Version: `0.1.0` (`versionCode 1`)
- Minimum Android: 6.0 / API 23
- Target and compile SDK: API 36
- Build Tools: 36.0.0
- Kotlin + Jetpack Compose

## Build directly in Termux (ARM64)

Use a current Termux installation with working package repositories. Keep the project under Termux home, **not** `/sdcard` or `/storage/emulated/0`, because shared storage does not provide the filesystem behavior Gradle expects.

After downloading the ZIP:

```bash
termux-setup-storage
cp /sdcard/Download/OrbitLauncher-termux.zip ~/
cd ~
unzip OrbitLauncher-termux.zip
cd OrbitLauncher
./termux-setup.sh
```

`termux-setup.sh` installs OpenJDK 21 and Termux's native ARM64 `aapt2`, downloads the official Android command-line tools, installs API 36 plus Build Tools 36.0.0, and builds the first debug APK.

The completed APK is copied to:

```text
OrbitLauncher-debug.apk
```

### Later builds

```bash
./termux-build.sh debug
```

Build and immediately open Android's package installer:

```bash
./termux-build.sh install
```

Other commands:

```bash
./termux-build.sh test
./termux-build.sh clean
./termux-build.sh release
```

The release command produces an **unsigned** optimized APK. The debug APK is already signed with Gradle's debug key and is directly installable.

### Why the custom Termux command is necessary

Android Gradle Plugin normally downloads an AAPT2 executable built for desktop x86_64 Linux. Android phones are generally ARM64, so `termux-build.sh` supplies:

```text
-Pandroid.aapt2FromMavenOverride=<Termux ARM64 aapt2>
```

This allows the rest of the normal Gradle Android build to run natively in Termux without a proot Linux distribution.

### Storage and memory notes

- Keep at least 2–3 GB free for the Android SDK, Gradle, Maven dependencies, and build caches.
- The project limits Gradle to two workers and a 1.5 GB heap for devices with modest memory.
- The first build downloads substantially more data than later builds.
- If `pkg` reports a broken or unavailable mirror, run `termux-change-repo`, select another main repository, and rerun `./termux-setup.sh`.

## Standard desktop build

Java 17 or newer is required:

```bash
./gradlew assembleDebug
```

The normal Gradle output is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Use as your launcher

1. Install and open Orbit Launcher.
2. Tap the Home button at the bottom-right.
3. Select Orbit Launcher and choose **Always** when Android asks for a Home app.

Some Android skins place this under **Settings > Apps > Default apps > Home app**.

## Privacy

Orbit Launcher does not request network, storage, contacts, location, or notification permissions. Its only package-visibility declaration is the launcher intent query needed to enumerate launchable activities.

## GitHub build

A GitHub Actions workflow is included at `.github/workflows/build-debug-apk.yml`. The completed workflow uploads `OrbitLauncher-debug` as an artifact.
