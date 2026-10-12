# 轻刷 LightSwipe · v0.2.0

使用中国大陆抖音官方网页的轻量 Android 浏览器。独立包名 `com.zhou.douyinlite`，不需要安装抖音原生 APP；账号、内容、推荐和播放权限由官网决定。

[安装包 LightSwipe-0.2.0-debug.apk](outputs/2026-10-12-v0.2.0/LightSwipe-0.2.0-debug.apk)，80,196 字节，约 80 KB。已在 Samsung SM-S9080 / Android 16 安装；使用手机系统 WebView，不把浏览器引擎重复打入 APK。实际 App 数据占用另计。

## 使用

- 顶部只显示原生“精选 / 推荐”两标签和右上角 `⋯`。精选显示官网视频卡片，推荐直接播放视频并上滑切换。
- 官网侧栏、搜索栏和精选分类条收起，内容铺满可用宽度。切频道使用官网原有导航，账号、推荐和视频仍由官网处理。
- 视频左右边缘静止长按约 0.4 秒进入 2 倍速，松手恢复之前的倍率；长按后下滑锁定，点顶部速度按钮解除。锁定后继续上滑可换视频。顶部标签和底部进度条不触发该手势。
- 普通滑动交给官网。切到后台、触摸取消、多点触摸、网页跳转会解除加速；后台暂停视频。
- 正常播放时不显示倍速按钮；加速时在画面上方显示临时提示，锁定后点提示解除。
- 右上角 `⋯` 提供刷新、手势说明、缓存统计和清理。固定竖屏，视频播放时保持屏幕亮。
- 登录或管理账号：`⋯` →“登录 / 账号”恢复完整官网界面；完成后选“返回精简界面”或点击任一频道。官网登录弹窗保持原界面。本 App 不复制 Chrome 的账号状态，也不读取密码；登录 Cookie 与站点存储仅存在应用私有空间。

## 缓存控制

允许官网按 HTTP 缓存规则复用网页和视频资源，WebView 与 Service Worker 使用 `LOAD_DEFAULT`。不下载或另存视频副本，不承诺离线播放；缓存复用取决于官网响应、视频传输方式和当前授权，官网自身可能预加载内容。

每 30 秒及退到后台时检查可识别的临时缓存；超过 **1 GiB（1024 MiB）软阈值**时通过系统 WebView 请求清理。该值不是整个 App 数据的硬上限，系统生成缓存和异步清理可能短暂超过它。第一版超阈值使用系统清理接口，不是自建视频 LRU；清理后需要重新缓冲。

“清理缓存”保留 Cookie、Local Storage、IndexedDB 等登录与网站数据。统计中的“网站及 WebView 数据”单独列出，不包含在缓存阈值中；如站点数据持续增长，可在设置中明确确认“重置网站数据”，这会退出本 App 登录。不会影响 Chrome 或轻听。

本 App 不修改抖音资源授权，不抓取接口或导出视频，不添加广告或分析 SDK，仅申请网络权限。禁止文件访问和明文 HTTP；备份关闭。

## 构建

JDK 17、Gradle 8.10.2、AGP 8.7.3、compileSdk / targetSdk 36、minSdk 26。配置本机 `JAVA_HOME`、`ANDROID_HOME`，使用本项目 Wrapper：

```powershell
.\gradlew.bat assembleDebug lintDebug
```

只有 Android framework 依赖，系统 WebView 由手机提供。调试版开放 WebView 调试，发布版关闭。不要将调试接口转发到外部网络。

## 验证与局限

各版本构建、设备、APK SHA-256、源码基线与逐文件哈希分别保存。模拟网页或自产视频验证不等同于抖音登录后真实播放。含账号或视频内容的截图只保留本机，不纳入公开仓库。

### v0.2.0 精简界面

本版记录位于 `outputs/2026-10-12-v0.2.0/`，覆盖安装保留已有官网登录。

