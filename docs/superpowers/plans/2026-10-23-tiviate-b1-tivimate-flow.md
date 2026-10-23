# Tiviate B1 — TiviMate main-screen flow, player options, settings overhaul

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make moving around Tiviate feel like TiviMate 5.x. That covers the guide's groups column and side menu, the Back chain, the fullscreen keys, an options panel (audio and subtitles for all channels, display mode, zoom, quality, stats, sleep timer), and settings arranged in TiviMate's sections.

**Architecture:** This builds on Phase A. The guide gains a side-navigation state (grid, groups, rail) driven by remote keys. The player swaps its quick-actions bar for an `OptionsPanel` overlay with picker dialogs. New `AppSettings` fields persist in `SettingsStore`, and the engine applies them through Media3 `TrackSelectionParameters`. Video framing (display mode and zoom) is done in Compose around the `PlayerView`.

**Tech Stack:** Kotlin, Compose for TV 1.1, Media3 1.11.1, DataStore, Room, JUnit/Robolectric, and instrumented tests on the `tiviate_tv28` AVD driven by remote keys.

**Spec:** `docs/superpowers/specs/2026-10-23-tiviate-design.md` covers Phase B (TiviMate parity, all free). The reference for flows is `MATE/reports/09-tivimate-feature-inventory.md`, together with telly's TiviMate captures. The owner allowed reading those captures on 2026-10-23, for observation only.

## Global Constraints

- **Original code only.** No TiviMate or telly code, images, colours, measurements or text. Labels are Tiviate's own wording.
- **TV first.** Every screen works with the D-pad alone. Instrumented tests drive it with `Remote` keys or `activate()`.
- **Light motion.** Tweens stay at 150 ms or less, with no blur and no shadows, and overlays are composed only while visible.
- **Every feature is free.** Nothing is gated.
- **No useless entries.** A menu item or setting appears only when its feature works.
- **Fullscreen keys (owner decision):** Up = next channel, and Down = options panel. OK = channel list (unchanged). Menu or long-OK = options panel. CH+/CH− still change channel both ways.

## Review Focus

1. **Back from any overlay closes that overlay first.** Only Back from the bare guide grid asks to exit.
2. **Leaving the groups column without choosing keeps the current group and focused row.**
3. **"For all channels" choices survive a channel change and an app restart:** audio language, subtitles and display mode.
4. **The options panel never steals or loses focus.** After a picker closes, focus returns to the card that opened it.
5. **When the sleep timer fires, playback stops and the app leaves.** Cancelling it ("Off") clears the timer.

---

### Task 1: Back chain and confirm-exit

**Files:** `ui/player/PlayerScreen.kt`, `ui/guide/GuideScreen.kt`, `data/Settings.kt`; tests in `androidTest/PlayerNavigationTest.kt`, `androidTest/GuideTest.kt`, `test/data/SettingsTest.kt`.

**Interfaces:** Produces `AppSettings.confirmExit: Boolean = true`.

- [ ] Test (unit `SettingsTest`): defaults include `confirmExit == true`, and a change persists.
- [ ] Test (instrumented): `backFromFullscreenOpensTheGuide`. With fullscreen and no overlay, Back shows `guide_screen` and the same channel keeps playing (`startCount` unchanged). This replaces `backTwiceExits`.
- [ ] Test (instrumented `GuideTest`): `backTwiceFromTheGuideExits`. Back at the bare guide shows "Press Back again to exit", and a second Back destroys the activity.
- [ ] Implement:
  - Player `BackHandler`: when in fullscreen with no overlay, push `Screen.Guide`.
  - Guide `BackHandler`: when the grid is bare, give the exit prompt (or finish at once if `confirmExit` is false). Otherwise close the open dialog, column or rail.
- [ ] Run the tests above, then commit.

### Task 2: Guide groups column and side menu

**Files:** `ui/guide/GuideScreen.kt`, new `ui/guide/GuideSideNav.kt`, `ui/guide/GuideViewModel.kt`; tests in `androidTest/GuideTest.kt`.

**Interfaces:**
- `enum class SideNav { GRID, GROUPS, RAIL }` in `GuideSideNav.kt`.
- `@Composable fun GroupsColumn(...)` with tag `guide_groups`; rows tagged `group_<label>`.
- `@Composable fun NavRail(onSearch, onTv, onSettings)` with tag `guide_rail`; rows tagged `rail_search`, `rail_tv`, `rail_settings`.
- `GuideViewModel.switchPlaylist(id: Long)`.

**Behaviour:**
- Left on a live programme cell (or on a row with no programmes) opens the groups column. Left on a past or future cell moves left. A long Left (key repeat) moves into the past.
- **Groups column contents:**
  - every playlist name as a header when there is more than one playlist; OK on another playlist's header switches to it;
  - then Favorites, All channels, Recent, and the provider groups.
  - The current group is marked.
