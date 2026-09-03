# XTools

`XTools` is a root-oriented Android system toolbox for device monitoring, performance tuning, memory management, process inspection, and power control.

Its design goal is:

- keep privileged system access outside the Android UI process where practical
- expose low-level `/proc` and `/sys` information through a structured interface
- provide device tuning controls without scattering shell commands across UI code
- keep feature code separated from reusable UI infrastructure
- build a modern Android interface on top of [`uihelper`](https://github.com/XiaoTong6666/uihelper)

> [!WARNING]
> `XTools` is under active development and directly interacts with kernel and device-specific interfaces.
>
> Incorrect values may cause instability, reboots, boot loops, excessive power consumption, or hardware-specific problems. Test settings manually before enabling any boot-time behavior.

## Requirements

`XTools` currently targets rooted Android devices.

Runtime requirements:

- Android 12L / API 32 or later
- a working root implementation providing `su`
- `arm64-v8a` or `armeabi-v7a`
- kernel and vendor interfaces required by the feature being used

The Android application is currently built with:

- `compileSdk 37`
- `targetSdk 36`
- `minSdk 32`
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- Kotlin + Jetpack Compose
- Rust stable for the privileged daemon

Many tuning paths are kernel-, SoC-, or vendor-specific. A feature being visible in the UI does not guarantee that the corresponding interface exists on every device.

## Current Features

### Home

The home dashboard provides a live overview of important system state, including:

- CPU load and per-core information
- memory usage
- swap state
- running processes
- battery status and temperature
- common memory maintenance actions

The dashboard is backed by structured snapshots instead of independent shell commands from every widget.

### CPU Control

The CPU control page currently supports:

- CPU cluster discovery
- CPU core online state
- available and current frequencies
- minimum and maximum frequency control
- CPU frequency governors
- governor parameter inspection and editing
- CPU frequency time-in-state information
- `cpuset` configuration for:
  - background
  - system-background
  - foreground
  - top-app
- Qualcomm KGSL GPU minimum and maximum frequency control when supported

The implementation reads and writes kernel interfaces directly through the privileged backend.

### Memory / Swap

The memory page provides both monitoring and tuning facilities.

Current functionality includes:

- physical memory information
- file-backed swap inspection and management
- swap file creation and removal
- swap priority configuration
- ZRAM inspection
- ZRAM size configuration
- compression algorithm selection
- `vm.swappiness`
- memory watermark related tuning
- saved memory profiles
- optional boot-time profile application

Boot-time settings should only be enabled after the same configuration has been verified to work correctly during normal runtime.

### Process Monitor

The process page provides a live process view with:

- PID
- process name
- Android application association when available
- CPU usage
- resident memory
- PSS and additional process statistics when available
- process search
- sorting by PID, CPU, memory, or name
- filtering between all processes and application processes
- continuously refreshed process detail information

### Battery and Charging

The charging pages currently expose:

- battery capacity
- temperature
- health
- charging state
- charging type
- current
- voltage
- calculated power
- USB PD capability and state
- bypass charging capability detection
- step charging information
- charging current limit control
- charging enable / disable control
- lower-level daemon battery information

These controls depend heavily on vendor kernel interfaces and may not be available on every device.

## Development Status

The feature catalog is larger than the currently completed implementation.

The primary native-backed pages currently include:

- Home
- CPU Control
- Memory / Swap
- Process Monitor
- Charging Control
- Charging Statistics

Several other entries are currently scaffolding or migration targets, including parts of:

- performance benchmarking
- power benchmarking
- FPS tools
- screen testing
- application tools
- Magisk tools
- add-ins
- backup / restore
- additional tuning and settings pages

Do not treat the presence of a catalog entry as a guarantee that the feature has already been implemented.

## Architecture

`XTools` is split into several layers.

### Android UI

The application UI is written primarily with Kotlin and Jetpack Compose.

Feature code lives under:

```text
app/src/main/java/io/github/xiaotong6666/feature/
```

Major feature packages currently include:

```text
feature/
├── battery/
├── cpu/
├── home/
├── process/
├── swap/
├── tuner/
└── legacy/
```

Feature pages should contain presentation and feature-specific state rather than privileged process-management logic.

### uihelper

Reusable UI infrastructure is provided by the [`uihelper`](https://github.com/XiaoTong6666/uihelper) submodule.

It owns generic UI concerns such as:

- adaptive components
- shared application chrome
- dialogs
- navigation helpers
- common presentation models
- reusable Android application UI extensions

`XTools` should keep system tuning models, kernel paths, daemon commands, and other project-specific semantics in the application rather than moving them into `uihelper`.

The intended dependency direction is:

```text
XTools feature
    ↓
uihelper adaptive/common APIs
    ↓
skin-specific UI implementation
```

Not:

```text
uihelper
    ↓
XTools feature code
```

### Privileged Bridge

Kotlin-side privileged access is organized under:

```text
core/
├── bridge/
├── daemon/
└── native/
```

The bridge provides structured operations for reading system state and applying supported changes without requiring individual UI components to manage root shell sessions themselves.

### Root Daemon

The privileged daemon is implemented in Rust:

```text
app/src/main/cpp/tools-daemon/
```

It is responsible for low-level operations such as:

- `/proc` inspection
- `/sys` inspection and writes
- CPU information and control
- memory and swap operations
- process inspection
- battery information
- device information
- controlled shell execution required by supported operations

The Android application extracts the matching daemon binary and starts it through `su`.

Communication uses an abstract Unix domain socket with per-session configuration, including an allowed application UID and a randomly generated session token.

A watchdog monitors the daemon and can restart it if the expected socket disappears.

### Native Code

Additional native functionality and JNI integration lives under:

```text
app/src/main/cpp/
```

This layer contains native helpers and JNI glue used by Android-side features that are better implemented outside Kotlin.

## Repository Layout

```text
XTools/
├── app/
│   ├── src/main/java/
│   │   └── io/github/xiaotong6666/
│   │       ├── app/
│   │       ├── core/
│   │       ├── feature/
│   │       └── ui/
│   └── src/main/cpp/
│       ├── core/
│       ├── jni/
│       ├── tools-daemon/
│       └── third_party/
├── gradle/
├── uihelper/
├── build.gradle.kts
└── settings.gradle.kts
```

`uihelper` is maintained as a Git submodule and should remain reusable independently of `XTools`.

## Building

Clone the repository together with its submodules:

```bash
git clone --recursive https://github.com/XiaoTong6666/XTools.git
cd XTools
```

If the repository has already been cloned without submodules:

```bash
git submodule update --init --recursive
```

Make sure the Android SDK is configured through one of:

```text
local.properties -> sdk.dir
ANDROID_HOME
ANDROID_SDK_ROOT
```

The build also requires:

- Android SDK platform 37
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- Rust and `rustup`

Build a debug APK with:

```bash
./gradlew :app:assembleDebug
```

Build a release APK with:

```bash
./gradlew :app:assembleRelease
```

The Gradle build automatically builds the Rust `tools-daemon` binaries and packages them into the application assets.

Rust Android targets are installed through the associated Gradle task when required.

## Formatting

Kotlin, Java, C/C++, and Rust formatting is integrated into the Gradle project.

Run:

```bash
./gradlew format
```

The formatting task uses Spotless for JVM/native source files and `cargo fmt` for the Rust daemon.

## Adding Features

New privileged features should normally follow this direction:

```text
Compose page / state
        ↓
Kotlin bridge
        ↓
daemon IPC
        ↓
Rust privileged implementation
        ↓
/proc, /sys, or other system interface
```

Avoid introducing new long-lived `su` shell sessions directly inside Compose pages.

When adding a feature:

1. keep UI state and rendering inside the corresponding `feature` package
2. put reusable structured models in the bridge layer when appropriate
3. implement privileged operations in the daemon when they require root
4. keep device-specific failures recoverable
5. treat missing sysfs/procfs nodes as unsupported hardware rather than fatal application errors
6. avoid boot-time application of values that have not already been validated interactively

## Device Compatibility

Android system tuning is not standardized across kernels and vendors.

For example, the following may differ between devices:

- CPU frequency driver layout
- available governors
- cpuset configuration
- KGSL GPU paths
- ZRAM configuration
- charging control nodes
- bypass charging interfaces
- battery telemetry paths
- memory tuning parameters

Code should therefore prefer capability detection and graceful fallback instead of assuming that a particular path exists.

## Project Direction

`XTools` is intended to evolve toward a structured root system toolbox rather than a collection of unrelated shell scripts.

The long-term architecture should preserve these boundaries:

```text
UI
↓
feature state
↓
typed privileged API
↓
daemon
↓
kernel / system
```

Feature pages should describe **what** operation is requested.

The privileged backend should decide **how** that operation is performed on the current device.
