# Tiviate Phase A (v1 core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working, tested Fire TV / Android TV IPTV player. It covers live playback, M3U-URL / local-M3U / Xtream playlists, an XMLTV guide, logos, categories, search, favorites, history, tracks and D-pad navigation.

**Architecture:**
- `:core` is a pure-Kotlin JVM module: parsers, matching and URL building, all unit-tested.
- `:app` is an Android module containing:
  - Room as the single source of truth for channels and the EPG.
  - WorkManager plus repositories for sync.
  - One app-scoped Media3 ExoPlayer behind a small `PlaybackEngine` interface.
  - A Compose-for-TV UI driven by a hand-rolled screen stack and overlay state.
  - Manual dependency injection through `AppGraph`.

**Tech Stack:**
- Build: AGP 8.13.2, Gradle 8.14.x, Kotlin 2.3.21 (+ compose plugin, KSP).
- UI and media: Compose BOM 2026.09.00, `androidx.tv:tv-material` 1.1.0, Media3 1.11.1 (exoplayer, hls, dash, datasource-okhttp, ui).
- Data and sync: Room 2.8.5, WorkManager 2.12.0, DataStore 1.3.0.
- Networking and images: OkHttp 5.5.1 + okio, Moshi `JsonReader` (streaming only), Coil 3.6.3 (+ svg, okhttp network).
- Parsing: `org.tukaani:xz` 1.12, kxml2 (tests only).
- Tests: JUnit4, Robolectric 4.17, MockWebServer 5.5.1, Compose UI test, Macrobenchmark/baselineprofile.

**Spec:** `docs/superpowers/specs/2026-10-23-tiviate-design.md` (read §5–§7 before any task).

## Global Constraints

**Build target:**
- applicationId `app.tiviate`.
- minSdk 23, targetSdk 35, compileSdk 36.
- Licence Apache-2.0. No GPL dependencies.

**Branding and copying:**
- App name `Tiviate`, held in one string resource `app_name`.
- No TiviMate, telly or OwnTV code, strings, colours, measurements or assets. Never open `reference/telly/docs/reference/`.

**Visual tokens:**
- Background `#05080F`, surface `#0B1220`, raised `#121C30`, outline `#1E2A44`.
- Text `#EAF2FF`, secondary `#8DA2C0`, brand gradient `#2D6BFF → #16D9E3`.
- Focus: 2 dp cyan border, glow, 1.04× scale.
- Design at 960×540 dp with a 48 dp safe margin.

**Memory and data size:**
- Streaming parsers only; never hold a whole playlist or EPG in memory.
- Database writes are batched at ~500 rows per transaction.
- SQL `IN (…)` lists are chunked to ≤ 900 values.

**Networking:**
- Cleartext HTTP is allowed. Cross-protocol redirects are followed.
- Each playlist has its own User-Agent.
- Channel headers come from `#EXTVLCOPT` lines.

**Channel identity:**
- `stableKey` never includes the stream URL.
- One active playlist at a time.

**EPG window and refresh:**
- Keep programmes from now − 1 day to now + 7 days.
- Refresh works by swapping generations, so the guide is never empty.
- Data counts as stale after 12 h. A periodic job runs every 12 h.

**Player buffer presets (min / max / start / restart):**
- Fast: 5 s / 20 s / 0.75 s / 2 s.
- **Normal (default):** 15 s / 40 s / 1.5 s / 3 s.
- Stable: 30 s / 90 s / 2.5 s / 5 s.

**Player recovery:**
- Retry backoff: 1, 2, 4, 8, 16 s, at most 5 tries.
- Watchdog: buffering longer than 20 s, or position frozen for 10 s while playing.

**Overlay timing:**
- Zap banner auto-hides after 4 s.
- Number entry commits after 1.5 s.
- History records a channel after 5 s of watching and keeps the last 50.

