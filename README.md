# Quay

A quiet television home screen. No sponsored rows, no recommendation feed, no banner you have to scroll
past — a dock, a clock, and whatever is playing.

Built for one set, a **Kogan KALED50RN9220STA** (Android TV 11, MediaTek m7332, 32-bit ARM, 1.37 GB RAM),
and published in case it is useful on another.

![Before and after](quay/screenshots/before-after.jpg)

Left: the launcher that ships on the set. Right: Quay.

---

## What it is

A fork of the reference launcher in [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv), which
does the hard parts — the Home guard, the install and rollback path, 260-odd tested debloat lists, the ADB
tooling. All of that is theirs and untouched here; see [README.upstream.md](README.upstream.md).

What this fork adds is a different front end, and the ability for a tile to reach a *second* device.

## What it adds

**A dock that behaves like one.** Square icons packed into a centred cluster, magnifying with their
neighbours pushed outward rather than growing over them, and a five-icon window that slides as focus moves
while the rest fade back. Every tile rides a retargetable spring, so moving focus before the row has
settled keeps its velocity instead of hitching.

**Glass.** Each icon sits on a plate that refracts the wallpaper behind it under a specular rim. Android
has no backdrop blur before API 31 and no budget for a per-frame one on a 1 GB set — but the wallpaper is a
photograph that never moves, so it is blurred *once* into a 64-pixel bitmap (about 8 KB) and each tile
samples its own slice, magnified about its centre. The specular sweep is drawn in front of the artwork, not
behind it; behind an opaque icon only the rim escapes, and it reads as a frame rather than as glass.

**TV inputs as tiles.** A dock entry of the form `input:<id>` is an HDMI port rather than a package.
Selecting it switches the television to that input.

