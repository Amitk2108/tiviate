# Changelog

All notable changes to Tiviate. Versions follow `versionName` in `app/build.gradle.kts`.

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
