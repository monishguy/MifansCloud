# XiaomiAlbumSyncer — Technical Map of the Xiaomi Cloud (i.mi.com) Integration

**Repository:** https://github.com/Coooolfan/XiaomiAlbumSyncer
**Branch analyzed:** `main` (tree SHA `239431854ef5c815405bb28e73373f16622ffac4`, retrieved 2026-08-16)
**Project type:** Self-hosted downloader (album photos/videos + voice recordings) from Xiaomi Cloud. Kotlin/JVM server (Solon framework) + Vue 3 web UI + SQLite metadata DB. Download-only: **no upload path exists**.

All source citations below use repo-relative paths against the `main` branch; every file was read from
`https://raw.githubusercontent.com/Coooolfan/XiaomiAlbumSyncer/main/<path>` (blob view: `https://github.com/Coooolfan/XiaomiAlbumSyncer/blob/main/<path>`).

---

## 0. Architecture overview

| Layer | Technology | Evidence |
|---|---|---|
| Backend | Kotlin (JVM 25 / GraalVM native-image), **Solon** framework, OkHttp 5 HTTP client, Jackson, Jimmer ORM, SQLite (sqlite-jdbc + Hikari), Flyway migrations, Sa-Token auth, webauthn4j, kotlinx-coroutines | `server/build.gradle.kts`; `server/README/DEPENDENCIES.md`; `DEVELOPER_GUIDE.md` §2 |
| Frontend | Vue 3, Vite, PrimeVue, Tailwind CSS, Pinia, Vue Router, TypeScript | `web/package.json`; `web/README/DEPENDENCIES.md`; `DEVELOPER_GUIDE.md` §2 |
| Xiaomi API client | `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/xiaomicloud/XiaoMiApi.kt` (endpoints, parsing, download) and `.../xiaomicloud/TokenManager.kt` (serviceToken exchange/cache) | — |
| Config | `server/src/main/resources/app.yml`: `xiaomi.api.base-url: ${XIAOMI_API_BASE_URL:https://i.mi.com}`; `.../config/XiaomiApiProperties.kt` resolves relative paths against the base URL | — |
| E2E ground truth | A stateful Go mock of the Xiaomi API, `xas-mock/internal/mock/server.go`, whose routes exactly mirror the endpoints the client calls; the E2E suite asserts route counts | — |

The single base URL for **every** Xiaomi call is `https://i.mi.com` (override with env `XIAOMI_API_BASE_URL`; used by tests to point at the mock). There is **no separate passport/account.xiaomi.com host used anywhere in the code**.

---

## 1. Auth & login flow

### 1.1 What the app itself does — and does not — authenticate

- **No username/password, no 2FA/OTP, no captcha handling exists in the app.** The app never talks to Xiaomi's passport/account login endpoints. The maintainer explicitly closed a feature request for phone + password + SMS-code login as *not planned*, citing the need to replicate the full login chain including human-verification (人机验证/captcha) and stability/success-rate concerns (issue [#49](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/49), owner comment 2026-06-28).
- Credentials are **captured out-of-band by the user** in a real browser session and pasted into the app:
  1. Log in at https://i.mi.com/
  2. Open the gallery page https://i.mi.com/gallery/h5#/ **once** — this is what triggers Xiaomi's device verification (README: "如果出现手机验证，勾选 `信任此设备`" — if phone verification appears, check "trust this device").
  3. Open browser DevTools → Application → Cookies and copy the `passToken` and `userId` cookie values.
  (Source: `README.md` §「获取 PassToken 与 UserId」; screenshot `static/copybydevtool.avif`.)
- The captured `passToken` + `userId` are stored in the SQLite `XiaomiAccount` table (`server/.../model/XiaomiAccount.kt`: `nickname`, `passToken`, `userId`) via the web API (`POST /api/account`, `XiaomiAccountController.kt`).

### 1.2 serviceToken exchange (the only in-app login step)