- **In the groups column:** OK on a group filters the guide to it, closes the column and focuses row 0. Right or Back closes the column without changing anything. Left opens the rail.
- **Rail:** Search, TV and Settings (pinned at the bottom). OK on Search or Settings pushes that screen. OK on TV, Right or Back returns to the groups column.
- The Menu key opens the groups column (this replaces the group picker dialog).

- [ ] Test `leftOpensGroupsAndOkFiltersTheGuide`: Left shows `guide_groups`, then Down and OK on "Entertainment" filters the guide to 3 channels, and `guide_groups` is gone.
- [ ] Test `leftTwiceReachesSettings`: Left, Left shows `guide_rail`, then Down to Settings and OK shows `settings_screen`.
- [ ] Test `backClosesTheGroupsColumnFirst`: Left, then Back closes the column, and the guide stays open.
- [ ] Implement, delete `GroupPicker`, run `GuideTest`, then commit.

### Task 3: Fullscreen keys and the options panel

**Files:**
- `ui/player/PlayerScreen.kt`, `ui/player/PlayerViewModel.kt`;
- new `ui/player/OptionsPanel.kt`;
- `data/Settings.kt`;
- remove `QuickActionsBar` from `QuickActions.kt` (the `TrackDialog`/`focusSoon` stay).

**Interfaces:**
- `enum class UpDownMode { UP_NEXT_DOWN_OPTIONS, ZAP }`, with `AppSettings.upDownMode = UP_NEXT_DOWN_OPTIONS`.
- `Overlay.Options` replaces `Overlay.QuickActions`, and `Overlay.Picker(kind: PickerKind)` replaces `Overlay.Tracks`.
- `enum class PickerKind { AUDIO, SUBTITLES, DISPLAY, ZOOM, QUALITY, SLEEP }`.
- `@Composable fun OptionsPanel(...)` with tag `options_panel`. Cards are tagged `option_guide`, `option_audio`, `option_subtitles`, `option_display`, `option_zoom`, `option_quality`, `option_stats`, `option_sleep`, `option_favorite`, `option_external`, `option_search` and `option_settings`. Each card shows its current value.
- **Panel layout:**
  - top: an info block (logo, title, time range and minutes left, number and name, quality and audio badges, next programme, progress);
  - bottom: one row of cards.

**Keys:**
- With `UP_NEXT_DOWN_OPTIONS`: Up = `vm.zap(forward = true)`, and Down = open `Options`.
- With `ZAP`: Up and Down zap, as in Phase A.
- In both modes, Menu or long-OK opens `Options`.
- Back from Options closes it.

- [ ] Unit test (`SettingsTest`): the `upDownMode` default and persistence.
- [ ] Instrumented tests:
  - `upChangesToTheNextChannel`;
  - `downOpensTheOptionsPanel`, with `option_audio` focused first;
  - `menuOpensTheOptionsPanelAndFavoriteToggles`, which replaces `menuOpensQuickActionsAndTogglesFavorite`;
  - update `zapDownChangesChannelAndShowsBanner` to use CH−.
- [ ] Implement, run `PlayerNavigationTest`, then commit.

### Task 4: Pickers — audio, subtitles, display mode, zoom, quality, stats, sleep

**Files:**
- `player/Media3Engine.kt`, `player/PlaybackEngine.kt`;
- `ui/player/VideoSurface.kt`, `ui/player/OptionsPanel.kt`, new `ui/player/Pickers.kt`, `ui/player/PlayerViewModel.kt`;
- `data/Settings.kt`;
- new `player/SleepTimer.kt`.

**Interfaces:**
- **New `AppSettings` fields:**
  - `preferredAudioLanguage: String? = null`
  - `subtitlesForAll: Boolean = false`
  - `preferredTextLanguage: String? = null`
  - `displayMode: DisplayMode = DisplayMode.FIT`
  - `zoomPercent: Int = 100`
- **`enum class DisplayMode(label)`:** `FIT` "Fit", `FILL` "Stretch", `ZOOM` "Fill screen", `RATIO_16_9` "16:9", `RATIO_4_3` "4:3". This replaces `AspectMode`.
- **Engine:**
  - `fun videoTracks(): List<TrackOption>`;
  - `fun selectVideo(option: TrackOption?)` (null = automatic quality);
  - `fun stats(): PlaybackStats`;
  - `reconfigure` applies the preferred languages and subtitle defaults to `trackSelectionParameters`.
