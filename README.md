# Quay

**A quiet home screen for any Android TV.** No sponsored row, no recommendation feed, no banner to scroll
past. A dock, a clock, and whatever is playing.

![Before and after](quay/screenshots/before-after.jpg)

Left: the launcher that shipped on the television. Right: Quay.

---

Cheap Android TVs ship a home screen that sells to you. The set this was built on opened to a Ralph Lauren
advertisement above a row called *Mega Movie Week*. The apps you actually use were below the fold.

Quay replaces it with somewhere you pass through rather than linger — and it does one thing no other
launcher does: **a tile can switch to an HDMI input and open an app on the device plugged into it.**

## What you get

### A dock that behaves like one

Square icons packed into a centred cluster. The focused icon magnifies and its neighbours are *pushed
outward* rather than grown over — the part that makes a dock read as a dock. A five-icon window slides as
focus moves while the rest fade back, so thirteen apps fit without a grid.

Every tile rides a retargetable spring rather than a timed animation. Move focus before the row has settled
and it carries its velocity into the new target instead of snapping onto a fresh curve, which is what makes
a fast D-pad feel smooth rather than jerky.

### Glass that actually refracts

![Quay](quay/screenshots/after.jpg)

Each icon sits on a plate that bends the wallpaper behind it under a specular rim.

Android has no backdrop blur before API 31, and no 1 GB television has the budget for a per-frame one. But
a wallpaper is a photograph that never moves — so it is blurred **once** into a 64-pixel bitmap, about 8 KB,
and every tile samples its own slice of it, magnified about its centre. The specular sweep draws in *front*
of the artwork; behind an opaque icon only the rim escapes and it reads as a picture frame.

Works from Android 8.0 up, costs one small bitmap, and no blur API.

### A tile that reaches your streaming stick

Add `dial:Netflix` to an input tile and selecting it switches the television to that HDMI port **and**
starts Netflix on whatever is plugged into it — so the stream comes off the faster box rather than the
television's own underpowered app.