`TokenManager.kt` (`genServiceToken`, lines 69–122) performs a 3-step, no-redirect-following exchange. `userId` is **not derived** — it is taken verbatim from the stored account.

1. **Generate a fresh device id:** `deviceId = "wb_" + UUID.randomUUID()` (`TokenManager.kt:71`). The `wb_` prefix marks a web device; the mock enforces it (`xas-mock/internal/mock/server.go:60`).
2. **Pre-login:** `GET {base}/api/user/login?ts=<epochMillis>&followUp=<urlencoded baseUrl>&_locale=zh_CN` with header `Cookie: userId=<userId>; deviceId=<deviceId>; passToken=<passToken>` → JSON `data.loginUrl` (`TokenManager.kt:74–83`).
3. **Follow the loginUrl manually:** `GET <loginUrl>` with the same three cookies. The OkHttp client has `followRedirects(false)` and `followSslRedirects(false)` (`utils/okHttpHelper.kt:30–31`), so the response is a 3xx whose **`Location`** header is read directly (`TokenManager.kt:86–101`; error `"no Location header"`).
4. **Token issuance:** `GET <Location>` with the same three cookies → collect all `Set-Cookie` response headers and extract the first that starts with `serviceToken=`; take the value up to the first `;` (`TokenManager.kt:104–120`; error `"no serviceToken from remote"`).

The mock implements this exact contract (`server.go:58–91`): `/api/user/login` validates cookies and returns `{data:{loginUrl}}`; `/mock/login` returns HTTP 302 with `Location`; `/mock/token` sets `Set-Cookie: serviceToken=...`.

### 1.3 Token storage & refresh