- 构建和 lint 完成，0 错误、4 项固定竖屏相关警告。21 组界面适配场景、14 组网页媒体场景、33 项手势状态检查、42 项缓存统计检查通过；3 项符号链接检查因 Windows 权限跳过。
- [真实官网界面检查](outputs/2026-10-12-v0.2.0/device-ui-final-02/)的 66 项检查通过：原生两频道选中、精选卡片和推荐播放、官网侧栏与页头隐藏、内容满宽、精选打开视频后系统返回、完整官网账号界面与精简恢复。
- [真实视频手势检查](outputs/2026-10-12-v0.2.0/device-real-final/)的 37 项检查通过：播放进度推进、普通上滑换视频、边缘长按 2 倍速和松手恢复、下滑锁定后换视频、临时提示解除、后台暂停与前台恢复。
- [缓存实机检查](outputs/2026-10-12-v0.2.0/device-cache-final/)的 13 项检查通过：1 GiB 软阈值仍显示，手动清理从 24.0 MiB 降到 1.7 MiB；清理后和刷新后，在完整官网界面仍识别到登录账号头像且没有登录按钮，再恢复精简界面。未在设备上填满 1 GiB。
- 精选详情由官网浮层显示，不保证增加 WebView 历史。本版通过官网自身 Escape 处理关闭浮层；Android 13 及以上注册平台返回回调，避免 Android 16 将返回直接交给系统退出。
- 开发中失败记录保留：第一轮卡片挂载等待不足，后续发现卡片预览不能作为详情打开的证据，以及 Android 16 不调用旧返回接口。已修正验收前提和返回处理，最终 APK 独立重测。

APK SHA-256：`5c7448a390cbd1e95d654c2151d1d3b4a12fcebaad36299ecbb819366b17af37`。详细源码对应关系见[本版交付核验](outputs/2026-10-12-v0.2.0/delivery-verification.json)。

### v0.1.0 历史验证

第一版交付记录位于 `outputs/2026-10-12-v0.1.0/`，下列结论对应当时 APK：

- 构建和 lint 完成，0 错误；4 项固定竖屏相关警告（主页面及仅 debug 的探针页面）。14 组网页媒体场景、33 项手势状态检查、42 项缓存统计检查通过；3 项符号链接检查因 Windows 权限跳过。
- [独立自产视频探针](outputs/2026-10-12-v0.1.0/device-probe-final/)通过播放、普通滑动、长按 2 倍速、松手恢复、下滑锁定、锁定后换视频、原生按钮解除及前后台恢复。探针用独立进程、独立 WebView 数据目录。
- [抖音真实视频](outputs/2026-10-12-v0.1.0/device-real-01/)完成相同手势和生命周期检查，进度实际推进、上滑后视频发生变化、锁定后新视频仍为 2 倍速，后台暂停全部视频，返回后同一视频恢复正常速度。
- [缓存实机检查](outputs/2026-10-12-v0.1.0/device-cache-final/cache-check.json)显示原生设置页的 1 GiB 阈值；手动清理从 17.8 MiB 降到 0.1 MiB，清理后及刷新后官网仍登录，精选 / 推荐入口仍可见。未在手机上填满 1 GiB，未承诺所有官网视频会命中缓存或支持离线回看。
- 覆盖安装保留用户此次官网登录。前两份探针记录分别因初始处于加速状态、初始在第二条（不能再上滑）而未通过，原始记录保留；之后重置前提完成验证，最终 APK 另存于 `device-probe-final`。

APK SHA-256：`4e194fa6916396ebfab1af72cc5946b5885c5d3df00f08d05cc2b6d5619292be`。详细源码对应关系见[交付核验](outputs/2026-10-12-v0.1.0/delivery-verification.json)。

官网随时可能调整布局或登录方式；长按手势适用于当前页面可识别的 HTML 视频，直播、图集、跨源 iframe 等暂不承诺。所有用户操作以官网返回结果为准。

实现参考：[Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)、[WebView 清理缓存](https://developer.android.com/reference/android/webkit/WebView#clearCache(boolean))、[HTML 视频倍速](https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/playbackRate)、[系统返回适配](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)。