**A tile can open an app on the device plugged into that input.** Add `dial:<App>` and the tile switches
the input *and* starts the app on whatever is downstream — over [DIAL](http://www.dial-multiscreen.org/),
the protocol a phone uses to start Netflix on a TV. No ADB, no debugging left enabled, no open port, no RSA
handshake, and it survives updates on the far device. The limit is coverage: a device only answers for apps
that register a DIAL name, which on Fire OS is Netflix and little else.

**Now playing.** Album art, track, artist and transport controls under the date, read from whatever media
session is active rather than from any one app. A top-bar button shows or hides it, and switching it off
pauses playback.

**Icon-only top bar**, where Inputs, Wi-Fi and Settings each drop the same glass menu.

![Quay](quay/screenshots/after.jpg)

## Hardware this was tested on

| | |
|---|---|
| Television | Kogan KALED50RN9220STA, Android TV 11, MediaTek m7332, armv7, 1.37 GB RAM |
| Second device | Fire TV Stick 4K (AFTKM, karat), Fire OS 8.1.8.5, on HDMI 4 |

Nothing here is specific to Kogan beyond `config.xml`. The input ids, the dock contents and the DIAL target
are all configuration.

## Build

JDK 17, Android SDK platform 34, build-tools 34.0.0.

```bash
cd launcher
./gradlew --no-daemon lintRelease assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

> Upstream caps the APK at 480 KB in CI. This fork is a few KB over, which matters only for a pull request
> upstream, not for installing it.

## Install

```bash
adb connect <tv-ip>:5555
adb install -r launcher/app/build/outputs/apk/release/app-release.apk

# Home will not move while the stock launcher is enabled: set-home-activity
# returns Success and Home still resolves to the old launcher. Disable it first.
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

# AOT compile. Cold start 2.78 s -> 764 ms on this set. Re-run after every install.
adb shell cmd package compile -m speed -f com.gathin.tvhome
```

To undo — `disable-user` is not an uninstall, so this restores the stock launcher completely and survives a
reboot:

```bash
adb shell pm enable --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity
```

## Two one-time setup steps

Neither survives a factory reset or a `pm clear`, and both fail quietly rather than loudly.

**The wallpaper** is read from the launcher's own directory, not packaged with it:

```bash
DIR=/sdcard/Android/data/com.gathin.tvhome/files
adb push quay/wallpaper.jpg $DIR/wallpaper.jpg
adb push quay/config.json   $DIR/config.json
adb shell am force-stop com.gathin.tvhome
```

`config.json` overlays `config.xml` and only overrides the keys it names; it sets `wallpaper_dim` so the
clock stays legible against a bright sky.

**The now-playing strip** needs an enabled notification listener, which is what Android gates
`getActiveSessions` behind:

```bash
adb shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
```

Writing `enabled_notification_listeners` with `settings put` is **not** enough — the value lands,
`dumpsys package` shows the service, and nothing binds, because NotificationManagerService does not re-read
the setting. Without the bind, `getActiveSessions` throws `SecurityException` and the strip simply stays
hidden.

## Configuring the dock

`launcher/app/src/main/res/values/config.xml`:

```xml
<string-array name="dock_apps">
    <item>input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW8=Fire TV</item>
    <item>input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW8=Netflix|dial:Netflix|icon:ic_app_tile</item>
    <item>com.google.android.youtube.tv</item>
</string-array>
<integer name="columns">9</integer>
```

A plain package name is an app tile. An `input:` key is a TV input, and takes options after `|`:

| Key | Does |
|---|---|
| `input:<id>` | Switch to this TV input. Find ids with `adb shell dumpsys tv_input` |
| `=<Label>` | Name the tile. Without it, the TV's own name for the input is used, including a rename made in TV settings |
| `\|dial:<App>` | After switching, start this DIAL app on the device plugged in there |
| `\|icon:<x>` | A package name to borrow an icon from, or a drawable bundled with the launcher |

Unknown options are ignored, so a key written for a later version still resolves its input.

`columns` is both the grid's span count and the number of dock slots — and because a tile is sized from its
cell, it also sets how large the icons are.

## Tuning

| Constant | File | Does |
|---|---|---|
| `DOCK_MAGNIFY` | `AppAdapter.java` | Focused icon and its two neighbours: `{1.42, 1.16, 1.05}` |
| `DOCK_WINDOW` | `AppAdapter.java` | 2 → five icons at full strength; 3 → seven |
| `DOCK_EDGE_ALPHA` | `AppAdapter.java` | 0.28 — the faded icons at the window's edge |
| `DOCK_EDGE_SCALE` | `AppAdapter.java` | 0.86 — how far those shrink back |
| `DOCK_STIFFNESS` | `AppAdapter.java` | 380 — spring speed; critically damped, no bounce |
| `DOCK_GAP_DP` | `AppAdapter.java` | 12 — space between packed icons |
| `LENS` | `GlassTile.java` | 1.10 — how hard the glass bends what is behind it; 1.0 is a flat window |
| `AMBIENT_ALPHA` | `MainActivity.java` | 0.18 — the focused app's colour wash over the wallpaper |
| `dock_magnify` | `config.xml` | false restores the stock tvOS dock |

## Things worth knowing

**Blur is not available.** `RenderEffect` is API 31 and this runs from API 26, and upstream rules out blur
on 1 GB sets regardless. Depth is carried by fade and scale, and the glass by a pre-blurred backdrop.

**The television answers DIAL itself**, on more than one port, and will offer any app installed locally — so
"has the app" cannot identify the right device. Discovery skips any responder answering from one of this
device's own addresses *first*, then takes one that offers the app.

**`adb uninstall` can report success and do nothing useful.** For a system app it only removes the update;
the factory copy stays and is still launchable. `pm uninstall --user 0` is what removes it for the user.

**App launches cannot use a component name.** Several TV apps register only `LEANBACK_LAUNCHER` and keep
their main activity unexported, so `am start -n` is denied. Launches resolve through `queryIntentActivities`
at press time, which also means a tile survives an app renaming its activity.

## Credit and licence

The launcher, the tooling and the debloat lists are the work of
[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv), MIT licensed. This fork keeps that licence
and that copyright; see [LICENSE](LICENSE). It is not affiliated with or endorsed by that project.

Kogan, Amazon, Fire TV, Netflix, Spotify and Android TV are the trademarks of their respective owners. This
project is not affiliated with any of them. No third-party artwork is distributed here: the tile icons are
placeholders, and a tile borrows a real icon from an app installed on your own device at runtime, via
`icon:<package>`.
