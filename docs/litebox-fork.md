# Argosy for LiteBox — what this fork changes and why

This fork of [Argosy Launcher](https://github.com/nendotools/argosy-launcher) talks to
**LiteBox**, a LaunchBox plugin that serves a LaunchBox library over the RomM API (it answers
as RomM 5.2.0). Everything upstream Argosy does against a real RomM server still works
unchanged; this document lists what the fork adds on top, where it lives in the code, and
how to keep rebasing it onto upstream.

The one rule behind every change: **a stock RomM server must not notice the fork exists.**
Every LiteBox-specific behaviour is gated twice — the client announces itself with an HTTP
header, and it only *uses* an extension after the server has announced the matching
capability. A RomM server never sees a new endpoint called, and the fork never reads a field a
RomM server does not send.

---

## 1. Branch layout

| Ref | Content |
|-----|---------|
| `main` | Upstream `nendotools/argosy-launcher` (last synced at `25ccb408`, 2.13.0) plus three save-sync fixes (§3.1) that are not LiteBox-specific but were found while testing against it. |
| `litebox-version-switch` | The LiteBox features, one commit per feature, in dependency order (§2). |
| `backup/litebox-version-switch-20260907` | Tag and branch: the original 14-commit history before it was regrouped. Same final tree, kept so nothing is lost. |

### Commits on `litebox-version-switch`, in order

1. `ci` — the build workflow can be run by hand on any branch, and runs on push to this branch (temporary; see §6).
2. `settings` — the stale auto-state guard (`protectAgainstStaleResume`, from `8ebbf180` on `main`) gets a switch in Settings › Saves.
3. `romm` — the LiteBox handshake: `X-LiteBox-Client` header and the capabilities probe.
4. `gamedetail` — the version/rom picker.
5. `romm` — one visible row per LaunchBox game across version switches (`liteboxGameId`, migration 184).
6. `gamedetail` — the ROM file name under the cover.
7. `settings` — RetroAchievements login handed over by the LiteBox desktop.
8. `gamedetail` — the library's own Progress vocabulary replaces RomM's five statuses (migration 185).

Each commit compiles on its own conceptually (later features are removed from shared files, not
just disabled), but only the final tree has been built and run. If you need to bisect, expect
to rebuild.

---

## 2. The handshake (commit 3)

**Client → server.** `RomMApiFactory` adds `X-LiteBox-Client: argosy/<version>` to **every**
request through an OkHttp interceptor. The header is stateless on purpose: it holds for
password and token auth alike and survives a re-pair. A RomM server ignores unknown headers.

**Server → client.** `LiteBoxService.capabilities()` does one `GET /api/litebox/capabilities`
per connection (keyed on the `RomMApi` instance, so a re-pair to another server re-probes). A
LiteBox answers `{"liteBox": true, "features": [...]}`; a RomM server answers 404/501, which is
read as "not a LiteBox", never as an error. **Only a real HTTP answer is cached** — a network
failure is not, otherwise the first game page opened offline would hide the features for the
rest of the process (measured, that is what the first version did).

Feature flags the server can announce, and what reads them:

| Feature | Read by | Enables |
|---------|---------|---------|
| `version-switch` | `LiteBoxService.supportsVersionSwitch()` | the version picker, the versions count on the game page, the one-row-per-game logic |
| `ra-credentials` | `LiteBoxService.supportsRaCredentials()` | the "Get login from LiteBox" button (announced only when the desktop actually holds a RetroAchievements login) |
| `litebox-progress` | `LiteBoxService.supportsProgress()` | the Progress vocabulary in the status picker |

Files: `data/remote/romm/RomMApiFactory.kt`, `LiteBoxService.kt`, `LiteBoxModels.kt`,
the `/api/litebox/...` block at the end of `RomMApi.kt`, and the `liteBox*` forwarders at the end
of `RomMRepository.kt`.

Server side (repository `LbApiHost`): `Host/Romm/RommLiteBoxApi.cs` (`IsLiteBoxClient`,
`Capabilities`), routes in `Host/Romm/RommServer.cs`.

---

## 3. Features

### 3.1 Save-sync fixes already on `main`

Three commits found while running Argosy against LiteBox's save sync; none is LiteBox-specific.

- `c29351e0` **autosave is a real channel** — `isLatestSaveFileName` treats a slot named
  `autosave` (or the ROM's own base name) as "the latest, no channel". Two places assumed the
  opposite: the Autosave row never showed content that existed on the server, and a fresh
  autosave download was buried as archival so a new pairing launched another named channel
  instead of the newest save. `resolveActiveEntry` no longer falls back to "newest overall".
- `cc793664` **a session captures its channel from the registry** — selecting a channel with
  nothing cached yet cleared the active row and the session locked in `channelName = null` for
  its whole lifetime; what it saved went to the upload's null fallback, never the chosen channel.
- `8ebbf180` **stale resume protection** — the built-in core's own `AUTO_SLOT`/`RESUME_SLOT`
  could resume from a moment the SRAM save no longer agreed with after a server pull, a history
  restore or a channel switch. Guarded by mtime comparison at launch and by deleting the
  auto/resume states whenever a download or restore actually changes the save's content hash.
  Preference `protectAgainstStaleResume` (default on), built-in core only.

Commit 2 of the branch adds the missing switch for that preference in Settings › Saves
(`SavesSection.kt`, `SyncSettingsDelegate.kt`, key classified in `SettingsBackupKeysTest`), and
brings the unit tests up to date with the constructors `8ebbf180` changed.

### 3.2 Version picker (commit 4)

A LaunchBox game can have several files: its own ROM plus *Additional Applications* (other
regions, revisions, hacks), and any of them can be an archive the LiteBox extractor takes apart.
LiteBox serves **one rom_id per game per client** and lets a paired client lock itself onto one
file. This is the desktop's own "Assignment" screen, exposed to the phone.

**Where.** A `Versions` row at the **bottom of the left menu** on the game page
(`GameDetailMenu.kt`, `MenuItem.VersionSwitch`, last in `ALL`), shown only when the server
announces `version-switch` and the game has something to choose. The count next to it is the
number of versions, or the number of roms when the only version is an eligible archive. It opens
a full page (`ui/screens/versionpicker/`, route `game/{gameId}/versions`) — a game can offer
thousands of entries, so this is a real destination with its own `LazyColumn` and
`InputHandler`, not a modal.

**Eligibility = the server's rom_id rule.** The versions list is what the LiteBox index pass
would give a rom_id to (`RommFiles.CandidatesOf`): the game's own file plus each Additional
Application marked *Use Emulator*. A version is *eligible* (its roms are chosen one by one on a
second screen) exactly when the pass marks it `IsExtract`: extractor module on, archive
extension, platform **not** zip-native (the arcade family — MAME, FBNeo, Neo Geo, CPS, Naomi,
Model 2/3… — is always served whole), extraction enabled for the game's effective emulator on
that platform, and a RomConfig mode other than *DoNothing*. The client never guesses from an
extension: `LiteBoxVersion.eligible` is the server's verdict.

**Two screens.**
1. *Versions.* A tap on a version served whole pins it. A tap on an eligible version drills into
   its roms. Each row: label (file name for the main file, the Additional Application's name
   otherwise), file name and size, a check on the version this device is currently served, the
   rom count on eligible archives, a "downloaded" mark when the rom_id has a local file.
2. *Roms inside one archive.* Header shows the archive file name. Per rom: last played,
   favourite, RetroAchievements match, tag score (the desktop picker's own columns, same
   ranking), "downloaded" mark, check on the current one. A tap pins the rom.

A game with a single version that is eligible opens directly on screen 2; B then leaves the
picker. Otherwise B goes back one screen at a time and leaves from screen 1 — never pinning
anything on the way out.

**Pinning.** `POST /api/litebox/roms/{id}/pin` with `{appId, path}` (or `{unpin: true}`). The
server refuses a rom-less pin on an eligible archive and a rom on a version served whole. The
answer carries the **new rom_id** this client is now served; the picker re-syncs the platform
and navigates to the new local game row (the old one is a different rom_id and disappears —
see §3.3), replacing the game page in the back stack.

Wire: `GET /api/litebox/roms/{id}/versions` → `[LiteBoxVersion]`;
`GET /api/litebox/roms/{id}/versions/{appId|main}/roms` → `LiteBoxRomsInVersion`
(`appId`, `versionLabel`, `archiveFileName`, `roms: [LiteBoxRomEntry]`). `main` names the game's
own file because a path segment cannot be empty.

Strings: `strings_version_picker.xml` (all locales), `gamedetail_menu_version_switch*`,
`gamedetail_footer_version_switch` in `strings_gamedetail.xml`.

Server: `RommLiteBoxApi.ListVersions / ListRomsInVersion / Pin`, `RommIndexer.PinClient /
UnpinClient / ValidRowsOf`.

Not done: there is no "follow the server's default again" row in the picker (the `unpin` call
exists in `RomMRepository.liteBoxUnpinVersion` but nothing drives it). The desktop's Assignment
tab remains the way to release a pin.

### 3.3 One visible row per LaunchBox game (commit 5)

Switching version changes the rom_id this client is served, so the platform sync creates a new
row and — because LiteBox answers **501 to `/api/permissions/me`** — never proves the old one
deleted: upstream keeps such orphans as a "visibility mask". Result before this commit: three
*Yoshi's Island* rows after two switches.

**Model.** The server sends LiteBox clients `litebox_game_id`, the LaunchBox game GUID, the same
for every version/rom row of one game (`RomMRom.liteboxGameId`, stored as
`games.liteboxGameId`). A local row whose GUID is served under **another** rom_id is a version the
user switched away from: it gets `games.liteboxSupersededBy = <that rommId>` and every visibility
predicate in `GameDao` (all 55 of them) filters it out with `AND games.liteboxSupersededBy IS
NULL`. It is *not* put in `user_roms_hidden` — that table is the user's own hide choice and syncs
back to the server. The row keeps its downloaded file and caches; the moment the server serves it
again the flag is cleared.

**Where it is decided.**
- `RomMLibrarySyncService.reconcileOrphans` (and the preserve branches): `supersedingLiveSibling`
  runs **before** the visibility gate, since on LiteBox that gate never opens.
- `adoptLegacyGhosts(platformId)` (called from `processPostPlatformSync`): rows synced before the
  GUID existed (negative synthetic rommId, no key) are matched to a served keyed row by title and
  file name and claimed.
- `hideSiblingVersions(gameId)` at **game-page open** (`GameDetailViewModel.
  refreshLiteBoxVersionsInBackground` → `RomMRepository.liteBoxHideSiblingVersions`): the game
  being shown is by construction the served version, so every other local row with the same GUID
  — or no GUID but same platform and same title — is hidden behind it. Learns the GUID from
  `GET /api/roms/{id}` when the row lacks it.

Migration `183 → 184` (`Migrations.kt`, `MigrationRegistry.kt`, schema `184.json`).

### 3.4 ROM file name under the cover (commit 6)

`GameDetailUi.fileName` = the server's file name for a RomM/LiteBox game, else the local file's
own name, null for an Android app or Steam game. Rendered under `ExpandedHeader`, above the
description. It is what tells two versions of the same game apart once switching is possible.

### 3.5 RetroAchievements login from the LiteBox desktop (commit 7)

LiteBox keeps the RetroAchievements username and **connect token** for RetroArch
(`RetroAchievementsToken` — the `cheevos_token`), which is exactly the pair Argosy stores after
its own `login2` call. So the desktop can hand its login to a paired device — with a human
approving, since it is a different secret with a different owner.

**Flow** (shaped like the device pairing, RFC 8628 words):
1. Settings › RetroAchievements › login form shows a **Get login from LiteBox** row (only when
   the server announces `ra-credentials`; it sits above *Login*, so *Login* and *Cancel* shift
   by one — `SettingsConfirmRouter` mirrors this).
2. `POST /api/litebox/ra/credentials/request` → `{request_id, expires_in, interval}`; a sticky
   card *Share / Deny* goes up on the desktop naming the device.
3. The app polls `GET /api/litebox/ra/credentials/{request_id}` every `interval` seconds for at
   most `expires_in` (5 min): 400 with `detail` = `authorization_pending` / `access_denied` /
   `expired_token`, or **once** 200 `{username, token}`.
4. `RetroAchievementsRepository.adoptCredentials` stores them exactly as `login()` would
   (preferences + achievement-fetch timestamps cleared).

No password ever travels — the server has none. The 200 answer is redacted from LiteBox's
request log. Only the requesting device's token can collect its own request.

Files: `RASettingsSection.kt`, `RASettingsDelegate.syncFromLiteBox`, `SettingsModels.kt`
(`liteBoxSyncAvailable`, `liteBoxSyncStatus`), strings `settings_ra_login_litebox_*`.
Server: `Host/Romm/RommLiteBoxRaApi.cs`, `RommTrace.RedactResponse`.

### 3.6 The library's own Progress vocabulary (commit 8)

RomM knows five statuses (`incomplete`, `finished`, `completed_100`, `retired`,
`never_playing`) plus two flags. LaunchBox's *Progress* is a free, user-organised list
("Not Started / Unplayed", "Active / Continuous", "Done / Mastered"…) that RomM's vocabulary
cannot carry. On a LiteBox server the picker works on the real list.

- The rom DTO (and the `PUT /api/roms/{id}/user` answer) carries `litebox_progress`, the exact
  LaunchBox Progress entry. Stored as `games.liteboxProgress` (migration `184 → 185`).
- `GET /api/litebox/progress/values` → `[{value, category, label}]`, the library's
  `ProgressPriorities` in the user's own order and spelling. Cached per connection
  (`LiteBoxService.progressValues()`).
- `StatusPickerModal` lists those entries instead of `CompletionStatus` when the list is
  non-empty (`RatingsStatusDelegate.showStatusPicker(currentStatus, liteBoxProgress, values)`).
- The choice is written to `games.liteboxProgress` at once and queued as
  `SyncType.LITEBOX_PROGRESS` (`RomMUserPropertyService.updateLiteBoxProgress`), sent as
  `litebox_progress` in `RomMUserPropsUpdateData`. The server writes it to `IGame.Progress`
  verbatim and **derives** the standard `status`/`backlogged`/`now_playing`/`completion` from it
  so stock clients stay consistent; a value outside the list is refused with 400.
- `StatusChip` shows the entry's value half ("Beaten") when present, borrowing icon and colour
  from the derived RomM status.

Known lag: the derived RomM status comes back on the next `refreshUserProps`, not instantly —
the sync queue writes first, the page re-reads later. Meanwhile the chip already shows the text
the user picked.

Server: `RommUserApi.UpdateRomUser` (`litebox_progress`, `ResolveProgressEntry`), `RomUserDto`
(now a dictionary so the field can be added for LiteBox clients only),
`RommLiteBoxApi.ProgressValues`. This commit also removed the old server-side gesture that
released a client's version pin when a game was marked retired/never_playing — the picker is the
explicit gesture now.

---

## 4. Server counterpart (LbApiHost, `Host/Romm/`)

| Client piece | Server piece |
|--------------|--------------|
| `X-LiteBox-Client`, capabilities | `RommLiteBoxApi.IsLiteBoxClient`, `Capabilities`, `Features()` |
| `litebox_game_id`, `litebox_progress` on the rom | `RommLibraryApi.RomDto` (`if (liteBox)` block) |
| versions / roms / pin | `RommLiteBoxApi.ListVersions`, `ListRomsInVersion`, `Pin`, `VersionsOf` (built on `RommFiles.CandidatesOf`) |
| RA hand-over | `RommLiteBoxRaApi.Request`, `Poll`; desktop card via `NotificationCenter.Input` |
| Progress values, `litebox_progress` write | `RommLiteBoxApi.ProgressValues`, `RommUserApi.UpdateRomUser` |
| request log redaction | `RommTrace.RedactResponse` |

All `/api/litebox/*` routes answer a plain 404 without the header, before authentication —
indistinguishable from an unknown path on a stock RomM.

---

## 5. Rebasing onto upstream

```
git remote add upstream https://github.com/nendotools/argosy-launcher.git   # once
git fetch upstream
git checkout main && git rebase upstream/main          # the three save-sync fixes
git checkout litebox-version-switch && git rebase main # the eight feature commits
```

Where conflicts will land, and what to do:

- **`GameDao.kt`** — commit 5 adds `AND games.liteboxSupersededBy IS NULL` to every visibility
  predicate. Any query upstream adds or rewrites needs the same clause, or superseded versions
  reappear in that one list. Grep for `user_roms_hidden` to find them all.
- **Room migrations** — `Migration_183_184` and `Migration_184_185`, `MigrationRegistry.ALL`,
  `ALauncherDatabase.version`, and `app/schemas/.../184.json`, `185.json`. When upstream adds its
  own migrations, renumber ours after theirs and regenerate the schema JSON (a debug build writes
  it; `exportSchema = true`).
- **`SyncType`** — `LITEBOX_PROGRESS` is stored by name; keep it, and keep the
  `when (item.syncType)` in `SyncCoordinator.processItem` exhaustive.
- **`GameDetailMenu.kt`** — `MenuItem.VersionSwitch` must stay **last** in `ALL`;
  `menuLayoutState()` carries `hasLiteBoxVersions`.
- **`RASettingsSection.kt` / `SettingsConfirmRouter.kt`** — the login-form row indices shift by
  one when the LiteBox row is shown; both sides must agree.
- **Strings** — every new key exists in all 8 locales (`values`, `-fr`, `-de`, `-es`, `-ru`,
  `-hi`, `-b+zh+Hans`, `-b+zh+Hant`); lint `MissingTranslation` is an error in this project.
  Apostrophes are escaped `\'`.
- **`RomMApiFactory.kt`** — the interceptor adding the header; if upstream restructures the
  OkHttp client, re-attach it.

The LiteBox-only files (`LiteBoxService.kt`, `LiteBoxModels.kt`, `ui/screens/versionpicker/`,
`strings_version_picker.xml`) never conflict; the shared ones above do.

---

## 6. Building

- CI: `.github/workflows/build.yml` gained `workflow_dispatch` and a push trigger on
  `litebox-version-switch`. The push trigger is the only way to get CI on this fork without
  opening a pull request against the upstream repository; drop it from `push.branches` once the
  branch is merged into `main`.
- Local: `./gradlew assembleDebug -PallAbis` → `app/build/outputs/apk/debug/app-universal-debug.apk`.
  `:app` is one module of ~340k lines; a cold `compileDebugKotlin` is about an hour, an
  incremental one a minute or two. Do not change Gradle log level or compiler flags between two
  runs you want incremental (they are task inputs for KSP).
- The Room schema JSON for a new database version is written by the debug build; commit it.
