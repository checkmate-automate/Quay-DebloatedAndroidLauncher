# Quay — this television

Device notes for the set this was built on, a **Kogan KALED50RN9220STA** (Android TV 11, MediaTek m7332, 32-bit ARM, 1.37 GB RAM).

The project README is one level up. This file is the device log: what is true of this particular
television, and the commands that were actually run on it.

## What this fork adds

**TV inputs as dock tiles.** A `dock_apps` entry of the form `input:<id>[=Label]` is a TV input, not a
package. Selecting it switches the television to that HDMI port through
`TvContract.buildChannelUriForPassthroughInput`, reusing the path `RemoteKeys.launch()` already had for
remote-button mappings. Without a label it takes the TV's own name for the input, including a rename made
in TV settings.

**A macOS dock.** Square full-bleed icons packed into one centred cluster, magnifying with their
neighbours pushed outward, a floating name pill above the focused icon, and a five-icon window that slides
as focus moves while the rest fade out. Everything rides retargetable springs, so moving focus before the
row settles keeps its velocity instead of hitching.

Switched on by `dock_magnify` in `config.xml`. Set it false and the stock tvOS dock comes back.

## This television's facts

| | |
|---|---|
| Fire Stick | `com.mediatek.tvinput/.hdmi.HDMIInputService/HW8` (HDMI 4) |
| ADB | `<tv-ip>:5555` (set a DHCP reservation; DIAL rediscovers, ADB does not) |
| Stock launcher | `com.google.android.tvlauncher` — disabled, **not** uninstalled |

The Fire Stick is on **HDMI 4 / HW8**, not HDMI 3. HW8 is the only HDMI that ever reports `state: 0`;
HW5, HW6 and HW7 sit at `state: 1` (standby) whether or not anything is attached, so on this driver
**standby does not mean empty** and only `state: 2` is really disconnected. The passthrough URI opens a
session on any of them regardless, which is why switching to HW7 "worked" while showing nothing. CEC
cannot settle it either: `dumpsys hdmi_control` returns one power-status line and no device names, so
the port state is the only signal, and `state: 0` is the one that identifies the live device.

YouTube's main activity is not exported, so `am start -n` on it is denied. App launches must resolve
through `queryIntentActivities` with `CATEGORY_LEANBACK_LAUNCHER`.

## Build

Needs JDK 17 and Android SDK platform 34 + build-tools 34.0.0. On this machine both are at
`%LOCALAPPDATA%\Android` (`jdk-17.0.20.1+1` and `Sdk`).

```powershell
$env:JAVA_HOME    = "$env:LOCALAPPDATA\Android\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd launcher
"sdk.dir=$($env:ANDROID_HOME -replace '\\','\\')" | Out-File -Encoding ascii local.properties
.\gradlew.bat --no-daemon lintRelease assembleRelease
```

Output: `launcher/app/build/outputs/apk/release/app-release.apk`.

> Upstream CI caps the APK at 491,520 B. This fork is a few KB over, which matters only for a pull
> request upstream, not for installing here.

## Install

```bash
adb connect <tv-ip>:5555
adb install -r launcher/app/build/outputs/apk/release/app-release.apk

# Home will not move while the stock launcher is enabled: set-home-activity
# returns Success and Home still resolves to Google's. Disable it first.
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

# AOT compile: cold start 2.78 s -> 764 ms. Re-run after every install.
adb shell cmd package compile -m speed -f com.gathin.tvhome
```

## Wallpaper

Not in the APK — the launcher reads its theme directory, which ADB can write to directly.

```bash
DIR=/sdcard/Android/data/com.gathin.tvhome/files
adb push quay/wallpaper.jpg $DIR/wallpaper.jpg
adb push quay/config.json   $DIR/config.json
adb shell am force-stop com.gathin.tvhome
```

`config.json` overlays `config.xml` and only overrides the keys it names. It currently sets
`wallpaper_dim: 16`, enough to hold the clock and pills against the bright sky without flattening it.

**These files do not survive a factory reset or Clear data on the launcher.** Re-push them.

## Revert

`disable-user` is not an uninstall, so this restores the stock launcher completely and survives a reboot.

```bash
adb shell pm enable --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity
```

## Tuning

| Constant | File | Does |
|---|---|---|
| `DOCK_MAGNIFY` | `AppAdapter.java` | Focused icon and its two neighbours: `{1.42, 1.16, 1.05}` |
| `DOCK_WINDOW` | `AppAdapter.java` | 2 → five icons at full strength; 3 → seven |
| `DOCK_EDGE_ALPHA` | `AppAdapter.java` | 0.28 — the faded icons at the window's edge |
| `DOCK_EDGE_SCALE` | `AppAdapter.java` | 0.86 — how far those shrink back |
| `DOCK_STIFFNESS` | `AppAdapter.java` | 380 — spring speed; critically damped, no bounce |
| `DOCK_GAP_DP` | `AppAdapter.java` | 12 — space between packed icons |
| `AMBIENT_ALPHA` | `MainActivity.java` | 0.18 — focused app's colour wash over the wallpaper |
| `columns` | `config.xml` | 8 — both the grid span count and the dock's slot count, and it sets icon size |

No blur anywhere: `RenderEffect` is API 31 and this runs from 26, and upstream rules it out on 1 GB
sets. Depth is carried by fade and scale instead.

## If Home reverts on its own

A firmware update re-enables disabled packages and may re-run `android.autoinstalls.config.homwee`,
bringing back both the stock launcher and some preloads. Re-apply the two install commands above.
