# magpie-usage-mobile

把 magpie 网页控制面板装进一个 Android App。

App 不重新实现用量、路由或供应商管理，也不调用 gateway API。它保存用户选择的 magpie **网页控制面板 origin 地址簿**，然后用系统 WebView 加载对应页面。网页端已有的手机布局、登录页、用量页和路由页全部保留。

> 仓库名沿用 `magpie-usage-moblie`（原仓库的拼写）；产品名使用 `magpie-usage-mobile`。当前实现版本为 v0.2.0。

## 目标

- 只做 Android，通过 GitHub Release 分发 APK，不依赖应用商店。
- 第一次打开时让用户输入控制面板地址，之后可管理最多 50 个已保存地址。
- 同时支持两种输入：
  - `http://192.168.31.60:3430`：进入网页原生的 key 登录表单。
  - `http://192.168.31.60:3430/?k=<key>`：直接兑换登录 cookie，自动进入面板。
- 启动直接进入最近使用的已保存地址；用户仍可随时增加、编辑名称、删除或切换地址。
- 左侧原生抽屉只负责地址簿、默认 `k`、登录捕获和数据清理入口，不复制网页业务 UI。
- 默认 `k` 的复用和登录捕获均默认关闭，开启前展示完整权限与跨地址泄露风险。
- 网页内容、样式、接口和权限逻辑不改。

## 非目标

- 不做 iOS、桌面端或 PWA。
- 不重写 usage/routing/providers 页面。
- 不调用 `/v1/magpie/*` gateway API。
- 不把 App 做成只读客户端；它保留网页的完整控制权限。
- 不做服务器、代理或 TLS 终止。
- 不做账号系统、云同步或推送。
- 不把默认 `k` 当作跨任意域名的通用认证机制。

## 推荐方案

使用 **Android 原生 Kotlin + 单 Activity + WebView**，UI 使用 XML，不使用 Compose、React Native、Flutter 或 Expo。

选择理由：

1. 需求本质上是一个地址簿和全屏 WebView，没有跨端负担。
2. Android WebView 可以直接加载运行时任意 `http/https` URL，不需要构建期域名白名单。
3. Cookie、HttpOnly、SameSite、303 登录跳转都由 WebView 原生处理。
4. 原生抽屉和对话框生命周期简单，不引入额外 UI 框架。
5. GitHub Actions 可以直接构建、签名并发布 APK，工具链简单。

## 高层结构

```text
启动
  │
  ├─ 迁移 v1 panel_origin / 读取地址簿
  │
  ├─ 没有保存的地址
  │    └─ 原生地址输入页 → 校验 → 保存 origin + 名称 → 打开 WebView
  │
  └─ 已有保存的地址
       └─ 标记最近使用 → 打开最近地址
              │
              ├─ 用户明确开启复用：只给该已保存 origin 追加默认 k
              ├─ URL 带 k：网页 303 兑换 cookie，进入干净地址
              └─ URL 不带 k：网页显示原生 key 登录表单
                     ├─ 普通提交：网页写 HttpOnly cookie
                     └─ 捕获开启且 origin 匹配：监听原生 401 根表单提交

左侧边缘手势
  └─ 仅边缘向右、超过 slop 且横向占优时接管
       └─ 原生抽屉：地址簿 / 默认 k / 捕获开关 / 清除数据
```

App 只负责外层容器、导航和受限凭据策略。网页内部仍通过 `/api/*` 请求数据，因此页面与 API 始终是同源关系。

## 地址簿

### 保存什么

每项只保存：

```text
origin = scheme://host[:port]
label = 单行名称（最多 40 字符）
lastUsedAt = 最近使用时间
```

例如：

```text
http://192.168.31.60:3430
https://magpie.example.com
```

不保存 query、fragment、路径或 `k`。JSON 使用 `version = 1` 和 `entries` 数组；解析时规范化 origin、去重、清理非法行并限制最多 50 项。

### 状态规则

