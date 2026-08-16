# 米饭云服务（MifansCloud）

> 面向 **没有 MI SDK 的第三方 Android 设备** 的小米云服务（i.mi.com）备份 / 同步工具。

本项目目标是：让非小米（或未内置小米云服务的）Android 设备，能够直接对接小米云服务 Web 端 `i.mi.com` 的底层接口，实现云端数据的**双向备份与同步**，覆盖（但不限于）：

- 📸 相册 / 图片 / 视频（Gallery）
- 📞 通讯录（Contacts）
- 📝 笔记（Notes）
- 🎙️ 录音（Recordings）
- 💬 短信（SMS）
- …（按需扩展：设备查找、存储配额等）

> ⚠️ 重要声明：小米云服务**没有公开、官方的 API**。本工具所依据的全部接口均为社区逆向所得（非官方），存在随时变更或触发账号风控的风险。请自行评估合规性与账号安全风险，谨慎使用。

---

## 当前项目状态

当前仓库是 **全新的 Android Studio 模板工程**（尚未开发业务代码）：

| 项 | 值 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose (Material 3) |
| minSdk / targetSdk | 31 / 37 |
| AGP / Kotlin | 9.3.1 / 2.2.10 |
| 包名 | `com.monishguy.mifanscloud` |
| 应用名 | 米饭云服务 |

**已完成的工作**：针对三个参考项目的源码级 API 调研，结论沉淀于
[`docs/XIAOMI-CLOUD-API.md`](docs/XIAOMI-CLOUD-API.md)（核心交付物），
原始调研材料（克隆仓库 + 报告）保留在 [`research/xiaomi-cloud/`](research/xiaomi-cloud/)。

---

## 参考项目（调研来源）

