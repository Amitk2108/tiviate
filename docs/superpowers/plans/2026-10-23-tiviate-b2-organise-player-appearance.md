# Tiviate B2 — channel organising, screen modes, player extras, appearance, languages

**Owner directive (2026-10-23):** build everything first, check that each step compiles, and run all testing at the end. Unit and emulator tests are written and run in the final task. This overrides test-first for B2.

**Owner decisions:**
- Menus in about 33 languages, like TiviMate.
- No parental PIN, so no blocking.
- AC3/DTS sound through a ready FFmpeg decoder; Tiviate's licence becomes **GPL-3.0**.
- No Multiview.
- Catch-up and recording are dropped.

**Inventory numbers** refer to the owner's list of 119.

## Tasks (in order)

### 0. Partly-done items
These are already written:
- Xtream User-Agent and HLS choice;
- guide source rename;
- recent count and delay;
- channel list switches;
- guide highlight;
- panel timeout;
- page keys;
- per-channel display mode;
- Pause / Back to live;
- four buffer sizes;
- the boot permission.

**Step:** commit.

### 1. Safe database upgrades
- Export the Room schema and keep `1.json`.
- Move to version 2 with a written `Migration(1, 2)`, so updates never wipe favourites, history or playlists.
- Destructive fallback stays only for downgrades.

**New columns on `channel`:**
- `originalName` TEXT
- `hidden` INTEGER default 0
- `customSort` INTEGER (null)
- `addedAt` INTEGER default 0
- `epgOverride` TEXT
- `renamed` INTEGER default 0

**New columns elsewhere:**
- `favorite.position` INTEGER default 0
- `playlist.namePrefixes` TEXT and `playlist.nameSuffixes` TEXT

**New tables:**
- `group_pref` (playlistId, name, hidden, position, displayName)
- `custom_group` (id, playlistId, name, position)
- `custom_group_member` (groupId, stableKey, position)

**Resync:** the upsert keeps `hidden`, `customSort`, `addedAt`, `epgOverride` and any custom name.

### 2. Organising data (items 18–22, 24–30)
- **Channel order and visibility:** list, position and neighbour queries go through one query builder (category, sort mode, hidden). Raw queries back the paging.
- **Sort modes:** playlist order, name, number, newest first, most watched, and manual.
- **Hiding:** hide or show a channel, hide or show a group.
- **Renaming:** rename a channel or group, and restore the original name.
- **Custom groups:** create, rename, delete, and add or remove channels (copy). Moving is a copy plus hiding the channel in its original group.
- **Favourites order:** stored in `favorite.position`.
- **Names editor:** prefixes and suffixes per playlist, applied when syncing and right away.
- **Manual guide link:** `epgOverride`; relinking respects it.
- **Opening a playlist** shows All channels or Favourites (setting).

### 3. Organising screens (items 14, 18, 21, 22, 24–31)
- **Long-OK sheet** on a channel, programme or group, in the guide and the channel list. Sections: Search and Settings; the programme (description); the channel (favourite, hide, rename, restore name, assign guide, add to group); the group (manage favourites, manage visibility, reorder channels, create group, group options: hide, rename, sort).
- **Managers:**
  - *Visibility:* OK toggles, and Left offers select all, invert or clear.
  - *Reorder:* OK picks a row, Up/Down moves it, Left/Right sends it to the top or bottom.
  - *Favourites order.*
  - *Add to group:* pick a custom group.
- **Guide channel picker** for the manual link, with a search field.

### 4. Screen modes (items 12, 13, 15, 16, 17)
- **Guide mode:** preview (today's look) or overlay (the guide over full-screen video).
- **Channel list mode:** overlay (today's look) or preview (video in a box at the top right).
- **Stay on the list or guide** after switching.
- **Autoplay** while scrolling: Off, or 1, 2 or 3 s.
- **Long Back** returns to fullscreen from anywhere (MainActivity detects it, AppRoot routes it).

### 5. Player extras
- **32 FFmpeg audio:** `org.jellyfin.media3:media3-ffmpeg-decoder` with the extension renderer mode on, so device decoders are tried first.
- **Licence:** GPL-3.0 in LICENSE, NOTICE, README and About.
- **33 Peek:** with the info bar showing, Left/Right shows the previous or next channel's info, and OK tunes it.
- **34 Last channel:** a "Previous channel" card and the LAST_CHANNEL key.
- **35 Option cards:** choose which show and their order (Settings → Remote control → Option cards).
- **36 Caption style:** follows the system style, with a link to it.
- **37 Surround:** prefer the surround track (setting).
- **38 Audio sync:** offset from −500 to +500 ms, through a forwarding audio sink that shifts the clock.
- **39 PiP:** a picture-in-picture card, plus "PiP on Home" (setting), in the manifest too.
- **41 External player:** always open channels in it (setting).
- **42 Black screen** while switching (setting).
- **43 Stop key:** stops playback and opens the guide.
- **44 Volume:** handled by the TV (noted).

### 6. Appearance (items 97–105)
- **97:** accent colour (12) and background theme (Dark, Black, Blue grey), read dynamically by `Tokens`.
- **98:** font size (small, medium, large) through the density's font scale.
- **99:** panel transparency.
- **100:** clock size, date, and an always-on clock over the video at a chosen corner.
- **101:** logo style (rounded, square, circle).
- **102:** two-line channel names and programme titles.
- **103:** playlist and group name in the info bar.
- **104:** show or hide All channels, Favourites and Recent in the groups lists.
- **105:** animations on or off (focus scaling and fades).

### 7. Languages (item 106)
- Move every UI string to `res/values/strings.xml`.
- Translate into about 33 languages: ar, bg, zh, hr, da, nl, fi, fr, ka, de, el, iw, hu, it, lv, lt, nb, pl, pt-rBR, ro, ru, sr, sk, sl, es, sv, tr, uk, vi, cs, plus hi, ta and te.
- Add a language picker in Settings → General (or "System"), applied through a locale-wrapped context, and recreate the activity.

### 8. Testing and release (at the end)
- Unit tests for the query builder, migration, sort modes, names editor and audio-offset maths.
- Emulator tests for the long-OK sheet, managers, screen modes, Long Back, peek, last channel, appearance settings and the language switch.
- Run the full suites.
- Build the release APK, install it, log in with the owner's Xtream account, and hand over.