It goes over [DIAL](http://www.dial-multiscreen.org/), the protocol your phone uses to start Netflix on a
TV. **No ADB, no debugging left enabled, no open port, no RSA handshake**, and it survives updates on the
far device. The limit is coverage: a device only answers for apps that register a DIAL name, which on Fire
OS is Netflix and little else.

### Now playing, from any app

Album art, track, artist and transport controls under the clock — read from whatever media session is
active, so it follows Spotify, a browser, anything. A top-bar button shows or hides it, and switching it
off pauses playback. The strip never changes its own footprint, so nothing on the screen ever shifts.

### A top bar that stays out of the way

Inputs, Wi-Fi and Settings as icon-only buttons, each dropping the same translucent menu, rather than
throwing you into a system screen.

---

## Will it work on my TV?

**Probably — but it has only been proven on one.** Being straight about that: this was developed and tested
on a single Kogan set. Everything below is read off the code and upstream's notes rather than verified
across a fleet.

Nothing device-specific lives in the code. Every chipset-dependent value is in one file, `config.xml`.

| | |
|---|---|
| **Android TV 8.0+** | The launcher itself: plain Java, `minSdk 26`, two AndroidX libraries, no device APIs |
| **Any chipset** | Input ids differ per vendor but are configuration, not code — find yours with `dumpsys tv_input` |
| **Google TV** | Should work; the launcher to disable is `com.google.android.apps.tv.launcherx`, not `...tvlauncher` |
| **Streaming boxes** | Runs, but input tiles and `dial:` have no HDMI to point at |
| **Fire TV** | **No.** Fire OS will not hand Home to a third-party launcher at all |
| **Some CVTE / China-market boards** | Refuse third-party launchers, or need the stock one left enabled — see upstream's notes |

The dock is on by default (`dock_magnify`). Set it false and you get the stock tvOS-style dock from
upstream, with everything else intact — useful if you are bringing this up on an unfamiliar set and want a
known-good starting point.

## Setting it up for your TV

`launcher/app/src/main/res/values/config.xml` ships as a worked example for the set it was built on.
Replace it with yours:

```bash
# your installed apps
adb shell cmd package query-activities -a android.intent.action.MAIN \
    -c android.intent.category.LEANBACK_LAUNCHER | grep packageName

# your HDMI inputs - the ids differ by chipset
adb shell dumpsys tv_input | grep inputId

# which launcher currently owns Home, so you know what to disable
adb shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME
```

Then:

```xml
<string-array name="dock_apps">
    <item>input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW8=Fire TV</item>
    <item>input:…/HW8=Netflix|dial:Netflix|icon:ic_app_tile</item>
    <item>com.google.android.youtube.tv</item>
</string-array>
<integer name="columns">9</integer>
```

A plain package name is an app tile. An `input:` key is a TV input, and takes options after `|`:

| Key | Does |
|---|---|
| `input:<id>` | Switch to this TV input |
| `=<Label>` | Name the tile. Without it, the TV's own name for the input is used, including a rename made in TV settings |
| `\|dial:<App>` | After switching, start this DIAL app on the device plugged in there |
| `\|icon:<x>` | A package to borrow an icon from, or a drawable bundled with the launcher |

Unknown options are ignored, so a key written for a later version still resolves its input.

`columns` is both the grid's span count and the number of dock slots — and because a tile is sized from its
cell, it also sets how large the icons are.

## Build

JDK 17, Android SDK platform 34, build-tools 34.0.0.

```bash
cd launcher
./gradlew --no-daemon lintRelease assembleRelease
# -> app/build/outputs/apk/release/app-release.apk   (~495 KB)
```

## Install

```bash
adb connect <tv-ip>:5555
adb install -r launcher/app/build/outputs/apk/release/app-release.apk

# Home will not move while the stock launcher is enabled: set-home-activity
# returns Success and Home still resolves to the old one. Disable it first.
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

# AOT compile. Cold start 2.78 s -> 764 ms on the test set. Re-run after every install.
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

**The now-playing strip** needs an enabled notification listener, which is what Android gates
`getActiveSessions` behind:

```bash
adb shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
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
| `LENS` | `GlassTile.java` | 1.10 — how hard the glass bends what is behind it; 1.0 is a flat window |
| `AMBIENT_ALPHA` | `MainActivity.java` | 0.18 — the focused app's colour wash over the wallpaper |
| `dock_magnify` | `config.xml` | false restores the stock tvOS dock |

## Things that cost time, so they need not cost yours

Everything here was found the hard way on real hardware.

**`settings put enabled_notification_listeners` does nothing on its own.** The value lands, `dumpsys
package` shows the service, and nothing binds — NotificationManagerService does not re-read the setting.
`cmd notification allow_listener` is what actually binds it.

**A notification listener service must be `exported="true"`.** The system binds it from *outside* the app;
`BIND_NOTIFICATION_LISTENER_SERVICE` is what protects it. With `exported="false"` the bind silently never
happens.

**`adb uninstall` can report `Success` and do nothing useful.** For a system app it only removes the
update, and the factory copy stays, still launchable. `pm uninstall --user 0` removes it for the user.

**A television answers DIAL itself**, on more than one port, and offers whatever is installed locally — so
"has the app" cannot identify the right device. Discovery must skip responders answering from its own
addresses *first*, then take one that offers the app.

**An HDMI port in standby is not an empty port.** On this driver every port reported `state: 1` whether or
not anything was attached; only `state: 0` identified the live device. The passthrough URI happily opens a
session on an empty port, so "it switched" is not proof you found the right one.

**A drawable named only as a string is invisible to the resource shrinker.** `getIdentifier` lookups get
stripped from release builds unless pinned in `res/raw/keep.xml`.

**`measure(UNSPECIFIED, UNSPECIFIED)` ignores `layout_height`** and returns the content's own height — which
places a fixed-height view at half its real size.

**Several TV apps keep their main activity unexported** and register only `LEANBACK_LAUNCHER`, so
`am start -n` is denied. Resolve through `queryIntentActivities` instead; it also survives an app renaming
its activity.

## Credit and licence

The launcher this is built on, the ADB tooling and 260-odd tested debloat lists are the work of
[**Gathin23/cleanyourtv**](https://github.com/Gathin23/cleanyourtv), MIT licensed — all of it still here and
untouched; see [README.upstream.md](README.upstream.md). If you are here for debloating rather than for a
launcher, go there. This fork keeps that licence and copyright (see [LICENSE](LICENSE)) and is not
affiliated with or endorsed by that project.

Kogan, Amazon, Fire TV, Netflix, Spotify and Android TV are the trademarks of their respective owners.
No third-party artwork is distributed here: the tile icons are placeholders, and a tile borrows a real icon
from an app installed on your own device at runtime via `icon:<package>`.
