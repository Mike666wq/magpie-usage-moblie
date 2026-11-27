# magpie-usage-mobile

把 magpie 网页控制面板装进一个 Android App。

App 不重新实现用量、路由或供应商管理，也不调用 gateway API。它只在启动时保存一个 magpie **网页控制面板地址**，然后用系统 WebView 原样加载该地址。网页端已有的手机布局、登录页、用量页和路由页全部保留。

> 仓库名沿用 `magpie-usage-moblie`（原仓库的拼写）；产品名使用 `magpie-usage-mobile`。

## 目标

- 只做 Android，通过 GitHub Release 分发 APK，不依赖应用商店。
- 第一次打开时让用户输入控制面板地址。
- 同时支持两种输入：
  - `http://192.168.31.60:3430`：进入网页原生的 key 登录表单。
  - `http://192.168.31.60:3430/?k=<key>`：直接兑换登录 cookie，自动进入面板。
- 之后启动直接进入上次的地址，不重复输入。
- 用户仍可随时更换地址。
- 网页内容、样式、接口和权限逻辑不改。

## 非目标

- 不做 iOS、桌面端或 PWA。
- 不重写 usage/routing/providers 页面。
- 不调用 `/v1/magpie/*` gateway API。
- 不把 App 做成只读客户端；它保留网页的完整控制权限。
- 不做服务器、代理或 TLS 终止。
- 不做账号系统、云同步或推送。

## 推荐方案

使用 **Android 原生 Kotlin + 单 Activity + WebView**，UI 使用 XML，不使用 Compose、React Native、Flutter 或 Expo。

选择理由：

1. 需求本质上只有一个地址输入框和一个全屏 WebView，没有跨端负担。
2. Android WebView 可以直接加载运行时任意 `http/https` URL，不需要构建期域名白名单。
3. Cookie、HttpOnly、SameSite、303 登录跳转都由 WebView 原生处理，不需要注入 JS。
4. GitHub Actions 可以直接构建、签名并发布 APK，工具链简单。
5. 不引入 React Native 的 Metro/Hermes、Expo prebuild 或较大的依赖体积。

## 高层结构

```text
启动
  │
  ├─ 没有保存的地址
  │    └─ 原生地址输入页 → 校验 → 保存 origin → 打开 WebView
  │
  └─ 已有保存的地址
       └─ 打开 WebView
              │
              ├─ URL 带 k：网页 303 兑换 cookie，进入干净地址
              └─ URL 不带 k：网页显示原生 key 登录表单
                     │
                     └─ 提交后写入 HttpOnly cookie，进入面板
```

App 只负责外层容器和导航。网页内部仍通过 `/api/*` 请求数据，因此页面与 API 始终是同源关系。

## 地址规则

### 保存什么

持久层只保存**规范化 origin**：

```text
scheme://host[:port]
```

例如：

```text
http://192.168.31.60:3430
https://magpie.example.com
```

不保存 query、fragment、路径或 `k`。默认打开时补上 `/?view=usage`；如果第一次输入带 `?view=routing`，可以在首次导航时保留该 view，但持久值仍只保存 origin。

### 支持什么

- `http` 和 `https`。
- IPv4、局域网主机名、公网域名。
- 显式端口；省略端口时使用协议默认端口。
- 用户粘贴的完整控制面板链接，包括 `?k=` 和 `?view=`。

### 校验什么

- 必须是完整绝对 URL。
- scheme 只能是 `http` 或 `https`。
- 必须有 host。
- 不能包含 userinfo（`user:password@host`）。
- 端口必须在 `1..65535`。
- 拒绝 `javascript:`、`data:`、`file:`、`content:`、`intent:` 等 scheme。
- 对输入做首尾空白清理；未写 scheme 时默认补 `http://` 并明确提示，或要求用户补全。建议默认补 `http://`，因为局域网 magpie 默认就是 HTTP。

### 带 `k` 的链接怎么处理

第一次打开完整链接时，直接让 WebView 顶层加载它。网页会：

1. 校验 `k`；
2. 写入 `magpie_web_<port>` cookie；
3. 返回 303，跳转到去掉 `k` 的地址。

因此 App **不需要读取、解析保存或转发 key**。带 key 的 URL 只存在于首次导航和 WebView 历史中。首次成功加载后，把 WebView 的 `source` 切到干净的 `origin/?view=usage`，并清理历史，避免 Android 返回键重新进入带 key 的地址。

日志、崩溃上报和导航回调不得记录完整 URL；至少移除 `k` 参数。建议完全不接入崩溃上报。

## 网页兼容性结论

以下结论来自对 `yetone/magpie` 源码的只读研究，不是猜测。

- `3430` 是网页端口；`3425` 才是 gateway 默认端口。本 App 要填的是**网页端口**。
- 不带 `k` 访问网页会得到 HTTP 401 的静态登录页。它只有一个 `key` 密码框和隐藏的 `back` 字段，不依赖 JavaScript，WebView 可以直接完成登录。
- 登录 cookie 名为 `magpie_web_<port>`，属性为 `Path=/; HttpOnly; SameSite=Lax; Max-Age=400 天`；HTTP 下没有 `Secure`。
- `?k=` 兑换成功是 303，并保留 `view` 等其它 query 参数。
- 手机布局由 `body.web` 和 CSS media query 触发，不依赖 User-Agent。正常手机 WebView 宽度会触发底部 tab。
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

### Cookie

