# CONTEXT.md — 领域语言与决策记录

> 本项目的事实来源。术语以这里为准；改动请先读此文件。
> 完整 API 调研见 `docs/XIAOMI-CLOUD-API.md`。

## 领域词汇表

| 术语 | 含义 |
| --- | --- |
| **凭证** | `XiaomiCredential` 密封类型两种形态：**PassToken**（浏览器 passToken，需换取链，可自动刷新）与 **ServiceToken**（浏览器 serviceToken，直连会话，跳过换取链，不可自动刷新） |
| **serviceToken** | 小米云请求链使用的会话令牌（`SessionToken`）。有效期极短（~1h），passToken 凭证每 10 分钟刷新；直连会话 401 即需重新登录 |
| **换取链** | 三步换取 serviceToken 的接口序列：`/api/user/login` → 302 Location → `Set-Cookie: serviceToken`（`XiaomiAuthService.exchange`） |
| **deviceId** | `"wb_" + UUID`，Web 设备标记，服务端强校验；每次刷新换新 |
| **签名直链** | 下载流程：`storage` 拿中转 URL → JSONP `cb({url,meta})` → POST url + meta → 文件流（M3 使用） |
| **数据模块** | 一个云端数据类型（相册/录音/通讯录/笔记/短信）+ 对应 API 类 + 本地表 + 同步策略 |
| **同步游标** | 云端增量依据：相册 `indexHash/dayCount`、笔记/通讯录/短信 `syncTag`、资产 `sha1+size` |

## 决策记录（ADR）

### ADR-001 认证方式：手动粘贴为主，WebView 增强
用户决策（2026-08）。第一迭代实现「粘贴整段 Cookie 自动解析 / 手动填写」；**WebView 内嵌登录为第二迭代（M2.1，已实现）**：内嵌浏览器登录 i.mi.com，自动检测登录态并跳相册页触发设备验证，提取 Cookie 后复用同一换取链。WebView 会话在提取后立即清除（`CookieManager.removeAllCookies`），凭证只经内存传递。
风险（实测待验证）：小米登录页可能通过 UA 指纹 / 验证码交互阻止 WebView 会话——需真机验证。

### ADR-002 serviceToken 生命周期：10 分钟强制刷新 + 401 重发一次
对齐 XiaomiAlbumSyncer `TokenManager`（内存缓存 + 10 分钟）与 MiCloud（401 自动重登）。刷新失败不静默重试，UI 提示重新配置。
**补充（真机实测 2026-08）**：多数用户的 i.mi.com Cookie 只有 serviceToken（无 passToken，需设备信任才下发）——因此支持 **ServiceToken 直连模式**：直接使用浏览器会话、跳过换取链；代价是不可自动刷新，401 时提示重新登录（见 `XiaomiCredential`）。

### ADR-003 测试策略：MockWebServer，不碰真实凭证
所有认证链/网络逻辑用 MockWebServer 模拟三步换取链验证；真实端到端由用户自行在真机验证。凭证绝不进调试环境。

### ADR-004 网络约定：全局禁重定向 + 桌面 Chrome UA
OkHttp `followRedirects(false)` / `followSslRedirects(false)`（签名直链与换取链均依赖手动处理 302）；UA 用桌面 Chrome/Edge 串（小米对非浏览器 UA 敏感）。

### ADR-005 凭证存储：EncryptedSharedPreferences（Keystore）
security-crypto **1.0.0 稳定版**，`MasterKeys.getOrCreate(AES256_GCM_SPEC)` + 键 AES-SIV / 值 AES-GCM。（注意：`MasterKey` 类仅存在于 1.1.0-alpha+，1.0.0 用 `MasterKeys`。）

### ADR-006 数据访问：统一 XiaomiApiClient
M3 起所有数据模块经 `XiaomiApiClient` 访问（自动注入 Cookie + 401 刷新重发），不各自散落认证逻辑。

## 验证命令

```bash
./gradlew :app:testDebugUnitTest   # 单元测试（MockWebServer 模拟小米服务）
./gradlew :app:assembleDebug       # 编译 APK
```