- `PanelBookState.upsert` 规范化 origin；新项达到 50 个时拒绝，已有项仍可更新。
- 名称为空时使用 `面板 <host>`；控制字符、换行和多余空白会被清理。
- `remove` 只删除指定 origin。
- 最近使用排序为主，名称和 origin 为稳定的次级排序键。
- `PanelBookStore.touch` 保存后返回更新后的整个 `PanelBook`，调用方必须用 `find(targetOrigin)` 取目标项，不能拿旧项或任意 `mostRecent` 代替。
- 地址输入保存也返回实际新增/更新的那一项，避免打开此前最近使用的其它地址。
- 编辑已有项时 origin 锁定；新增重复 origin 会被拒绝。
- v0.1 的 `panel_origin` 迁移为「默认面板」，迁移成功后移除旧键；非法旧值丢弃。地址簿已满时保留现有内容，不强行替换。

### 支持与校验

- `http` 和 `https`。
- IPv4、局域网主机名、公网域名、带方括号的 IPv6。
- 显式端口；省略端口时使用协议默认端口。
- 未写 scheme 时默认补 `http://` 并提示。
- 用户粘贴的完整控制面板链接，包括 `?k=` 和 `?view=`。
- 拒绝 userinfo、空 host、`1..65535` 之外的端口、非法 IPv6/主机名，以及 `javascript:`、`data:`、`file:`、`content:`、`intent:` 等 scheme。
- `view` 只允许 `usage` 或 `routing`，其它值回退到 `usage`；其余 query 不复制到干净目标。

### 带 `k` 的链接怎么处理

第一次打开完整链接时，直接让 WebView 顶层加载它。网页会：

1. 校验 `k`；
2. 写入 `magpie_web_<port>` cookie；
3. 返回 303，跳转到去掉 `k` 的地址。

带 key 的 URL 只存在于首次导航和 WebView 历史中。首次成功加载后，App 切到干净的 `origin/<path>?view=<view>` 并清理历史；超时/失败回退到干净地址，不重放该次粘贴的 key。日志、崩溃上报和导航回调不得记录完整 URL；建议完全不接入崩溃上报。

## 原生抽屉与触摸边界

- `activity_main.xml` 的根 `FrameLayout` 有明确 id `main_root`；`NavigationDrawer` 绑定这个根视图，而不是 `android.R.id.content`。
- 抽屉、scrim、边缘把手和列表都从同一布局树查找，避免运行时 null。
- `ACTION_DOWN` 只记录边缘候选，**不消费事件**，WebView 正常收到点击和滚动。
- `ACTION_MOVE` 只有在最左边缘区域内、向右超过 `scaledTouchSlop` 且 `dx > abs(dy)` 时才开启拖动；竖向占优或反向移动会结束候选。
- 开始拖动后，抽屉跟随横向位移，松手按位移阈值打开或关闭；`ACTION_CANCEL` 不打开。
- 普通点击、竖向滚动、非边缘位置和 WebView 自己的左侧横向手势均交还 WebView。该规则仍需真机验证不同 WebView 版本的手势冲突。

## 默认 `k` 策略

### 存储

- `DefaultKeyStore` 使用 Android Keystore 中的 **AES-256-GCM** non-exported 密钥。
- alias：`magpie_usage_default_key_v1`；transformation：`AES/GCM/NoPadding`；GCM tag：128 bit；每次加密生成新的 128-bit IV。
- SharedPreferences 只保存格式版本、Base64 IV 和 Base64 密文，不保存明文。
- Keystore 不可用、密钥失效、版本不符或密文损坏时 `load()` 返回 null，不输出敏感错误。
- 不开启 Android 自动备份；密钥不随普通数据导出，也不承诺跨设备、卸载重装或恢复后可用。

### 使用边界

- `REUSE_ENABLED` 与 `CAPTURE_ENABLED` 默认均为 false。
- 捕获到 key 只做加密保存，不自动把复用开关设为 true。
- 默认 `k` 只能由 `appendForSavedPanel(panel, cleanUrl, key, reuseEnabled)` 追加，调用方必须传入一个已保存的 `PanelEntry`。
- 复用关闭、空 key、非法 URL、目标 origin 不同、URL 已含任意形式 `k` 时一律返回 null。
- key 通过 UTF-8 URL 编码后追加；同一 URL 不重复追加。
- 重定向、外链、新窗口、弹窗和下载路径都不调用该函数。完整粘贴链接自带 `k` 时也不被默认 key 覆盖。
- 开启复用前显示确认，明确同一完整权限 key 会发往用户选择的每个已保存地址。

