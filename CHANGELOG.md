# Changelog

All notable changes to Tiviate. Versions follow `versionName` in `app/build.gradle.kts`.

## 0.2.3 — 2026-10-29

### Changed
- The channel selected with Up/Down in the guide has a big yellow frame around its name and programmes,
  and its name turns yellow, so it is noticed at once. The playing channel's outline is a lighter green.

### Fixed
- Pressing Back from the side menu no longer switches the guide to Favorites; it returns to the group you were on.

## 0.2.2 — 2026-10-24

### Added
- Settings → Playback → Always highest quality (on by default): the best stream quality the TV supports.
  Turn it off for automatic quality that follows your connection.

### Faster
- Moving through the guide on a Fire TV: about 46 ms → 14 ms per frame (libraries compiled ahead of time).
- Logos already saved on the TV are not loaded again at start; missing ones load later, a few at a time.
- No more stutter 5 s after each channel change (watch history no longer reloads the channel lists).
- Zapping and browsing groups no longer redraw every screen; the start-up guide refresh waits 45 s.

## 0.2.1 — 2026-10-24

### Changed
- The groups column (Left in the guide) sits beside the guide and pushes it right, instead of covering
  the channels.
- Moving through the groups shows each group's channels right away, in the guide and in the player's
  channel list; no OK needed.

### Fixed
- Xtream groups now show in the provider's category order (as the provider lists them), not in the order
  their first channel appears. Update the playlist once to apply it.

## 0.2.0 — 2026-10-24

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
