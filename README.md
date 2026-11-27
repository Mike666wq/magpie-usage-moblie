# magpie-usage-mobile

Android 上的 magpie 网页控制面板壳。

Usage、Routing、Providers、登录和手机布局都使用 magpie 网页端，不在 App 里重写。v0.3.1 修复导航回调身份判断、加载层与超时绑定，并补齐 Activity 重建、外部 intent、后台恢复和抽屉异步状态；保留 v0.3.0 的捕获令牌隔离、网页原生 303 历史清理和真实加载进度。

- 平台：Android 7.0+（minSdk 24）
- 分发：GitHub Release APK
- 技术栈：Kotlin + XML + WebView
- 构建工具：JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3
- 当前版本：`0.3.1`（versionCode 5）

## 使用方式

1. 从 GitHub Release 下载并安装 APK。
2. 输入控制面板地址，例如 `http://192.168.31.60:3430`，保存后直接打开；未写 scheme 时按 `http://` 处理并提示。
3. 可继续使用网页原生登录页粘贴 key，也可粘贴带 `?k=` 的完整链接。后者只参与首次请求；网页自己的 303 转到同源干净 URL 后，App 在该页加载完成时清理历史，不再延迟跳转或通过 `about:blank` 重载。
4. 从屏幕最左边缘向右拖，或点击左侧竖向把手，打开地址簿和设置。只有超过触摸 slop、向右且明显比竖向位移更大的边缘拖动才会接管；普通点击、竖向滚动和左侧普通横向触摸仍交给 WebView。
5. 每次打开或切换地址都会立即显示不透明加载层，隐藏旧网页，并显示 WebView 的真实加载百分比。超过 45 秒仍未完成时显示超时与重试入口；App 不会主动 `stopLoading`，页面若稍后完成会自动恢复显示。
6. Activity 真正重建或从后台恢复时，如果导航状态失效、加载层没有对应的导航，或 WebView 回到空文档，会显式重载最近地址；正常页面不会因每次 `onResume` 反复重载。外部再次打开地址时会更新 intent 并回到预填地址表单，不使用旧任务栈状态。

### 地址簿

- 使用抽屉中的「新增地址」，或地址输入页直接保存并打开。
- 最多 50 项，支持命名、编辑名称、删除和打开；启动时打开最近使用项。
- 每项只保存规范化 `scheme://host[:port]`、名称和最近使用时间，**不保存路径、query、fragment 或 `k`**。
- v0.1 的 `panel_origin` 会迁移成「默认面板」；损坏的旧值会被丢弃，不会变成导航目标。
- 编辑不能更改 origin；重复 origin 会提示打开或编辑已有项。
- 删除地址项不会删除该站点的 WebView Cookie 或缓存。「清除 Cookie 和缓存」会等待异步删除完成后返回地址页，地址簿和默认 `k` 仍保留。

### 默认 `k`（完整控制权限）

默认 `k` 和复用功能都默认关闭。手动保存不会自动开启复用；**登录捕获成功后会自动开启 `REUSE_ENABLED`**，把同一个捕获值用于之后选择的已保存地址。

- 密钥通过 **Android Keystore 的 AES-256-GCM** 加密，alias 为 `magpie_usage_default_key_v1`；SharedPreferences 只存格式版本、随机 IV 和密文，不存明文。
- Keystore 被系统删除、备份/恢复后不可解密或记录损坏时，读取会失败关闭；请手动重新保存。App 不崩溃、不输出密钥。
- 只有明确打开一个**已保存地址**、且 URL 与该项 origin 完全相同时，才可能追加默认 `k`。不同 scheme、主机名/IP、端口、重定向目标、外链、弹窗和下载都不会追加。
- URL 已有任何形式的 `k`（包括编码名、空值）时不会覆盖或追加第二个。
- 追加值经过 URL 编码。该功能只对用户已选择的可信地址启用；不要把同一完整权限 key 发给不同实例。
- **复用风险**：同一完整权限 key 可完全控制对应实例；发给不同 scheme、主机、端口或服务会直接泄露控制权，HTTP 还会被网络观察者读取。默认关闭和 origin 校验只能减少误用，不能消除主动开启后的风险。
- 开启「复用」前会显示风险确认。关闭复用后，已保存的 key 仍可供用户重新开启或捕获逻辑使用，但不会自动发送。
- 清除默认 `k` 会同时关闭复用和登录捕获，但不删除地址簿或 Cookie。
- 抽屉刷新只在主线程读取 `isPresent()`，文案先显示「已加密保存默认 k」，不在主线程解密；打开抽屉时才在后台线程检测可读性，结果 post 回主线程，并在 Activity 销毁或状态刷新后丢弃过期结果。