- **`data class PlaybackStats`:** `videoCodec`, `width`, `height`, `frameRate`, `videoBitrate`, `audioCodec`, `audioChannels`, `sampleRate`, `bufferedMs`, `droppedFrames`, `bandwidthKbps`.
- **`class SleepTimer(clock: () -> Long)`:** `start(minutes)`, `cancel()`, `remainingMs()`, `isDue()`.
- **`fun videoFrame(mode: DisplayMode, zoomPercent: Int, containerW: Int, containerH: Int, videoW: Int, videoH: Int): IntSize`:** a pure function, used by `VideoSurface` through `Modifier.layout`, with clipping.

**Pickers:**
- **Audio:** tracks, plus a "Use this language for all channels" toggle.
- **Subtitles:** Off and tracks, plus a "Turn on for all channels" toggle.
- **Display mode:** the five modes, applied to all channels.
- **Zoom:** 100, 110, 120, 130, 140 and 150%.
- **Quality:** Automatic plus the video tracks, as "1280 × 720 · 3.2 Mbps".
- **Sleep:** Off, 15, 30, 60, 90 and 120 minutes.
- **The Stats card** toggles a top-left stats overlay (tag `stats_overlay`), refreshed once a second.

- [ ] **Unit tests:**
  - `SettingsTest`: the new defaults and persistence.
  - `Media3EngineTest`: `preferredAudioLanguageIsApplied` (after `reconfigure(AppSettings(preferredAudioLanguage = "hi"))`, `player.trackSelectionParameters.preferredAudioLanguages == listOf("hi")`) and `subtitlesForAllEnablesTextTracks`.
  - `PlayerLogicTest`: `videoFrame` for FIT, FILL, ZOOM, 16:9 and 4:3 at 100% and 120%.
  - `SleepTimerTest`: due exactly at the start time plus N minutes, and cancel clears it.
- [ ] **Instrumented tests:**
  - `audioForAllChannelsPersists`: choose the second track, turn "all channels" on, and `settings.preferredAudioLanguage` is set.
  - `displayModePersists`.
  - `statsOverlayToggles`.
  - `subtitleDialogHasOffOption`, adjusted to the new path.
  - `audioDialogListsTwoTracks`, adjusted.
- [ ] Implement, run the tests above, then commit.

### Task 5: Settings in TiviMate's sections

**Files:**
- `ui/settings/SettingsScreen.kt` (split into `SettingsSections.kt` if it goes past 450 lines);
- `data/Settings.kt`;
- new `BootReceiver.kt` and its manifest entry;
- `data/sync/SyncWorker.kt`;
- `data/sync/EpgSyncer.kt` (past days from settings);
- `ui/AppRoot.kt` (update on start).

**Interfaces — new `AppSettings` fields:**
- `autoStartOnBoot = false`
- `epgPastDays = 1`
- `updateOnStart = true`
- `updateIntervalHours = 12`

**`SyncScheduler.schedulePeriodic(context, hours)`** re-enqueues the periodic job with `UPDATE` when the interval changes.

**`EpgSyncer`** takes `pastMs` from a provider lambda, `pastDays: () -> Int`.

**Sections and tags:**
- **General** (`section_general`): open last channel fullscreen on start; confirm exit with a second Back; start Tiviate when the TV turns on; default User-Agent.
- **Playlists** (`section_playlists`): as now, plus "Update all playlists".
- **TV guide** (`section_guide`): sources; update now; days of past programmes to keep (1, 2, 3, 7); update on app start; update every (6, 12 or 24 h).
- **Appearance** (`section_appearance`): channel numbers, clock, info bar timeout.
- **Playback** (`section_player`): buffer; software decoder; tunneling; match refresh rate; audio language for all channels (shows the value, with Reset); subtitles for all channels; default display mode.
- **Remote control** (`section_remote`): Up/Down in fullscreen (Up next channel + Down options, or Up/Down change channel).
- **Other** (`section_other`): clear history.
- **About** (`section_about`).

**Steps:**
- [ ] Unit tests:
  - `SettingsTest` for the new defaults and persistence;
  - `EpgSyncerTest.pastDaysSettingControlsWhatIsKept`;
  - `BootReceiverTest` (Robolectric): starts `MainActivity` only when `autoStartOnBoot` is on.
- [ ] Instrumented `SettingsTest`:
  - update the existing paths: `section_player` stays for the buffer, `section_guide` for EPG, `section_playlists`;
  - add `remoteControlModeSwitchesUpDown`;
  - add `confirmExitCanBeTurnedOff`.
- [ ] Implement, run the unit suite and the instrumented `SettingsTest`, then commit.

### Task 6: Verify on the emulator with the owner's playlist

- [ ] Run the full unit and instrumented suites, both green.
- [ ] Build the release APK, then run `seed_xtream.sh`.
- [ ] Take screenshots of the guide with the groups column and rail, the options panel, a picker, the stats overlay, and Settings.
- [ ] Check logcat: no crashes and no ANRs.
- [ ] Update `reports/09-tivimate-feature-inventory.md` statuses.
