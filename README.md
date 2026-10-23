# Tiviate

A fast, lightweight live-TV player for **Android TV** and **Fire TV**, built around your own IPTV subscription: M3U links, Xtream Codes logins and local M3U files, with a full TV guide.

Tiviate is a player only. It ships no channels, playlists or credentials.

## Features (v0.1)

- **Smooth live playback**:
  - Media3 ExoPlayer with hardware decoding and automatic decoder fallback
  - Fast channel start; the last frame stays on screen while zapping
  - Auto-reconnect with backoff and a frozen-stream watchdog
  - Optional refresh-rate matching and tunneled playback
- **Playlists**:
  - M3U/M3U8 links, Xtream Codes, and local `.m3u` files (with a built-in file browser for Fire TV)
  - Several playlists, switchable from the channel list
- **TV guide (XMLTV)**:
  - Detected automatically from the playlist, plus extra guide links; `.gz` and `.xz` supported
  - Refreshed in the background
  - Matches channels by `tvg-id`, then by name
- **Logos and programme images**:
  - Fallback order: playlist logo, then the guide's channel icon, then a monogram tile
  - https is tried first, so slow plain-http image servers don't block loading
- **Browsing**:
  - Channel list with groups
  - Favorites and Recent (the last 50 channels watched)
  - Search over channels and programmes on now or next
- **Player controls**:
  - Audio and subtitle track selection
  - Picture modes (Fit/Zoom/Stretch)
  - Number entry
  - Play/Pause, including on live TV
  - Open in another player
- **Remote-first**: everything works with a basic Fire TV remote. Phones and tablets work by touch (tap for controls, swipe to change channel).
- **Clear errors**: when a channel can't play, Tiviate explains why — the channel is down at the provider, the account is busy elsewhere, access was denied, there's no connection, or the format is unsupported — and offers Try again / Next channel / Other player.

## Remote control

| Key | In fullscreen | In the channel list | In the TV guide |
|---|---|---|---|
| OK | Open the channel list | Play the channel (OK again closes) | Preview the channel; OK again goes fullscreen |
| Up / Down, CH± | Change channel | Move | Move between channels (CH± pages) |
| Left | Groups and categories | Groups and categories | Earlier programme |
| Right / Guide | TV guide | — | Later programme |
| Menu / hold OK | Audio, subtitles, picture, favorite, other player | — | Choose a group |
| Play/Pause | Pause or resume (rejoins the live point after 30 s) | | |
| FF / RW | | | Jump ±2 hours |
| 0–9 | Go to a channel number | | |
| Back | Close the overlay; press twice to exit | Close | Back to fullscreen |

## Install on a Fire TV or Android TV

1. On the TV, enable developer options:
   - **Fire TV:** Settings → My Fire TV → About → click the device name 7 times. Then in **Developer options** turn on **ADB debugging** and **Apps from unknown sources**.
   - **Android TV:** Settings → Device Preferences → About → click Build 7 times.
2. Find the TV's IP address under Settings → Network.
3. On your computer, run:
   ```
   adb connect <TV-IP>:5555
   adb install -r Tiviate-0.1.0.apk
   ```
   Accept the debugging prompt on the TV the first time.

Alternatively, upload the APK somewhere you can reach and open it with the **Downloader** app on the Fire TV.

## Build

Requirements: JDK 17 and the Android SDK (compileSdk 36).

```
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:connectedDebugAndroidTest      # on an Android TV emulator/device
./gradlew :app:assembleRelease                # signed release (needs keystore.properties)
```

- **Release signing:** reads `keystore.properties` (gitignored) — `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Keep the keystore safe: every update must be signed with the same key.
- **Optional real-provider check:** add `local.test.properties` (gitignored) containing `xtream.server`, `xtream.username` and `xtream.password`. `RealProviderSyncTest` then syncs your real account.

## Project layout

- `core/`: pure Kotlin. M3U, XMLTV and Xtream parsers (all streaming), EPG matching, stable channel keys.
- `app/`: Android.
  - Data: Room database, sync (WorkManager), settings (DataStore).
  - Player: Media3 engine and controller.
  - UI: Compose for TV.
- `docs/superpowers/`: design spec and implementation plan.

## Licence

Apache License 2.0 — see `LICENSE`. Third-party components are listed in `NOTICE`.
