# CyclingSync · 骑行数据同步

原生 Android 应用，配置三个骑行平台的账号后，一键把骑行数据互相同步。

**同步逻辑全部在设备内实现** —— 账号密码不出设备，不依赖任何服务器，无服务端成本。

## 下载

**Releases 页面直接下载 APK**：

> https://github.com/wulibaibao/cycling-sync-app/releases/latest

当前版本 `v1.0.0` —— Android 7.0+ 可直接安装，正式签名。

### 正式签名

构建 release 时用专属密钥库签名（密钥库路径与口令在 `local.properties`，不进版本库）：

```properties
release.keystore.path=/path/to/cycling-sync-release.jks
release.keystore.storePassword=...
release.keystore.keyAlias=cyclingsync
release.keystore.keyPassword=...
```

未配置时退回 debug 签名，保证本地始终能构建。

## 支持平台

| 平台 | 登录方式 | 作为数据源 | 作为目标 |
|---|---|---|---|
| 顽鹿 OneLap | 账号 + 密码 | ✅ 主源（原始码表 FIT，含功率/踏频） | ✅ |
| IGPSPORT | 手机 + 短信验证码 / 密码 | ✅（含 FIT 下载） | ✅ |
| 行者 XOSS | 账号 + 密码（RSA 加密 + sessionid） | ❌ | ✅ |

## 支持链路

- 顽鹿 → 行者
- 顽鹿 → IGPSPORT
- IGPSPORT → 行者
- IGPSPORT → 顽鹿

## 关键设计

### 顽鹿是主源

顽鹿的 FIT 文件是**码表原始文件**（含逐点功率、踏频、温度、坡度）。
所以「顽鹿 → X」能保留完整数据；反方向「IGPSPORT → 顽鹿」会丢失逐点功率。

### 三层幂等判重

重复同步不会产生重复记录：

| 层 | 机制 |
|---|---|
| 1. 本地同步历史 | `filesDir/sync_history.json`，只记录成功上传，最多 500 条 |
| 2. 服务端指纹 | 开始时间 ±15 秒、距离 ±`max(30m, 0.5%)`（时区固定 `Asia/Shanghai`） |
| 3. md5 辅助 | 内容指纹作为补充键 |

**失败不写本地历史** —— 失败后可以安全重试，不会漏传。

### 平台签名密钥来自构建配置

顽鹿 `ONELAP_SIGN_KEY` 与 IGPSPORT `IGP_HMAC_KEY` 是**第三方平台的静态签名常量**，
不是用户账号凭据，但属于第三方私有凭据，因此**不进版本库**。

构建时从 `local.properties` 读取（该文件已被 `.gitignore` 排除）：

```properties
sdk.dir=/path/to/android-sdk

# 第三方平台静态签名密钥
igp.hmac.key=...
onelap.sign.key=...
```

未配置时用占位符，构建能通过，但平台签名会失败。

Gradle 会把这两个值生成为 Kotlin 源文件 `PlatformKeysGenerated.kt`
（位于 `build/generated/source/keys/`，不进版本库），供 `Crypto.kt` 使用。

### OSS 上传为什么不用 HttpURLConnection

IGPSPORT 上传走 OSS 预签名 URL，而**预签名把 Content-Type 算进了签名** ——
客户端多一个 `Content-Type` 头就是 `403 SignatureDoesNotMatch`。

Android 的 `HttpURLConnection`（底层 OkHttp）在请求带 body 时会**自动补**
`Content-Type: application/x-www-form-urlencoded`（见 AOSP `HttpURLConnectionImpl`：
`if (headers.get("Content-Type") == null)`），空字符串能否挡住在不同 Android 版本上行为不一致。

因此 OSS PUT 走 `RawHttp.kt` —— 用 `Socket` 手写 HTTP/1.1 + TLS，header 完全可控，
只发 `Host` / `User-Agent` / `Accept` / `Content-Length`。

`OssSigningRuleTest` 会锁住这条规则：OSS PUT 必须走 `RawHttp`，且 `RawHttp` 里不能出现 `Content-Type`。

### 凭据存储

- AES-256-GCM 自持密钥（密钥存 Android Keystore）
- 用户凭据不出设备，不上传服务器
- 登录后界面只显示账号信息，不显示密码 / token / sessionid

## 构建

```bash
cd cycling-sync-app
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"

gradle assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk

gradle testDebugUnitTest --no-daemon
```

工具链安装见 skill `android-build`。

### 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.10.2 |
| AGP | 8.7.3 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| Compose | material3 1.4.0 / ui 1.7.6 |

## 项目结构

```
app/src/main/java/com/wulibaibao/cyclicsync/
├── MainActivity.kt              # Activity、导航、登录状态恢复
├── platform/
│   ├── Crypto.kt                # 签名算法（密钥来自生成的 PlatformKeysGenerated）
│   ├── Http.kt                  # 极简 HTTP + multipart
│   ├── RawHttp.kt               # 手写 HTTP/1.1（OSS 预签名上传专用）
│   ├── OneLapClient.kt          # 顽鹿：登录/列表/详情/FIT 下载/上传
│   ├── IgpClient.kt             # IGPSPORT：短信登录/刷新/列表/FIT 下载/三步 OSS 上传
│   └── XingzheClient.kt         # 行者：RSA 密码登录 + sessionid + 上传
├── data/
│   ├── CredentialStore.kt       # AES-GCM 凭据存储
│   ├── Dedup.kt                 # 时间/距离指纹索引
│   ├── SyncHistory.kt           # 本地同步历史
│   ├── SyncEngine.kt            # 同步引擎（链路编排 + 三层判重）
│   └── UiPrefs.kt               # 同步方向偏好
└── ui/
    ├── PlatformScreens.kt       # Compose UI：同步页 / 平台页 / 日志页
    └── Theme.kt                 # Material 3 Expressive 主题
```

## 测试

```bash
gradle testDebugUnitTest --no-daemon
```

| 测试 | 覆盖 |
|---|---|
| `SignatureTest` | 固定签名的 Kotlin/Python 一致性（基准在 `signature_baseline.json`） |
| `XingzheCryptoTest` | 行者 RSA 公钥 DER 解析（含长格式长度字段） |
| `DedupTest` | 时间/距离指纹判重容差、时区固定 |
| `OssSigningRuleTest` | OSS PUT 必须走 RawHttp、不得发送 Content-Type |

## 已知限制

1. **IGPSPORT → 顽鹿 丢失逐点功率** —— 反方向上传的是 IGPSPORT 侧的 FIT，
   不含顽鹿码表的逐点功率/踏频原始数据。
2. **判重依赖平台列表的返回量** —— 目标平台历史活动拉取有上限，
   很久以前的记录可能被判为「未同步」而重复上传。
3. **无真机自动化测试** —— 本机无模拟器，登录与上传行为需真机验证。

## License

MIT
