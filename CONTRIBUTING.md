# Contributing to Tiviate

Thanks for helping make Tiviate better! Bug reports, ideas, translations, docs and code are all welcome.
Tiviate is created and maintained by **Amit Kumar** ([@Amitk2108](https://github.com/Amitk2108)).

## Ground rules

- **Player only.** Tiviate never ships, links to or hosts channels, playlists, streams or credentials.
  Please don't add any — not in code, tests, issues or screenshots. Test with the built-in fixture playlist
  (`app/src/androidTest/java/app/tiviate/FixtureServer.kt`) or your own subscription, kept private.
- **Original work only.** Don't copy code, images, icons, texts, colours or measurements from TiviMate or
  any other proprietary app, and don't decompile one to "see how it does it". Describe the behaviour you
  want in your own words and build it fresh.
- **No secrets.** Never commit `local.test.properties`, `keystore.properties`, `*.jks` or any login. They are
  git-ignored; keep it that way.
- **TV first.** Everything must work with a basic remote (D-pad, OK, Back, Menu). Touch is a bonus.
- **Light on low-end TVs.** Fire TV sticks have little memory. Avoid heavy effects (blur, shadows, large
  images); keep animations short.
- **Free for everyone.** No paywalls, ads, analytics or tracking.

## Getting set up

1. Install **JDK 17** and **Android Studio** (or the Android SDK with compileSdk 36).
2. Fork the repo and clone your fork:
   ```bash
   git clone https://github.com/<you>/tiviate.git
   cd tiviate
   ```
3. Create an Android TV emulator (1080p, API 28 or newer) or connect a TV with adb.
4. Build and run:
   ```bash
   ./gradlew :app:installDebug
   ```

## Making a change

1. Create a branch from the default branch: `git checkout -b fix/guide-left-key`.
2. **Write a test first**, then the code. JVM tests live in `core/src/test` and `app/src/test`; UI tests on
   the emulator live in `app/src/androidTest` and drive the app with remote keys.
3. Run the checks:
   ```bash
   ./gradlew :core:test :app:testDebugUnitTest
   ./gradlew :app:connectedDebugAndroidTest
   ```
4. Keep the code style of the file you touch (Kotlin, Compose for TV, short functions, comments that say why).
5. Commit with a clear message, for example `fix(guide): Left on an empty future slot moves back in time`.
6. Open a pull request: what changed, why, how you tested it, and a screenshot for UI changes (taken with
   the fixture playlist).

## Translations

On-screen texts go through `t("English text")` / `tf("Text %s", value)`. Translations live in
`app/src/main/assets/i18n/<language>.json`, keyed by the English text; anything missing falls back to
English.

To improve a language: edit its JSON file. Keep `%s` / `%d` placeholders, and keep product names
(Tiviate, Xtream Codes, User-Agent, M3U, HLS, XMLTV) unchanged.

## Reporting bugs

Open an issue with:
- your device (for example *Fire TV Stick 4K, Fire OS 8*) and the Tiviate version (*Settings → About*);
- what you did, what you expected and what happened;
- a photo or screenshot if it's visual. **Hide channel names, logos, server addresses and logins.**

## Licence

By contributing you agree that your work is released under the project's licence,
the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).