**Real-provider facts (owner's Xtream account, probed 2026-10-23):**

| Area | Observed |
|---|---|
| Size | 1,407 live streams in 22 categories |
| Output format | `allowed_output_formats=["m3u8"]` only |
| Connections | **`max_connections=1`**: never open two streams at once (preview and fullscreen must share one player) |
| EPG IDs | 330 duplicated `epg_channel_id` values |
| Logos | Mostly http PNG; 11 have no extension; 1 SVG |
| EPG file | `xmltv.php` → 302 to `https` GitHub asset, `application/octet-stream` gzip (2.6 MB → 25 MB XML), 2,730 channels, 69k programmes, `+0000` times, programme `<icon>` on nearly all programmes, `catchup-id` attribute |
| Streams | HLS master behind a 302 with a token; H.264/AAC 720p/1080p at **25 fps** with a separate audio rendition |

**Credential handling:**
- Credentials live only in the gitignored `local.test.properties`.
- Instrumented real-provider tests receive them as `-Pandroid.testInstrumentationRunnerArguments.xtream*`, so they are **never** compiled into an APK.

## Review Focus

1. **A huge playlist (50k channels) or EPG (200 MB) on a 1 GB device.** Sync must finish without running out of memory, and the UI must stay responsive while it runs. *Task 7 test:* `syncLargePlaylistStreamsInBatches` uses 20k generated entries and checks that the row count is right and that no list of all entries is built.
2. **Messy real-world M3U lines.** This covers commas in titles, `tvg-name` containing commas, single or no quotes, a BOM, CRLF line endings, a missing `#EXTM3U`, and a URL line that comes before the next `#EXTINF`. The parse must be correct, and broken entries are skipped rather than failing the whole import. *Task 2 tests.*
3. **The provider rotates stream-URL tokens, or `tvg-id` values repeat.** Favorites and history must survive a resync. *Task 5 test:* `stableKeyIgnoresUrlAndDisambiguatesDuplicates`. *Task 7 test:* `resyncKeepsFavorites`.
4. **A logo URL that is SVG, has no extension, needs a User-Agent, redirects http→https, or returns 404.** The logo shows, or the monogram fallback does. It never shows a blank tile or crashes. *Task 10 test:* `logoModelFallsBackInOrder`, plus the SVG decoder registered and the image OkHttp client following redirects with the User-Agent set.
5. **A stream that dies mid-play, or a 404 on tune.** Playback auto-retries with backoff, then shows the error card. Focus stays usable throughout. *Task 9 tests:* `RetryPolicyTest`, `StallWatchdogTest`.

---

### Task 1: Project scaffold that builds and launches on TV

**Files:**
- Create:
  - `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, Gradle wrapper (8.14.x).
  - `core/build.gradle.kts` (kotlin-jvm, JVM 17).
  - `app/build.gradle.kts`.
  - `app/src/main/AndroidManifest.xml`, `app/src/main/java/app/tiviate/TiviateApp.kt`, `app/src/main/java/app/tiviate/MainActivity.kt`.
  - `app/src/main/res/values/strings.xml` (`app_name`=Tiviate).
  - `res/mipmap-*` launcher icons and `res/drawable-xhdpi/banner.png` (320×180), generated from `../Tiviate Neon Play Icon.png`.
  - `res/xml/network_security_config.xml` (cleartext permitted).
  - `LICENSE` (Apache-2.0), `.gitignore`.
- Test: `app/src/test/java/app/tiviate/ManifestTest.kt` (Robolectric).

**Interfaces:** Produces `TiviateApp : Application` (holds `lateinit var graph: AppGraph` — added in Task 6) and `MainActivity : ComponentActivity`.

- [ ] Step 1: `git init` and write the build files. In the manifest:
  - `uses-feature android.software.leanback required=false` and `android.hardware.touchscreen required=false`.
  - Permissions: INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, READ_EXTERNAL_STORAGE (`maxSdkVersion=32`).
  - `MainActivity` gets LAUNCHER + LEANBACK_LAUNCHER intent filters and `android:banner="@drawable/banner"`, plus landscape orientation, `singleTask` launch mode and `configChanges` covering screen, uiMode, keyboard and navigation.
- [ ] Step 2: Write the failing test `ManifestTest.launcherHasLeanbackAndBanner`. It resolves `Intent.CATEGORY_LEANBACK_LAUNCHER` to MainActivity and asserts the application banner is non-zero.
- [ ] Step 3: `MainActivity` shows a Compose-for-TV `Text("Tiviate")` on the background colour.
- [ ] Step 4: Run `./gradlew :core:build :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL, test passes.
- [ ] Step 5: Commit `chore: scaffold Tiviate project`.

### Task 2: M3U streaming parser (`:core`)

**Files:** Create `core/src/main/kotlin/app/tiviate/core/m3u/M3uParser.kt` and `M3uModels.kt`. Test: `core/src/test/kotlin/app/tiviate/core/m3u/M3uParserTest.kt`.

**Interfaces — produces:**
```kotlin
data class M3uHeader(val epgUrls: List<String>, val attrs: Map<String, String>)
data class M3uEntry(
    val name: String, val url: String, val tvgId: String?, val tvgName: String?, val tvgLogo: String?,
    val groupTitle: String?, val tvgChno: Int?, val catchup: String?, val catchupSource: String?,
    val catchupDays: Int?, val userAgent: String?, val referrer: String?, val attrs: Map<String, String>,
)
data class M3uStats(val entries: Int, val skipped: Int)
object M3uParser { fun parse(source: okio.BufferedSource, onHeader: (M3uHeader) -> Unit = {}, onEntry: (M3uEntry) -> Unit): M3uStats }
```

- [ ] Step 1: Failing tests, each with an inline playlist string:
  - `parsesBasicAttributes`: `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-chno`.
  - `titleKeepsCommasAfterFirstUnquotedComma`: `#EXTINF:-1 tvg-name="A, B" group-title="News",CNN, International` gives name `CNN, International` and tvgName `A, B`.
  - `singleQuotedAndUnquotedAttributes`.
  - `handlesBomCrlfAndMissingHeader`.
  - `headerEpgUrls`: `url-tvg="a.xml.gz,b.xml"` and `x-tvg-url` give `[a.xml.gz, b.xml]`.
  - `extgrpAppliesWhenNoGroupTitle`.
  - `extvlcoptUserAgentAndReferrer`.
  - `catchupAttributesStored`.
  - `skipsEntryWithoutUrlAndCounts`: stats.skipped == 1.
  - `urlLineWithoutExtinfUsesUrlAsName`.
- [ ] Step 2: Run `./gradlew :core:test`. Expected: FAIL (unresolved).
- [ ] Step 3: Implement as a line-by-line state machine.
  - The attribute section ends at the first comma that is not inside quotes; the title is everything after it, trimmed.
  - Duration is the first token after `#EXTINF:`.
- [ ] Step 4: Run `./gradlew :core:test`. Expected: PASS.
- [ ] Step 5: Commit `feat(core): streaming M3U parser`.

### Task 3: XMLTV parser + time + compression (`:core`)

**Files:** Create `core/.../xmltv/XmltvParser.kt`, `XmltvModels.kt`, `XmltvTime.kt`, `core/.../io/Decompress.kt`. Test: `XmltvParserTest.kt`, `XmltvTimeTest.kt`, `DecompressTest.kt`. Test fixtures go in `core/src/test/resources/xmltv/` (`small.xml` plus its gz and xz versions, generated in the test with JDK GZIP and `org.tukaani.xz.XZOutputStream`).

**Interfaces — produces:**
```kotlin
data class XmltvChannel(val id: String, val displayNames: List<String>, val iconUrl: String?)
data class XmltvProgramme(val channelId: String, val startMs: Long, val stopMs: Long, val title: String,
    val subtitle: String?, val description: String?, val category: String?, val iconUrl: String?, val episode: String?)
interface XmltvHandler { fun onChannel(c: XmltvChannel); fun wantsChannel(channelId: String): Boolean; fun onProgramme(p: XmltvProgramme) }
class XmltvParser(private val newPullParser: () -> org.xmlpull.v1.XmlPullParser, private val windowStartMs: Long, private val windowEndMs: Long) {
    fun parse(input: java.io.InputStream, handler: XmltvHandler) }
object XmltvTime { fun parse(value: String): Long? }          // epoch ms
object Decompress { fun wrap(input: java.io.InputStream): java.io.InputStream }   // sniffs gzip 1f8b / xz fd377a585a00
```
Dependencies: `compileOnly("xmlpull:xmlpull:1.1.3.1")`, `testImplementation("net.sf.kxml:kxml2:2.3.0")`, `implementation("org.tukaani:xz:1.12")`.

- [ ] Step 1: Failing tests:
  - `XmltvTimeTest`: `"20261023120000 +0200"` → `2026-10-23T10:00:00Z`; `"20261023120000"` → UTC; `"202610231200 -0500"`; garbage → null.
  - `parsesChannelsWithMultipleDisplayNamesAndIcon`.
  - `programmeFieldsAndProgrammeIcon`.
  - `missingStopUsesNextStartForSameChannel`.
  - `missingStopOnLastProgrammeDefaultsToOneHour`.
  - `skipsProgrammesOutsideWindow`.
  - `skipsUnwantedChannels`: `wantsChannel` false means no callbacks.
  - `DecompressTest`: `gzipAndXzAndPlainAllRoundTrip`.
- [ ] Step 2: Run `./gradlew :core:test`. Expected: FAIL.
- [ ] Step 3: Implement.
  - Pull-parse with `FEATURE_PROCESS_NAMESPACES=false`.
  - Programmes with a null stop are kept in a per-channel pending map: a pending programme is emitted when that channel's next start arrives, and the rest are flushed at end of document with `stop = start + 3_600_000`.
  - `Decompress` reads the magic bytes through `BufferedInputStream.mark(6)`.
- [ ] Step 4: Run `./gradlew :core:test`. Expected: PASS.
- [ ] Step 5: Commit `feat(core): streaming XMLTV parser with gz/xz`.

### Task 4: Xtream Codes API models + URLs + streaming JSON (`:core`)

**Files:** Create `core/.../xtream/XtreamUrls.kt`, `XtreamJson.kt`, `XtreamModels.kt`. Test: `XtreamUrlsTest.kt`, `XtreamJsonTest.kt`. Dependency: `com.squareup.moshi:moshi:1.15.2` (JsonReader only).

**Interfaces — produces:**
```kotlin
data class XtreamCredentials(val server: String, val username: String, val password: String)
data class XtreamAccount(val status: String?, val expiresAtMs: Long?, val maxConnections: Int?, val activeConnections: Int?, val authorized: Boolean,
    val allowedFormats: List<String>)   // real provider returns ["m3u8"] only → live() ext must follow this (prefer "ts" only if allowed)
data class XtreamCategory(val id: String, val name: String)
data class XtreamLiveStream(val streamId: Long, val num: Int?, val name: String, val icon: String?, val epgChannelId: String?,
    val categoryId: String?, val tvArchive: Boolean, val tvArchiveDays: Int?)
object XtreamUrls {
    fun normalizeServer(raw: String): String
    fun api(c: XtreamCredentials, action: String? = null): String
    fun live(c: XtreamCredentials, streamId: Long, ext: String = "ts"): String
    fun xmltv(c: XtreamCredentials): String }
object XtreamJson {
    fun account(src: okio.BufferedSource): XtreamAccount
    fun categories(src: okio.BufferedSource): List<XtreamCategory>
    fun liveStreams(src: okio.BufferedSource, onStream: (XtreamLiveStream) -> Unit): Int }
```

- [ ] Step 1: Failing tests:
  - `normalizeServer`: `"host:8080"` → `http://host:8080`; `"http://h:80/"` → `http://h:80`; `"http://h/player_api.php?username=x"` → `http://h`; `"https://h/get.php?username=a&password=b&type=m3u"` → `https://h`.
  - URL-encoding of credentials in `api` and `xmltv`.
  - `live` → `{server}/live/{u}/{p}/{id}.ts`.
  - `accountParsesUserInfo`: `auth`=1, `status` "Active", `exp_date` "1798761600" → ms.
  - `liveStreamsToleratesStringOrNumberFields`: `stream_id` "12" or 12, `tv_archive` "1" or 1, `num` null.
  - `liveStreamsSkipsMalformedItem`.
- [ ] Step 2: Run `./gradlew :core:test`. Expected: FAIL.
- [ ] Step 3: Implement with Moshi `JsonReader.of(src)`. Read every scalar through a `peek()`-based helper that accepts strings, numbers, booleans and nulls.
- [ ] Step 4: Run `./gradlew :core:test`. Expected: PASS.
- [ ] Step 5: Commit `feat(core): Xtream Codes client models and streaming JSON`.

### Task 5: Name normalization, EPG matching, stable keys (`:core`)

**Files:** Create `core/.../match/NameNormalizer.kt`, `EpgMatcher.kt`, `StableKeyAssigner.kt`. Test: `NameNormalizerTest.kt`, `EpgMatcherTest.kt`, `StableKeyAssignerTest.kt`.

**Interfaces — produces:**
```kotlin
object NameNormalizer { fun normalize(name: String): String }
class EpgMatcher {   // fed channels from all sources in priority order
    fun add(sourceId: Long, priority: Int, channel: XmltvChannel)
    fun match(tvgId: String?, names: List<String>): EpgRef? }
data class EpgRef(val sourceId: Long, val xmltvId: String)
class StableKeyAssigner { fun keyFor(xtreamStreamId: Long?, tvgId: String?, name: String, group: String?): String }
```

- [ ] Step 1: Failing tests:
  - `normalize`: `"US: CNN HD"` → `"cnn"`; `"UK | BBC One FHD"` → `"bbc one"`; `"Sky Sports 1 (4K) HEVC"` → `"sky sports 1"`; `"ESPN 50fps"` → `"espn"`; `"  Ça   Va! "` → `"ca va"`.
  - `matchPrefersTvgIdCaseInsensitive`.
  - `matchFallsBackToNormalizedDisplayName`.
  - `lowerPriorityNumberWins`: priority 0 beats 1.
  - `noMatchReturnsNull`.
  - `stableKeyIgnoresUrlAndDisambiguatesDuplicates`: the same tvg-id + name twice gives a `#2` suffix on the second; Xtream gives `x:12`; no tvg-id gives `n:{norm}|{normGroup}`; the key is the same across two assigner instances fed the same sequence.
- [ ] Step 2: Run `./gradlew :core:test`. Expected: FAIL.
- [ ] Step 3: Implement.
  - Normalization:
    1. Strip a country prefix: 2–3 letters followed by `:`, `|` or `-`.
    2. Strip quality and codec tokens.
    3. Strip brackets.
    4. Apply NFD and drop accent marks.
    5. Lowercase, drop non-alphanumerics, collapse spaces.
  - Keys:
    - With a tvg-id: `t:{tvgId}|{norm}`.
    - With no tvg-id: `n:{norm}|{normGroup}`.
    - Both forms get `#k` for the k-th repeat.
- [ ] Step 4: Run `./gradlew :core:test`. Expected: PASS.
- [ ] Step 5: Commit `feat(core): name normalization, EPG matching, stable keys`.

### Task 6: Room database, DAOs, AppGraph

**Files:**
- Create in `app/src/main/java/app/tiviate/data/db/`:
  - `TiviateDatabase.kt`.
  - `Entities.kt`: `PlaylistEntity`, `ChannelEntity`, `FavoriteEntity`, `HistoryEntity`, `EpgSourceEntity`, `EpgChannelEntity`, `ProgrammeEntity`, `ChannelFts`, `ProgrammeFts`.
  - `Daos.kt`: `PlaylistDao`, `ChannelDao`, `FavoriteDao`, `HistoryDao`, `EpgDao`.
  - `Chunked.kt`.
- Create `app/src/main/java/app/tiviate/AppGraph.kt`.
- Test: `app/src/test/java/app/tiviate/data/db/DaoTest.kt` (Robolectric, in-memory database).

**Interfaces — produces:**
```kotlin
suspend fun <T> chunkedIn(keys: List<String>, size: Int = 900, query: suspend (List<String>) -> List<T>): List<T>
// ChannelDao
fun categories(playlistId: Long): Flow<List<CategoryRow>>              // CategoryRow(name: String, count: Int)
fun channelsInGroup(playlistId: Long, group: String?): PagingSource<Int, ChannelEntity>   // group null = All
fun channelList(playlistId: Long, group: String?): Flow<List<ChannelEntity>>   // ordered by sortIndex, for zapping
suspend fun byKey(playlistId: Long, stableKey: String): ChannelEntity?
suspend fun search(playlistId: Long, ftsQuery: String, limit: Int = 50): List<ChannelEntity>
// FavoriteDao: toggle(playlistId, key), observeKeys(playlistId): Flow<Set<String>>, favoriteChannels(playlistId): Flow<List<ChannelEntity>>
// HistoryDao: record(playlistId, key, nowMs, watchedSec), recent(playlistId, limit = 50): Flow<List<ChannelEntity>>, lastWatched(): HistoryEntity?
// EpgDao: programmesFor(refs: List<String>, from: Long, to: Long): List<ProgrammeEntity>  (refs = "sourceId:xmltvId", chunked)
//         nowNext(refs, nowMs): List<ProgrammeEntity>; activeGeneration(sourceId): Int; swapGeneration(sourceId, newGen) @Transaction
//         searchProgrammes(ftsQuery, fromMs, toMs, limit = 50)
class AppGraph(context: Context) { val db: TiviateDatabase; val http: OkHttpClient /* Task 7 */ ; ... }
```
`ChannelEntity` columns are exactly those in spec §6.1, plus `epgRef: String?` (`"sourceId:xmltvId"`) and `logoFallback: String?` (the EPG icon). `ProgrammeEntity` has `epgRef`, `generation` and indexes `(epgRef, start)` and `(epgRef, stop)`.

- [ ] Step 1: Failing tests:
  - `categoriesCountPerGroup`.
  - `favoritesToggleAndSurviveChannelReplace`: delete and reinsert a channel with the same stableKey; the favorite is still listed.
  - `historyKeepsLast50`.
  - `programmesForChunksOver900Refs`: 2,000 refs; all rows returned.
  - `swapGenerationDeletesOld`.
  - `channelFtsSearchFindsPrefix`.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*DaoTest*'`. Expected: FAIL.
- [ ] Step 3: Implement the entities, DAOs and database. Turn on WAL with `setJournalMode(WRITE_AHEAD_LOGGING)` and call `exportSchema=false`. Wire `AppGraph` into `TiviateApp`.
- [ ] Step 4: Run the same command. Expected: PASS.
- [ ] Step 5: Commit `feat(data): Room schema and DAOs`.

### Task 7: Sync — playlists (M3U URL/file, Xtream), EPG, WorkManager

**Files:**
- Create in `app/src/main/java/app/tiviate/data/sync/`: `HttpClients.kt`, `PlaylistSyncer.kt`, `EpgSyncer.kt`, `SyncWorker.kt`, `SyncScheduler.kt`.
- Create `app/src/main/java/app/tiviate/data/PlaylistRepository.kt`.
- Test: `app/src/test/java/app/tiviate/data/sync/PlaylistSyncerTest.kt` and `EpgSyncerTest.kt` (Robolectric + MockWebServer).

**Interfaces — consumes:** Tasks 2–6. **Produces:**
```kotlin
sealed interface SyncProgress { data class Channels(val count: Int) : SyncProgress; data class Guide(val programmes: Int) : SyncProgress; data class Failed(val message: String) : SyncProgress; data object Done : SyncProgress }
class PlaylistRepository(graph: AppGraph) {
    suspend fun addM3uUrl(name: String, url: String, epgUrl: String?, userAgent: String?): Long
    suspend fun addM3uFile(name: String, uri: String, epgUrl: String?): Long
    suspend fun addXtream(name: String, creds: XtreamCredentials, useHls: Boolean): Long
    fun sync(playlistId: Long): Flow<SyncProgress>   // playlist then its EPG sources
    suspend fun delete(playlistId: Long)
    suspend fun xtreamAccount(playlistId: Long): XtreamAccount? }
object SyncScheduler { fun schedulePeriodic(context: Context); fun syncIfStale(context: Context, staleMs: Long = 12 * 3_600_000L) }
```
`HttpClients.default(userAgent)` follows cross-protocol redirects (`followSslRedirects(true)`), uses an 8 s connect timeout and a 20 s read timeout, and handles gzip transparently. The default User-Agent is `Tiviate/<versionName> (Linux; Android <release>) ExoPlayerLib/1.11.1`.

- [ ] Step 1: Failing tests (MockWebServer):
  - `m3uUrlSyncInsertsChannelsAndDetectsEpg`.
  - `resyncKeepsFavorites`: tokens in URLs change between syncs.
  - `resyncDeletesRemovedChannels`.
  - `syncLargePlaylistStreamsInBatches`: 20k generated entries; count == 20000.
  - `xtreamSyncBuildsTsUrlsAndCategories`.
  - `failedDownloadEmitsFailedAndKeepsOldChannels`.
  - `EpgSyncerTest.gzEpgMatchesByIdAndNameAndSwapsGeneration`: a first sync, then a second sync with different titles; at no point does a query between the two return 0 rows.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*Syncer*'`. Expected: FAIL.
- [ ] Step 3: Implement.
  - **Playlist sync:**
    - Stream-parse into batches of 500 inside `db.withTransaction`, inserting with a new `syncStamp`.
    - Afterwards delete rows whose stamp is not the new one.
    - A failure before completion leaves the old rows untouched, because the stamp is never committed.
  - **EPG sync:**
    - Sources come from the playlist's AUTO source plus all MANUAL sources.
    - Build `EpgMatcher` from the `<channel>` elements, then write `epgRef`, and `logoFallback` from the channel icon, onto the channels.
    - Write programmes under generation+1, then call `swapGeneration`.
    - The parser is created with `Xml.newPullParser()`.
  - **Worker:** `SyncWorker` (CoroutineWorker) syncs every enabled playlist. `SyncScheduler` schedules `PeriodicWorkRequest` (12 h) with `NetworkType.CONNECTED`.
- [ ] Step 4: Run the same command. Expected: PASS.
- [ ] Step 5: Commit `feat(data): playlist and EPG sync`.

### Task 8: Settings (DataStore)

**Files:** Create `app/src/main/java/app/tiviate/data/Settings.kt`. Test: `SettingsTest.kt` (Robolectric).

**Interfaces — produces:**
```kotlin
enum class BufferPreset(val minMs: Int, val maxMs: Int, val startMs: Int, val restartMs: Int) {
    FAST(5_000, 20_000, 750, 2_000), NORMAL(15_000, 40_000, 1_500, 3_000), STABLE(30_000, 90_000, 2_500, 5_000) }
data class AppSettings(val activePlaylistId: Long?, val bufferPreset: BufferPreset = BufferPreset.NORMAL, val preferSoftwareDecoder: Boolean = false,
    val tunneling: Boolean = false, val matchRefreshRate: Boolean = false, val defaultUserAgent: String? = null,
    val showChannelNumbers: Boolean = true, val showClock: Boolean = true, val bannerTimeoutMs: Int = 4_000,
    val lastChannelKey: String? = null, val lastGroup: String? = null)
class SettingsStore(context: Context) { val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings) }
```

- [ ] Step 1: Failing test `defaultsThenUpdatePersists`.
- [ ] Step 2: Run it. Expected: FAIL.
- [ ] Step 3: Implement it with Preferences DataStore.
- [ ] Step 4: Run it. Expected: PASS.
- [ ] Step 5: Commit `feat(data): settings store`.

### Task 9: Player — engine, controller, recovery

**Files:**
- Create in `app/src/main/java/app/tiviate/player/`: `PlaybackEngine.kt`, `Media3Engine.kt`, `PlayerController.kt`, `RetryPolicy.kt`, `StallWatchdog.kt`, `StreamType.kt`, `ExternalPlayer.kt`.
- Test: `app/src/test/java/app/tiviate/player/RetryPolicyTest.kt`, `StallWatchdogTest.kt`, `StreamTypeTest.kt`.

**Interfaces — produces:**
```kotlin
data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)
data class PlaybackState(val channelKey: String?, val isPlaying: Boolean, val isBuffering: Boolean, val error: String?,
    val videoWidth: Int, val videoHeight: Int, val frameRate: Float?, val audioLabel: String?, val retrying: Int)
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun play(url: String, headers: Map<String, String>, userAgent: String, channelKey: String)
    fun stop(); fun release()
    fun audioTracks(): List<TrackOption>; fun textTracks(): List<TrackOption>
    fun selectAudio(o: TrackOption); fun selectText(o: TrackOption?)   // null = off
    fun bindSurface(view: androidx.media3.ui.PlayerView)
}
class PlayerController(app: TiviateApp) { val engine: PlaybackEngine; fun tune(channel: ChannelEntity); fun zap(delta: Int, list: List<ChannelEntity>); val current: StateFlow<ChannelEntity?> }
class RetryPolicy(private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000)) { fun nextDelay(attempt: Int): Long?; }
class StallWatchdog(private val bufferingLimitMs: Long = 20_000, private val frozenLimitMs: Long = 10_000) {
    fun onSample(nowMs: Long, isPlaying: Boolean, isBuffering: Boolean, positionMs: Long): Boolean  // true = re-prepare
    fun reset() }
object StreamType { fun mimeFromUrl(url: String): String?; fun looksLikeHls(firstBytes: ByteArray): Boolean }
```
`Media3Engine` uses the following, with values from `BufferPreset` and `AppSettings`:
- `DefaultRenderersFactory.setEnableDecoderFallback(true)`.
- `DefaultLoadControl.Builder().setBufferDurationsMs(...)`.
- `OkHttpDataSource.Factory` with the default request properties.
- `DefaultMediaSourceFactory`.
- `setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)` and tunneling via `TrackSelectionParameters`.
- On `BehindLiveWindowException`: `seekToDefaultPosition(); prepare()`.

A watchdog tick runs every 1 s on the main thread while playing.

- [ ] Step 1: Failing tests:
  - `RetryPolicyTest`: delays 1, 2, 4, 8, 16 s, then null.
  - `StallWatchdogTest`: `bufferingOver20sTriggers`, `frozenPositionOver10sWhilePlayingTriggers`, `advancingPositionNeverTriggers`, `resetClears`.
  - `StreamTypeTest`: `.m3u8` → HLS; `.mpd` → DASH; `.ts` → null (default); `#EXTM3U` bytes → true.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*player*'`. Expected: FAIL.
- [ ] Step 3: Implement the classes. `ExternalPlayer.open(context, url, title)` uses `ACTION_VIEW` with a `video/*` data type and returns false if nothing handles it.
- [ ] Step 4: Run the same command. Expected: PASS.
- [ ] Step 5: Commit `feat(player): Media3 engine, controller, recovery`.

### Task 10: Design system, focus, image loading

**Files:**
- Create in `app/src/main/java/app/tiviate/ui/theme/`: `Tokens.kt`, `Theme.kt`, `Type.kt`.
- Create in `app/src/main/java/app/tiviate/ui/components/`: `Focusable.kt` (the focus border/glow/scale modifier), `ChannelLogo.kt` (Coil + monogram), `ProgressLine.kt`, `KeyHandling.kt` (long-press detector).
- Create `app/src/main/java/app/tiviate/ui/images/Images.kt` (ImageLoader).
- Test: `app/src/test/java/app/tiviate/ui/LogoModelTest.kt`, `LongPressTest.kt`.

**Interfaces — produces:**
- `fun logoCandidates(channel: ChannelEntity): List<String>`, in the order `logoUrl`, `logoFallback`.
- `@Composable fun ChannelLogo(channel: ChannelEntity, size: Dp)`, which falls back to `MonogramTile(name)`.
- `fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(8.dp)): Modifier`.
- `class LongPressDetector(thresholdMs: Long = 500)` with `fun onKey(event: KeyEvent): KeyAction` (`Short | Long | Repeat | None`).
- `fun buildImageLoader(context, http: OkHttpClient): ImageLoader`, with:
  - `SvgDecoder.Factory()`.
  - The OkHttp network fetcher using the User-Agent client.
  - Memory cache at 15 % of the heap, disk cache 250 MB under `cacheDir/logos`.
  - `crossfade(false)`.

- [ ] Step 1: Failing tests:
  - `logoModelFallsBackInOrder`: null or blank URLs are skipped.
  - `monogramInitials`: `"BBC One"` → `"BO"`; `"5"` → `"5"`.
  - `longPressAfterThreshold`: repeatCount > 0 and held for 500 ms or more → Long; released earlier → Short.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*ui*'`. Expected: FAIL.
- [ ] Step 3: Implement. `ChannelLogo` tries each candidate in turn with Coil's `AsyncImage` `onError` and moves to the next, ending at the monogram.
- [ ] Step 4: Run the same command. Expected: PASS.
- [ ] Step 5: Commit `feat(ui): design system and image loading`.

### Task 11: Navigation shell + Welcome / Add-playlist flow + local file browser

**Files:**
- Create in `app/src/main/java/app/tiviate/ui/`:
  - `Navigator.kt`: `sealed interface Screen { Welcome, AddPlaylist(type), Syncing(playlistId), Player, Guide, Search, Settings(section) }`, a back stack, and `Navigator.back(): Boolean`.
  - `welcome/WelcomeScreen.kt`.
  - `welcome/AddPlaylistScreen.kt`.
  - `welcome/SyncingScreen.kt`.
  - `welcome/FileBrowser.kt`: lists `Environment.getExternalStorageDirectory()` folders and `.m3u`/`.m3u8` files. It is used when `ACTION_OPEN_DOCUMENT` cannot be resolved.
- Modify `MainActivity.kt` so it hosts the Navigator and routes `onKeyDown`/`onKeyUp` to the top screen.
- Test: `app/src/androidTest/java/app/tiviate/AddPlaylistFlowTest.kt` (instrumented, MockWebServer serving fixture M3U + XMLTV + HLS).

- [ ] Step 1: Write the failing instrumented test `addM3uUrlThenAutoPlays`. It uses D-pad events to type the fixture URL (via `performTextInput` on the focused field), confirms, waits for `Screen.Player`, and asserts that `PlaybackState.isPlaying` is true within 10 s.
- [ ] Step 2: Run `./gradlew :app:connectedDebugAndroidTest` on the TV emulator. Expected: FAIL.
- [ ] Step 3: Implement the screens.
  - Type cards: **M3U link**, **Xtream login**, **Local file**.
  - Fields, in order: name, URL (or server/user/pass), EPG URL (optional), User-Agent (optional), Save.
  - Syncing shows live `SyncProgress`, then sets the active playlist and opens the Player.
  - All copy is original.
- [ ] Step 4: Run the same command. Expected: PASS.
- [ ] Step 5: Commit `feat(ui): onboarding and add playlist`.

### Task 12: Player screen — zap, banner, channel panel, categories, quick actions, number entry

**Files:**
- Create in `app/src/main/java/app/tiviate/ui/player/`: `PlayerScreen.kt`, `PlayerViewModel.kt`, `ZapBanner.kt`, `ChannelPanel.kt`, `CategoriesRail.kt`, `QuickActions.kt`, `TrackDialog.kt`, `NumberEntry.kt`, `ErrorCard.kt`.
- Test: `app/src/androidTest/java/app/tiviate/PlayerNavigationTest.kt`.

**Key map (spec §6.4):**

| Key | Action |
|---|---|
| Up / Down, CH± (`KEYCODE_CHANNEL_UP/DOWN`, `PAGE_UP/DOWN`) | Zap |
| OK | Channel panel |
| Long-OK, `MENU` | Quick actions |
| Left | Categories rail |
| Right, `GUIDE` | Guide |
| `0–9` | Number entry |
| Back | Close the top overlay, otherwise toast "Press Back again to exit" within 2 s |

The panel and banner read `nowNext` for their visible channels only. History is recorded after 5 s. `lastChannelKey` and `lastGroup` are saved on every tune.

- [ ] Step 1: Failing instrumented tests:
  - `zapDownChangesChannelAndShowsBanner`.
  - `okOpensPanelAndOkTunesFocused`.
  - `leftOpensCategoriesAndFavoritesFilters`.
  - `menuOpensQuickActionsAndToggleFavorite`.
  - `audioDialogListsTwoTracks`: the fixture HLS has 2 audio + 1 subtitle track.
  - `subtitleOffOption`.
  - `historyRecordsAfterFiveSeconds`.
  - `relaunchResumesLastChannel`.
  - `backTwiceExits`.
- [ ] Step 2: Run `./gradlew :app:connectedDebugAndroidTest`. Expected: FAIL.
- [ ] Step 3: Implement.
  - `PlayerView` sits in an `AndroidView` with `useController=false`, `keepContentOnPlayerReset=true` and SurfaceView, and `FLAG_KEEP_SCREEN_ON` is set while playing.
  - Overlays are composed only while visible.
- [ ] Step 4: Run the same command. Expected: PASS.
- [ ] Step 5: Commit `feat(ui): player screen and overlays`.

### Task 13: TV Guide

**Files:**
- Create in `app/src/main/java/app/tiviate/ui/guide/`: `GuideScreen.kt`, `GuideState.kt` (the focus state machine), `GuideGrid.kt`, `GuideHeader.kt`.
- Test: `app/src/test/java/app/tiviate/ui/guide/GuideStateTest.kt`, `app/src/androidTest/java/app/tiviate/GuideTest.kt`.

**Interfaces — produces:**
```kotlin
class GuideState(val slotMs: Long = 30 * 60_000L) {
    var row: Int; var focusTimeMs: Long; var viewStartMs: Long
    fun moveRow(delta: Int, rows: Int); fun moveTime(direction: Int, programmesInRow: List<ProgrammeEntity>, nowMs: Long)
    fun page(deltaRows: Int, rows: Int); fun jumpHours(h: Int) }
```
- Moving up or down keeps `focusTimeMs`.
- Moving left or right goes to the adjacent programme, or a 30-min empty slot where there is none, and scrolls `viewStartMs` so the focused block stays visible.
- The visible window is 2 h.
- Only the visible rows (about 8) and the blocks intersecting `[viewStartMs, viewStartMs + 2h]` are composed.
- The top 40 % holds the shared player preview and the programme details (with the programme image when one exists).

- [ ] Step 1: Failing tests:
  - Unit:
    - `upDownKeepsTime`.
    - `rightMovesToNextProgrammeAndScrolls`.
    - `gapUsesEmptySlot`.
    - `jumpHoursMovesWindow`.
    - `cannotScrollBeforeWindowStart` (now − 1 day).
  - Instrumented:
    - `guideOpensWithNowFocusedAndOkTunes`.
    - `previewDoesNotRetune`: `PlaybackState.channelKey` is unchanged after OK on the current channel.
- [ ] Step 2: Run the unit and instrumented tests. Expected: FAIL.
- [ ] Step 3: Implement.
- [ ] Step 4: Run them. Expected: PASS.
- [ ] Step 5: Commit `feat(ui): TV guide`.

### Task 14: Search

**Files:** Create `app/src/main/java/app/tiviate/ui/search/SearchScreen.kt`. Test: `app/src/androidTest/java/app/tiviate/SearchTest.kt`.

- [ ] Step 1: Write the failing test `typingFindsChannelAndProgrammeAndOkTunes`.
- [ ] Step 2: Run it. Expected: FAIL.
- [ ] Step 3: Implement.
  - Debounce for 300 ms.
  - Build the FTS query as prefix tokens (`foo* bar*`).
  - Show two rows: *Channels* and *On now & next* (programmes from now to now + 3 h).
  - OK tunes the channel.
- [ ] Step 4: Run it. Expected: PASS.
- [ ] Step 5: Commit `feat(ui): search`.

### Task 15: Settings screens

**Files:** Create `app/src/main/java/app/tiviate/ui/settings/SettingsScreen.kt`, `PlaylistsSection.kt`, `GuideSection.kt`, `PlayerSection.kt`, `InterfaceSection.kt`, `AboutSection.kt`. Test: `app/src/androidTest/java/app/tiviate/SettingsTest.kt`.

- [ ] Step 1: Failing tests:
  - `changeBufferPresetPersists`.
  - `addManualEpgSourceAndRefresh`.
  - `deletePlaylistReturnsToWelcomeWhenNoneLeft`.
  - `xtreamAccountStatusShown`.
- [ ] Step 2: Run them. Expected: FAIL.
- [ ] Step 3: Implement the two-pane layout with these sections: Playlists, Guide, Player, Interface, History (clear), About (version, licences: Apache-2.0 plus third-party list).
- [ ] Step 4: Run them. Expected: PASS.
- [ ] Step 5: Commit `feat(ui): settings`.

### Task 16: Performance, release build, docs

**Files:**
- Create `baselineprofile/` (module), `app/proguard-rules.pro`, `README.md`, `NOTICE`, and `scripts/make-fixtures.ps1` (generates the 10k-channel / 50 MB EPG perf fixtures).
- Modify `app/build.gradle.kts`: release signing reads `keystore.properties` (gitignored), with `minifyEnabled`, `shrinkResources` and the baseline profile.

- [ ] Step 1: Generate the baseline profile on the TV emulator. Run `./gradlew :app:generateReleaseBaselineProfile`. Expected: `baseline-prof.txt` is written.
- [ ] Step 2: Run a perf smoke test with the 10k/50 MB fixture.
  - `adb shell dumpsys gfxinfo app.tiviate` after guide scrolling: janky frames < 10 %.
  - `dumpsys meminfo`: TOTAL PSS < 350 MB.
  - No ANR.
- [ ] Step 3: Run `./gradlew :app:assembleRelease`. Expected: a signed APK. Install it on the emulator and complete the manual smoke checklist (spec §7).
- [ ] Step 4: Write the README (build steps; Fire TV sideload via `adb connect <ip>:5555` and `adb install`, or via the Downloader app) and the NOTICE (third-party licences).
- [ ] Step 5: Commit `chore: release build, baseline profile, docs`.

### Task 17: FFmpeg audio fallback (LGPL, audio decoders only)

**Files:** Create `scripts/build-ffmpeg-audio.sh` (WSL), `ffmpeg/` (an Android library module containing Media3's `decoder_ffmpeg` sources at tag 1.11.1, Apache-2.0, plus the built `.so` files). Modify the `Media3Engine` renderers factory and `NOTICE`.

- [ ] Step 1: In WSL, use the Linux NDK and Media3's `build_ffmpeg.sh` with FFmpeg 6.x, `--enable-decoder=mp2,mp3,ac3,eac3,dca,truehd,mlp,aac,opus,vorbis,flac,alac`, and without `--enable-gpl`. Build for armeabi-v7a, arm64-v8a, x86 and x86_64. Expected: `libffmpegJNI.so` per ABI.
- [ ] Step 2: Write the failing instrumented test `mp2AudioPlaysViaFfmpeg`: an MPEG-TS fixture with MP2 audio produces an audio renderer `decoderName` that contains "ffmpeg", or plays successfully on the emulator.
- [ ] Step 3: Wire it in with `DefaultRenderersFactory.setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)` (the extension is used only when no platform decoder exists). Split APKs per ABI.
- [ ] Step 4: Run the test. Expected: PASS. Then update NOTICE with the FFmpeg LGPL-2.1 notice and a source offer.
- [ ] Step 5: Commit `feat(player): LGPL FFmpeg audio fallback`.
