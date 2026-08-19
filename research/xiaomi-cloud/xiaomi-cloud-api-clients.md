# Xiaomi Cloud API Clients — Primary-Source Report

Research of how two open-source clients call Xiaomi's underlying cloud APIs, read directly from their source code.

## Sources examined

| Library | Repo / package | Version / commit | Files read |
|---|---|---|---|
| **mi-service-lite** (Node.js/TypeScript) | GitHub: https://github.com/idootop/mi-service-lite · npm: https://www.npmjs.com/package/mi-service-lite (v3.1.0) | commit `820fc8f2abe0e821d99f4701bfc3dc54dbb61f41` (2024-08-26); npm tarball `mi-service-lite-3.1.0.tgz` from `https://registry.npmjs.org/mi-service-lite/-/mi-service-lite-3.1.0.tgz` (dist verified identical to the TypeScript source) | `src/index.ts`, `src/mi/{index,account,common,mina,miot,types}.ts`, `src/utils/{base,codec,debug,hash,http,io,is,json,rc4}.ts`, `README.md`, `.env.example`, `tests/index.ts`, `package.json` |
| **MiCloud** (Go) | https://github.com/CLOUDERHEM/MiCloud | commit `1e41decf98df5b49a85ec25ffc872399a6da45c0` (2025-02-01) | `miaccount/{account,login}.go`, `client/client.go`, `micloud/micloud.go`, `micloud/{gallery,note,recording,sms,contact,device,status}/**/*.go` (all `api.go` + `model.go` + manager files), `utility/{request,response,parse,validate,parallel}/*.go`, `README.md`, all `*_test.go`, `go.mod` |

Both projects are **unofficial** and undocumented reverse-engineered clients. Neither contains any official Xiaomi documentation; everything below is derived from the code.

**Key architectural difference up front:** the two libraries target *different* Xiaomi API surfaces:

- **mi-service-lite** implements the **private, signed** "Mi Home / Mi AI speaker" APIs: `account.xiaomi.com/pass/*` (password login), `api.io.mi.com/app/*` (Mi Home / MIoT, signed + RC4-encrypted), `api2.mina.mi.com/*` and `userprofile.mina.mi.com/*` (XiaoAI speaker / MICO), plus `api2.mina.mi.com` `mico`-family calls via a generic `ubus` RPC channel.
- **MiCloud** implements the **public (cookie-only, unsigned) web API** of `https://i.mi.com` (the Xiaomi Cloud web app): gallery/albums, notes, recordings, SMS, contacts, devices, find-device, storage quota. It performs **no request signing at all** — no `_signature`, no nonce, no HMAC, no RC4 (verified by grep across the whole repo: the only `sha1`/`passport` hits are a `sha1` JSON field in two model structs and the string `passport` inside one endpoint URL).

---

## 1. Auth flow

### 1.1 mi-service-lite — full password login (passport.xiaomi.com / account.xiaomi.com)

Source: `src/mi/account.ts`, `src/mi/index.ts`, `src/utils/hash.ts`, `src/utils/http.ts`.

Login base URL (hard-coded constant, `src/mi/account.ts:10`):

```ts
const kLoginAPI = "https://account.xiaomi.com/pass";
```

