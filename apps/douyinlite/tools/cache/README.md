# 缓存策略与检查

第一版不自行下载视频，不另存视频副本。WebView 和 Service Worker 的网络资源缓存模式设置为 `LOAD_DEFAULT`，允许正常缓存和复用资源；网站返回的缓存策略与系统 WebView 决定具体复用行为。网络、网页缓冲和网站存储仍由官方网页与系统 WebView 处理，不保证刷过的全部视频都能离线重播。

缓存采用 **1 GiB（1,073,741,824 字节）软阈值**。Activity 定期检查和进入后台时调用 `trimIfNeeded`，超过阈值且距上次请求至少 30 秒时调用 `WebView.clearCache(true)`。清理入口调用相同 API；组件不递归删除应用缓存根目录，不直接删除活跃 WebView 的 HTTP 缓存、Code Cache、数据库或其他内部文件，也不宣称实现了按单个视频淘汰的自定义 LRU。

1 GiB 不是应用总占用的硬上限。WebView 没有按应用设置磁盘硬上限的公开 API，网站也可以使用 Cache Storage 等存储；引擎异步清理和播放中的缓冲可以让观察值临时超过阈值。回调中的 `clearRequested` 只表示已向系统请求清理，之后的大小是当时观察值，不能作为所有引擎缓存已经删除的证明。

建议设置页文案：

> 正常缓存并复用网页资源。缓存软阈值 1 GiB，超过后定期请求系统清理，保留登录与网站数据。系统 WebView 可能临时超过此值。

## 统计范围

- `cacheBytes` 是应用 `cache/` 和 `app_webview/` 内已知资源缓存目录的文件逻辑大小之和，包含已知 HTTP Cache、Cache、Code Cache、GPUCache、ShaderCache 与 GrShaderCache；不是实际磁盘块占用，也不是整个应用体积。
- `siteBytes` 是 `app_webview/` 中其余网站及 WebView 数据，包含 Cookies、Local Storage、IndexedDB、Service Worker、网站 Cache Storage 和引擎配置。组件只读取文件元信息，不读取这些文件内容，不清理这些数据。
- 网站缓存的目录布局随 WebView 版本变化；未知目录保守计入网站及 WebView 数据，不能据此宣称全部可清理缓存均已识别。Android 的 `code_cache/` 也不属于本组件的扫描或删除范围。
- 文件按文件系统标识去重；标识不可用时先去重规范绝对路径，再用文件系统同一性检查识别硬链接。扫描不跟随符号链接；部分读取失败时 `complete=false`，界面应注明统计不完整。
- 该快照是扫描过程中观察到的文件大小；WebView 在并行写入/清理，数字会变化。

登录和网站数据删除使用 Activity 的独立确认入口，不能把“清理缓存”绑定到 `CookieManager.removeAllCookies`、`WebStorage.deleteAllData` 或删除 `app_webview/`。

## 接入

```java
CacheController cache = new CacheController(this);
cache.configure(webView); // 主线程，加载网页前。
cache.snapshotAsync(snapshot -> { /* 主线程更新缓存、网站数据和软阈值。 */ });
cache.trimIfNeeded(webView); // 主线程，定期检查/进入后台。
cache.clear(webView, snapshot -> { /* 已请求清理；显示最新观察值。 */ });
// Activity.onDestroy:
cache.close();
```

`snapshot()` 是同步文件扫描，供工作线程使用；界面调用 `snapshotAsync`。`clear` 和 `trimIfNeeded` 操作 WebView，必须在主线程调用。组件关闭后停止工作线程并丢弃回调。

Debug 隔离探针在独立进程中先调用 `WebView.setDataDirectorySuffix("probe")`，然后使用 `new CacheController(this, "probe")`。后缀构造只扫描实机确认的 `cache/webview_probe/`（小写 `w`）和 `app_webview_probe/`，不扫描主配置的 `app_webview/` 或主缓存。原来的单参数构造等价于后缀 `null`。后缀只允许非空 `[A-Za-z0-9_-]+`，拒绝路径分隔符等越界内容；构造方法只选统计路径，不替调用方设置 WebView 引擎的后缀。

## 纯 Java 检查

在当前 App 目录运行，JDK 17 与 Android SDK 路径通过本机环境设置：

```powershell
.\tools\cache\run-cache-tests.ps1
```

检查使用独立临时目录，覆盖已知缓存分类、Cookies/Local Storage/IndexedDB/Cache Storage 保护、大小相加、重叠目录与硬链接去重、软阈值压力下统计和原文件保留，以及探针后缀路径隔离和非法后缀拒绝；主机支持符号链接时也检查不越界、不跟随链接。编译使用 Android SDK 类型，执行的是纯 Java 文件统计，不加载真实 WebView。

这些检查不替代手机实测。最终 APK 还需记录实机缓存大小、清理前后登录仍可使用，以及独立“删除网站数据”操作的行为；不使用这些模拟目录结果宣称抖音视频已经登录或播放成功。

公开 API 依据：[WebView.clearCache](https://developer.android.com/reference/android/webkit/WebView#clearCache(boolean))、[WebSettings.LOAD_DEFAULT](https://developer.android.com/reference/android/webkit/WebSettings#LOAD_DEFAULT)、[ServiceWorkerWebSettings](https://developer.android.com/reference/android/webkit/ServiceWorkerWebSettings#setCacheMode(int))。
