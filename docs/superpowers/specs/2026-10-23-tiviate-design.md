# Tiviate — v1 design

Date: 2026-10-23 · Status: draft for approval

## 1. Intent

Tiviate is a personal, open-source **live-TV IPTV player** for **Fire TV and Android TV**. It is written from scratch in original code.

The owner watches their IPTV provider's channels on a Xiaomi Fire TV Edition TV (Fire OS 7 = Android 9 / API 28) and wants something that feels as good as TiviMate, with smooth playback first.

v1 is a rock-solid core: playback, playlists, guide, logos, search, favorites, history and tracks, all fully remote-driven. Once v1 passes its tests, later releases push the UI/UX well beyond TiviMate. v1 visuals must therefore be clean and original, but the main investment is architecture, smoothness and correctness that later UI work can build on.

Research behind this design: `../../../../reports/` (TiviMate static analysis and reference-project review).

## 2. Goals (v1)

1. **Smooth live playback.** Hardware decoding, fast start, fast zapping (CH±/Up/Down), the last frame kept during a zap, and automatic recovery from network errors and frozen streams.
2. **Playlist sources** the owner uses. Several playlists can exist at once.
   - **M3U/M3U8 URL** (including `get.php?…` links).
   - **Xtream Codes login** (server + username + password): live channels only.
   - **Local M3U file.**
3. **XMLTV EPG.**
   - Sources: auto-detected from the playlist (`url-tvg`/`x-tvg-url` header, Xtream `xmltv.php`) plus manually added URLs.
   - Formats: plain, `.gz` or `.xz`.
   - Refreshed in the background.
   - Matched to channels by `tvg-id`, then by normalized name.
4. **Logos and programme images always load.** This is an explicit owner requirement ("make sure tv guide icon thumbnail all fetch").
   - Channel logo order: `tvg-logo` → Xtream `stream_icon` → XMLTV `<channel><icon>` → generated monogram tile.
   - Programme image: XMLTV `<programme><icon>`.
   - Images are cached on disk, decoded at display size, and fetched with a browser-like User-Agent and redirect following.
   - SVG and missing file extensions must work.
5. **Browsing.**
   - Categories: provider groups plus *All*, *Favorites*, *Recent*.
   - Search across channels and now/next programmes.
   - Favorites.
   - History: recently watched, and resume the last channel on launch.
6. **Track control.** Audio track, subtitle track (including off) and aspect mode (Fit / Zoom / Stretch). Optional "open in external player" handoff to VLC or MX.
7. **D-pad first.** Every screen is fully usable with a basic Fire TV remote: D-pad, OK, Back, Home, Menu, Play/Pause, Rewind and Fast-forward. Richer remotes also get CH±, Guide and number keys. Focus is always visible and never lost.
8. **Fire TV + Android TV compatible.**
   - No Google Play Services.
   - `LEANBACK_LAUNCHER` + 320×180 banner.
   - minSdk 23. Primary device is API 28 (Fire OS 7); also Android TV/Google TV up to API 35+.
9. **Tested.** JVM unit tests, instrumented tests on an Android TV emulator, and a manual smoke checklist must all pass before v1 is called done.

## 3. Scope phases

**Owner direction (2026-10-23):** "fully clone TiviMate — 100% features, UI/UX, engine; then add our own touch."

That means **feature and interaction parity**: the same capabilities, the same familiar remote-driven layout patterns (fullscreen player, side channel list, grid TV guide, quick menu) and the same engine stack (Media3 ExoPlayer + FFmpeg audio). It does **not** mean copying TiviMate's code, icons, images, strings or colour palette (see §4). Those are expressed originally, and the later "own touch" pass restyles freely.