### 复用风险

同一个 `k` 能完全控制对应 magpie 实例。只要用户保存的多个地址实际上指向同一实例，复用才有意义；域名/IP、scheme、主机或端口不同都可能把 key 发给错误的服务。HTTP 还会让网络观察者读取 key。默认关闭、逐地址明确打开、只在 origin 完全一致时追加，是降低误用的边界，不能消除用户主动开启后的泄露风险。

## 登录捕获

### 注入条件

只有以下条件同时成立才注入：

1. `CAPTURE_ENABLED == true`；
2. `CAPTURE_ORIGIN == savedOrigin`；
3. 当前完成加载的 URL 与已保存 origin 完全一致；
4. URL 是顶层原生根登录文档路径 `/`；
5. 页面脚本确认 `window.origin` 相同且 `window.top === window.self`。

`injectIfEligible` 自身接收 `captureEnabled` 并再次执行这些检查，不依赖调用方记得预检。其它路径、普通页面、子 frame、跨源 frame、其它 origin 和弹窗不注入。

### 识别与校验

- 只查找 `method="post"`、action 以 `/_magpie/sign-in` 结尾的原生表单。
- 只读取该表单中的 key 密码框和隐藏 `back`，不遍历 DOM、其它表单或 URL。
- submit 必须是 `isTrusted`、未被 `preventDefault`，且由原生提交按钮触发；脚本模拟提交不捕获。
- Kotlin 侧再次要求捕获开关、`CAPTURE_ORIGIN == savedOrigin`、key 非空、`path == "/"`。
- `back` 必须是无 scheme/authority/fragment 的本机绝对路径，不能以 `//`、`/\` 开头，不能回到 `/_magpie/sign-in`。
- 对 path/query 分段做百分号解码，拒绝编码后的斜杠/反斜杠跨站形式和编码后的登录页回环。
- 满足条件才加密保存；不记录、不持久化明文、不输出 key。

### 失败回退

网页结构变化、自定义非根部署、开关关闭、切换地址、导航代次变化、校验失败或 Keystore 保存失败时：

- 不保存捕获值，不改变网页正常提交；
- 不把失败当成网络错误；
- 可继续使用 WebView Cookie，或从抽屉手动粘贴默认 `k`；
- 成功捕获后尝试清理首次登录历史，清理失败也不回退到明文存储。

## 网页兼容性结论

以下结论来自对 `yetone/magpie` 源码的只读研究，不是猜测。

- `3430` 是网页端口；`3425` 才是 gateway 默认端口。本 App 要填的是**网页端口**。
- 不带 `k` 访问网页会得到 HTTP 401 的静态登录页。它只有一个 `key` 密码框和隐藏的 `back` 字段，不依赖 JavaScript，WebView 可以直接完成登录。
- 登录 cookie 名为 `magpie_web_<port>`，属性为 `Path=/; HttpOnly; SameSite=Lax; Max-Age=400 天`；HTTP 下没有 `Secure`，HTTPS 下有 `Secure`。
- `?k=` 兑换成功是 303，并保留 `view` 等其它 query 参数。
- 手机布局由 `body.web` 和 CSS media query 触发，不依赖 User-Agent。正常手机 WebView 的宽度会触发底部 tab。
- 页面不使用 WebSocket、ServiceWorker 或 BroadcastChannel；主要靠 `fetch` 轮询。
- `/api/*` 有严格同源保护。WebView **顶层加载网页**时，页面自己的 API 请求是同源，可以正常工作；App 自己的网页用 iframe 或跨 origin fetch 不可行。
- 控制面板主 HTML 没有 `X-Frame-Options`，但同源保护仍使外部 iframe 方案不可用。
- Usage 在 `?view=usage`，Routing 在 `?view=routing`。路由详情来自网页 `/api/gateway/trace`，不是 gateway 的 `/v1/magpie/*`。

因此 App 不应该：

- 注入业务 JS；
- 劫持 `/api/*`；
- 自己实现登录表单；
- 自己读取 HttpOnly cookie；
- 把远程页面嵌进 `file://` 或 `assets://` 的 iframe。

## WebView 行为

### Cookie：按 host，不按 port

- 启用 `CookieManager.setAcceptCookie(true)`。
- 启用 `setAcceptThirdPartyCookies(webView, true)`。面板自身是同源 cookie，但页面内部插件 iframe 可能需要更宽松的策略；这会增加第三方页面获取其自身 Cookie 的机会。
- 不开启 incognito，不清空 WebView 数据作为常规启动流程。
- Cookie 规范按 host 隔离，**不按端口隔离**。`magpie_web_<port>` 的名称包含端口，但同主机不同端口的网页仍共享站点 Cookie 空间；其它服务清除站点数据或挤占 Cookie 限额可能影响登录。
- 因此「地址簿 origin 不同」不等于「Cookie 一定不同」。切换地址默认保留 Cookie/缓存。
- 「清除数据」等待 Cookie 异步删除完成后，关闭所有弹窗 WebView、把主 WebView 切到 `about:blank`、清空历史，再显示地址输入页。

### JavaScript / DOM Storage

- `javaScriptEnabled = true`。
- `domStorageEnabled = true`。
- 正常设置 viewport；不伪造桌面 UA，也不强制缩放。
- 页面自身的 `<meta name="viewport">` 已包含 `viewport-fit=cover`。
- 唯一注入是上述受限登录捕获；普通业务页面不注入。

### 返回键

```text
Android 返回键
  ├─ 抽屉打开 → 关闭抽屉
  ├─ WebView.canGoBack() → webView.goBack()
  └─ 否则 → 退出 App
```

地址输入页按普通 Android 返回处理。`history.replaceState` 不会新增历史，网页内部大量 replace 不会制造返回键困局。

### 新窗口 / 外链

- 同 origin 的页面导航留在 WebView。
- 顶层跨 origin HTTP(S) 自动跳转被阻止。
- 用户打开的 HTTP(S) 新窗口在 App 的弹窗 WebView 中加载；跨 origin HTTP(S) 新窗口交系统处理。
- 其它 scheme（`mailto:`、`tel:`、`intent:` 等）交给系统。
- 不静默忽略 `window.open`，否则网页上的链接会像坏掉一样。
- 不在 App 内提供通用浏览器功能，也不在弹窗注入登录捕获或追加默认 `k`。

### 下载

- 开启 WebView 下载监听。
- Android 使用 `DownloadManager` 下载 CSV 等附件到公共 Downloads。
- 只有下载 URL 与当前 panel origin 完全一致时，才显式转发对应端口名下的 HttpOnly Cookie。
- 动态申请必要的存储权限只作为旧版本兼容；Android 10+ 优先使用系统下载器，不申请全部文件访问权限。

### 页面错误

- HTTP 401 不是错误，不拦截、不替换成 App 的错误页。
- 带 `k` 的首次请求完成后，只有目标 panel origin 的 HTTP(S) 主文档才被视为登录成功；内部 `about:` 页面不触发替换。
- WebView 的 `onReceivedHttpError` 不能把 401 登录页当成失败。
- 只有 DNS、连接拒绝、超时等 `onReceivedError` 才显示可重试的原生错误层。
- 错误页提供「重试」和「更改地址」，不显示敏感 URL。

## Android 网络安全配置

局域网默认是 HTTP，Android 9+ 默认禁止明文流量，因此需要显式放行：

```xml
android:usesCleartextTraffic="true"
```

因为 host 是运行时输入的，按域名配置 Network Security Config 无法覆盖所有可能地址。该设置只影响 App 自身；仅用于自用/内测 APK 时风险可控，不要描述为生产级安全建议。更安全的长期方案是给 magpie 网页配置 HTTPS，再移除全局明文放行。

## 权限

最小权限：

- `android.permission.INTERNET`
- 下载兼容所需的存储权限（仅低版本 Android 按需处理）

不需要：

- 精确位置
- 通讯录
- 相机
- 麦克风
- 蓝牙
- 全部文件访问

iOS Local Network 权限不在本项目范围内。

## 应用图标与第三方许可

- 应用内图标、启动器 foreground/round/monochrome 资源来自 magpie 官方图标：
  <https://github.com/yetone/magpie/blob/main/site/public/img/icon-256.png>
- 图标来源、本地来源路径和 MIT 许可全文记录在 `THIRD_PARTY_NOTICES.md`。
- MIT 原文来自 `/Users/bbben/Desktop/magpie/magpie/LICENSE`。

## GitHub Release 构建

### 调试包

日常开发输出：

```text
app-debug.apk
```

可直接安装，用于功能验证。

### 发布包

GitHub Actions 构建 release APK：

1. 检出代码。
2. 设置 JDK 和 Android SDK。
3. Gradle 执行 `assembleRelease`。
4. 使用仓库 Secret 中的签名信息生成 keystore。
5. 签名 APK。
6. 上传 APK 到 GitHub Release。

需要的 Secret：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

不要把 keystore、密码或签名文件提交进仓库。

### 分发语义

GitHub Release APK 是直接分发的安装包，不经过 Google Play。用户需要允许安装来自此来源的未知应用。

不上架公共应用商店，因此不存在 WebView 纯壳在 App Store 4.2 / Google Play 最低功能性政策下的审核问题。

## JVM 测试设计

`testDebugUnitTest` 不构造 Android View/WebView/Keystore，只覆盖纯逻辑：

- `PanelAddressTest`：迁移到 `PanelUrl` 后的旧行为兼容、view 白名单、IPv6/IDN、默认 scheme、非法输入、key 编码与已有 key 拒绝。
- `PanelBookStateTest`：upsert/remove、label 清理与默认值、50 项上限、最近使用与显示排序。
- `PanelBookSerializerTest`：roundtrip、损坏 JSON、重复规范化 origin、只有 origin 的旧行、50 项截断。
- `PanelBookMigrationTest`：legacy 迁移、重复迁移、非法旧值、坏 JSON、已满地址簿。
- `DefaultKeyPolicyTest`：开关关闭、空 key、不同 origin、已有 k、正确追加与编码、可用性开关。
- `LoginKeyCaptureTest`：根 path 和同源相对 `back` 校验、跨站/编码绕过、登录页回环拒绝。
- `NavigationPolicyTest`：顶层同源边界与 scheme 分派。

`PanelBookSerializer` 和登录捕获的桥接使用 `org.json`。Android 提供平台实现；JVM 测试通过 `org.json:json:20240303` 提供实现，避免出现 Android stub 的 “Method not mocked” 失败。该依赖仅为 `testImplementation`，不进入 APK。

## 实施阶段

### 阶段 1：最小可用壳

- 创建 Android 工程。
- 地址输入与规范化。
- origin 持久化。
- 全屏 WebView。
- 两种 URL 输入。
- Cookie 与明文流量配置。
- 返回键。
- 更改地址入口。

验收：填 `http://192.168.31.60:3430` 能看到原生登录页；登录后看到 Usage；切到 Routing 能加载；杀进程重开仍在面板内。

### 阶段 2：壳子体验

- 新窗口处理。
- CSV 下载。
- 网络错误重试。
- 首次带 `k` 链接的历史清理。
- 清除 Cookie/缓存选项。
- 简单加载状态，不遮挡网页内容。

验收：`?k=` 自动登录后返回键不回到 key 地址；CSV 能下载；断网时能重试；切换地址不掉登录态。

### 阶段 3：发布工程

- Release 签名。
- GitHub Actions。
- APK 版本管理。
- README 安装说明。
- magpie 官方图标和 MIT 第三方声明。

验收：Release 页面能下载已签名 APK，覆盖安装后保留网页登录态。

### 阶段 4（v0.2.0）：地址簿、抽屉与默认 key

- 50 项地址簿和 v1 迁移。
- 原生抽屉及只接管边缘横向拖动的手势。
- Android Keystore AES-256-GCM 默认 key。
- 默认关闭、仅已保存 origin 可追加的复用策略。
- 当前已保存 origin 原生 401 根登录页的受限捕获。
- 纯 JVM 状态、序列化、迁移、URL、导航和 key 策略测试。

验收：保存/打开/编辑/删除目标一致；旧地址迁移；普通 WebView 左侧触摸不被吞；默认 key 不进入重定向或外链；捕获失败仍能正常网页登录。

## 测试清单

### 地址簿

- [ ] 新增、重命名、删除、打开与最近使用排序正确。
- [ ] 打开新地址不会错误打开此前最近使用的地址。
- [ ] 达到 50 项后拒绝新增，但允许更新已有项。
- [ ] v0.1 origin 迁移为「默认面板」，重复启动不重复。
- [ ] 编辑不能改 origin，重复 origin 有提示。
- [ ] 保存值不含 `k`、路径或 query。

### 登录与默认 key

- [ ] 不带 key 时显示网页原生 401 表单。
- [ ] 表单登录成功后进入面板。
- [ ] 带 key 链接自动兑换并 303。
- [ ] 重启 App 后仍登录。
- [ ] 清除数据后回到未登录状态。
- [ ] 复用与捕获默认关闭；捕获不自动开启复用。
- [ ] 默认 key 只追加到明确打开的已保存同 origin 地址。
- [ ] 不同 scheme/主机/端口、已有 k、重定向、外链均不追加。
- [ ] Keystore 失效/损坏时失败关闭，可手动重新保存。
- [ ] 普通页面、其它路径、子 frame、跨源 frame 不注入捕获。
- [ ] `back` 跨站、编码绕过或登录回环被拒绝。
- [ ] 捕获失败不阻止网页登录，可回退到 Cookie 或手动粘贴。

### 页面与抽屉

- [ ] Usage 数据加载。
- [ ] Requests 列表加载。
- [ ] Routing 页面加载。
- [ ] Providers 页面加载。
- [ ] 手机底部 tab 显示。
- [ ] 页面内链接可用。
- [ ] 左侧普通点击、竖向滚动和普通横向触摸交给 WebView。
- [ ] 只有最左边缘向右且横向占优的拖动打开抽屉。
- [ ] 抽屉列表、scrim、边缘把手均可见可操作。

### 导航与下载

- [ ] 返回键先关闭抽屉，再回退网页历史。
- [ ] 没有历史时退出。
- [ ] `window.open` 不被忽略。
- [ ] CSV 能下载到 Downloads。
- [ ] 更换地址入口可用。
- [ ] Cookie 按 host 共享的风险在文档中明确。

### 网络

- [ ] Android 9+ 可访问 HTTP 内网地址。
- [ ] DNS 失败有重试页。
- [ ] 服务器不可达有重试页。
- [ ] 401 不被误报为网络错误。

## 安全边界

- App 持有网页的完整控制权限，不是只读工具。
- 网页 key 能查看和修改供应商、key、设置，不应分享给不可信的人。
- HTTP 明文适合受控内网，不适合公网。
- Cookie 按 host 而非 port 隔离，其它端口服务可能影响登录。
- 不记录带 key 的 URL，不把明文 key 写入 SharedPreferences。
- 默认 key 复用默认关闭；开启后会把同一完整权限 key 发往多个已保存地址，存在明显泄露风险。
- 登录捕获只作用于当前已保存 origin 的顶层原生 401 根表单；其它页面、路径和 frame 不注入。
- 不抓取或上报网页内容，不向第三方分析服务发送导航地址。

## 关键参考

- [magpie 网页登录与同源实现](https://github.com/yetone/magpie/blob/main/internal/gui/web.go)
- [magpie 网页用量接口](https://github.com/yetone/magpie/blob/main/internal/gui/usage.go)
- [magpie 网页路由接口](https://github.com/yetone/magpie/blob/main/internal/gui/trace.go)
- [magpie 官方图标](https://github.com/yetone/magpie/blob/main/site/public/img/icon-256.png)
- [Android WebView 指南](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android 明文流量配置](https://developer.android.com/privacy-and-security/security-config)
