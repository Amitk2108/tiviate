# Changelog

All notable changes to Tiviate. Versions follow `versionName` in `app/build.gradle.kts`.

## 0.5.1 — 2026-11-22

### Fixed: programme archives (GitHub issue #4)
- **Flussonic** archives (`catchup="flussonic"` or `"fs"`) play whatever the channel's link looks like. Servers name
  their archive links differently, so when one is refused Tiviate tries the next.
- The guide goes back **as far as the archive does** (`catchup-days`, up to 14 days) instead of one day.
- Programmes that aired **stay in the guide** after a refresh, also when the provider's guide no longer lists them.
- **Past half hours without programme information** can be played from the archive too.
- Playlists reload **once after an app update**, so what a new version reads from them works right away.

## 0.5.0 — 2026-11-21

### Added: DRM-protected channels (GitHub issue #3)
- Channels protected with **Widevine**, **ClearKey** or **PlayReady** now play when your playlist describes them
  with Kodi-style `#KODIPROP` lines (before or after `#EXTINF`):
  - `inputstream.adaptive.license_type` = `com.widevine.alpha`, `clearkey` / `org.w3.clearkey` or `com.microsoft.playready`
  - `inputstream.adaptive.license_key` = a licence server (`https://…|Header=value&…`), or ClearKey keys
    (`kid:key`, several separated by commas, or JSON)
  - `inputstream.adaptive.manifest_type=mpd` for DASH links without ".mpd"; `inputstream.adaptive.drm_legacy` works too
- **DASH (.mpd)** streams play, with or without DRM.
- When a channel's licence or keys are refused, Tiviate says so clearly instead of "Playback stopped".
- Tiviate unlocks channels only the way the playlist says (the provider's licence server or its keys), through
  Android's own DRM; nothing is bypassed.

### Good to know
- Protected and DASH channels can't be recorded.

## 0.4.1 — 2026-11-20

### Phones and tablets
- Menus and choices now open as **bottom sheets**: the long-press menu on a channel, the player's choices (audio,
  subtitles, display, sleep timer…), rename and group dialogs, the PIN pad, reminders and the update offer.
  Drag the handle down, tap above the sheet or press Back to close it.
- **Fixed**: Back on the long-press menu closed Tiviate instead of the menu.
- The phone's long-press menu starts with the channel (Search and Settings are in the bottom bar).

TVs are unchanged.

## 0.4.0 — 2026-11-20

### Added
- **Catch-up**: on channels whose provider keeps an archive (Xtream accounts, or M3U playlists with `catchup`),
  programmes that already aired show ↺ in the TV guide; OK plays them. Options → *Back to live* returns to the channel.
- **Movies & Series** (Xtream accounts): posters by group, Continue watching, details, seasons and episodes. Films and
  episodes carry on where you stopped. TV: guide → side menu → *Movies & Series*; phones: the *Movies* tab.
- **Recording**: record the channel you are watching (Options → *Record*) or a programme from the TV guide
  (long-press OK → *Record*), to the device or a USB drive. *Recordings* (guide side menu, or Settings → Recording)
  to watch or delete them; a REC badge shows while the channel on screen records.
- **Multi-view**: up to four channels at once (Options → *Multi-view*). The selected one has the sound; Back shows it
  fullscreen.
- **Parental control**: a PIN locks the channels and groups you choose (long-press → *Lock*), and Settings if you
  want. Settings → Parental control.