| 项目 | 语言 | 价值点 | 与本项目的关系 |
| --- | --- | --- | --- |
| [XiaomiAlbumSyncer](https://github.com/Coooolfan/XiaomiAlbumSyncer) | Kotlin / JVM | 功能完整的相册+录音下载器：Cookie 认证、增量下载、定时任务、Web UI、Exif 处理、多账号 | **最值得借鉴**：下载链路、增量同步、cookie 刷新可直接对齐其实现 |
| [mi-service-lite](https://github.com/idootop/mi-service-lite)（npm） | TypeScript | 演示了小米 passport 密码登录 + Mi Home 签名（RC4/HMAC）API 的底层调用方式 | 借鉴其**登录流程**与**签名算法**理解（本项目不依赖 Mi Home） |
| [MiCloud](https://github.com/CLOUDERHEM/MiCloud) | Go | 纯 Cookie 认证的 `i.mi.com` 全套封装：相册/笔记/录音/通讯录/SMS/设备/配额 | 端点目录最全：**本项目各数据类型的接口清单主要依据它** |

三个项目的源码均已克隆到 `research/xiaomi-cloud/` 下，供开发时直接查阅。

---

## 核心调研结论（摘要）

详见 [`docs/XIAOMI-CLOUD-API.md`](docs/XIAOMI-CLOUD-API.md)，这里摘录关键事实：

1. **认证模型**：`i.mi.com` 采用 **Cookie 认证**（无需签名）。
   - 最简方案（XiaomiAlbumSyncer 做法）：用户在浏览器登录 i.mi.com 后，复制 `userId` 与 `passToken`（即 `serviceToken`），请求时发送 `Cookie: userId=...; serviceToken=...;`。
   - 升级方案（MiCloud 做法）：粘贴整段浏览器 Cookie，通过 `GET i.mi.com/api/user/login` → 302 → STS URL 链自动换取 `serviceToken` cookie（**双源验证**：MiCloud 与 XiaomiAlbumSyncer 的 `TokenManager` 实现同一链路）。
   - 密码登录（mi-service-lite 做法）：`account.xiaomi.com/pass/serviceLoginAuth2` + `MD5(密码).toUpperCase()`，可拿到 `ssecurity/passToken/serviceToken`，但**容易触发异地登录安全验证**，移动端不推荐首用。
   - ⚠️ **serviceToken 有效期极短**：需每 10 分钟强制刷新（重跑换取链，`deviceId = "wb_" + UUID` 前缀是 Web 设备标记）；401 时刷新重发一次。
2. **数据接口**：全部为 `https://i.mi.com` 下的 GET/POST，带 `ts=<毫秒>` 时间戳（部分带 `_dc` 防缓存）。
   - 相册：`/gallery/user/album/list`、`/gallery/user/galleries`、`/gallery/user/timeline`、`/gallery/storage?id=`（下载）。
   - 录音：`/sfs/ns/recorder/dir/0/list`、`/sfs/ns/recorder/file/{id}/storage/geturl`。
   - 笔记：`/note/full/page/`、`/note/note/{id}/`、`/file/full?type=note_img&fileid=`。
   - 通讯录：`/contacts/initdata`。
   - 短信：`/sms/full/thread`。
3. **下载机制**：接口返回的不是直链，而是一个**签名中转 URL**（JSONP `cb({url, meta})`），需携带 `meta` 表单参数 POST 到 `url` 才拿到真实文件流——两步签名直链流程，禁止跟随重定向。
4. **增量同步**：以资产元数据（`sha1` / `dateTaken` / `size`）+ 本地数据库去重为基础；相册/笔记/短信另有 `syncTag` / `indexHash` / `dayCount` 等云端增量游标。
5. **上传现状**：三个参考项目**均未实现上传**（只有下载/删除）。上传接口需要后续自行抓包逆向（详见 API 文档的「上传可行性」一节）。

---

## 架构草案（规划）

```
app/
├── data/
│   ├── auth/                # 小米凭证：WebView 登录 / Cookie 提取 / 加密存储 / 自动续期
│   ├── remote/              # i.mi.com REST 客户端（OkHttp + CookieJar + ts 参数）
│   │   ├── GalleryApi.kt    # 相册/资产/时间线/下载
│   │   ├── RecordingApi.kt  # 录音列表/下载
│   │   ├── NoteApi.kt       # 笔记列表/详情/图片
│   │   ├── ContactApi.kt    # 通讯录
│   │   └── SmsApi.kt        # 短信
│   ├── local/               # 本地数据库（Room）：资产清单、去重指纹、同步游标
│   └── sync/                # 增量同步引擎：拉取清单 → 比对 → 下载 → 校验（sha1）
├── ui/                      # Compose 界面：登录页、数据源页、同步任务页、设置页
└── worker/                  # WorkManager 定时同步
```

设计原则：

- **认证先行**：先实现「WebView 登录 i.mi.com → CookieManager 提取 cookie → 加密存储」闭环，这是所有数据模块的前置依赖。
- **数据模块可插拔**：每个数据类型独立一个 API 类 + 本地表 + 同步策略，先做相册（参考最充分），再逐个扩展。
- **安全**：Cookie 属于高敏凭证，使用 Android Keystore + EncryptedSharedPreferences 存储；网络全 HTTPS；不读写非必要权限。

---

## 开发路线图

- [x] **M1 调研**：三个参考项目源码级 API 调研 → `docs/XIAOMI-CLOUD-API.md`
- [x] **M2 认证闭环**：凭证配置（粘贴 Cookie/手动）、Keystore 加密存储、serviceToken 10 分钟刷新、401 重发、Compose 登录/状态页 ✅ 已交付（2026-08）
- [x] **M2.1 WebView 内嵌登录**：内嵌浏览器登录 i.mi.com，自动检测登录态、跳相册页触发设备验证、提取 Cookie ✅ 已交付（真机验证；该 ROM WebView 白屏为渲染问题，手动粘贴路径不受影响）
- [x] **M2.2 serviceToken 直连模式**：无 passToken 的已登录 Cookie 直接可用（跳过换取链）✅ 已交付（真机端到端验证通过）
- [x] **M3 相册智能同步**：纯缩略图浏览（清单自带 URL/base64 缩略图）、本机两级匹配（dateTaken+size / sha1）、徽标区分「本机已有/云端新增/已下载」、按需下载原图到 SAF 备份文件夹 ✅ 已交付（69 单元测试全绿）
- [x] **M4 录音同步**：列表（offset/limit 分页）+ 编码文件名还原（类型码：录音机/通话/FM/应用）+ 按需下载 ✅ 已交付
- [x] **M5 通讯录 / 笔记 / 短信**：清单拉取（syncTag 增量）+ JSON 导出到备份文件夹 ✅ 已交付
- [ ] **M6 上传方向**：抓包逆向上传接口（相册上传、笔记同步）
- [ ] **M7 定时同步**：WorkManager + 前台服务（Android 12+ 限制）
- [ ] **M8 双向冲突处理 / 删除同步**（按需）

## 构建与测试

```bash
./gradlew :app:testDebugUnitTest   # 单元测试（69 个，MockWebServer 模拟小米接口）
./gradlew :app:assembleDebug       # 编译 debug APK
```

认证与全部数据模块逻辑均经本地 mock 验证（不依赖真实账号）；真机安装后由用户填入自己的 i.mi.com Cookie 做端到端验证（已通过：粘贴 Cookie → 主页 → 相册浏览/下载）。

---

## 文档索引

| 文档 | 说明 |
| --- | --- |
| [`CONTEXT.md`](CONTEXT.md) | 领域词汇表 + 决策记录（ADR-001~006），开发前先读 |
| [`docs/XIAOMI-CLOUD-API.md`](docs/XIAOMI-CLOUD-API.md) | **小米云 API 调研文档**：认证、端点目录、下载/上传机制、增量同步、Android 实现要点（核心文档） |
| [`xiaomi-album-syncer-research/XiaomiAlbumSyncer-xiaomi-cloud-technical-map.md`](xiaomi-album-syncer-research/XiaomiAlbumSyncer-xiaomi-cloud-technical-map.md) | XiaomiAlbumSyncer 专项技术地图（子代理产出）：TokenManager 换取链、全部端点、增量模式、调度/通知/Exif 等完整实现剖析 |
| `research/xiaomi-cloud/xiaomi-cloud-api-clients.md` | mi-service-lite 与 MiCloud 的源码级调研报告（子代理产出，含逐文件引用） |
| `research/xiaomi-cloud/` | 三个参考项目的完整克隆（开发时可直接查阅源码） |

---

## 免责声明

本项目与小米公司无关，未获得任何官方授权。所有接口均为非官方逆向接口，可能随时失效；使用本项目导致的账号限制、数据丢失或其他风险，由使用者自行承担。本项目仅供学习研究使用。
