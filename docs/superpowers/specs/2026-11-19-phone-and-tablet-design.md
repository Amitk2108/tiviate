# Tiviate on phones and tablets — design

Date: 2026-11-19 · Status: approved by the owner (phone layout: video on top + list; everything ships together as 0.3.0)
Trigger: GitHub issue #1 ("Version for mobile devices"): on a phone the touchscreen does nothing; on a TV everything is fine.

## Why

Tiviate is a TV-first app, but people also install it on phones and tablets. Today they get a TV screen they can't touch. The goal is one APK that looks and works right on each kind of screen, without making the TV app worse.

## What is wrong today (checked on a phone emulator, Pixel 9 Pro XL)

1. **Taps do nothing.** Every button, card, row and tile is `FocusSurface`, built on the Android TV library's `Surface`. That surface reacts only to the remote's OK key; it ignores touch. Tapping "Xtream Codes" on the welcome screen does nothing.
2. **The guide can't be dragged.** The TV guide list has `userScrollEnabled = false` and moves only with the remote's arrow keys.
3. **Always landscape.** The activity is locked to `landscape`, so a phone held upright shows a sideways TV screen.
4. **TV-sized layouts.** Screens are designed for 960 × 540 dp (a 1080p TV). A phone in portrait is about 412 dp wide.

## Goals

- Touch works everywhere: tap = select, long-press = the same menu as holding OK, swipe to scroll.
- Each device gets a layout made for it, chosen automatically:
  - **TV** (Android TV, Google TV, Fire TV): today's layout, unchanged.
  - **Tablet** (window 600 dp wide or more): TV-like two-pane layout, made for touch.
  - **Phone** (narrower than 600 dp): a portrait layout with the video on top and the channels below; rotate for fullscreen.
- Remote, keyboard and touch all keep working on every layout (tablets with keyboards, phones with game pads, Chromebooks).
- Still one APK and one codebase. Only layouts differ; playlists, guide, player, settings and backups are shared.

## Non-goals (for now)

Movies and series (VOD), casting to a TV, using the phone as a remote, syncing between devices, a Play Store listing, a separate phone app.

## How the device type is decided

```
TV       = UiModeManager mode is TELEVISION, or the device has the leanback feature
Tablet   = not a TV, and the app window is at least 600 dp wide
Phone    = everything else
```

- It is worked out again on every rotation and window resize, so foldables, split screen and tablets turned upright pick the right layout.
- It is provided once in `AppRoot` as `LocalFormFactor`, and each screen chooses its layout from it.
- Input is a separate question from layout. Focus rings, the yellow selection frame and the "press OK" hints show only when the remote or keyboard was used last (Compose `InputMode.Keyboard`). Touch users don't see a stuck focus ring.

## Layouts

### Phone, portrait (main screen)

```
┌──────────────────────────────┐
│        ▶ live video 16:9      │  tap: controls · double-tap: fullscreen
│  Aurora News · LIVE  ▓▓▓░ 12m │
├──────────────────────────────┤
│ [All] [★ Favourites] [News] …│  group chips, swipe sideways
├──────────────────────────────┤
│ 1  AN  Aurora News       ▶   │  playing: light green
│        Now: Show 3 · 12 min   │
│ 2  BS  Beacon Sports          │  tap: play · long-press: menu
│        Now: Match day         │
│ …                             │
├──────────────────────────────┤
│  Live    Guide   Search  ⚙   │  bottom bar
└──────────────────────────────┘
```

- **Rotate to landscape:** fullscreen video with the system bars hidden. Tap shows controls: back, channel name, previous and next, play/pause, channel list, options, picture-in-picture. Swipe up or down changes channel (this already exists).
- **Guide tab:** a narrow fixed column of channel logos on the left; the timeline scrolls sideways, channels scroll up and down. Tap a programme to open its details with "Watch".
- **Settings:** one column, the same sections as on TV, with the section list as its own first page.

### Tablet

- **Landscape:** the TV layout (preview on top, guide below, groups column on the left), with touch scrolling in both directions and taps on programmes.
- **Portrait:** like the phone layout, but the channel list shows a second column with what's on next.

### TV

Unchanged. All existing TV tests must keep passing.

## Touch rules (every layout)

- Tap: moves the focus there and selects it (same as OK). Long-press: same as holding OK.
- Touch targets are at least 48 dp. Lists use the platform's normal scroll and fling.
- Back gesture: same as the remote's Back. Holding Back has no gesture equivalent; the bottom bar's Live tab covers it.
- Rotation is free on phones and tablets and locked to landscape on TV.

## How it is built

1. `FocusSurface` gains tap and long-press handling (one change, and every screen that uses it becomes tappable).
2. `LocalFormFactor` (TV, tablet, phone), worked out in `AppRoot` from the UI mode and the window width.
3. The guide grid accepts drag scrolling when the form factor isn't TV, and a tap on a programme focuses it.
4. Phone and tablet screens are new composables next to the TV ones (`ui/phone/…`). They reuse the same ViewModels, `VideoHost`/`VideoSlot` (the shared video surface already moves to any rectangle) and settings rows.
5. The manifest drops the landscape lock; the activity sets it at run time on TV only.

## Plan and releases

The owner chose one release: steps 1–3 ship together as **0.3.0**, and issue #1 is answered and closed then.

| Step | What viewers get |
|---|---|
| 1. Touch everywhere | Taps, long-presses and scrolling work on every screen; the player gets touch controls. |
| 2. Phone layout | Portrait main screen, phone guide, one-column settings, fullscreen on rotate. |
| 3. Tablet layout | Landscape: the TV layout with touch. Portrait: the phone layout, with what's next beside each channel. |
| 4. Polish (after 0.3.0) | Phone and tablet screenshots on the website and README. |

## Testing

- Unit tests: device-type rules (TV mode, 599 vs 600 dp, rotation).
- Instrumented tests on a phone emulator and a tablet emulator: tap to add a playlist, tap a channel to play, scroll the guide, rotate to fullscreen. The existing TV test suite runs on a TV emulator (which has to be recreated, about 1 GB).
- The owner tries each step on a real phone and on the Fire TV before release.

## Risks

- **TV regressions.** Mitigation: the TV layout code isn't edited for steps 2 and 3, and the TV tests must pass before each release.
- **Two sets of layouts to maintain.** Mitigation: only layouts are duplicated; data and logic stay shared.
- **Small phones in landscape.** About 360 dp tall, so step 1's TV layout will be cramped until step 2 lands.