- **Reminders**: *Remind me* on programmes still to come (guide menu, a phone's programme card, Search); when it
  starts, Tiviate offers to switch to it.
- **Search** now also finds programmes later this week and ones you can still watch from the archive.
- **TV guide time offset** for guides in the wrong time zone: Settings → TV guide → a source → −1 h / +1 h.

### Good to know
- Recording and multi-view each use one of your provider's connections while they run.

## 0.3.3 — 2026-11-20

### Added: channels on the Android TV home screen (GitHub issue #2)
- Your Favorites and Recent channels now appear as rows on the Android TV home screen (Android 8.0 and newer).
  Pick a channel there and Tiviate opens fullscreen on it.
- The Favorites row is added by itself. Android TV lets an app add only one row automatically, so turn on the
  Recent row once: home screen → Customize channels → Tiviate → Recent.
- The rows follow the active playlist and update as you add favourites and watch channels.
- Fire TV and phones have no such rows, so nothing changes there.

## 0.3.2 — 2026-11-19

### Added: seek bar
- Films and series in your playlist, and live channels whose provider keeps a rewind window, now show a seek
  bar with the time played and the total length (live: how far behind live, and a tap on "Live" goes back).
- Phones and tablets: tap the video for the controls, then drag the dot, tap anywhere on the line, or use the
  10-second back and forward buttons.
- TV: in the options (Down), press Up to reach the seek bar, then Left/Right to move 10 seconds. The remote's
  rewind and fast-forward keys also move 10 seconds.
- Ordinary live channels can't be moved through, so they show no bar.

### Fixed
- A film paused for a while carries on from where it was (it used to start again from the beginning), and a
  film that reconnects after a network hiccup keeps its place.

## 0.3.1 — 2026-11-19

### Fixed on phones and tablets
- Tapping a text box now opens the keyboard everywhere (adding a playlist, Search, Settings). Before, taps on
  text boxes did nothing.
- The M3U link, Xtream Codes and Local file screens fit a phone: the explanation on top, full-width boxes below.
- The screen moves up above the keyboard, so the box you type in stays visible.

### Added
- A Paste button at the end of every text box on phones and tablets: copy a playlist link or login, tap Paste.

## 0.3.0 — 2026-11-19

### Added: phones and tablets (GitHub issue #1)
- Tiviate now works with touch. Taps, long-presses and scrolling work on every screen, with haptic feedback.
- The right layout is picked automatically: TV, phone or tablet, and again whenever the device is turned.
- Phones held upright: the video on top, what's on now, the groups as chips, the channel list, and a bottom
  bar with Live, TV guide, Search and Settings. Turn the phone (or tap Fullscreen) for fullscreen video.
- A touch TV guide: drag sideways through time, scroll through channels, tap a programme for its details and Watch.
- Every player option (audio, subtitles, display, zoom, quality, sleep timer…) is one tap away with Options.
- Tablets: upright they use the phone layout with what's on next beside each channel; sideways they use the
  TV layout, with touch.
- Settings on phones: a list of sections, each opening full width.
- Gesture navigation and three-button navigation both work: the app draws edge to edge and keeps clear of the
  system bars and camera cut-outs; fullscreen video hides the bars.

### Unchanged
- The TV app: same screens and remote keys. The TV test suite still passes.

## 0.2.5 — 2026-11-19

### Added (requested by viewers)
- Settings → Remote control → Left / Right change channel: Left and Right switch to the previous and next
  channel while watching. The channel list and groups are then on OK → Channels. Off by default.
- Settings → General → Show the TV guide: turn it off and Tiviate opens fullscreen, Right and the Guide key
  open the channel list, the TV guide card is hidden and Back leaves the app. On by default.

### Changed
- The "Always highest quality" setting is now translated into all 40 languages.

## 0.2.4 — 2026-10-29

### Fixed — your data
- An empty answer or an error page from the provider no longer wipes the playlist: channels, hidden/renamed
  channels and favourites are kept and the error is shown. Xtream without its group list keeps the groups.
- "Change details" after a failed edit no longer deletes the playlist.
- A failed restore changes nothing (it used to leave the app empty); backups from newer versions restore.
- Editing a playlist while it updates is no longer undone. Deleting a playlist always finishes.
- Channels assigned to a guide by hand now get their programmes, also after a restore.

### Fixed — playback
- A stream the provider closes is reconnected (it used to freeze on the last picture).
- A short error no longer locks a channel onto a link that doesn't work; "Try again" starts fresh.
- A playlist update no longer undoes a working .ts/.m3u8 choice.
- Pressing Home during a channel change no longer starts sound behind the home screen.
- The sleep timer no longer closes the app the next time it opens.
- Fast CH+/CH- presses land on the right channel; CH+/CH- follow the group picked in the guide.
- Pause during "Reconnecting…" stays paused. A failed save (storage full) no longer crashes the app.

### Fixed — remote and focus
- The channel list keeps the focus after choosing a group without the playing channel.
- The guide has focus on an empty group (no favourites yet, Recent cleared).
- Right or Back straight after moving to a group shows that group; switching playlist starts on All channels.
- A renamed custom group keeps its place; a deleted one falls back to All channels.
- The focus comes back after closing the update dialog.

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