### 登录捕获的范围与回退

- 「登录一次后捕获默认 k」默认关闭；只对**当前已保存 panel origin** 生效。开启确认文案明确说明：捕获后会加密保存，并自动用于之后选择的已保存地址（跨主机复用）。
- 只在顶层、规范根路径 `/` 的 magpie 原生 401 登录文档注入最小提交监听；页内脚本还会检查 `window.origin` 与已保存 origin 相同，且 `window.top === window.self`。其它页面、其它路径、子 frame、跨源 frame 和弹窗不注入。
- 每次 `openPanel` 都有新的导航代次和随机 `captureToken`；旧页面的回调、旧 JS 消息和切换地址后的迟到保存结果均被拒绝。切换地址会立即让旧 token 失效。
- 只识别原生 POST `/_magpie/sign-in` 表单里的 key 密码框和隐藏 `back`，不读取其它表单或页面内容。使用 capture 阶段 submit 监听，并以可信的原生 submit 按钮 click 兜底，兼容 Android 7/旧 WebView 缺失 `event.submitter` 的情况；脚本与 native 按 token 去重。
- 原生提交后的 `back` 必须是本机相对路径、无 authority/scheme/fragment，并拒绝编码后的跨站斜杠、反斜杠和登录页回环；`path` 必须是 `/`。
- 网页结构变化、自定义非根部署、关闭开关、切换地址、Keystore 失败或校验失败时，不保存捕获值，也不阻止网页自身登录。可继续用 Cookie，或从抽屉手动粘贴默认 `k`。
- App 不记录、持久化或输出捕获的明文 key。保存成功后只加密保存、显示 acknowledgement、自动开启复用并刷新抽屉，不强制导航或重载；网页自行提交并通过 303 登录，干净同源页完成后清理历史。

### Cookie、下载与错误

- Cookie 由 WebView 按 **host 管理，而不是按 port 隔离**。`magpie_web_<port>` 是 HttpOnly、`SameSite=Lax`、最长 400 天的登录 cookie；HTTPS 下另有 `Secure`。同主机不同端口的其它服务可能共享/清除站点数据，因此 Cookie 隔离弱于地址簿的 origin 隔离。
- 为兼容页面资源，WebView 接受第三方 Cookie；这会扩大网页生态的凭据暴露面。建议仅访问可信面板，公网优先使用 HTTPS。
- CSV 等附件通过系统 `DownloadManager` 保存到公共 Downloads。只有下载 URL 与当前面板 origin 完全一致时，才显式转发对应 `magpie_web_<port>` HttpOnly Cookie，避免发送到其它站点。
- Android 9+ 不申请存储权限；Android 7/8 仅在首次实际下载时按需申请旧版 `WRITE_EXTERNAL_STORAGE`，不申请全部文件访问。
- 主页面断网等错误提供「重试」和「更改地址」；HTTP 401 原生登录页正常显示，不会误报为网络错误。
- 加载层使用不透明背景覆盖 WebView；`onProgressChanged` 只更新当前 `loadingNavigation` 绑定的真实进度。`onPageStarted` 使用回调 URL 判断本次目标，不读取可能仍是旧文档的 `webView.url`；`onPageFinished` 即使完成 URL 不匹配当前 origin，也会按 generation 安全结束加载层，旧 generation 不能影响新页面。45 秒超时只显示错误层和重试入口，不主动停止当前请求。
- `onSaveInstanceState` 只保存 origin、加载状态、进度、超时/错误标志和导航代数，不保存完整 URL、路径、query 或 `k`。重建后先恢复为不可继续的导航占位，再用新 generation/token 显式重载最近地址；`onNewIntent`、`onResume` 的空页面兜底、抽屉 `reset()` 以及不调用 `pauseTimers()` 的 WebView 生命周期共同避免后台白屏和卡死手势。

> 这里填的是网页控制面板端口，不是 gateway 端口。magpie 网页默认 `3430`，gateway 默认 `3425`。

## 导航策略

App 保留当前 panel origin 的顶层信任边界：

- 顶层 HTTP(S) 导航和自动重定向只允许当前已保存 panel origin；跨 origin 跳转被阻止。
- 用户打开的 HTTP(S) 新窗口保留在 App 的弹窗 WebView 中；跨 origin 的 HTTP(S) 新窗口交系统处理，不加载跨源页面。
- `mailto:`、`tel:`、`intent:`、`geo:` 等其它 scheme 通过 `ACTION_VIEW` 交系统处理；没有对应应用时显示提示。
- `window.open` 与 `shouldOverrideUrlLoading` 共用 `NavigationPolicy`，避免两套外链策略漂移。
- 默认 `k` 不在上述任何重定向、链接或弹窗路径中使用。

