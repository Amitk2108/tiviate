# Changelog

All notable changes to Tiviate. Versions follow `versionName` in `app/build.gradle.kts`.

## Unreleased

### Added
- Updates inside the app from GitHub Releases: daily check (can be turned off), "Check for updates" in About,
  download with progress, install; "Later" skips that version. Fire TV gets a hint for allowing installs.
- Backup and restore (Settings → Other): playlists with logins, favorites, recent, own groups, channel choices,
  guide links and settings, saved to `Download/Tiviate`; restore from the list or any file.
- Automatic TS ↔ HLS fallback when a channel fails, remembered per channel for the session.
- Baseline profile: cold start on a Fire TV went from about 0.69 s to about 0.49 s.

### Fixed
- Pressing Install in the update dialog no longer changes the channel behind it.

## 0.1.0 — 2026-10-24

First public release, by Amit Kumar.

### Playback
- Media3 engine with hardware decoding, software fallback, auto-reconnect and clear error messages.
- FFmpeg audio decoder for AC3 / E-AC3 / DTS.
- One shared video surface: no black flash between fullscreen and the guide; the picture glides between them.
- Player options on OK: Channels, TV guide, Pause / Back to live, Audio, Subtitles, Display, Zoom, Quality,
  Audio sync, Stats, Sleep timer, Favorite, PiP, Other player, Channel options, Search, Settings.
- Subtitles on by default for English channels, off for Hindi and other channels (switchable).

### Guide and browsing
- TV guide with live preview, minutes left, empty slots shown as "No information", the playing channel marked
  in green, a clock-following focus, and an automatic refresh when the provider's guide runs out.
- Groups column with icons and counts, side menu, channel list, favorites, recent, own groups, search.
- Opens on the last group used; nothing plays until you press OK.

### Organising, playlists, look
- Long-press menus, hide/rename/reorder, names editor, sort modes, manual guide links.
- M3U, Xtream Codes and local files; playlist updates on start / daily / weekly / monthly.
- Modern TV design, 12 accent colours, font sizes, clock options, 40 languages.
- About screen with the author and a legal notice.