- 启用 `CookieManager.setAcceptCookie(true)`。
- 启用 `setAcceptThirdPartyCookies(webView, true)`。面板自身是同源 cookie，但页面内部插件 iframe 可能需要更宽松的策略。
- 不开启 incognito。
- 不清空 WebView 数据作为常规启动流程。
- 提供独立的「清除数据」入口：等待 Cookie 异步删除完成后，关闭所有弹窗 WebView、把主 WebView 切到 `about:blank`、清空历史，再显示地址输入页。默认切换地址不自动清 Cookie/缓存，避免用户每次换 URL 都重新登录。

### JavaScript / DOM Storage

- `javaScriptEnabled = true`。
- `domStorageEnabled = true`。
- 正常设置 viewport；不伪造桌面 UA，也不强制缩放。
- 页面自身的 `<meta name="viewport">` 已包含 `viewport-fit=cover`。

### 返回键

优先 WebView 历史：

```text
Android 返回键
  ├─ WebView.canGoBack() → webView.goBack()
  └─ 否则 → 退出 App
```

地址输入页按普通 Android 返回处理。

`history.replaceState` 不会新增历史，所以网页内部大量 replace 不会制造返回键困局。

### 新窗口 / 外链

- 同 origin 的页面导航留在 WebView。
- `http/https` 的新窗口先尝试在当前 WebView 打开。
- 其它 scheme（`mailto:`、`tel:`、`intent:` 等）交给系统。
- 不静默忽略 `window.open`；否则网页上的链接会像坏掉一样。
- 不在 App 内提供通用浏览器功能。

### 下载

- 开启 WebView 下载监听。
- Android 使用 `DownloadManager` 下载 CSV 等附件到公共 Downloads。
- 动态申请必要的存储权限只作为旧版本兼容；Android 10+ 优先使用系统下载器，不申请全部文件访问权限。

### 页面错误

- HTTP 401 不是错误，不拦截、不替换成 App 的错误页。
- 带 `k` 的首次请求完成后，只有目标 panel origin 的 HTTP(S) 主文档才被视为登录成功；内部 `about:` 页面不触发替换。
- WebView 的 `onReceivedHttpError` 不能把 401 登录页当成失败。
- 只有 DNS、连接拒绝、超时等 `onReceivedError` 才显示可重试的原生错误层。
- 错误页提供「重试」和「更改地址」，不显示敏感 URL。

## Android 网络安全配置

局域网默认是 HTTP，Android 9+ 默认禁止明文流量，因此需要显式放行。

推荐在 AndroidManifest 中设置：

```xml
android:usesCleartextTraffic="true"
```

这会让 App 可以访问用户填写的任意 HTTP 地址。因为 host 是运行时输入的，按域名配置 Network Security Config 无法覆盖所有可能地址。

该设置只影响 App 自身，不影响系统其它应用。仅用于自用/内测 APK 时风险可控；不要把它描述为生产级安全建议。更安全的长期方案是给 magpie 网页配置 HTTPS，再移除全局明文放行。

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

iOS Local Network 权限不在本项目范围内，因为只做 Android。

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
- 可选的应用图标与启动页。

验收：Release 页面能下载已签名 APK，覆盖安装后保留网页登录态。

## 测试清单

### 地址

- [ ] `192.168.31.60:3430` 自动补 HTTP。
- [ ] 完整 `http://` 地址。
- [ ] `https://` 地址。
- [ ] 带端口 / 不带端口。
- [ ] 域名 / IPv4。
- [ ] 带 `?k=`。
- [ ] 带 `?view=routing`。
- [ ] 空输入、非法 scheme、非法端口被拒绝。
- [ ] 保存值不含 `k`。

### 登录

- [ ] 不带 key 时显示网页原生 401 表单。
- [ ] 表单登录成功后进入面板。
- [ ] 带 key 链接自动兑换并 303。
- [ ] 重启 App 后仍登录。
- [ ] 清除数据后回到未登录状态。

### 页面

- [ ] Usage 数据加载。
- [ ] Requests 列表加载。
- [ ] Routing 页面加载。
- [ ] Providers 页面加载。
- [ ] 手机底部 tab 显示。
- [ ] 页面内链接可用。

### 导航与下载

- [ ] 返回键先回退网页历史。
- [ ] 没有历史时退出。
- [ ] `window.open` 不被忽略。
- [ ] CSV 能下载到 Downloads。
- [ ] 更换地址入口可用。

### 网络

- [ ] Android 9+ 可访问 HTTP 内网地址。
- [ ] DNS 失败有重试页。
- [ ] 服务器不可达有重试页。
- [ ] 401 不被误报为网络错误。

## 安全边界

- App 持有网页的完整控制权限，不是只读工具。
- 网页 key 能查看和修改供应商、key、设置，不应分享给不可信的人。
- HTTP 明文适合受控内网，不适合公网。
- 不记录带 key 的 URL。
- 不把 key 写入 SharedPreferences。
- 不抓取或上报网页内容。
- 不向第三方分析服务发送导航地址。

## 关键参考

- [magpie 网页登录与同源实现](https://github.com/yetone/magpie/blob/main/internal/gui/web.go)
- [magpie 网页用量接口](https://github.com/yetone/magpie/blob/main/internal/gui/usage.go)
- [magpie 网页路由接口](https://github.com/yetone/magpie/blob/main/internal/gui/trace.go)
- [Android WebView 指南](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- [Android 明文流量配置](https://developer.android.com/privacy-and-security/security-config)