- **Storage:** `serviceToken` is held **only in memory** in `tokenCache: ConcurrentHashMap<Long, CachedToken>` where `CachedToken(serviceToken, userId, lastFreshenTime)` (`TokenManager.kt:26–32`). Nothing is written to disk except the user-supplied `passToken`/`userId` in SQLite.
- **Refresh policy:** `needRefresh()` forces a regeneration when the cached token is older than **10 minutes** (`TokenManager.kt:64–67`; comment: "serviceToken 的过期时间非常短，10 分钟强制刷新" — the serviceToken expires very quickly, force-refresh every 10 min). Refresh re-runs the whole 3-step exchange with the same stored `passToken`/`userId` and a **new random deviceId**.
- **Invalidation triggers:** `invalidateToken(accountId)` clears the cache entry; called from `XiaomiAccountService.update()` and `delete()` (`XiaomiAccountService.kt:52, 61`). A refresh failure (e.g. `cookiesSize: 0` observed in issue [#54](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/54)) surfaces as an exception that fails the sync run — there is **no background re-login or backoff**.
- **Per-request auth header:** every API call attaches `Cookie: userId=<userId>; serviceToken=<serviceToken>;` via `Request.Builder.authHeader(pair)` (`utils/okHttpHelper.kt:18–23`).

---

## 2. API surface (exact endpoint strings)

All requests are `GET` (one exception: the final download is `POST`), all carry `Cookie: userId=...; serviceToken=...`, plus the desktop UA below. The single retry helper `executeWithRetry` re-issues **once** on `SocketTimeoutException` only (`okHttpHelper.kt:52–59`).

```kotlin
const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36 Edg/139.0.0.0"
```
(`okHttpHelper.kt:13–14`)

### 2.1 Auth (login chain) — `TokenManager.kt`

| Method | Path + query | Cookies sent | Response shape used |
|---|---|---|---|
| GET | `/api/user/login?ts=<ms>&followUp=<urlencoded base>&_locale=zh_CN` | `userId; deviceId; passToken` | `data.loginUrl` |
| GET | `<loginUrl>` (opaque, server-provided) | `userId; deviceId; passToken` | `Location` header (302) |
| GET | `<Location>` (opaque, server-provided) | `userId; deviceId; passToken` | `Set-Cookie: serviceToken=...` |

### 2.2 Gallery / album (photos & videos) — `XiaoMiApi.kt`

| Method | Path + query (exact strings) | Notes |
|---|---|---|
| GET | `gallery/user/album/list?ts=<ms>&pageNum=<n>&pageSize=10&isShared=false&numOfThumbnails=1` | Album list, paged (pageNum 0-based, pageSize fixed 10). Response: `data.albums[]` items `{albumId, name, mediaCount, lastUpdateTime}`; `data.isLastPage` (lines 38–48). Special IDs: `1000` = private album (私密相册, **skipped**), `1` = 相机/Camera, `2` = 屏幕截图/Screenshots (lines 56–59). |
| GET | `gallery/user/galleries?ts=<ms>&pageNum=<n>&pageSize=200&albumId=<remoteId>` | Asset list for one album, pageSize 200. Optional date filter appended: `&startDate=yyyyMMdd&endDate=yyyyMMdd` (lines 97–98, 105). Response: `data.galleries[]` `{id, fileName, type, dateTaken, sha1, mimeType, title, size}`; `data.isLastPage` (lines 119–123, 229–245). |
| GET | `gallery/user/timeline?ts=<ms>&albumId=<remoteId>` | Album timeline: `data.indexHash` (string) + `data.dayCount` (map `yyyyMMdd` → count) (lines 158–176). |

### 2.3 Recordings (录音) — `XiaoMiApi.kt`

| Method | Path + query | Notes |
|---|---|---|
| GET | `sfs/ns/recorder/dir/0/list?ts=<ms>&limit=500&offset=<n>` | Recording list, pageSize 500, offset pagination (0-based). Response: `data.list[]` `{id, name, create_time, sha1, size}` (lines 102–103, 119–121, 247–262). **No `isLastPage` field** — client paginates while a full page is returned (`shouldFetchNextAssetPage`: audio → `assetCount == pageSize`; gallery → `assetCount > 0 && !isLastPage`, lines 267–274). |

Recordings are surfaced in the app as a synthetic album with `remoteId = -1`, name 录音 (`fetchAllAlbums`, lines 76–82; `Album.isAudioAlbum()` checks `remoteId == -1L`, `utils/extendFunctions.kt:20–22`). The recording **name** carries a type suffix parsed by `XIAOMI_RECORDING_NAME_REGEX = ^(.+)\.([^._]+)_(\d+)_(\d+)_(\d+)_(\d+)$` (XiaoMiApi.kt:276–298): the last group is the `RecordingType` code — `0`=录音机录音 RECORDER, `1`=通话录音 PHONE_CALL, `2`=FM录音 FM, `3`=应用录音 APP, `-1` UNKNOWN (`model/RecordingType.kt`).

### 2.4 Download URL resolution — `XiaoMiApi.kt:178–227`

| Step | Request | Response used |
|---|---|---|
| 1a (gallery) | GET `gallery/storage?ts=<ms>&id=<assetId>` | `{code:0, data:{url:<ossUrl>}}`; **`code 50050` = media deleted → skip file** (lines 183, 199–202) |
| 1b (recording) | GET `sfs/ns/recorder/file/<assetId>/cb/dl_sfs_cb_<ms>_0/storage?ts=<ms>` | same shape, `data.url` (line 181) |
| 2 | GET `<ossUrl>` (no auth cookies, UA only) | **JSONP** body `dl_callback({...})`; parsed by `readJsonpTree`, which skips bytes until `(` then parses JSON → `{url: <downloadUrl>, meta: <meta>}` (lines 207–211; `okHttpHelper.kt:40–49`) |
| 3 | **POST** `<downloadUrl>` with form body `meta=<meta>` (UA only) | raw binary stream saved to disk (lines 214–223) |

### 2.5 Anything else (contacts / notes / SMS)

**Not implemented.** The only remote data types touched are gallery (images/videos) and recorder (audio). No contacts, notes, SMS, or other Xiaomi Cloud APIs are referenced anywhere in `server/src`.

---

## 3. File download / upload mechanics

### 3.1 Download flow
1. Resolve a signed/real URL: `id → /gallery/storage` (or recorder `/sfs/ns/recorder/file/{id}/cb/{cb}_0/storage`) → `data.url` → JSONP `dl_callback` → `{url, meta}`.
2. POST to `url` with `meta` form field → stream the body to disk with an 8192-byte buffer (`ResponseBody.saveToFile`, `okHttpHelper.kt:67–75`).
3. **Temp-file + atomic rename:** the file is first written to `"<targetFileName>.<detailId>.tmp"` in the same directory, then moved to the final path with `ATOMIC_MOVE` (fallback `REPLACE_EXISTING` for non-atomic filesystems); the temp file is deleted on any failure (`pipeline/stages/DownloadStage.kt:36–63, 85–92`).
4. Target path (default): `<targetPath>/<album name>/<fileName>`; recordings get an id prefix `<assetId>_<fileName>` to avoid name collisions (`model/CrontabHistoryDetail.kt:121–131`). Overridable via the `${...}`-expression `expressionTargetPath` template (`README/expression-target-path.md`).

### 3.2 Resumable / range downloads
**Not implemented.** No `Range`/`Content-Range` headers anywhere; a failed download restarts from zero (temp file deleted). The only resilience is the single timeout retry (`executeWithRetry`). There is no explicit rate limiting or throttling code (the developer guide explicitly notes Xiaomi's API may rate-limit and that the code has no explicit limiting logic — `DEVELOPER_GUIDE.md` §7).

### 3.3 Upload
**Not implemented.** The project is strictly download (backup) oriented; no upload session, chunking, or callback logic exists.

---

## 4. Incremental sync logic

Sync runs per scheduled task (`Crontab` → `CrontabPipeline.execute`, `pipeline/CrontabPipeline.kt`). Pipeline order: metadata refresh → download → (optional) SHA-1 verify → (optional) EXIF fill → (optional) FS-time rewrite → notification.

### 4.1 What is "new": two metadata refresh modes
- **Timeline-diff mode (`diffByTimeline=true`)** — `AssetService.refreshAssetsByDiffTimeline` (`AssetService.kt:44–108`):
  1. Fetch each album's current `AlbumTimeline` (`indexHash` + `dayCount: Map<LocalDate,Long>`) from `/gallery/user/timeline`; persist it into the new `CrontabHistory.timelineSnapshot` (serialized map, `model/CrontabHistory.kt:23`).
  2. Compare with the snapshot of the **previous** completed run (`CrontabService.getAlbumTimelinesHistory` — most recent history with `endTime != null`, `CrontabService.kt:151–159`).
  3. `AlbumTimeline.minus` (`model/AlbumTimeline.kt:22–28`): if `indexHash` is unchanged → empty diff; otherwise compute per-date count deltas and keep only dates with a non-zero delta.
  4. For each changed date, fetch only that day: `gallery/user/galleries?...&startDate=yyyyMMdd&endDate=yyyyMMdd`, concurrently (semaphore 10, `runBlocking(Dispatchers.IO)`), UPSERTing assets.
- **Full-refresh mode** — `AssetService.refreshAssetsFull` (`AssetService.kt:110–139`): refetch every page of every album (semaphore 5), update `Album.assetCount`, then persist the fresh timeline snapshot.

Timeline-diff is only used when all hold (`CrontabPipeline.checkTimelineDiffUsable`, lines 173–189): flag enabled; a previous snapshot exists; the crontab does **not** include the 录音 (`-1`) album (recordings have no timeline); and the album set (`remoteId`s) is unchanged. Otherwise it silently falls back to full refresh.

### 4.2 What is "changed"/needs download: per-asset completion records
`AssetService.getAssetsUndownloadByCrontab` (`AssetService.kt:148–177`) selects assets of the crontab's albums, keyset-paginated (`id > lastId`, batch `fetchFromDbSize` default 2), **excluding** any asset that already has a `CrontabHistoryDetail` for this crontab with all four flags true:
`downloadCompleted && sha1Verified && exifFilled && fsTimeUpdated`.
Type filters apply (`downloadImages/downloadVideos/downloadAudios`).

- Assets are upserted by `id` into the local `Asset` table (`SaveMode.UPSERT`), so the **remote `sha1`, `size`, `dateTaken` become the source of truth** for de-dup.
- Optional **SHA-1 verification**: `VerificationStage` hashes the downloaded file and compares to `asset.sha1` (case-insensitive) — mismatch throws and skips subsequent stages (`pipeline/stages/VerificationStage.kt:25–46`).
- Optional **skip-existing-file**: if `skipExistingFile` and the final target path already exists on disk, download is skipped (`DownloadStage.kt:45–46`).
- Album list refresh: `AlbumsService.refreshAlbumsByAccount` upserts remote albums by `(remoteId, accountId)` and marks every local album not seen remotely with `shadow = true` (deleted-on-cloud detection) (`AlbumsService.kt:35–73`; `model/Album.kt` `shadow` field).
- Re-run guard: `TaskScheduler.executeWithGuard` refuses to start a second run of the same crontab while one is running (`config/TaskScheduler.kt:119–131`).

---

## 5. Cookie handling details

- **There is no cookie file.** `passToken`/`userId` persist in SQLite (`XiaomiAccount`); `serviceToken` exists only in the in-memory `tokenCache`.
- **Expiry:** serviceToken is force-refreshed every 10 minutes regardless of server hints; the server's actual lifetime is undocumented ("疑似长期有效" per maintainer, but users report ~1 h expirations — issue [#54](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/54)).
- **Re-login triggers:** (a) cache age > 10 min; (b) `invalidateToken()` on account update/delete; (c) any run that calls `getAuthPair` when no/expired cache. Failure of the exchange (no `Location` header, no `serviceToken` Set-Cookie — observed as `cookiesSize: 0` in issue #54) aborts that sync run; there is **no automatic retry loop, no re-login notification, and no backoff**.
- Browser-side gotcha (maintainer's advice, issue #54): after copying the cookies, **do not log out of i.mi.com** in the browser, or the tokens stop working.

---

## 6. Extra features (scheduler, Web UI, notifications) & implementation

### 6.1 Scheduling
- Solon scheduling (`solon-scheduling-simple`) + `IJobManager`. `TaskScheduler.initJobs()` (called at startup `@Init` and after every crontab create/update/delete) registers each enabled crontab as `Scheduled(cron = config.expression, zone = config.timeZone)` with job id `"<crontabId>:<crontabName>"` (`config/TaskScheduler.kt:44–101`). Invalid cron expressions are logged and skipped.
- A second cron drives the daily-summary notification (`daily-summary-notify`).
- Manual triggers: `POST /api/crontab/{id}/executions`; single-stage patches: `POST /api/crontab/{id}/fill-exif/executions`, `POST /api/crontab/{id}/rewrite-fs-time/executions` (`CrontabController.kt`).
- Concurrency defaults in `model/CrontabConfig.kt`: `downloaders=8`, `verifiers=2`, `exifProcessors=2`, `fileTimeWorkers=2`, `fetchFromDbSize=2`; stages are pipelined with kotlinx `channelFlow` + `flatMapMerge(concurrency)` (`CrontabPipeline.kt:71–147`).

### 6.2 Web UI / management API
- Vue 3 + Vite + PrimeVue + Tailwind + Pinia + Vue Router + TypeScript under `web/`; served by the Solon backend (single binary deployment; Docker images `coolfan1024/xiaomi-album-syncer`).
- Auth for the app itself: Sa-Token session login by password (`GET /api/token?password=...`, `DELETE /api/token`) **or** WebAuthn passkey (webauthn4j): `/api/passkey/available`, `/register/start|finish`, `/authenticate/start|finish`, `/`, `DELETE /{credentialId}`, `POST /{credentialId}/name` (`controller/PasskeyController.kt`; config `webauthn.rpId`/`rpName` in `app.yml`; doc `README/passkey-login.md`).
- Self-API surface (all under `/api`, Sa-Token protected except noted): `/api/system-config` (GET init check — public; POST init — public; `/normal`, `/password`, `/info`, `/info/debug`, `/mount-path`, `/import-from-v2`, `/notify-config`), `/api/token` (public), `/api/account` CRUD, `/api/album` + `/api/album/latest/{accountId}` + `/api/album/date-map`, `/api/asset/{albumId}` + `/api/asset/{albumId}/latest`, `/api/crontab` CRUD + `/executions` + `/current` + `/history/{id}/details` + `/histories` + `/fill-exif/executions` + `/rewrite-fs-time/executions` (see controllers under `controller/`; OpenAPI docs auto-served at `/api/openapi.yml`, `/api/openapi.zip`, `/api/openapi.html`).

### 6.3 Notifications
`service/NotifyService.kt`: after each run (if `config.notify`), `POST` to a user-configured webhook URL with custom headers and a template body supporting `${crontab.name}`, `${crontab.id}`, `${success}`, `${total}` (auto JSON-escaped when the template starts with `{` or `[`). Daily summary cron renders `${summary}` + `${date}` from the last 24 h of `CrontabHistory` records. Notification failures are logged, not fatal.

### 6.4 Post-processing stages
- **EXIF fill** (`pipeline/stages/ExifProcessingStage.kt` + `utils/ExifHelper.kt`): shells out to external `exiftool` (path in system config). Images: sets `EXIF:DateTimeOriginal` and `EXIF:OffsetTimeOriginal` from `asset.dateTaken` only when the tag is missing/zero (`-if "not defined $DateTimeOriginal or ..."`). Videos: rewrites QuickTime `MediaCreateDate/MediaModifyDate/TrackCreateDate/TrackModifyDate/CreateDate/ModifyDate` when zero. Audio: skipped. `/tmp` paths skipped.
- **FS-time rewrite** (`pipeline/stages/FileTimeStage.kt` + `utils/FSTimeHelper.kt`): `BasicFileAttributeView.setTimes(mtime=dateTaken, null, atime=dateTaken)`.

### 6.5 Other
- Multi-account support (per-account token cache; DB FKs cascade delete albums/crontabs).
- v2→v3 data migration (`POST /api/system-config/import-from-v2`, `utils/DataImporter.kt`).
- Docker mount-path detection (`service/MountPathService.kt`).
- Debug endpoint dumping virtual-thread stacks + JVM info (`service/DebugService.kt`).
- GraalVM native-image builds with committed reflection metadata, verified by an API E2E suite that drives a stateful Go mock of the Xiaomi API (`server/src/apiE2eTest/.../ApiE2eSuite.kt`, `xas-mock/`).

---

## 7. Documented login gotchas

| Gotcha | Detail | Source |
|---|---|---|
| Device verification required first | You must visit the gallery page `https://i.mi.com/gallery/h5#/` once; if phone verification appears, tick "信任此设备" (trust this device). Otherwise `passToken` may not be present in cookies (only `serviceToken` is). | `README.md` §「获取 PassToken 与 UserId」; issues [#55](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/55), [#56](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/56) |
| Phone/OTP/captcha login not available | Owner closed the feature request: replicating the login chain incl. human-verification is unstable; only manual cookie capture is supported. | issue [#49](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/49) |
| Token lifetime is short/unreliable | serviceToken is force-refreshed every 10 min; users observed passToken/serviceToken expiring within ~1 h (`cookiesSize: 0` on refresh). Maintainer: passToken "疑似长期有效" (presumably long-lived); do **not** log out of i.mi.com in the browser after capturing. | `TokenManager.kt:64–67`; issue [#54](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/54) |
| Cookie extraction method | Copy `passToken` and `userId` from DevTools → Application → Cookies (shown in `static/copybydevtool.avif`); some users find only `serviceToken`, meaning the gallery/device-verification step was skipped. | `README.md`; issues [#55](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/55), [#56](https://github.com/Coooolfan/XiaomiAlbumSyncer/issues/56) |
| Rate limits | No explicit rate limiting in code; developer guide warns the Xiaomi API may throttle and to be careful under high concurrency. | `DEVELOPER_GUIDE.md` §7 |

---

## Appendix A — One-shot listing of every Xiaomi endpoint string in the code

```
GET  /api/user/login?ts=<ms>&followUp=<enc>&_locale=zh_CN                 TokenManager.kt:76
GET  <loginUrl>                                                          TokenManager.kt:86
GET  <Location>                                                          TokenManager.kt:104
GET  gallery/user/album/list?ts=<ms>&pageNum=<n>&pageSize=10&isShared=false&numOfThumbnails=1   XiaoMiApi.kt:39
GET  gallery/user/galleries?ts=<ms>&pageNum=<n>&pageSize=200&albumId=<id>[&startDate=<yyyyMMdd>&endDate=<yyyyMMdd>]  XiaoMiApi.kt:105
GET  gallery/user/timeline?ts=<ms>&albumId=<id>                          XiaoMiApi.kt:160
GET  sfs/ns/recorder/dir/0/list?ts=<ms>&limit=500&offset=<n>             XiaoMiApi.kt:103
GET  gallery/storage?ts=<ms>&id=<assetId>                                XiaoMiApi.kt:183
GET  sfs/ns/recorder/file/<assetId>/cb/dl_sfs_cb_<ms>_0/storage?ts=<ms>  XiaoMiApi.kt:181
GET  <ossUrl>            (JSONP dl_callback)                             XiaoMiApi.kt:207
POST <downloadUrl>  form: meta=<meta>                                    XiaoMiApi.kt:219
```

## Appendix B — Key source files

| File (repo path) | Role |
|---|---|
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/xiaomicloud/XiaoMiApi.kt` | All gallery/recorder endpoints, parsing, download URL resolution, recording-name parsing |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/xiaomicloud/TokenManager.kt` | serviceToken exchange + 10-min in-memory cache |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/utils/okHttpHelper.kt` | UA, Cookie auth header, no-redirect client, JSONP parse, retry, streaming save |
| `server/src/main/resources/app.yml` | `xiaomi.api.base-url: ${XIAOMI_API_BASE_URL:https://i.mi.com}`, SQLite/WebAuthn config |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/service/AssetService.kt` | Timeline-diff & full-refresh, undownloaded-asset selection |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/service/AlbumsService.kt` | Album list refresh / shadow marking |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/pipeline/CrontabPipeline.kt` + `pipeline/stages/*` | Sync pipeline: download / SHA-1 verify / EXIF / FS-time |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/config/TaskScheduler.kt` | Cron scheduling via Solon `IJobManager` |
| `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/model/*.kt` | Jimmer entities: `XiaomiAccount`, `Album`, `Asset`, `Crontab(+Config)`, `CrontabHistory(+Detail)`, `AlbumTimeline`, `RecordingType`, `SystemConfig`, `NotifyConfig` |
| `xas-mock/internal/mock/server.go` (+ `state.go`, `scenario.go`, `types.go`, `content.go`) | Go mock of the Xiaomi API — authoritative mirror of the endpoint contract |
| `server/src/apiE2eTest/kotlin/com/coooolfan/xiaomialbumsyncer/e2e/ApiE2eSuite.kt` | Black-box E2E over the mock: auth, albums, assets, recordings, download, notify |
