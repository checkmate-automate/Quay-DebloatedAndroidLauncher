# Quay

**Your TV came with a home screen that sells to you. This one doesn't.**

![Before and after](quay/screenshots/before-after.jpg)

The television this was built for opened on an advertisement for Ralph Lauren, above a row called *Mega
Movie Week*, above *Trending on Google*. The apps I actually used were below the fold.

Quay is a dock, a clock, and whatever's playing.

## The dock

![Quay](quay/screenshots/after.jpg)

Icons sit in a tight row at the bottom of the screen. The focused one grows and the icons beside it move
aside to make room. Thirteen apps fit in a window of five that slides as you move along, so the dock never
becomes a grid.

Tiles move on springs, so holding down a direction stays smooth at speed.

## Glass

Every icon sits on a plate that bends the wallpaper behind it, lit along its top edge.

Android has no backdrop blur below API 31, and a 1 GB television can't afford one per frame. A wallpaper
doesn't move, though, so it's blurred once into a 64 pixel bitmap of about 8 KB, and each tile samples the
slice behind it and magnifies it. Real refraction on Android 8.0 and up, for 8 KB and no blur API.

## Open an app on your streaming stick

Put `dial:Netflix` on an input tile. Pressing it switches the TV to HDMI 4 and opens Netflix on the Fire
Stick plugged into it, so the stream comes off the faster box instead of the television's own app.

