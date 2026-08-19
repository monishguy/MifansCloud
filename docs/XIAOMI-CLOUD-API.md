# 小米云服务（i.mi.com）API 调研文档

> 面向「米饭云服务（MifansCloud）」Android 项目的技术调研文档。
> 结论全部来自三个参考项目的**源码级**阅读（一手来源）：
> - [XiaomiAlbumSyncer](https://github.com/Coooolfan/XiaomiAlbumSyncer)（Kotlin/JVM，相册+录音下载器）
> - [mi-service-lite](https://github.com/idootop/mi-service-lite)（TypeScript，passport 登录 + Mi Home 签名 API）
> - [MiCloud](https://github.com/CLOUDERHEM/MiCloud)（Go，i.mi.com 全套数据接口封装）
>
> 原始源码克隆在 `research/xiaomi-cloud/`，逐文件引用见 `research/xiaomi-cloud/xiaomi-cloud-api-clients.md`。
>
> ⚠️ 所有接口均为**非官方逆向接口**，无文档、可能随时变更；生产使用前务必自行验证。

---

## 目录

1. [认证模型总览](#1-认证模型总览)
2. [认证路径详解](#2-认证路径详解)
3. [通用请求约定](#3-通用请求约定)
4. [数据类型 × 端点目录](#4-数据类型--端点目录)
5. [文件下载机制（两步签名直链）](#5-文件下载机制两步签名直链)
6. [增量同步机制](#6-增量同步机制)
7. [上传方向：现状与思路](#7-上传方向现状与思路)
8. [Mi Home 签名 API（仅参考）](#8-mi-home-签名-api仅参考)
9. [Android 实现要点](#9-android-实现要点)
10. [风险与注意事项](#10-风险与注意事项)

---

## 1. 认证模型总览

`i.mi.com`（小米云 Web 端）的核心事实：

- **纯 Cookie 认证**，不需要请求签名、不需要加密参数（对比 Mi Home 的 `api.io.mi.com` 签名+RC4，见 §8）。
- 会话由 **`userId` + `serviceToken`** 两个 cookie 决定。`serviceToken` 是长期有效的会话令牌（XiaomiAlbumSyncer 的 README 中把从浏览器复制的字段叫 `passToken`，实际请求时是作为 **`serviceToken` cookie 值**发送的）。
- 小米浏览器登录体系（passport）本身可提供**完整密码登录**流程，但容易触发「异地登录安全验证」（手机验证码/滑块）。

三种可行的认证路径：

| 路径 | 用户操作 | 优点 | 缺点 | 参考实现 |
| --- | --- | --- | --- | --- |
| **A. 手动复制 Cookie** | 浏览器登录 i.mi.com，开发者工具复制 `userId`/`passToken` | 最简单、最稳，无验证码问题 | 需要用户手动操作；cookie 过期需重新复制 | XiaomiAlbumSyncer |
| **B. Cookie 升级链（STS）** | 粘贴整段浏览器 Cookie | 全自动换取 `serviceToken`；可主动续期 | 仍依赖手动登录一次 | MiCloud |
| **C. 密码登录** | 输入账号密码 | 全自动、无浏览器依赖 | 极易触发安全验证（notificationUrl/captchaUrl）；需处理 2FA | mi-service-lite |

**本项目推荐**：Android 端用 **WebView 内嵌登录 i.mi.com → 提取 cookie（路径 A/B 结合）**，兼顾用户体验与稳定性；密码登录（路径 C）作为兜底方案，仅在 WebView 不可用时使用。

---

## 2. 认证路径详解

### 2.1 路径 A：手动复制 Cookie（XiaomiAlbumSyncer 方式）

用户操作步骤（来自 [README](https://github.com/Coooolfan/XiaomiAlbumSyncer)「获取 PassToken 与 UserId」一节）：

1. 浏览器登录 https://i.mi.com/
2. **访问一次相册页面** https://i.mi.com/gallery/h5#/ （触发服务端发 cookie）
3. 若出现手机验证，勾选「信任此设备」
4. 开发者工具 → Application/应用程序 → Cookies（`i.mi.com` 域）
5. 复制 `passToken` 与 `userId` 两个字段的值

请求注入方式（`server/.../utils/okHttpHelper.kt` 的 `authHeader`）：

```kotlin
Cookie: userId={userId}; serviceToken={passToken};
User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36
            (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36 Edg/139.0.0.0
```

要点：浏览器里的字段名是 `passToken`，**请求时放进 `serviceToken` cookie**；`userId` 原样发送。UA 需伪装成 Chrome/Edge 桌面端。HTTP 客户端必须 **`followRedirects(false)`**（签名直链流程依赖手动处理 302）。

> ⚠️ 常见问题（issues #55/#56）：若开发者工具里**只看到 `serviceToken` 而找不到 `passToken`**，说明第 2 步「访问相册页/完成设备验证」没做——`passToken` 只有完成设备信任后才会下发。复制后**不要退出浏览器登录**，否则 token 失效。

### 2.2 路径 B：Cookie 升级链 / STS（✅ 双源验证：MiCloud + XiaomiAlbumSyncer）

用户只需**登录一次** i.mi.com 并复制 cookie，程序自动换取 `serviceToken`。该链路被 **MiCloud**（`miaccount/login.go`、`miaccount/account.go`）与 **XiaomiAlbumSyncer**（`TokenManager.kt`）两个项目独立实现并验证，契约如下：

```
① 预登录
   GET https://i.mi.com/api/user/login?ts={ms}&followUp={urlencode(baseUrl)}&_locale=zh_CN
   Cookie: userId={userId}; deviceId={deviceId}; passToken={passToken}
   → { "data": { "loginUrl": "<url>" } }

② 手动跟随登录 URL（不跟随 302，读 Location 头）
   GET <loginUrl>，携带同三 cookie
   → HTTP 302，Location = STS（Security Token Service）URL

③ 换取 serviceToken
   GET <stsUrl>，携带同三 cookie
   → 收集全部 Set-Cookie，取第一个 serviceToken=...（值截止到第一个分号）
```

细节（XiaomiAlbumSyncer `TokenManager.kt`）：

- `deviceId = "wb_" + UUID.randomUUID()`：**`wb_` 前缀标记 Web 设备**（小米 mock 服务器强制校验该前缀）。
- `userId` 不派生，直接使用用户提供的值；`passToken` 即浏览器复制值。
- 全程 `followRedirects(false)`，302 目标从 `Location` 头手动读取。
- MiCloud 变体：用户粘贴的是**整段**浏览器 cookie（而非三个单字段），②③ 步携带整段；`followUp` 值为 `https://i.mi.com/`。
- 成功后 `serviceToken` 即可单独抽出使用（`parse.GetValueByKey(cookie, "serviceToken")`）。

**Cookie 刷新 / 续期（关键！）**：

- **serviceToken 有效期极短**：XiaomiAlbumSyncer 将其**仅存内存**并**每 10 分钟强制刷新**（`tokenCache` + `needRefresh()`；注释原文：「serviceToken 的过期时间非常短，10 分钟强制刷新」）。用户实测约 1 小时左右过期（issue #54）。
- **刷新 = 重跑 ①→③ 全链路**（同一 `passToken`/`userId` + 新随机 deviceId）。刷新失败（无 Location 头 / 无 serviceToken Set-Cookie）会使当次同步失败，**无自动重试与退避**。
- **401 兜底**：MiCloud 在任意请求 401 时自动重跑全链路换新 cookie 并重发请求。
- **主动续期端点**（MiCloud）：

```
GET https://i.mi.com/status/lite/setting?type=AutoRenewal&inactiveTime=10&ts={ms}
Cookie: <原始 passport cookie>          ← 注意：用原始 cookie，不是升级后的
→ Set-Cookie 返回新的 serviceToken（可能为空 = 无需续期）
```

- ⚠️ **浏览器侧铁律**（维护者建议，issue #54）：复制 cookie 后**不要在浏览器里退出登录 i.mi.com**，否则 token 立即失效。

### 2.3 路径 C：密码登录（mi-service-lite 方式，passport）

完整流程（`src/mi/account.ts`）：

```
① GET https://account.xiaomi.com/pass/serviceLogin?sid=<xiaomiio|micoapi>&_json=true&_locale=zh_CN
   请求 cookie：userId、deviceId、passToken（此前保存的会话；无则跳过）
   响应：&&&START&&&{...}（JSON 前缀包裹；需把大整数改写为字符串避免精度丢失）
   解析出：qs、_sign、callback、location 等登录参数

② POST https://account.xiaomi.com/pass/serviceLoginAuth2
   Content-Type: application/x-www-form-urlencoded
   body: _json=true&qs={qs}&sid={sid}&_sign={_sign}&callback={callback}
         &user={账号}&hash={MD5(密码).toUpperCase()}
   → 响应含：ssecurity、nonce、passToken、userId、cUserId、location、psecurity

③ GET {location}?_userIdNeedEncrypt=true&clientSign={sha1("nonce=" + nonce + "&" + ssecurity)}
   → 从 Set-Cookie 中提取 serviceToken

安全验证分支：若 ② 响应含 notificationUrl 或 captchaUrl → 异地登录验证，
库只打印 URL 并中止；README 提示手动验证后账号状态约 1 小时恢复。
```

`deviceId` 首次生成 `"android_" + uuid()` 并持久化（`PassportDeviceId` cookie）；`cUserId` 由服务端下发，**不是本地推导的**。

> 移动端提示：passport 密码登录对风控敏感，作为兜底方案即可。

---

## 3. 通用请求约定

- **Base URL**：`https://i.mi.com`（中国区；Global 区域名不同，本项目暂只支持中国区）
- **时间戳**：几乎所有 GET 带 `ts={unixMillis}`；多数接口另带 `_dc={同值}` 作缓存击穿参数
- **User-Agent**：Chrome/Edge 桌面 UA（XiaomiAlbumSyncer）；或 `Sec-Ch-Ua` 系列（MiCloud）。**不要**用默认 OkHttp UA
- **重定向**：`followRedirects(false)` / `CheckRedirect = ErrUseLastResponse`——重定向目标从 `Location` 头手动读取（签名直链、STS 链都依赖）
- **响应信封**：`{ "code": 0, "data": ..., "result": "ok", "description": ... }`，`code == 0` 为成功
- **已知错误码**：
  - `401` 系：会话失效（cookie 过期）
  - `50050`：文件已被删除（`/gallery/storage` 返回）
  - `403`：签名无效（仅签名链路）
- **防呆**：`folderId`（笔记）、`msgId`（查找设备）等字段可能**有时是 int 有时是 string**，解析需容忍两种
- **限频**：MiCloud 对笔记批量拉取做了 1–(len/5) 秒随机间隔的限速；自行实现时也建议加节流

---

## 4. 数据类型 × 端点目录

> 图例：✅ 已验证（至少一个参考项目实现并跑通）｜📄 有实现但建议复核｜⚠️ 仅逆向线索

### 4.1 相册 / 图片 / 视频 Gallery（✅ 双源验证）

**a) 相册列表**

```
GET /gallery/user/album/list?ts={ms}&pageNum={n}&pageSize=10&isShared=false&numOfThumbnails=1
```

响应 `data`：

```jsonc
{
  "albums": [
    { "albumId": 1, "name": "相机", "mediaCount": 1234, "lastUpdateTime": 1710000000000,
      "thumbnails": [{ "url": "...", "orientation": 0 }] }   // thumbnails 为 MiCloud 版本字段
  ],
  "isLastPage": true,
  "indexHash": "..."       // MiCloud 版本
}
```

特殊 albumId：`1` = 相机、`2` = 屏幕截图、`1000` = 私密相册（XiaomiAlbumSyncer 直接跳过）。分页以 `isLastPage` 判定。

**b) 相册内资产列表**

```
GET /gallery/user/galleries?ts={ms}&pageNum={n}&pageSize=200&albumId={albumId}[&startDate=yyyyMMdd&endDate=yyyyMMdd]
```

- `startDate/endDate` 可做**按天增量**（XiaomiAlbumSyncer 时间线增量用）
- 响应 `data.galleries[]`，核心字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 资产全局 ID（下载用） |
| `fileName` | 完整文件名（含扩展名） |
| `title` / `description` | 标题 / 描述 |
| `type` | 资产类型（image / video 等，大写后映射） |
| `mimeType` | MIME |
| `sha1` | **内容指纹（去重/校验关键）** |
| `size` | 字节数 |
| `dateTaken` / `createTime` / `dateModified` / `dateToken` / `sortTime` | 时间戳（毫秒） |
| `status` / `tag` / `groupId` | 状态 / 标签 / 分组 |
| `isFavorite` / `isFrontCamera` / `isUbiImage` | 布尔标记 |
| `geoInfo` | `{address, addressList, gps, isAccurate}`（GPS 信息） |
| `bigThumbnailInfo` / `thumbnailInfo` | `{data, isUrl}` 缩略图 |

分页：`isLastPage`；XiaomiAlbumSyncer 页大小 200，页满继续翻。

**c) 时间线（增量游标）**

```
GET /gallery/user/timeline?ts={ms}&albumId={albumId}
```

响应 `data`：

```jsonc
{
  "indexHash": "sha256(id:dateTaken:size:sha1; id:dateTaken:size:sha1; ...)",  // 内容指纹哈希
  "dayCount": { "20240701": 12, "20240702": 5 }                                // 每天资产数
}
```

**d) 下载**（详见 §5）

```
GET /gallery/storage?ts={ms}&id={assetId}          // XiaomiAlbumSyncer 写法
GET /gallery/storage?ts={ms}&id={assetId}&callBack=dl_img_cb_{ts}_0   // MiCloud 写法（显式回调名）
```

错误码 `50050` = 文件已删除。

**e) 删除**

```
GET /gallery/info/delete?id={id}&serviceToken={token}
```

### 4.2 录音 Recordings（✅ 双源验证）

**a) 录音列表**

```
GET /sfs/ns/recorder/dir/0/list?ts={ms}&_dc={ms}&offset={n}&limit=500
```

响应 `data.list[]`：

```jsonc
{
  "id": 123, "name": "录音_20240701_0930_1_2_3_0.m4a_1_0_1_3",
  "type": "...", "parent_id": 0,
  "size": 204800, "sha1": "...", "ver": 1,
  "create_time": 1710000000000, "modify_time": 1710000000000
}
```

`name` 是**编码过的**文件名，需用正则还原（XiaomiAlbumSyncer `parseXiaomiRecordingName`）：

```
正则：^(.+)\.([^._]+)_(\d+)_(\d+)_(\d+)_(\d+)$
例：  base.ext_typeCode_1_0_1_3  → 真实文件名 "base.ext" + 录音类型码 typeCode
```

（兼容写法：从右侧剥四段 `_数字` 尾缀。）

`typeCode` → 录音类型（XiaomiAlbumSyncer `RecordingType` 枚举）：

| 编码 | 类型 | 说明 |
| --- | --- | --- |
| `0` | RECORDER | 录音机录音 |
| `1` | PHONE_CALL | 通话录音 |
| `2` | FM | FM 录音 |
| `3` | APP | 应用录音 |
| `-1` | UNKNOWN | 未知/解析失败 |

分页判定：录音接口**没有 isLastPage**，以「返回条数 == limit」判断是否还有下一页。

**b) 下载**（两个可用变体，返回不同形态）：

```
变体 1（XiaomiAlbumSyncer，走 §5 通用 JSONP 流程）：
GET /sfs/ns/recorder/file/{id}/cb/dl_sfs_cb_{ts}_0/storage?ts={ms}
→ data.url（中转 URL）→ JSONP → POST → 文件

变体 2（MiCloud，直接返回 JSON url）：
GET /sfs/ns/recorder/file/{id}/storage/geturl?ts={ms}
→ { "url": "<可直接 GET 的文件地址>" }
```

变体 2 更简单，建议优先验证。

**c) 删除**

```
POST /sfs/ns/recorder/file/{id}/delete?ts={ms}
body: permanent=false&serviceToken={token}
```

### 4.3 笔记 Notes（✅ MiCloud 验证）

**a) 全量/增量笔记列表**

```
GET /note/full/page/?limit={n}&ts={ms}
```

响应：

```jsonc
{
  "entries":  [Note...],
  "folders":  [Folder...],
  "lastPage": true,
  "syncTag":  "..."        // 增量游标：下次请求用它继续
}
```

`Note` 含 `id`、`content`、`folderId`（**int 或 string 都可能**）、时间戳等；`Folder` 类似。

**b) 单条笔记**

```
GET /note/note/{id}/?ts={ms}
→ { "entry": Note }
```

**c) 笔记内图片 / 附件**

```
GET /file/full?type=note_img&fileid={fileId}
→ 302，Location 头 = 文件下载地址（手动取，不跟随）
```

**d) 删除 / 回收站**

```
POST /note/full/{id}/delete
body: tag={tag}&purge={true|false}&serviceToken={token}

GET /note/deleted/page?ts={ms}&_dc={ms}&limit={n}&syncTag={tag}   // 回收站
```

### 4.4 通讯录 Contacts（✅ MiCloud 验证）

```
GET /contacts/initdata?ts={ms}&_dc={ms}&syncTag=0&limit={n}&syncIgnoreTag=0
```

响应：

```jsonc
{
  "content": {
    "<contactId>": { "content": { ... }, "pinyin": "...", "type": "...",
                     "createTime": 1710000000000, "updateTime": 1710000000000 }
  },
  "letterIndex": { "A": ["<contactId>", ...], "B": [...], "#": [...] },
  "syncIgnoreTag": "...", "syncTag": "...", "lastPage": true
}
```

联系人 `content` 内层结构：

```jsonc
{
  "displayName": "张三",
  "id": "...",
  "name": { "formatted": "张三" },
  "phoneNumbers": [ { "type": 1, "value": "13800138000" } ],
  "starred": 0, "status": 0, "tag": "..."
}
```

增量：记录返回的 `syncTag`，下次请求带上（`syncTag=0` 为全量首拉）。

### 4.5 短信 SMS（✅ MiCloud 验证）

```
GET /sms/full/thread?ts={ms}&_dc={ms}&limit={n}&syncTag={tag}&syncThreadTag={tag}
    &readMode=older&withPhoneCall=false
```

响应：

```jsonc
{
  "entries": [ { "entry": Message, "operation": "..." } ],
  "watermark": { "syncTag": "...", "syncThreadTag": "..." }
}
```

`Message`：

```jsonc
{
  "folder": "inbox|sent|...", "id": "...", "lastUpdateTime": 1710000000000,
  "localTime": 1710000000000, "recipients": ["13800138000"], "snippet": "短信内容预览",
  "tag": "...", "threadId": "...", "total": 3, "unread": 0
}
```

回收站：`GET /sms/deleted/thread`（参数同）。完整短信正文可能需要逐条拉取（MiCloud 只实现了线程列表）。

### 4.6 其他有用接口（✅ MiCloud 验证）

| 用途 | 端点 | 说明 |
| --- | --- | --- |
| 登录 URL 获取 | `GET /api/user/login?...` | 见 §2.2 |
| 会话主动续期 | `GET /status/lite/setting?type=AutoRenewal&inactiveTime=10&ts={ms}` | 原 cookie 换新 serviceToken |
| 存储配额 | `GET /status/lite/alldetail?ts={ms}` | `totalQuota/used/usedDetail{GalleryImage,Recorder,AppList}` |
| 已登录设备 | `GET /passport/user/all/devices?locale=zh_CN?ts={ms}` | ⚠️ URL 里有两个 `?`（上游 bug），照抄即可 |
| 查找设备 | `GET /find/device/full/status?ts={ms}` | 设备定位/电量等（`locationReceiptList[].gpsInfo`） |

---

## 5. 文件下载机制（两步签名直链）

这是本项目**最容易踩坑**的环节。直接抓到的 `data.url` **不是**最终文件地址，而是带签名的中转页，必须走三步：

```
① 拿中转 URL
   GET https://i.mi.com/gallery/storage?ts={ms}&id={assetId}     （相册）
   GET https://i.mi.com/sfs/ns/recorder/file/{id}/cb/.../storage （录音，变体1）
   → { "code": 0, "data": { "url": "<中转URL>" } }
   （code=50050 ⇒ 文件已被删除，跳过并记录，勿反复重试）

② 请求中转 URL（带同款认证 cookie）
   → 响应是 JSONP： dl_callback({ "url": "<下载地址>", "meta": "<签名meta>" })
   解析：剥掉 "dl_callback(" 前缀与尾部 ")"，再 JSON.parse（XiaomiAlbumSyncer 有现成实现）

③ POST 下载地址
   POST <下载地址>
   body(form): meta={上一步的 meta}
   → 响应体 = 文件二进制流，流式写入磁盘
```

要点：

- **全程 `followRedirects(false)`**：任何一步的重定向都要手动处理，否则签名丢失。
- **cookie 要带到第 ② 步**（XiaomiAlbumSyncer 的 `fetchSignedUrlReq` 也带 `.ua()` 和认证头；第 ③ 步仅 POST meta，可带可不带 UA）。
- 录音变体 2（`/storage/geturl`）直接返回 JSON `{url}`，可省掉 JSONP 解析，但下载地址形态可能与变体 1 不同（自行验证）。
- 超时重试：XiaomiAlbumSyncer 对 `SocketTimeoutException` 无条件重试一次。
- 大文件：`ResponseBody.byteStream()` 流式 `copyTo(outputStream, 8192)`，避免 OOM。

---

## 6. 增量同步机制

三个数据面各有游标，可叠加使用：

### 6.1 资产级去重（所有类型通用）

本地数据库保存每资产的 `(remoteId, sha1, size, dateTaken)`。拉取清单后：

- `sha1 + size` 相同 → 已下载，跳过
- 不同 → 下载；下载完成后 **VerificationStage 再校验一次 sha1**（XiaomiAlbumSyncer 的校验阶段）
- 云端 `code=50050`（已删除）→ 标记本地删除，避免反复请求

### 6.2 按天增量（相册，XiaomiAlbumSyncer 的 timeline-diff 模式）

`/gallery/user/timeline` 返回 `dayCount`（每天多少张）与 `indexHash`（内容指纹）。XiaomiAlbumSyncer 的做法（`AssetService.refreshAssetsByDiffTimeline`）：

1. 每次运行把每个相册的 `AlbumTimeline`（`indexHash` + `dayCount`）**快照持久化**（`CrontabHistory.timelineSnapshot`）。
2. 与**上一次成功运行**的快照比较（`AlbumTimeline.minus`）：
   - `indexHash` 未变 → 空差异，跳过；
   - 否则逐日算 `dayCount` 差，只保留有差异的日期。
3. 对每个变更日并发拉取（信号量 10）：`gallery/user/galleries?albumId=...&startDate=yyyyMMdd&endDate=yyyyMMdd`，UPSERT 资产。
4. `indexHash` 变化但 `dayCount` 未变（同日数换内容）→ 该相册按变更日重拉。

**回退规则**（`checkTimelineDiffUsable`）：以下任一条件不满足则**静默回退全量刷新**——
- 任务包含录音（`remoteId=-1`，录音无 timeline）；
- 相册集合（remoteId 列表）与上次不同；
- 无上次快照。

**资产完成判定**：某资产已有该任务的历史记录且四个标志全 true 才算已处理：
`downloadCompleted && sha1Verified && exifFilled && fsTimeUpdated`（对只下载场景，前两个即关键）。

**并发配置参考**（`CrontabConfig`）：downloaders=8、verifiers=2、exifProcessors=2、fileTimeWorkers=2；流水线用 `channelFlow + flatMapMerge`。

### 6.3 syncTag 游标（笔记 / 通讯录 / 短信）

- 笔记：`/note/full/page/` 返回 `syncTag`，下次带上；`lastPage=false` 时继续翻页
- 通讯录：`/contacts/initdata` 的 `syncTag`（首拉 `0`）
- 短信：`/sms/full/thread` 的 `watermark.syncTag / syncThreadTag`

### 6.4 相册列表刷新（shadow 标记法）

XiaomiAlbumSyncer `AlbumsService`：先把本地全部相册标记 `shadow=true`，用远端列表 upsert（新插入 / 更新名称数量），最后保留 `shadow=true` 的就是云端已删除的相册。

### 6.5 下载流水线参考（XiaomiAlbumSyncer 的 pipeline 设计）

`server/.../pipeline/stages/` 下的阶段链（`CrontabPipeline` 按序执行），每个阶段对一条「资产 × 任务」记录操作：

1. **DownloadStage**（`DownloadStage.kt`）：下载到目标文件旁的临时文件 `{fileName}.{detailId}.tmp` → 成功后将临时文件 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 原子改名为目标文件（不支持原子移动的文件系统回退普通 move）；失败清理临时文件。若任务配置 `skipExistingFile` 且目标文件已存在则直接跳过。完成后落库 `downloadCompleted = true`。
2. **VerificationStage**：校验已下载文件（sha1 与云端清单比对），失败则标记重下。
3. **FileTimeStage / ExifProcessingStage**：把云端时间写入文件系统时间与 Exif 元数据（照片/视频）。

参考要点：临时文件 + 原子改名是「下载中断不产生半成品」的标准做法；校验放在下载之后单独成阶段，便于失败重试。

### 6.6 分页判定速查

| 接口 | 判据 |
| --- | --- |
| `/gallery/user/album/list` | `data.isLastPage` |
| `/gallery/user/galleries` | `data.isLastPage` |
| `/sfs/ns/recorder/dir/0/list` | 返回条数 == limit（无 isLastPage） |
| `/note/full/page/` | `lastPage` |
| `/contacts/initdata` | `lastPage` |

---

## 7. 上传方向：现状与思路

> ⚠️ 三个参考项目**均未实现上传**（只有下载与删除）。以下为**逆向线索与推断**，需实测。

- **相册上传**：小米云相册 Web 端上传走分片上传 + 回调确认（类似 `POST /gallery/upload` 系 + 分片合并接口），并需按 §8 的签名/校验体系构造。建议：用浏览器开发者工具（Network 面板）录制一次「Web 端上传照片」的完整请求序列（含 form 字段、分片大小、完成回调、最终元数据提交），作为第一手依据。
- **笔记同步**：笔记存在 `/note/...` 系同步接口（`full/page` 已证明可读），写入方向大概率有 `PUT/POST /note/...`（如 `/note/update`、`/note/create`），同样需抓包确认。
- **通讯录 / 短信上传**：`/contacts/initdata` 的写入对称接口（`/contacts/...sync`）与短信发送同步接口均未见公开实现，列为后续逆向任务。
- **通用建议**：上传接口的抓包记录（HAR）请保存到 `research/` 目录，作为开发依据；抓包时注意清除无关流量、脱敏个人信息。

---

## 8. Mi Home 签名 API（仅参考）

本项目**不需要** Mi Home（智能家居）接口，但 mi-service-lite 的签名实现是理解小米私有 API 的钥匙，简述以备将来：

- 仅 `api.io.mi.com/app/*` 需要签名；`mina.mi.com` 与 `i.mi.com` 均不需要。
- 签名流程（`src/utils/codec.ts`、`rc4.ts`、`hash.ts`）：

```
nonce   = 12 随机字节 base64
snonce  = base64( SHA256( base64decode(ssecurity) ‖ base64decode(nonce) ) )
RC4 密钥 = snonce 的原始字节；先用密钥流冲刷 1024 字节
map = { data: JSON字符串 }
map.rc4_hash__ = sha1(method&uri&k=v...&ssecurity)      // 明文 data 的哈希（base64）
对 map 每个值 RC4 加密再 base64
map.signature = sha1(method&uri&加密后的k=v...&ssecurity)
请求带 data / rc4_hash__ / signature / _nonce / ssecurity（明文！）
响应解密：同密钥 + 冲刷 1024 字节，若 miot-content-encoding: GZIP 则解 gzip
```

若将来要做「通过小米账号控制米家设备」可参考；做云备份同步用不到。

---

## 9. Android 实现要点

### 9.1 登录与 Cookie 提取

- **首选 WebView**：`WebView` 加载 `https://i.mi.com/`（可先跳 `https://i.mi.com/gallery/h5#/` 触发完整会话），用户完成登录（含短信验证等）后，用 `CookieManager.getInstance().getCookie("https://i.mi.com")` 一次性取出全部 cookie，解析 `userId` / `passToken` / `serviceToken`。
  - `CookieManager.setAcceptCookie(true)`；Android 12+ 需 `WebView` 可用（模拟器/真机均正常）。
  - 或 `WebViewClient.onPageFinished` + 轮询判断是否已登录（检测 cookie 中 `serviceToken` 存在）。
- **备选 Custom Tab**：体验更好，但 cookie 无法跨进程读取（除非自有域名回跳 + 同域，本项目不适用），故不推荐。
- 登录后**立即清除 WebView 缓存/数据**（`CookieManager.removeAllCookies`），避免留下会话。
- 换取 `serviceToken`（§2.2 链路）时需**自行生成 `deviceId = "wb_" + UUID.randomUUID()`**——`wb_` 前缀是 Web 设备标记，服务端强校验（XiaomiAlbumSyncer 每次刷新都用新随机 deviceId）。

### 9.2 Cookie 安全存储

- 凭证属于高敏数据：`androidx.security:security-crypto` 的 `EncryptedSharedPreferences`（Keystore 主密钥），或 `Keystore` 加密后存 `DataStore`。
- 明文 `serviceToken` 禁止落日志；`minify` 时按官方规则保留。
- **serviceToken 生命周期设计（对齐 XiaomiAlbumSyncer）**：内存缓存 + **10 分钟强制刷新**（重跑 §2.2 三步链）；任意请求 401 → 立即刷新并重发一次；刷新失败给出明确 UI 提示（提示用户回浏览器检查登录态），不要静默无限重试。

### 9.3 网络层

- OkHttp；全局 `followRedirects(false)`（除登录跳转外）；每个请求注入 `Cookie: userId=...; serviceToken=...;` + Chrome UA。
- 统一封装 `MiCloudClient`：自动加 `ts`/`_dc`、JSONP 解析器、401 拦截（触发重新登录/升级链）、`code!=0` 异常映射。
- JSONP 解析：读字符流，跳过 `(` 前内容，`JSON.parse` 剩余。

### 9.4 下载

- 流式写入（8KB buffer）；先写 `.part` 临时文件，sha1 校验通过后原子重命名。
- 断点续传：`Range` 头请求 + 本地已写字节数（注意签名 URL 短时效，续传需重新走 §5 流程）。
- 大任务用 `WorkManager`（`CoroutineWorker` + `setForeground`）或前台服务（`dataSync` 类型），避免进程被杀。

### 9.5 数据模型（Room）

```kotlin
@Entity data class RemoteAsset(
  @PrimaryKey val remoteId: Long,      // 云端 id
  val albumId: Long, val fileName: String, val type: String,
  val sha1: String, val size: Long, val dateTaken: Long,
  val downloaded: Boolean, val localPath: String?
)
@Entity data class SyncCursor(
  val dataType: String,                 // gallery / recording / note / contact / sms
  val cursor: String?                   // syncTag / 日期游标
)
```

### 9.6 权限

- 仅需网络权限（`INTERNET`）+ 存储（Android 13+ 用 `MediaStore` 或 SAF 目录，避免 `READ/WRITE_EXTERNAL_STORAGE` 大权限）。
- 通讯录/短信**写入本地**用文件/数据库即可，不需要系统通讯录权限（本工具是「云端 ↔ 本地文件」备份，不是「云端 ↔ 系统通讯录」同步）。

---

## 10. 风险与注意事项

1. **非官方接口**：无 SLA、无文档，字段与路径可能随时调整；开发期建议先用自己账号小流量验证。
2. **账号风控**：高频请求、异常 UA、新设备登录都可能触发验证或封禁。建议：限速（参考 MiCloud 的随机 sleep）、低频同步、异常时降级为手动模式。
3. **cookie 泄露风险**：`serviceToken` 可长期访问用户云端数据，务必加密存储、随包不落明文、日志脱敏。
4. **地区差异**：本文档全部基于中国区 `i.mi.com`；Global（`i.global.mi.com`）接口与域名不同，暂不支持。
5. **删除/覆盖操作**：先做「只下载」模式，删除/双向同步等破坏性功能等验证充分后再开放。
6. **合规**：仅供个人备份与研究；勿用于数据倒卖、批量爬取等用途。

---

*文档生成日期：2026-07 ｜ 依据源码版本：XiaomiAlbumSyncer main（v0.17 系）、mi-service-lite v3.1.0、MiCloud main（2025-02 提交）*