- **Phase A — v1 core (this document's detail):** everything in §2. Must pass §7 before Phase B starts.
- **Phase B — parity, built after A passes, each feature its own plan:**
  - Catch-up/timeshift (`catchup*` attributes, Xtream `tv_archive`).
  - VOD movies + series (Xtream VOD/series, M3U VOD), with resume.
  - Recording (scheduled/instant, foreground service) and program reminders (exact alarms + popup).
  - Multiview (2–4 streams).
  - Parental PIN and blocked channels/groups.
  - Manage groups: hide, reorder, rename, custom groups, copy/move channels.
  - Channel sorting modes.
  - Remote key remapping (short and long press).
  - Backup/restore.
  - Stalker portal.
  - Appearance options (accent colours, transparency, font size, clock).
  - Auto-start on boot, sleep timer, PiP, voice search, merged "All playlists" view, external player choice, and a libVLC engine option.
- **Phase C — "own touch":** the UI/UX upgrade beyond TiviMate. It includes a dedicated phone layout (portrait, bottom navigation).

**Phones and tablets (owner request, 2026-10-23):** the same APK must also work on phones and tablets.
- In Phase A every screen is touch-usable: every focusable item is tappable.
- The player gets a tap-to-show control bar (Channels, Guide, Menu, Play/Pause), and swiping up/down zaps.
- On phones the app runs landscape with the TV layout.
- The phone-optimized design is Phase C.

**Never in scope:** premium gating or licensing checks, accounts/backend, telemetry, ads, bundled content.

## 4. Anti-goals (failure even if it "works")

- Copying anything from TiviMate (code, layouts, strings, colours, measurements, icons) or from telly/OwnTV code. **Do not open `reference/telly/docs/reference/`** (TiviMate screenshots).
- Premium gating, licence checks, DRM circumvention, bundled playlists, channels or credentials.
- Telemetry, ads, trackers.
- Lost or invisible focus, Back unexpectedly exiting the app, an empty guide while the EPG refreshes, crashes or ANRs on big playlists, or out-of-memory errors on 1–1.5 GB devices.

## 5. Constraints

| Constraint | Consequence |
|---|---|
| Native **Kotlin + Jetpack Compose for TV** (`androidx.tv:tv-material` 1.1.x), **Media3 ExoPlayer 1.11.x** (`exoplayer`, `exoplayer-hls`, `exoplayer-dash`, `datasource-okhttp`, `ui`). *Owner decision* | No React Native/Expo, no Leanback UI |
| **Licence: Apache-2.0** for Tiviate code. *My call; it can be relicensed to GPL-3.0 later if GPL components are wanted* | **No GPL dependencies.** Jellyfin's prebuilt FFmpeg decoder is GPL-3.0, so we don't use it. Instead the **last v1 milestone** builds Media3's own FFmpeg audio extension (Apache-2.0) against an **audio-decoders-only LGPL FFmpeg** (mp2, ac3, eac3, dca, truehd, mlp…) compiled in WSL with the NDK. It is used only when the device has no decoder, which avoids silent MP2/DTS channels. This adds native libs, so APKs are split per ABI (armeabi-v7a, arm64-v8a, x86/x86_64 for the emulator). `NOTICE` gains the FFmpeg LGPL notice and a source offer. *My call* |
| Fire OS 7 / API 28: SQLite limit of 999 bound variables, no Google Play Services, often **no system document picker** | Chunk `IN (…)` queries to ≤ 900. Local-file import uses SAF when available, else a built-in file browser over shared storage with `READ_EXTERNAL_STORAGE` |
| Low RAM (1–1.5 GB) and slow TV SoCs | **Streaming parsers only** (never hold a whole playlist or EPG in memory). Batched transactional DB writes. Windowed queries. Baseline Profile. R8 |
| Big data: playlists up to ~50k channels, EPG up to ~200 MB XML / ~1M programmes | Paged channel lists. The guide renders only visible rows and the visible time window. Store only programmes for channels we have, within [now − 1 day, now + 7 days] |
| Providers use plain HTTP, custom User-Agents, redirects (http↔https) | Cleartext allowed. Per-playlist User-Agent. Per-channel headers from `#EXTVLCOPT:http-user-agent/referrer`. Cross-protocol redirects allowed |
| Name **"Tiviate"** and the owner-supplied logo (`Tiviate Neon Play Icon.png`). *Owner decision* | App name lives in one string resource. ⚠ *Risk note:* the name is one letter away from "TiviMate". That is fine for personal sideloading. If the app is ever published to Play or Amazon, expect name complaints, so rename then |

## 6. Architecture

Gradle project in `MATE/tiviate/`, its own git repository.

- `:core`: **pure Kotlin/JVM**, no Android.
  - M3U parser, XMLTV parser (against the `org.xmlpull.v1` API; the platform supplies it on Android, kxml2 in tests), Xtream JSON models and URL builder.
  - EPG↔channel matching and name normalization, guide time math.
  - Unit-tested on the JVM, so tests are fast.
- `:app`: Android. Room database, repositories, OkHttp, WorkManager sync, DataStore settings, Coil image loading, player, Compose UI.
- `:baselineprofile`: Macrobenchmark generator for startup, channel-list scroll and guide scroll.

Splitting `:app` further is left to the builder.

### 6.1 Data (Room, WAL)

| Entity | Key fields | Notes |
|---|---|---|
| `Playlist` | id, name, type `M3U_URL \| M3U_FILE \| XTREAM`, url / fileUri / server+user+pass, userAgent, detectedEpgUrl, lastSyncAt, lastError, enabled, sortIndex | |
| `Channel` | id, playlistId, **stableKey**, number, name, groupName, logoUrl, streamUrl, tvgId, tvgName, headers (UA/referrer), epgChannelRef, sortIndex | `stableKey` = Xtream `stream_id` → else `tvg-id` *if unique within the playlist* → else normalized name + group (+ occurrence index for duplicates). **The URL is never part of the key**: providers rotate tokens in stream URLs. Favorites and history use `(playlistId, stableKey)` so they survive resyncs |
| `Favorite` | playlistId, stableKey, addedAt, sortIndex | |
| `HistoryEntry` | playlistId, stableKey, lastWatchedAt, totalWatchSec | Added after 5 s of watching. Last 50 kept |
| `EpgSource` | id, url, name, origin `AUTO \| MANUAL`, playlistId (AUTO only), priority, enabled, timeOffsetMin, lastSyncAt, lastError | |
| `EpgChannel` | sourceId, xmltvId, displayName, normalizedName, iconUrl | |
| `Programme` | epgChannel key, start, stop, title, subtitle, description, category, iconUrl, generation | Indexes on (channel, start) and (channel, stop) |
| FTS4 | channel names; programme titles | Search |

**Multiple playlists: one active playlist at a time** *(my call)*.

- The categories rail starts with a **playlist switcher** (shown only when there are 2 or more playlists). Categories, zap order, search, guide rows and number entry are all scoped to the active playlist.
- Channel numbers are the provider's (`tvg-chno` / Xtream `num`), otherwise playlist order 1…N. Numbers are therefore unique within the active playlist, and number keys are unambiguous.
- Groups belong to their playlist.
- EPG sources: an **auto** source (detected from a playlist) applies to that playlist only. **Manual** sources apply to all playlists. When several sources match a channel, priority is auto first, then manual sources in list order.
- A merged "All playlists" view is a later enhancement.

**EPG refresh swap.** Insert into a new `generation`, then flip the active generation and delete the old one, all in one transaction. The guide is never empty mid-refresh.

### 6.2 Sync

- **Playlist sync.** Steps:
  1. OkHttp streaming download (gzip-aware); local file via content/file stream.
  2. Streaming parse.
  3. Upsert in batches of about 500 per transaction.
  4. Delete channels that disappeared.
  5. Record the detected EPG URL.
  6. Report progress (channels so far).
- **M3U parser.** Must handle:
  - BOM, CRLF and missing `#EXTM3U`.
  - Quoted, unquoted and single-quoted attributes.
  - Titles containing commas (title = text after the first comma *outside* the attribute section).
  - `#EXTGRP`, `#EXTVLCOPT`, `catchup*` attributes (stored for later).
  - `url-tvg`/`x-tvg-url` in the header.
  - Blank lines and comments.
  - Invalid entries are skipped and counted, never fatal.
- **Xtream.**
  - `player_api.php`: account info (status and expiry, shown in settings), `get_live_categories`, `get_live_streams`.
  - Parse JSON with a **streaming** reader.
  - Stream URL is `{server}/live/{u}/{p}/{stream_id}.ts`, with an option for `.m3u8`.
  - EPG comes from `{server}/xmltv.php?username=…&password=…`.
  - Credentials stay on the device only.
- **EPG sync.**
  1. Sniff gzip (`1f 8b`) and xz (`fd 37 7a 58 5a 00`) from the first bytes.
  2. Parse streaming. Read `<channel>` elements and match them to our channels. Keep `<programme>` only for matched channels inside the window.
  3. Parse XMLTV times with their offsets (`20261023120000 +0200`).
  4. Apply the per-source offset.
  5. A missing `stop` is set to the next programme's start.
- **Matching** (in `:core`, unit-tested):
  1. Case-insensitive `tvg-id` = xmltv id.
  2. Otherwise normalized `tvg-name`/name = normalized display-name. Normalization lowercases; strips country prefixes (`US:`, `UK |`), quality tags (HD/FHD/UHD/4K/SD/HEVC/H265/50fps) and punctuation; and collapses whitespace.
  3. Unmatched channels show "No programme information".
- **Scheduling.** Sync when the app starts if data is older than 12 h (default). WorkManager runs a periodic job every 12 h on a connected network, and the user can tap "Refresh now". Sync never blocks playback.

### 6.3 Player

- **One shared ExoPlayer** owned by an app-scoped `PlayerController`. The guide preview and fullscreen use the same instance, so pressing OK on the preview never re-tunes. It is released when the app goes to the background.
- **Engine interface.** A `PlaybackEngine` interface with a Media3 implementation and an `ExternalPlayer` handoff, so libVLC can be added later without touching the UI. The interface stays small: play / stop / tracks / state / video size.
- **Media source.**
  - OkHttp data source with playlist UA and channel headers; cross-protocol redirects allowed; connect/read timeouts about 8 s.
  - The type comes from the URL extension. When ambiguous, sniff the first bytes for `#EXTM3U` and fall back to HLS. Xtream `.ts` streams play as progressive MPEG-TS.
- **Rendering.** SurfaceView (not TextureView), keep last frame on reset, `FLAG_KEEP_SCREEN_ON` while playing.
- **Renderers.** Decoder fallback on. Extension renderers off in v1. Hardware-first selection, with a "prefer software video decoder" setting for broken devices.
- **Buffer presets** (setting; Normal is the default). The builder tunes the exact values on device:

| Preset | Min | Max | Start | Restart |
|---|---|---|---|---|
| Fast start | 5 s | 20 s | 0.75 s | 2 s |
| **Normal** | 15 s | 40 s | 1.5 s | 3 s |
| Stable | 30 s | 90 s | 2.5 s | 5 s |

- **Recovery.**
  - Network/IO errors: retry with backoff 1, 2, 4, 8, 16 s (5 tries).
  - `BehindLiveWindowException`: seek to default position and prepare.
  - Watchdog: a stream counts as frozen if it buffers for more than 20 s, or plays but its position doesn't advance for 10 s. Then re-prepare.
  - After the retries run out, show an error card with *Retry* / *Next channel* / *Open in external player*.
- **Display.**
  - Frame-rate matching via Media3's `VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS` by default.
  - An optional "Match display refresh rate" setting switches `Display.Mode` (off by default).
  - Tunneled playback is an optional setting (off by default) and turns itself off after a failure.
- **Tracks.** Dialogs list audio tracks (language, codec, channels) and subtitles (language/label, Off). The choice is remembered per channel for the session.

### 6.4 UI and navigation (original design)

**Visual language,** derived from the owner's logo:

| Token | Value |
|---|---|
| Background | `#05080F` |
| Surface | `#0B1220` |
| Raised surface | `#121C30` |
| Outline | `#1E2A44` |
| Text | `#EAF2FF` |
| Secondary text | `#8DA2C0` |
| Brand gradient | `#2D6BFF → #16D9E3` |

- **Focus:** cyan 2 dp border + soft glow + 1.04× scale (fast, no bounce).
- **"Now":** cyan time line and progress bars.
- **Type:** system sans at 10-ft sizes (body ≥ 14 sp, titles 22–28 sp).
- Design at 960×540 dp with a 48 dp overscan-safe margin.

**Screens:**

1. **Welcome / Add playlist** (first run, or from Settings).
   - Pick the type (M3U link / Xtream login / Local file).
   - Fill in the fields (name, URL or server/user/pass, optional EPG URL, optional User-Agent).
   - The app validates the input, then shows sync progress (channels, then guide).
   - After that it lands on fullscreen playback of the first channel.
2. **Player (home)**: fullscreen video of the last watched channel.
   - **Zap:** Up/Down or CH± zaps within the current category.
   - **Info banner:** shown at the bottom while zapping. It holds logo, number, name, now title + progress, next title, badges (resolution, fps, audio codec/channels) and a clock. It auto-hides after 4 s.
   - Number keys type a channel number (1.5 s commit).
   - **OK** opens the **Channel panel**: a left overlay about 40 % wide with the category title and a list of channels (logo, number, name, now title + thin progress). Up/Down moves, OK tunes, Right shows that channel's next programmes, Left opens categories.
   - **Left** (from fullscreen or the panel) opens the **Categories rail**: Search, All, Favorites, Recent, provider groups…, Settings.
   - **Menu**, long-OK or a long press opens **Quick actions**: Audio, Subtitles, Aspect, Add/Remove favorite, Open in external player, Refresh guide.
   - **Guide key**, or **Right** in fullscreen, opens the **TV Guide**.
   - **Back:** closes the top overlay, otherwise "Press Back again to exit".
3. **TV Guide.**
   - Top ~40 %: live preview (shared player) + focused programme details (title, time, image, description).
   - Bottom: timeline header (30-min slots, now marker) and channel rows. The channel cell holds logo + number + name; programme blocks are sized by duration.
   - Only visible rows and the visible time window are built. Focus is a small state machine (row index + focused time) that keeps the time position when moving between rows.
   - OK on a current programme tunes fullscreen; on a past or future programme it shows details.
   - CH± pages, and FF/RW move ±2 h.
4. **Search.** Text field (system keyboard; voice if a recognizer exists) with results in two rows, *Channels* and *On now/next*. OK tunes.
5. **Settings** (two-pane):
   - Playlists: list, add, edit, refresh, delete, Xtream account status.
   - Guide: sources, add URL, refresh interval, past/future days, per-source offset, "Refresh now".
   - Player: buffer preset, prefer software decoder, tunneling, match refresh rate, default User-Agent.
   - Interface: channel numbers, clock, banner timeout.
   - History: clear.
   - About: version, licences.

**Images.**
- Coil 3 on OkHttp, with SVG support.
- Memory cache about 15 % of the app heap, disk cache 250 MB.
- Requests are sized to the view; crossfade off.
- Placeholder and fallback is a monogram tile (initials on the brand gradient).
- After a sync, the logos for the first visible rows are prefetched.

### 6.5 Performance rules

- Baseline Profile, R8 full mode, resource shrinking.
- Stable keys and `contentType` in lazy lists.
- Paging for categories with more than about 500 channels.
- Progress and clock values tick at most once per second, and only inside the composables that show them.
- No overlays composed over the video while hidden.
- All DB and parsing work off the main thread; DB writes are batched.

## 7. Testing and "done"

- **JVM unit tests (`:core`).**
  - M3U edge cases (commas in titles, quoting styles, BOM/CRLF, `#EXTVLCOPT`, header EPG URL, broken entries).
  - XMLTV (gz/xz, offsets, missing stop, several display-names, icons, window filtering).
  - Xtream JSON → channels/URLs.
  - Name normalization and matching.
  - Guide time math.
- **Android tests.**
  - Room DAOs, including chunked queries with more than 5,000 ids.
  - Sync repositories against MockWebServer fixtures (M3U, Xtream JSON, gz XMLTV).
- **Instrumented UI tests on an Android TV emulator** (API 28-equivalent or newer TV image) with D-pad key injection:
  - Add a playlist from the fixture server, then auto-play.
  - Zap with Up/Down.
  - Open the channel panel and tune.
  - Open the guide, move, and tune.
  - Search, then tune.
  - Toggle a favorite and confirm the Favorites category updates.
  - Confirm History records the channel.
  - Open the audio and subtitle dialogs on a fixture HLS stream that has 2 audio tracks + 1 subtitle track (served locally).
- **Performance check on the emulator** with a 10k-channel / 50 MB EPG fixture: no ANR, guide scroll without dropped-frame bursts (`gfxinfo`), memory reasonable (`meminfo`).
- **Manual smoke checklist** (emulator now, Fire TV later): launcher banner, first-run flow, playback start time, zap, guide, logos visible, tracks, Back behaviour, resume last channel after relaunch.
- **Done** means: all automated tests green, smoke checklist passed on the emulator, and signed APKs built (universal; v1 has no native libs). The README covers build and Fire TV sideload steps (ADB over Wi-Fi or the Downloader app), `LICENSE` (Apache-2.0) and `NOTICE` (third-party licences).

## 8. Decisions already made

| Decision | By |
|---|---|
| Native Kotlin + Compose for TV + Media3 ExoPlayer | Owner |
| Playlist types: M3U URL, Xtream Codes, local M3U | Owner |
| Name "Tiviate" + supplied logo | Owner |
| Emulator testing now, Fire TV later | Owner |
| Original code; neither telly nor OwnTV forked | Owner (requirement) + my recommendation from licence review |
| Apache-2.0; no GPL deps; no FFmpeg in v1 | My call |
| minSdk 23; primary target Fire OS 7 / API 28 | My call |
| Out-of-scope list (§3) | My call, following the owner's "first do this, then enhance" |

## 9. Left to the builder

Module split beyond `:core`/`:app`/`:baselineprofile`; DI approach; navigation mechanism; JSON streaming library; exact dp sizes and typography scale; guide rendering technique (lazy layout vs custom draw); final buffer numbers after on-device tuning; the HLS test fixture generation method; exact Gradle/AGP/Kotlin versions (latest stable mutually compatible set).