It uses [DIAL](http://www.dial-multiscreen.org/), the protocol your phone uses to send Netflix to a TV. No
ADB, no debugging left enabled, no open port, and nothing to re-pair after an update.

DIAL only reaches apps that register a DIAL name. On Fire OS that's Netflix and little else.

## Now playing

Album art, track, artist and transport controls under the clock, read from the active media session. It
follows Spotify, a browser, or anything else that publishes one. A button in the top bar hides it, and
hiding it pauses playback.

## Top bar

Inputs, Wi-Fi and Settings as three icons, each opening its own frosted menu rather than the system
settings app.

## Will it work on my TV?

It's been tested on one television. Nothing device specific lives in the code; every chipset dependent
value is in `config.xml`.

| | |
|---|---|
| **Android TV 8.0+** | Yes. Plain Java, two AndroidX libraries |
| **Any chipset** | Input ids differ by vendor, but they're config |
| **Google TV** | Should work. Different launcher package to disable |
| **Streaming boxes** | Runs, but has no HDMI inputs to point at |
| **Fire TV** | No. Fire OS won't hand Home to another launcher |
| **Some CVTE and China market boards** | Refuse third party launchers. See upstream's notes |

Set `dock_magnify` to false for upstream's stock dock with everything else intact, which is a safer
starting point on an unfamiliar set.

## Setting it up for your TV

`launcher/app/src/main/res/values/config.xml` ships as a worked example for the set it was built on.
Replace it with yours:

```bash
# your installed apps
adb shell cmd package query-activities -a android.intent.action.MAIN \
    -c android.intent.category.LEANBACK_LAUNCHER | grep packageName

# your HDMI inputs, whose ids differ by chipset
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
| `=<Label>` | Name the tile. Without it the TV's own name is used, including a rename made in TV settings |
| `\|dial:<App>` | After switching, start this DIAL app on the device plugged in there |
| `\|icon:<x>` | A package to borrow an icon from, or a drawable bundled with the launcher |

Unknown options are ignored, so a key written for a later version still resolves its input.

`columns` sets the grid's span count and the number of dock slots. Tiles are sized from their cell, so it
also controls how large the icons are.

## Build

JDK 17, Android SDK platform 34, build-tools 34.0.0.

```bash
cd launcher
./gradlew --no-daemon lintRelease assembleRelease
# -> app/build/outputs/apk/release/app-release.apk   (about 495 KB)
```

## Install

```bash
adb connect <tv-ip>:5555
adb install -r launcher/app/build/outputs/apk/release/app-release.apk

# Home won't move while the stock launcher is enabled. set-home-activity returns
# Success and Home still resolves to the old one, so disable it first.
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity

# AOT compile. Cold start went from 2.78 s to 764 ms on the test set.
# Re-run after every install.
adb shell cmd package compile -m speed -f com.gathin.tvhome
```

To undo. `disable-user` is not an uninstall, so this restores the stock launcher completely and survives a
reboot:

```bash
adb shell pm enable --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity
```

## Two one-time setup steps

Neither survives a factory reset or a `pm clear`, and both fail quietly.

**The wallpaper** is read from the launcher's own directory rather than packaged with it:

```bash
DIR=/sdcard/Android/data/com.gathin.tvhome/files
adb push quay/wallpaper.jpg $DIR/wallpaper.jpg
adb push quay/config.json   $DIR/config.json
adb shell am force-stop com.gathin.tvhome
```

**The now playing strip** needs an enabled notification listener, which is what Android gates
`getActiveSessions` behind:

```bash
adb shell cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener
```

## Tuning

| Constant | File | Does |
|---|---|---|
| `DOCK_MAGNIFY` | `AppAdapter.java` | Focused icon and its two neighbours: `{1.42, 1.16, 1.05}` |
| `DOCK_WINDOW` | `AppAdapter.java` | 2 gives five icons at full strength, 3 gives seven |
| `DOCK_EDGE_ALPHA` | `AppAdapter.java` | 0.28, the faded icons at the window's edge |
| `DOCK_EDGE_SCALE` | `AppAdapter.java` | 0.86, how far those shrink back |
| `DOCK_STIFFNESS` | `AppAdapter.java` | 380, spring speed. Critically damped, no bounce |
| `DOCK_GAP_DP` | `AppAdapter.java` | 12, space between packed icons |
| `LENS` | `GlassTile.java` | 1.10, how hard the glass bends what's behind it. 1.0 is a flat window |
| `AMBIENT_ALPHA` | `MainActivity.java` | 0.18, the focused app's colour wash over the wallpaper |
| `dock_magnify` | `config.xml` | false restores the stock tvOS dock |

## Notes for anyone building on Android TV

**`settings put enabled_notification_listeners` doesn't bind anything.** The value saves and `dumpsys`
shows the service, but NotificationManagerService never re-reads the setting. Use
`cmd notification allow_listener`.

**A notification listener must be `exported="true"`.** The system binds it from outside the app, and
`BIND_NOTIFICATION_LISTENER_SERVICE` is what protects it. With `exported="false"` the bind fails silently.

**`adb uninstall` reports `Success` on a system app without removing it.** It strips the update and leaves
the factory copy installed and launchable. `pm uninstall --user 0` removes it for the user.

**Televisions answer DIAL themselves**, on more than one port, offering whatever is installed locally.
Checking which device "has the app" won't identify the right one. Skip responders answering from your own
addresses first.

**A standby HDMI port reports the same state as an empty one.** On this driver every port read `state: 1`
regardless, and only `state: 0` indicates a live device. The passthrough URI will open a session on an
empty port, so a successful switch proves nothing.

**A drawable referenced only by name is stripped from release builds.** `getIdentifier` is invisible to the
resource shrinker. Pin it in `res/raw/keep.xml`.

**`measure(UNSPECIFIED, UNSPECIFIED)` ignores `layout_height`** and returns the content height, which
positions a fixed height view at half its size.

**Several TV apps keep their main activity unexported** and register only `LEANBACK_LAUNCHER`, so
`am start -n` is denied. Resolve through `queryIntentActivities` instead, which also survives an app
renaming its activity.

## Credit and licence

The launcher this builds on, the ADB tooling and 260-odd tested debloat lists are the work of
[**Gathin23/cleanyourtv**](https://github.com/Gathin23/cleanyourtv), MIT licensed, all of it still here and
untouched. See [README.upstream.md](README.upstream.md). If you're here for debloating rather than a
launcher, go there.

This fork keeps that licence and copyright (see [LICENSE](LICENSE)) and isn't affiliated with or endorsed
by that project.

Kogan, Amazon, Fire TV, Netflix, Spotify and Android TV are the trademarks of their respective owners. No
third party artwork is distributed here. The tile icons are placeholders, and a tile borrows a real icon
from an app installed on your own device at runtime via `icon:<package>`.