## 应用图标与许可

应用内图标、启动器 foreground 和 monochrome 图标直接由 magpie 官方 SVG 的 `pathData` 生成 VectorDrawable；地址页、抽屉、加载层与地址行均使用矢量图标。启动器保留旧版 PNG 图标。

<https://github.com/yetone/magpie/blob/main/site/public/img/icon-256.png>

图标按 MIT 许可使用；来源说明和完整许可全文见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 安装 APK

1. 打开设备的「设置 → 安全/隐私 → 安装未知应用」，允许当前浏览器或文件管理器安装来源。
2. 打开 Release 中的 APK；如 Android 提示签名/版本冲突，先卸载旧包（会删除旧 App 数据），再安装新包。
3. 首次输入 panel 地址并登录。正常覆盖安装会保留 WebView Cookie；若手工卸载重装则不会保留。默认 `k` 的 Keystore 密钥不参与跨设备或卸载后恢复。

Release 资产名固定为：

```text
magpie-usage-vX.Y.Z.apk
```

其中 `X.Y.Z` 必须与 `app/build.gradle.kts` 的 `versionName` 完全一致。

## 构建与验证

仓库已提交 Gradle Wrapper（Gradle 8.9，wrapper JAR SHA-256：
`498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`）。
Wrapper properties 固定了 Gradle 8.9 distribution URL 和 SHA-256。

本机需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。设置 `ANDROID_HOME`，或安装 SDK 后创建 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

纯 JVM 单测覆盖 URL 兼容、地址簿状态/序列化/迁移、默认 key 追加策略、登录捕获回退地址校验、导航策略，以及导航状态的后台恢复/代数推进。序列化单测通过 `testImplementation("org.json:json:20240303")` 使用 JVM 版 `org.json`，不依赖设备上的 Android stub：

```bash
./gradlew :app:testDebugUnitTest
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

本轮修复按约束只做静态检查，**没有运行 Gradle build/assemble 或 `testDebugUnitTest`，也没有产出 APK**；设备上的真实手势、WebView Cookie 和 Keystore 行为仍需集成验证。

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

当前版本为 `0.3.1`。发布流程：

1. 更新 `app/build.gradle.kts` 的 `versionName`（如有需要也更新 `versionCode`）。
2. 提交代码。
3. 创建并推送语义化 tag，例如：

```bash
git tag v0.3.1
git push origin v0.3.1
```

CI 会读取 tag 和 Gradle 文件并校验：`vX.Y.Z` 必须映射到 `versionName = "X.Y.Z"`，且 checkout commit 必须与 tag 指向一致。此规则不硬编码旧 tag。

## Actions 构建

工作流：

- `.github/workflows/android-check.yml`：推送 main 或 PR 时安装 JDK 17 / SDK 35，运行 `:app:lintDebug :app:testDebugUnitTest :app:assembleDebug`，并把 debug APK 作为 CI 验证产物上传 14 天。它不生成 Release。
- `.github/workflows/android-release.yml`：`push` 匹配 `vX.Y.Z` tag 时自动运行。
- `workflow_dispatch` 可手动输入一个**已存在**的 tag；校验仍要求该 tag 指向当前代码且 versionName 匹配。
- 使用 `setup-java@v4` Temurin 17 和 GitHub runner 自带的 `/usr/local/lib/android/sdk`，通过其 `cmdline-tools/latest/bin/sdkmanager` 安装 `platforms;android-35` 和 `build-tools;35.0.0`；不再使用会安装废弃 `tools` 包的 `setup-android@v3`。
- 执行 `./gradlew --no-daemon :app:assembleRelease`，签名配置从环境变量读取。
- 使用 Build Tools 35.0.0 的 `apksigner verify`，然后创建或更新同名 GitHub Release，上传 `magpie-usage-vX.Y.Z.apk`。

仓库没有配置 Secret 时，签名配置阶段会失败，不会发布未签名包。

## 安全提示

这是完整控制面板壳，不是只读工具。网页 key 可以修改 magpie 的供应商、密钥和设置。复用默认 `k` 会把同一完整权限凭据发送给多个已保存地址，存在明显的跨主机/端口泄露风险，默认关闭。HTTP 明文和按 host（非 port）共享的 Cookie 仅建议用于受控内网；公网使用 HTTPS。
