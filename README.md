# magpie-usage-mobile

Android 上的 magpie 网页控制面板壳。

启动时填写一次 magpie 网页地址，App 会用 WebView 原样打开控制面板。Usage、Routing、Providers、登录和手机布局都使用 magpie 网页端，不在 App 里重写。

- 平台：Android 7.0+（minSdk 24）
- 分发：GitHub Release APK
- 技术栈：Kotlin + XML + WebView
- 构建工具：JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3
- 状态：阶段 1–3 代码配置已完成；推送 main 后执行线上编译与单测，签名 Secret 配置后再打 Release tag

## 使用方式

1. 从 GitHub Release 下载并安装 APK。
2. 输入控制面板地址，例如 `http://192.168.31.60:3430`。
3. 打开 magpie 网页原生登录页，粘贴网页 key。
4. 也可直接粘贴带 `?k=` 的完整链接；网页会自动兑换登录 cookie，首次完成后 App 切换到干净的 `/?view=usage` 并清理历史。

App 只持久化地址 origin，不解析、保存或输出 `k` 的值。Cookie 由 WebView 管理，正常重启不会掉登录。

- 右上角「更改地址」只切换地址，默认不清 Cookie/缓存。
- 右上角「清除数据」会确认后清除当前 WebView 的 Cookie、cache、表单历史、DOM Storage，并回到 `about:blank` 地址页；**已保存的 origin 保留**，再次打开即可重新登录。
- CSV 等附件通过系统 `DownloadManager` 保存到公共 Downloads；仅当下载地址与当前面板同源时，显式转发 HttpOnly 登录 Cookie，避免把控制面板凭据发给其它站点。Android 9+ 不申请存储权限；Android 7/8 仅在首次实际下载时按需申请旧版 `WRITE_EXTERNAL_STORAGE`，不申请全部文件访问。
- 主页面断网等错误提供「重试」和「更改地址」，HTTP 401 原生登录页不会误报为网络错误。

> 这里填的是网页控制面板端口，不是 gateway 端口。magpie 网页默认 `3430`，gateway 默认 `3425`。

## 导航策略

App 保留控制面板的 origin 信任边界，但外链不会被静默丢弃：

- 顶层 HTTP(S) 导航只允许已保存的 panel origin；跨 origin 自动跳转仍被阻止，防止登录会话被带到其它站点。
- 用户打开的 HTTP(S) 新窗口在当前 WebView 加载。
- `mailto:`、`tel:`、`intent:`、`geo:` 等其它 scheme 通过 `ACTION_VIEW` 交系统处理；没有对应应用时显示提示。

`window.open` 与 `shouldOverrideUrlLoading` 共用 `NavigationPolicy`，避免两套外链策略漂移。

## 安装 APK

1. 打开设备的「设置 → 安全/隐私 → 安装未知应用」，允许当前浏览器或文件管理器安装来源。
2. 打开 Release 中的 APK；如 Android 提示签名/版本冲突，先卸载旧包（会删除旧 App 数据），再安装新包。
3. 首次输入 panel 地址并登录。正常覆盖安装会保留 WebView Cookie；若手工卸载重装则不会保留。

Release 资产名固定为：

```text
magpie-usage-vX.Y.Z.apk
```

其中 `X.Y.Z` 必须与 `app/build.gradle.kts` 的 `versionName` 完全一致。

## 构建

仓库已提交 Gradle Wrapper（Gradle 8.9，wrapper JAR SHA-256：
`498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`）。
Wrapper properties 固定了 Gradle 8.9 distribution URL 和 SHA-256。

本机需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。设置 `ANDROID_HOME`，或安装 SDK 后创建 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Release 构建必须配置签名。任何会产出 release 包的任务（包括 `assembleRelease`、`packageRelease`、`bundleRelease`、`assemble` 或 `build`）都会检查以下环境变量，缺任一项都会明确失败，不会产出静默未签名 APK；debug、configuration 和 JVM 单测任务无需这些 Secret：

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

示例（本地 shell；不要把密码提交或写进命令行历史）：

```bash
export ANDROID_KEYSTORE_PATH=/secure/path/release.jks
# 通过安全的交互式方式设置其余三个环境变量
./gradlew --no-daemon :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。可用 Android SDK Build Tools 验证：

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

本次实现按要求只做静态检查，未运行 `assemble`、`build` 或 Gradle 单元测试，也没有产出 APK。

## 首次配置 GitHub Actions 签名 Secret

在仓库 **Settings → Secrets and variables → Actions → New repository secret** 创建四个 Secret：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 文件的 Base64 文本 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 私钥 alias |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

Base64 可在本机生成并复制到 Secret：

```bash
base64 < /secure/path/release.jks | pbcopy
```

不要把 keystore、Base64 内容、密码或 `signing.properties` 提交进仓库。工作流只申请 `contents: write`，keystore 解码、Gradle 签名和删除在同一个步骤中完成；EXIT trap 覆盖成功、失败、取消和超时，签名后不在 runner 上跨步骤保留密钥。密码仅通过步骤环境变量传入 Gradle，不写入命令行或 `GITHUB_ENV`。

## Tag 发布

当前版本为 `0.1.0`。发布流程：

1. 更新 `app/build.gradle.kts` 的 `versionName`（如有需要也更新 `versionCode`）。
2. 提交代码。
3. 创建并推送语义化 tag，例如：

```bash
git tag v0.1.0
git push origin v0.1.0
```

CI 会读取 tag 和 Gradle 文件并校验：`vX.Y.Z` 必须映射到 `versionName = "X.Y.Z"`，且 checkout commit 必须与 tag 指向一致。此规则不硬编码旧 tag。

## Actions 构建

工作流：

- `.github/workflows/android-check.yml`：推送 main 或 PR 时安装 JDK 17 / SDK 35，运行 `:app:testDebugUnitTest :app:assembleDebug`，并把 debug APK 作为 CI 验证产物上传 14 天。它不生成 Release。
- `.github/workflows/android-release.yml`：`push` 匹配 `vX.Y.Z` tag 时自动运行。
- `workflow_dispatch` 可手动输入一个**已存在**的 tag；校验仍要求该 tag 指向当前代码且 versionName 匹配。
- 使用 `setup-java@v4` Temurin 17、`android-actions/setup-android@v3`，安装 `platforms;android-35` 和 `build-tools;35.0.0`。
- 执行 `./gradlew :app:assembleRelease`，签名配置从环境变量读取。
- 使用 Build Tools 35.0.0 的 `apksigner verify`，然后创建或更新同名 GitHub Release，并上传 `magpie-usage-vX.Y.Z.apk`。

仓库没有配置 Secret 时，签名配置阶段会失败，不会发布未签名包。

## 安全提示

这是完整控制面板壳，不是只读工具。网页 key 可以修改 magpie 的供应商、密钥和设置。HTTP 地址仅建议用于受控内网。
