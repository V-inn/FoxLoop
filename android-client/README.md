# FoxLoop Android client

The tablet half of FoxLoop: decodes the H.264 stream the daemon sends over USB and
draws it fullscreen, and sends S Pen / touch input back the other way over the
same connection.

The same project also builds **FoxLoop Console**, a terminal for a headless
machine over the same kind of cable. They are two product flavors with two
application IDs, so both install side by side:

| Flavor | Application ID | Source set |
|---|---|---|
| `display` | `io.github.v_inn.foxloop` | `app/src/display/` |
| `console` | `io.github.v_inn.foxloop.console` | `app/src/console/` |

`app/src/main/` holds only what both share: the accessory input buffer, the
theme and the fonts. `app/src/displayDebug/` adds `INTERNET` to debug builds of
the display only, for the adb-forward development transport.

Was `experiments/android-decode-test/` until Milestone 21 — it started as a
throwaway decode spike in Milestone 3 and quietly became the real client. See
MILESTONES.md for anything referring to the old path.

## Build and install

Needs an Android SDK. Point `local.properties` at it (or set `ANDROID_HOME`):

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDisplayDebug
adb install -r app/build/outputs/apk/display/debug/app-display-debug.apk
```

Nothing else has to be launched by hand: the daemon switches the tablet into
USB accessory mode, and Android routes the resulting `USB_ACCESSORY_ATTACHED`
intent straight to this app (`app/src/display/res/xml/accessory_filter.xml` has
to keep matching the identification strings `daemon/src/portal_capture.rs`
passes to `crates/foxloop-aoa`).

## Layout

Display flavor, under `app/src/display/java/io/github/v_inn/foxloop/` unless the
path says `main`:

| File | What it does |
|---|---|
| `main: BufferedAccessoryInput.kt` | Buffered reads over the accessory fd, shared with the console |
| `MainActivity.kt` | Transport, protocol, decode loop, S Pen capture |
| `Settings.kt` | Persisted options; the session ones packed into the handshake's `config_flags` |
| `SettingsActivity.kt` | Hosts the settings screen; owns the staged draft |
| `SettingsDraft.kt` | What you are proposing, as against what is saved |
| `SessionConfig.kt` | What the *running* session was started with, so the screen can mark a change as staged |
| `CursorOverlay.kt` | Draws the desktop pointer, for client-side cursor mode |
| `LatencyOverlay.kt` | Per-frame latency over the video. Must **not** swallow input — the opposite of `GearButton` |
| `FramePreview.kt` | Grabs a still of the desktop on the way into settings, for the slab |
| `GearButton.kt` | The draggable, edge-snapping settings entry point that survives streaming |
| `GearEdge.kt` | Which edge it is parked against |
| `main: ui/FoxLoopTokens.kt` | Colours, spacing, shapes — in Kotlin, not `res/values` |
| `main: ui/FoxLoopType.kt` | The three faces, loaded from `res/font` |
| `main: ui/FoxLoopTheme.kt` | The little `material3` would have given us, including the reduced-motion scale |
| `main: ui/Controls.kt` | Bespoke switch, segmented choice, buttons, focus ring |
| `ui/SlabControl.kt` | The tablet drawn to scale, with the real desktop in it |
| `ui/SettingsScreen.kt` | Two-pane layout and the sections |
| `ui/SettingsScreenState.kt` | Everything the screen draws and can do, in one value |
| `tools/build-fonts.sh` | Regenerates `res/font` from the upstream OFL sources |

The wire protocol both sides speak is documented in one place,
[`daemon/src/protocol.rs`](../daemon/src/protocol.rs). The constants at the
bottom of `MainActivity.kt` mirror it and must be changed together.

## Things that will bite you

- **The decode loop is split across two threads on purpose.** A reader feeds the
  decoder, a separate thread blocks on `dequeueOutputBuffer` and renders.
  Merging them back costs a full frame interval of latency — that was Milestone
  18.
- **Never drop a frame.** Since the encoder gained a real GOP, discarding one
  P-frame corrupts everything until the next IDR, up to a second later.
- **`BufferedAccessoryInput` exists for a reason.** USB accessory fds can't use
  `BufferedInputStream` (`available()` throws) and can't be read unbuffered
  either (an undersized read silently discards the rest of the packet). See its
  doc comment.