(Note: Xiaomi's passport API is reachable at both `passport.xiaomi.com` and `account.xiaomi.com`; this library uses the `account.xiaomi.com` hostname.)

**Step 1 — probe existing session / get login parameters** (`getAccount`, `src/mi/account.ts:12-24`):

```
GET https://account.xiaomi.com/pass/serviceLogin?sid=<sid>&_json=true&_locale=zh_CN
```

- `sid` is `"xiaomiio"` (Mi Home) or `"micoapi"` (XiaoAI speaker), set in `src/mi/index.ts:29`.
- Request cookies: `userId`, `deviceId`, `passToken` (`_getLoginCookies`, `src/mi/account.ts:86-92`).
- Response body is JSON wrapped with a `&&&START&&&` prefix. `parseAuthPass` (`src/utils/codec.ts:15-32`) strips the prefix and also rewrites large integers into strings via regex `/:(\d{9,})/g` → `:"$1"` (so `userId`/`nonce` don't lose precision).
- If `pass.code !== 0` (e.g. no stored session), it performs Step 2.

**Step 2 — OAuth2 password login** (`src/mi/account.ts:27-38`):

```
POST https://account.xiaomi.com/pass/serviceLoginAuth2
Content-Type: application/x-www-form-urlencoded
Body: _json=true&qs=<pass.qs>&sid=<sid>&_sign=<pass._sign>&callback=<pass.callback>&user=<userId>&hash=<MD5(password).toUpperCase()>
```

- `qs`, `_sign`, `callback` are values echoed back by Step 1's `serviceLogin` response.
- Password credential: `hash = md5(account.password).toUpperCase()` (`src/utils/hash.ts:3-5` returns lowercase hex, then `.toUpperCase()`).
- Cookies `userId` + `deviceId` + `passToken` are sent on this POST as well.

**Step 3 — obtain `serviceToken`** (`_getServiceToken`, `src/mi/account.ts:94-113`):

After Step 2 the parsed response contains the fields the library keeps as `MiPass` (`src/mi/types.ts:1-12`): `qs`, `_sign`, `callback`, `location`, `ssecurity`, `passToken`, `nonce`, `userId`, `cUserId`, `psecurity`.

```
GET <pass.location>?_userIdNeedEncrypt=true&clientSign=<sha1("nonce=" + nonce + "&" + ssecurity)>
```

- `pass.location` is a URL returned by the passport login (points back at `account.xiaomi.com`).
- `clientSign = sha1(\`nonce=${nonce}&${ssecurity}\`)` — SHA-1 over the literal string `nonce=<nonce>&<ssecurity>`, **Base64** digest (`src/utils/hash.ts:7-9`).
- The response's `Set-Cookie` header is scanned for a cookie whose name is `serviceToken`; its value is extracted by `cookie.split(";")[0].replace("serviceToken=", "")`.
- Failure branches: if `location`/`nonce`/`passToken` are missing and the response carries `notificationUrl` or `captchaUrl`, the library prints those URLs and aborts — this is Xiaomi's "unusual-location security verification" (异地登录安全验证) gate (`src/mi/account.ts:45-58`).

**Step 4 — (optionally) resolve the speaker / IoT device** (`src/mi/account.ts:68-82`):

- `sid === "micoapi"` → `MiNA.getDevice` → `GET https://api2.mina.mi.com/admin/v2/device_list` and matches `account.did` against `deviceID`/`miotDID`/`name`/`alias` (`src/mi/mina.ts:19-41`).
- `sid === "xiaomiio"` → `MiIOT.getDevice` → signed `POST https://api.io.mi.com/app/home/device_list` and matches against `did`/`name` (`src/mi/miot.ts:17-43`).

**Session keep-alive / refresh:**

- **401 auto-refresh** (`src/utils/http.ts:133-208`): the axios response interceptor intercepts any error whose `response.status === 401` **and** whose URL contains `mina.mi.com` or `io.mi.com`. `TokenRefresher.refreshTokenAndRetry` re-runs the *entire* login flow (`getMiService({ service, relogin: true })` — which reads stored `userId`/`password` from `.mi.json` and re-executes Steps 1–4), then patches the in-memory `account` object, updates the `serviceToken` cookie, and re-sends the failed request. Up to 3 attempts with a 3-second sleep between failures. No proactive refresh / no timer.
- **Persistent re-login every process start**: each `getMiService` call always goes through `getAccount`, which starts with `serviceLogin` and only falls back to `serviceLoginAuth2` when the previous session is rejected — i.e. the stored `passToken`/`serviceToken` are *tried first*, and a full password login only happens if that fails.
- There is **no** periodic "keepalive" ping in mi-service-lite (compare MiCloud's `Renewal` below).

**Device-related headers/cookies** (Mi Home side, `src/mi/miot.ts:51-74`):

```
User-Agent: MICO/AndroidApp/@SHIP.TO.2A2FE0D7@/2.4.40
x-xiaomi-protocal-flag-cli: PROTOCAL-HTTP2
miot-accept-encoding: GZIP
miot-encrypt-algorithm: ENCRYPT-RC4
Cookie: countryCode=CN; locale=zh_CN; timezone=GMT+08:00; timezone_id=Asia/Shanghai; userId=<userId>; cUserId=<pass.cUserId>; PassportDeviceId=<deviceId>; serviceToken=<serviceToken>; yetAnotherServiceToken=<serviceToken>
```

- `deviceId` is randomly generated as `"android_" + uuid()` on first run and persisted in `.mi.json` (`src/mi/index.ts:23`).
- `cUserId` comes from the passport login response (`MiPass.cUserId`) — it is *not derived*, it is echoed back by the server (`src/mi/types.ts:10`).

### 1.2 MiCloud — cookie-based login via i.mi.com (no password)

Source: `miaccount/login.go`, `miaccount/account.go`, `client/client.go`, `micloud/status/setting/api.go`.

**Model:** MiCloud does **not** take a username/password. `miaccount.New(miAccountCookie string)` (`miaccount/account.go:11-16`) takes a **cookie string from an already-authenticated browser session** (the user logs into https://i.mi.com manually and pastes the cookie). The library then *upgrades* that passport cookie into a MiCloud (serviceToken) cookie.

**Step 1 — get the passport login redirect URL** (`GetLoginUrl`, `miaccount/login.go:12-35`):

```
GET https://i.mi.com/api/user/login?&followUp=https%3A%2F%2Fi.mi.com%2F&_locale=zh_CN&ts=<unixMillis>
```

- `ts` = `time.Now().UnixMilli()` appended directly after `ts=`.
- No cookie sent. Response JSON: `{ "loginUrl": "<url>" }` (struct `LoginUrl`, `miaccount/login.go:16-18`).
- `loginUrl` is the passport (STS bootstrap) URL the web client would normally be redirected to.

**Step 2 — follow the login URL with the user's cookie to get the STS URL** (`getSTSUrl`, `miaccount/account.go:39-52`):

```
GET <loginUrl>          Cookie: <miAccountCookie>
```

- The HTTP client is configured with `CheckRedirect = http.ErrUseLastResponse` (`utility/request/request.go:34-36`), so the 302 is *not* followed; the redirect target is read from the `Location` response header. This Location is the **STS (Security Token Service) URL**.

**Step 3 — hit the STS URL to mint the MiCloud cookie** (`getMiCloudCookie`, `miaccount/account.go:54-67`):

```
GET <stsUrl>            Cookie: <miAccountCookie>
```

- All `Set-Cookie` response headers are collected, joined with `;`, and deduplicated by `parse.TidyKvs` (`utility/parse/kv.go:38-52`) — for repeated keys the first *non-empty* value wins (empty and `""` values are replaced). The result is the **MiCloud cookie string** (contains `serviceToken` among others).
- The whole chain is wrapped in `GenMiCloudCookie()` (`miaccount/account.go:23-33`).

**How `serviceToken` is read back:** `parse.GetValueByKey(cookie, "serviceToken")` (`miaccount/account.go:35-37`, parser in `utility/parse/kv.go:13-21`).

**Session keep-alive / refresh:**

- **401 auto-refresh** (`client/client.go:39-63`): `Client.DoRequest` retries up to `RetryTimes+1` times; on `http.StatusUnauthorized` it calls `MiAccount.GenMiCloudCookie()` again and swaps `c.cookie`, then re-issues the request.
- **Explicit renewal endpoint** (`micloud/status/setting/api.go`, wrapped as `Status.Renewal()` in `micloud/status/status.go:18-24`):

```
GET https://i.mi.com/status/lite/setting?type=AutoRenewal&inactiveTime=10&ts=<unixMillis>
```

  - Note: this request sends the **original `client.MiAccount.Cookie`** (the passport cookie), *not* the generated MiCloud cookie (`req.Header.Add("Cookie", client.MiAccount.Cookie)`).
  - The response's `Set-Cookie` is parsed for a fresh `serviceToken` value (may be empty → logs "serviceToken is empty, do not need to renew"). This is a manual keep-alive call, not automatic.

**Device-related headers** (`client/client.go:65-70`, applied to every API request):

```
Cookie: <micloud cookie>
Sec-Ch-Ua: "Chromium";v="128", "Not;A=Brand";v="24", "Google Chrome";v="128"
Sec-Ch-Ua-Mobile: ?0
Sec-Ch-Ua-Platform: "Windows"
```

No `deviceId`/`cUserId`/`PassportDeviceId` concepts exist in MiCloud — authentication is the cookie alone.

---

## 2. API request signing

### 2.1 mi-service-lite — the signed `api.io.mi.com` scheme (Mi Home / MIoT)

This is Xiaomi's private signed API. Only the Mi Home (`api.io.mi.com/app/*`) calls are signed; the `mina.mi.com` calls are **not** signed (they rely on `serviceToken` cookies only). All signing code lives in `src/utils/codec.ts`, `src/utils/hash.ts`, `src/utils/rc4.ts`.

**Request construction** (`encodeMiIOT`, `src/utils/codec.ts:77-99`):

```ts
export function encodeMiIOT(method, uri, data, ssecurity): MiIOTRequest {
  let nonce = randomNoice();                       // 12 random bytes, base64
  const snonce = signNonce(ssecurity, nonce);      // sha256(ssecurity || nonce), base64
  let key = Buffer.from(snonce, "base64");
  let rc4 = new RC4(key);
  rc4.update(Buffer.alloc(1024));                  // skip first 1024 bytes of keystream
  let json = jsonEncode(data);
  let map: any = { data: json };
  map.rc4_hash__ = rc4Hash(method, uri, map, snonce);       // hash over PLAINTEXT map
  for (let k in map) {
    map[k] = rc4.update(Buffer.from(map[k])).toString("base64");  // RC4-encrypt each value
  }
  map.signature = rc4Hash(method, uri, map, snonce);        // hash over ENCRYPTED map
  map._nonce = nonce;                                        // plaintext nonce
  map.ssecurity = ssecurity;                                 // plaintext ssecurity (!)
  return map;
}
```

**The nonce** (`randomNoice`, `src/utils/hash.ts:35-41`): 12 random bytes → base64 string (not time-based).

**The signature key derivation** (`signNonce`, `src/utils/hash.ts:18-23`):

```ts
export function signNonce(ssecurity: string, nonce: string) {
  let m = crypto.createHash("sha256");
  m.update(ssecurity, "base64");   // interpret ssecurity as base64 bytes
  m.update(nonce, "base64");       // interpret nonce as base64 bytes
  return m.digest().toString("base64");
}
```

i.e. `snonce = base64( SHA256( base64decode(ssecurity) ‖ base64decode(nonce) ) )`.

**The signature hash** (`rc4Hash`, `src/utils/rc4.ts:44-65`) — this is Xiaomi's `_signature` scheme:

```ts
export function rc4Hash(method, uri, data, ssecurity) {
  var arrayList = [];
  if (method != null) arrayList.push(method.toUpperCase());
  if (uri != null)   arrayList.push(uri);
  if (data != null)  for (var k in data) arrayList.push(k + "=" + data[k]);
  arrayList.push(ssecurity);
  return sha1(arrayList.join("&"));   // base64 digest
}
```

- `sha1` returns a **Base64** digest (`src/utils/hash.ts:7-9`).
- `uri` is the path **without** the `https://api.io.mi.com/app` prefix (e.g. `/home/device_list`, `/miotspec/prop/get`).
- `rc4_hash__` is computed over the plaintext `data` field, `signature` is computed *after* RC4-encrypting every field — both use the same `rc4Hash` formula with `ssecurity` appended last.

**RC4 details** (`src/utils/rc4.ts:3-42`): standard RC4 (KSA + PRGA), 256-byte state; 1024 zero bytes are pushed through the keystream before encrypting the payload (matching the well-known Xiaomi MiIO trick).

**Transport of the signed fields** (`src/mi/miot.ts:45-95`):

- `GET`: signed fields go in the **query string**: `GET https://api.io.mi.com/app<path>?data=<...>&rc4_hash__=<...>&signature=<...>&_nonce=<...>&ssecurity=<...>` (via `Http.get`/`buildURL`, `src/utils/http.ts:103-111`).
- `POST`: signed fields go in the **form-encoded body**: `POST https://api.io.mi.com/app<path>` with `Content-Type: application/x-www-form-urlencoded` body `encodeQuery(map)` (`src/utils/codec.ts:34-45`).
- Request headers: `User-Agent: MICO/AndroidApp/@SHIP.TO.2A2FE0D7@/2.4.40`, `x-xiaomi-protocal-flag-cli: PROTOCAL-HTTP2`, `miot-accept-encoding: GZIP`, `miot-encrypt-algorithm: ENCRYPT-RC4`, plus the cookie block from §1.1.

**Response decryption** (`decodeMiIOT`, `src/utils/codec.ts:101-124`, invoked at `src/mi/miot.ts:88-94`):

```ts
let key = Buffer.from(signNonce(ssecurity, nonce), "base64"); // same derivation as request
let rc4 = new RC4(key);
rc4.update(Buffer.alloc(1024));                       // skip 1024 bytes again
let decrypted = rc4.update(Buffer.from(data, "base64"));
if (gzip) decrypted = pako.ungzip(decrypted, { to: "string" });
return JSON.parse(decrypted);
```

- `gzip` is decided by the response header `miot-content-encoding === "GZIP"`.
- `nonce` here is the request's `_nonce`, `data` is the raw response body (base64).

**Which endpoints require signing:** all `api.io.mi.com/app/*` calls in `src/mi/miot.ts` — `/home/device_list`, `/home/rpc/<did>`, `/miotspec/prop/get`, `/miotspec/prop/set`, `/miotspec/action`. (No `cUserId`/`signedTime`/`_signature` query keys appear anywhere — that terminology belongs to a different Xiaomi signing variant not used here; the signed params are `data`, `rc4_hash__`, `signature`, `_nonce`, `ssecurity`.)

**Also signed-adjacent:** the passport `serviceToken` fetch uses `clientSign = sha1("nonce=" + nonce + "&" + ssecurity)` (base64, `src/mi/account.ts:100`).

### 2.2 MiCloud — no signing

**Not implemented.** Verified by grep of every `*.go` file for `signature|ssecurity|nonce|hmac|sha1|sha256|rc4|_sign` — the only hits are a `sha1` JSON field in `micloud/gallery/gallery/model.go:22` and `micloud/recording/recording/model.go:9`, and the substring `passport` inside the devices URL `micloud/device/device/api.go:13`. All MiCloud API calls are plain `GET`/`POST` with query/body parameters and the MiCloud cookie; the only "auth" parameters ever attached are literal `serviceToken=<...>` values in a few DELETE endpoints (see §3). The `ts` (unix-millis) and `_dc` (cache-buster, same value) query parameters that appear on most calls are the *only* per-request dynamic fields.

---

## 3. API base URLs and endpoints (exact strings)

### 3.1 mi-service-lite

Base URLs:

- `https://account.xiaomi.com/pass` — passport login (`src/mi/account.ts:10`)
- `https://api.io.mi.com/app` — Mi Home / MIoT, signed (`src/mi/miot.ts:51`)
- `https://api2.mina.mi.com` — XiaoAI speaker / MICO, cookie-only (`src/mi/mina.ts:54`)
- `https://userprofile.mina.mi.com` — speaker dialogue-note API (`src/mi/mina.ts:197`)

| Method | Path (exact) | Params / body | Notes | Source |
|---|---|---|---|---|
| GET | `/pass/serviceLogin` | `sid`, `_json=true`, `_locale=zh_CN` | returns `&&&START&&&{...}`; cookies `userId`,`deviceId`,`passToken` | `src/mi/account.ts:15-19` |
| POST | `/pass/serviceLoginAuth2` | form: `_json=true`, `qs`, `sid`, `_sign`, `callback`, `user`, `hash=MD5(pw).toUpperCase()` | OAuth2 password login | `src/mi/account.ts:36-38` |
| GET | `<pass.location>` | `_userIdNeedEncrypt=true`, `clientSign=sha1("nonce="+nonce+"&"+ssecurity)` | `serviceToken` read from `Set-Cookie` | `src/mi/account.ts:96-103` |
| POST | `/app/home/device_list` | body `{getVirtualModel:false, getHuamiDevices:0}` (RC4-encrypted) | signed; returns `{list:[MiIOTDevice]}` | `src/mi/miot.ts:21-29` |
| POST | `/app/home/rpc/<device.did>` | body `{id, method, params}` | raw device RPC | `src/mi/miot.ts:101-107` |
| POST | `/app/miotspec/prop/get` | body `[{did, siid, piid}]` + `datasource` | MIoT spec property get | `src/mi/miot.ts:114-119, 129-138` |
| POST | `/app/miotspec/prop/set` | body `[{did, siid, piid, value}]` + `datasource` | MIoT spec property set | `src/mi/miot.ts:140-150` |
| POST | `/app/miotspec/action` | body `{did, siid, aiid, in}` + `datasource` | MIoT spec action | `src/mi/miot.ts:152-160` |
| GET | `/admin/v2/device_list` | query: `requestId=<uuid>`, `timestamp=<unixSec>` (merged by `__callMina`) | returns `{code:0, data:[MinaDevice]}` | `src/mi/mina.ts:43-81` |
| POST | `/remote/ubus` | form: `requestId`, `timestamp`, `deviceId`, `path` (=ubus scope, e.g. `mediaplayer`, `mibrain`), `method` (=ubus command), `message` (JSON string) | generic XiaoAI RPC; cookies carry device `sn`,`hardware`,`deviceId`,`deviceSNProfile` | `src/mi/mina.ts:91-99, 43-81` |
| GET | `/device_profile/v2/conversation` | query: `limit`, `timestamp`, `requestId=<uuid>`, `source=dialogu`, `hardware=<device.hardware>` | dialogue-note / conversation history; UA is a full Android WebView UA + `Referer: https://userprofile.mina.mi.com/dialogue-note/index.html`; returns `{code, data}` where data is the `MiConversations` JSON | `src/mi/mina.ts:191-227` |

`MiIOTDevice` fields (`src/mi/types.ts:14-28`): `did, token, name, localip, mac, ssid, bssid, model, isOnline, desc, uid, pd_id, rssi`.
`MinaDevice` fields (`src/mi/types.ts:30-45`): `deviceId, deviceID, serialNumber, name, alias, presence, miotDID, hardware, deviceSNProfile, deviceProfile, brokerEndpoint, brokerIndex, mac, ssid`.
`MiConversations` (`src/mi/types.ts:100-110`): `{ bitSet, records: [{bitSet, answers[], time (ms), query, requestId}], nextEndTime }`, where each `answer` is an LLM / TTS / AUDIO block.

**Not implemented in mi-service-lite:** gallery/albums (no `mico/album`, no `api.io.mi.com/app/home/v1`), notes, recordings, cloud file download, contacts, SMS. The only endpoints present are the passport login + Mi Home device/RPC/MIoT spec + XiaoAI speaker device/ubus/conversation calls above.

### 3.2 MiCloud — all endpoints (base `https://i.mi.com`)

| Method | Path (exact) | Params / body | Response model (JSON keys) | Source |
|---|---|---|---|---|
| GET | `/api/user/login?&followUp=https%3A%2F%2Fi.mi.com%2F&_locale=zh_CN&ts=<ms>` | — | `{loginUrl}` | `miaccount/login.go:13` |
| GET | `<loginUrl>` | cookie: passport cookie | 302 → `Location` = STS URL | `miaccount/account.go:39-52` |
| GET | `<stsUrl>` | cookie: passport cookie | `Set-Cookie` → MiCloud cookie | `miaccount/account.go:54-67` |
| GET | `/gallery/user/album/list` | `ts`, `_dc`, `pageNum`, `pageSize`, `isShared`, `numOfThumbnails=1` | `Albums`: `{albums:[{albumId,lastUpdateTime,mediaCount,name,userId,thumbnails:[{url,orientation}]}], indexHash, isLastPage}` | `micloud/gallery/album/api.go:13-32` + `model.go` |
| GET | `/gallery/user/galleries` | `ts`, `startDate`, `endDate`, `pageNum`, `pageSize`, `albumId` | `Galleries`: `{galleries:[{id,title,fileName,description,mimeType,type,status,tag,sha1,size,createTime,dateModified,dateToken,sortTime,isFavorite,isFrontCamera,isUbiImage,groupId,geoInfo,bigThumbnailInfo,thumbnailInfo}], indexHash, isLastPage}`; `ThumbnailInfo`: `{data,isUrl}`; `GeoInfo`: `{address,addressList,gps,isAccurate}` | `micloud/gallery/gallery/api.go:16-38` + `model.go` |
| GET | `/gallery/storage` | `ts`, `id`, `callBack=dl_img_cb_<ts>_0` | `StorageFile`: `{url}` | `micloud/gallery/gallery/api.go:40-58` |
| GET | `<storageUrl>` | cookie | JSONP body `cb({url,meta})` — parsed by slicing from first `{` to `len-1` | `micloud/gallery/gallery/api.go:60-80`, `GalleryFile{url,meta}` |
| GET | `/gallery/info/delete` | `id`, `serviceToken=<token>` | `StorageFile` (validated) | `micloud/gallery/gallery/api.go:82-98` |
| GET | `/gallery/user/timeline` | `ts`, `albumId` | `Timeline`: `{dayCount: {yyyyMMdd: n}, indexHash}` | `micloud/gallery/timeline/api.go:12-27` + `model.go` |
| GET | `/note/full/page/?limit=<n>&ts=<ms>` | — | `Notes`: `{entries:[Note], folders:[Folder], lastPage, syncTag}` | `micloud/note/note/api.go:15-36` + `model.go` |
| GET | `/note/note/<id>/?ts=<ms>` | — | `{entry: Note}` | `micloud/note/note/api.go:16,38-53` |
| POST | `/note/full/<id>/delete` | form: `tag`, `purge=<bool>`, `serviceToken=<token>` | `{}` validated | `micloud/note/note/api.go:17,55-77` |
| GET | `/file/full` | `type=note_img`, `fileid=<fileId>` | 302 → `Location` = file download URL | `micloud/note/note/api.go:18,79-96` |
| GET | `/note/deleted/page` | `ts`, `_dc`, `limit`, `syncTag` | `Notes` (recycle bin) | `micloud/note/recyclebin/api.go:14-31` |
| GET | `/sfs/ns/recorder/dir/0/list` | `offset`, `limit`, `ts`, `_dc` | `{list:[Recording]}`; `Recording`: `{id,name,type,parent_id,size,sha1,ver,create_time,modify_time}` | `micloud/recording/recording/api.go:14-38` + `model.go` |
| GET | `/sfs/ns/recorder/file/<id>/storage/geturl?ts=<ms>` | — | `{url}` | `micloud/recording/recording/api.go:15,40-57` |
| POST | `/sfs/ns/recorder/file/<id>/delete?ts=<ms>` | form: `permanent=false`, `serviceToken=<token>` | `{}` validated | `micloud/recording/recording/api.go:16,59-81` |
| GET | `/sms/full/thread` | `ts`, `_dc`, `limit`, `syncTag`, `syncThreadTag`, `readMode=older`, `withPhoneCall=false` | `Messages`: `{entries:[{entry:Message,operation}], watermark:{syncTag,syncThreadTag}}`; `Message`: `{folder,id,lastUpdateTime,localTime,recipients,snippet,tag,threadId,total,unread}` | `micloud/sms/message/api.go:13-33` + `model.go` |
| GET | `/sms/deleted/thread` | same params as `/sms/full/thread` | `Messages` (recycle bin) | `micloud/sms/recyclebin/api.go:14-34` |
| GET | `/contacts/initdata` | `ts`, `_dc`, `syncTag=0`, `limit`, `syncIgnoreTag=0` | `Contacts`: `{content: {<id>: {content, pinyin, type, createTime, updateTime}}, letterIndex: {<letter>:[ids]}, syncIgnoreTag, syncTag, lastPage}`; contact `content`: `{displayName,id,name:{formatted},phoneNumbers:[{type,value}],starred,status,tag}` | `micloud/contact/contact/api.go:13-31` + `model.go` |
| GET | `/passport/user/all/devices?locale=zh_CN?ts=<ms>` | — | `Devices`: `{list:[{devId,model,modelInfo:{deviceName,deviceType,fullImageUrl,model,modelName},statusMicloud}]}` | `micloud/device/device/api.go:13-36` + `model.go` |
| GET | `/find/device/full/status?ts=<ms>` | — | `Status`: `{devices:[{commandList,devId,deviceType,imei,isTZDevice,lastLocationReceipt,lastResponse,locationReceiptList,model,phone,regId,showUnavailableNotice,snapshot,status,updateTime,version}], locale}`; `LocationReceipt`: `{gpsInfo,gpsInfoTransformed,infoTime,msgId,phone,powerLevel,responseType,serverReceiveTime}`; `GPSInfo`: `{accuracy,address,area,coordinateType,inChinaMainLand,latitude,longitude,sourceType}` | `micloud/device/status/api.go:12-23` + `model.go` |
| GET | `/status/lite/alldetail?ts=<ms>` | — | `Detail`: `{autoRenewal,currentRecordIsLongTermRecord,level,settingType,totalDetail:{baseQuota,bonusSize,extendPackageSize,yearlyPackageExpireTime,yearlyPackageExpireSize,yearlyPackageExpireType},totalQuota,used,usedDetail:{AppList,GalleryImage,Recorder}` (each `{size,text}`)`}` | `micloud/status/detail/api.go:12-26` + `model.go` |
| GET | `/status/lite/setting?type=AutoRenewal&inactiveTime=10&ts=<ms>` | cookie: **original** passport cookie | `Set-Cookie` → fresh `serviceToken` (renewal) | `micloud/status/setting/api.go:16-41` |

Notes on shapes:

- Every API response is parsed as `Response[T] = { code, data, result, description }` (`utility/response/response.go:10-15`; `result` is a string, e.g. `"ok"`, and `code: 0` means success — `utility/validate/validate.go:11`). HTTP status must be `200` and `code` must be `0` (`validate.Validate`, `utility/validate/validate.go:15-30`).
- All GETs use `ts=<unixMillis>` and most also `_dc=<same millis>` as a cache-buster.
- The `Note`/`Folder` models note in code comments that `folderId` "can be int or string" (`micloud/note/note/model.go:8-9, 29-30`), and `LocationReceipt.MsgId` likewise (`micloud/device/status/model.go:31-32`).

**Not implemented in MiCloud:** `api.io.mi.com` (Mi Home / MIoT), `mico/*` endpoints, any signed/encrypted request, password login, and 2FA/captcha handling.

### 3.3 Coverage of the user-listed endpoint families

| Endpoint family | mi-service-lite | MiCloud |
|---|---|---|
| `api.io.mi.com/app/home/v1`-style (Mi Home) | ⚠️ different version: `api.io.mi.com/app/home/device_list`, `/home/rpc/<did>`, `/miotspec/*` (signed) | not implemented |
| gallery / albums (`mico/album`) | not implemented | ✅ `i.mi.com/gallery/user/album/list`, `/gallery/user/galleries`, `/gallery/storage`, `/gallery/user/timeline`, `/gallery/info/delete` |
| cloud storage / file download | not implemented | ✅ `/gallery/storage` + raw `<storageUrl>` fetch; `/file/full` (302 → file URL) |
| contacts | not implemented | ✅ `/contacts/initdata` |
| notes (`mico/note`) | not implemented | ✅ `/note/full/page/`, `/note/note/<id>/`, `/note/full/<id>/delete`, `/note/deleted/page` |
| recordings (`mico/recording`) | not implemented | ✅ `/sfs/ns/recorder/dir/0/list`, `/sfs/ns/recorder/file/<id>/storage/geturl`, `/sfs/ns/recorder/file/<id>/delete` |
| SMS | not implemented | ✅ `/sms/full/thread`, `/sms/deleted/thread` |

---

## 4. How cookies are stored / loaded

### mi-service-lite

- **Session state is persisted to a JSON file** `.mi.json` in `process.cwd()` (`src/mi/index.ts:12` `const kConfigFile = ".mi.json"`; read at line 24, written at line 40 via `readJSON`/`writeJSON` in `src/utils/io.ts:68-72`). Shape: `{ miiot?: MiAccount, mina?: MiAccount }` (`src/mi/index.ts:8-11`).
- The persisted `MiAccount` (`src/mi/types.ts:47-58`) holds `sid`, `deviceId`, `userId`, `password`, `pass` (the whole `MiPass` object including `ssecurity`, `nonce`, `passToken`, `cUserId`, `location`, …), and `serviceToken`.
- At runtime, cookies are serialized into a single `Cookie` header per request by `HTTPClient.buildConfig` (`src/utils/http.ts:113-128`): `Object.entries(cookies).map(([k,v]) => \`${k}=${v};\`).join(" ")`.
- The axios instance applies a fixed default `User-Agent: Dalvik/2.1.0 (Linux; U; Android 10; RMX2111 Build/QP1A.190711.020) APP/xiaomi.mico APPV/2004040 MK/Uk1YMjExMQ== PassportSDK/3.8.3 passport-ui/3.8.3`, `Accept-Encoding: gzip, deflate`, `Content-Type: application/x-www-form-urlencoded`, `proxy: false`, `decompress: true` (`src/utils/http.ts:9-18`). Default timeout 3 s (`src/utils/http.ts:76`).

### MiCloud

- **Cookies live in memory only** — a plain `string` field: `MiAccount.Cookie` (`miaccount/account.go:18-21`), mirrored into `Client.cookie` (`client/client.go:30-37`). There is also a package-level default `var GlobalCookie = ""` used by `GlobalClient` (`client/client.go:11-20`).
- **No file persistence, no cookie jar** — the standard `net/http` client is used without any jar; the cookie is re-attached manually on every request via `postProcessReq` (`client/client.go:65-70`). Redirects are deliberately not auto-followed (`CheckRedirect = ErrUseLastResponse`, `utility/request/request.go:34-36`).
- `Set-Cookie` responses are flattened to a `k=v;...` string by `parse.TidyKvs` (`utility/parse/kv.go:38-52`) — that string *is* the "cookie store".

---

## 5. Gotchas

1. **Two different auth models.** mi-service-lite = full password login (username + MD5-uppercased-hash against `account.xiaomi.com/pass/serviceLoginAuth2`). MiCloud = cookie hand-me-down (user supplies a logged-in `i.mi.com` browser cookie; library turns it into a `serviceToken` cookie via the `i.mi.com/api/user/login` → 302 → STS chain). MiCloud has **no password flow** and no way to obtain a session from credentials — `miaccount.New("")` with an empty cookie (as in its own test `miaccount/account_test.go`) is the documented starting point only for exercising the flow.
2. **401 handling / serviceToken expiry.** Both refresh automatically on `401`: mi-service-lite re-runs the whole login using stored credentials (only for `mina.mi.com`/`io.mi.com` URLs, up to 3 tries, 3 s apart — `src/utils/http.ts:139-169`); MiCloud regenerates the MiCloud cookie from the original passport cookie and retries (`client/client.go:43-63`). MiCloud additionally exposes an explicit renewal call hitting `i.mi.com/status/lite/setting?type=AutoRenewal&inactiveTime=10` with the *original* passport cookie (`micloud/status/setting/api.go:19-41`). Neither library has a proactive token-expiry timer.
3. **ssecurity is sent in the clear.** In the Mi Home signed API the request ships `_nonce` **and** `ssecurity` as literal query/form fields (`src/utils/codec.ts:96-97`) — the scheme's confidentiality comes from RC4-encrypting the `data`/`rc4_hash__`/`signature` payload, not from hiding the key material.
4. **Signature algorithm precision.** The `signature` field is `sha1(method.toUpperCase() + "&" + uri + "&" + k1=v1 + "&" + k2=v2 + ... + "&" + ssecurity)` over the **RC4-encrypted** field map (Base64 output); `rc4_hash__` is the same formula over the **plaintext** `data` only; the RC4 keystream skips its first 1024 bytes; the response is decrypted with the same `signNonce` key and is gzip only when the `miot-content-encoding: GZIP` response header says so.
5. **`deviceId` / `cUserId` derivation.** mi-service-lite generates a fresh `deviceId = "android_" + uuid()` when none is stored (`src/mi/index.ts:23`) and sends it as `PassportDeviceId` cookie; `cUserId` is **not** derived locally — it is returned by the passport login and stored in `MiPass.cUserId` (`src/mi/types.ts:10`). MiCloud has neither concept (cookie-only auth).
6. **Region (cn/global): not implemented in either library.** mi-service-lite hard-codes the China region in the Mi Home cookie block: `countryCode=CN`, `locale=zh_CN`, `timezone=GMT+08:00`, `timezone_id=Asia/Shanghai` (`src/mi/miot.ts:63-73`) and `_locale=zh_CN` on passport calls; MiCloud hard-codes the `i.mi.com` (CN) host and `_locale=zh_CN` (`miaccount/login.go:13`). There is no `cn`/`global`/`de`/`sg` switch and no region detection anywhere.
7. **HTTPS only.** Both libraries use `https://` exclusively; mi-service-lite also sets `proxy: false` and `decompress: true` on axios (`src/utils/http.ts:10-11`).
8. **User-Agent requirements.** mi-service-lite sets *three different* UAs: the Dalvik/PassportSDK UA for passport calls (axios default), `MICO/AndroidApp/@SHIP.TO.2A2FE0D7@/2.4.40` for Mi Home/MIOTA speaker calls, and a full Chrome/Android WebView UA plus `Referer: https://userprofile.mina.mi.com/dialogue-note/index.html` for the conversation endpoint (`src/mi/mina.ts:208-212`). MiCloud sends no `User-Agent` at all, only `Sec-Ch-Ua`-family headers impersonating Chrome 128 on Windows (`client/client.go:67-69`).
9. **Security-verification trap.** If passport answers with `notificationUrl`/`captchaUrl` (unusual-login verification), mi-service-lite prints the URLs and fails; its README notes the account state may take ~1 h to refresh after manual verification (`src/mi/account.ts:45-58`).
10. **Literal URL bugs in MiCloud** worth knowing when replicating: the devices endpoint is `https://i.mi.com/passport/user/all/devices?locale=zh_CN?ts=<ms>` — note the **second `?`** before `ts` (`micloud/device/device/api.go:13,17`); the note list is `https://i.mi.com/note/full/page/?limit=%v&ts=%v` (trailing slash + `?limit=`); the gallery file download parses a **JSONP** body by slicing from the first `{` to the last character (`micloud/gallery/gallery/api.go:73-79`), and the `callBack` parameter passed to `/gallery/storage` is `dl_img_cb_<ts>_0` (`micloud/gallery/gallery/api.go:45`).
11. **Envelope & error semantics.** MiCloud's envelope is `{code, data, result, description}` with `code:0` = success (`utility/response/response.go:10-15`, `utility/validate/validate.go:10-12`); errors surface as `"<description>:<result>"` (e.g. `成功:ok` on success). mi-service-lite's MiNA envelope is `{code, data, ...}` with `code:0` = success (`src/mi/mina.ts:74-80`), while Mi IOT returns the decrypted JSON directly (`src/mi/miot.ts:88-94`).
12. **Rate limiting.** MiCloud's `Note.ListFullNotes` throttles parallel note fetches with a randomized 1–(len/5) second sleep per request (`NumOfReqInSec` default 5, `micloud/note/note.go:25-35`); no other rate limiting exists in either library.
13. **`folderId`/`msgId` type instability.** MiCloud's own model comments flag that `folderId` (notes) and `msgId` (find-device) arrive as `int` **or** `string`; any reimplementation should tolerate both (`micloud/note/note/model.go:8-9,29-30`, `micloud/device/status/model.go:31-32`).
14. **Dependencies.** mi-service-lite needs only `axios` + `pako` (`package.json:23-26`); MiCloud needs only `github.com/tidwall/gjson` (`go.mod:5`). No OpenSSL / OS crypto deps — `crypto` (Node) and stdlib Go are used.
