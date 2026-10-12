package com.zhou.douyinlite;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.ServiceWorkerController;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 网页缓存的节制策略，和保存登录状态的网站数据分别处理。
 *
 * <p>1 GiB 是检查后请求清理的软阈值。允许系统正常缓存和复用资源，网站缓存策略优先。
 * WebView 没有按应用设置磁盘硬上限的公开 API；网站也可以另用 Cache Storage / IndexedDB。
 * 本类不拦截视频，不下载副本，不手动删除 WebView 的任何文件。</p>
 */
public final class CacheController implements AutoCloseable {
    public static final long SOFT_LIMIT_BYTES = 1024L * 1024L * 1024L;
    private static final long CLEAR_COOLDOWN_MS = 30_000L;
    private static final long OBSERVE_AFTER_CLEAR_MS = 500L;

    // 只用作统计分类，绝不据此递归删除。不同 WebView 版本的目录布局可以不同。
    private static final String[] WEB_CACHE_DIRECTORIES = {
            "Cache", "HTTP Cache", "Code Cache", "GPUCache", "ShaderCache", "GrShaderCache",
            "Default/Cache", "Default/HTTP Cache", "Default/Code Cache", "Default/GPUCache",
            "Default/ShaderCache", "Default/GrShaderCache"
    };

    private final File appCacheDirectory;
    private final File webViewDirectory;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "douyin-cache-inventory");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean trimQueued = new AtomicBoolean();
    private long lastClearAt = Long.MIN_VALUE;

    public CacheController(Context context) {
        this(context, null);
    }

    /**
     * 独立 WebView 进程可传入与 setDataDirectorySuffix 相同的后缀。
     * 后缀构造只统计该配置的缓存和网站目录，避免探针读取主配置文件元信息。
     * 本构造方法不替调用方设置 WebView 的全局后缀，须在初始化任何 WebView API 前设置。
     */
    public CacheController(Context context, String dataDirectorySuffix) {
        Context application = context.getApplicationContext();
        appCacheDirectory = profileCacheDirectory(application.getCacheDir(), dataDirectorySuffix);
        // 不调用 getDir("webview")，避免只是查看大小就创建网站数据目录。
        webViewDirectory = profileWebViewDirectory(application.getDataDir(), dataDirectorySuffix);
    }

    static File profileWebViewDirectory(File applicationDataDirectory, String suffix) {
        validateSuffix(suffix);
        return new File(applicationDataDirectory, suffix == null
                ? "app_webview" : "app_webview_" + suffix);
    }

    static File profileCacheDirectory(File applicationCacheDirectory, String suffix) {
        validateSuffix(suffix);
        return suffix == null ? applicationCacheDirectory
                : new File(applicationCacheDirectory, "webview_" + suffix);
    }

    private static void validateSuffix(String suffix) {
        if (suffix != null && !suffix.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("WebView data suffix must match [A-Za-z0-9_-]+");
        }
    }

    /** 在创建 WebView 后、加载网站前于主线程调用。登录及 DOM storage 设置由 Activity 管理。 */
    public void configure(WebView webView) {
        requireMainThread();
        webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings()
                .setCacheMode(WebSettings.LOAD_DEFAULT);
    }

    /** 同步读取文件元信息；请在工作线程使用，界面应优先使用 snapshotAsync。 */
    public Snapshot snapshot() {
        return inspect(appCacheDirectory, webViewDirectory);
    }

    /** 后台统计，回调回到主线程。close() 后不再回调。 */
    public void snapshotAsync(Consumer<Snapshot> callback) {
        if (closed.get()) return;
        try {
            worker.execute(() -> {
                Snapshot result = snapshot();
                deliver(callback, result);
            });
        } catch (RejectedExecutionException error) {
            // close() 与其他线程刚提交的统计请求同时发生时，关闭后静默丢弃该请求。
            if (!closed.get()) throw error;
        }
    }

    public void clear(WebView webView) {
        clear(webView, null);
    }

    /**
     * 请求系统 WebView 清理资源缓存，保留 Cookies 和网站存储。
     *
     * <p>必须在主线程调用。WebView 的磁盘删除由引擎异步执行；回调是请求之后的观察值，
     * 不代表引擎所有缓存已经删完，也不能据此宣称“清空网站数据”。</p>
     */
    public void clear(WebView webView, Consumer<Snapshot> callback) {
        requireMainThread();
        if (closed.get()) return;
        webView.clearCache(true);
        lastClearAt = SystemClock.uptimeMillis();
        mainHandler.postDelayed(() -> {
            if (!closed.get()) snapshotAsync(snapshot -> {
                if (callback != null) callback.accept(snapshot.withClearRequested());
            });
        }, OBSERVE_AFTER_CLEAR_MS);
    }

    public void trimIfNeeded(WebView webView) {
        trimIfNeeded(webView, null);
    }

    /**
     * 在定期检查或进入后台时调用。扫描不阻塞主线程，同一时间只安排一次检查。
     * 超阈值且距上次清理至少 30 秒时，请求 WebView 清理；不直接碰活跃缓存或数据库。
     */
    public void trimIfNeeded(WebView webView, Consumer<Snapshot> callback) {
        requireMainThread();
        if (closed.get() || !trimQueued.compareAndSet(false, true)) return;
        snapshotAsync(snapshot -> {
            boolean cooledDown = lastClearAt == Long.MIN_VALUE
                    || SystemClock.uptimeMillis() - lastClearAt >= CLEAR_COOLDOWN_MS;
            if (snapshot.cacheBytes > SOFT_LIMIT_BYTES && cooledDown) {
                clear(webView, result -> {
                    trimQueued.set(false);
                    if (callback != null) callback.accept(result);
                });
            } else {
                trimQueued.set(false);
                if (callback != null) callback.accept(snapshot);
            }
        });
    }

    /** 由 Activity.onDestroy 调用；不销毁 WebView，也不更改登录状态。 */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        mainHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    private void deliver(Consumer<Snapshot> callback, Snapshot snapshot) {
        if (callback == null || closed.get()) return;
        mainHandler.post(() -> {
            if (!closed.get()) callback.accept(snapshot);
        });
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WebView cache operation must run on the main thread");
        }
    }

    // 包可见，允许纯 Java 检查使用独立临时目录；不需要加载、操纵真实 WebView。
    static Snapshot inspect(File cacheDirectory, File webDirectory) {
        Inventory inventory = new Inventory();
        inventory.scan(cacheDirectory, false);
        inventory.scan(webDirectory, true);
        return new Snapshot(inventory.appCacheBytes, inventory.webCacheBytes,
                inventory.siteBytes, inventory.cacheFileCount, inventory.siteFileCount,
                inventory.complete, false);
    }

    static boolean isKnownWebCache(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        for (String directory : WEB_CACHE_DIRECTORIES) {
            if (normalized.equals(directory) || normalized.startsWith(directory + "/")) {
                return true;
            }
        }
        return false;
    }

    private static final class Inventory {
        private final Set<Object> visitedFiles = new HashSet<>();
        private final Map<Long, List<Path>> filesWithoutKey = new HashMap<>();
        private long appCacheBytes;
        private long webCacheBytes;
        private long siteBytes;
        private int cacheFileCount;
        private int siteFileCount;
        private boolean complete = true;

        private void scan(File directory, boolean websiteTree) {
            Path root = directory.toPath().toAbsolutePath().normalize();
            try {
                BasicFileAttributes rootAttributes = Files.readAttributes(
                        root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // 子目录符号链接也不跟随，防止循环、重复统计或越出应用自己的已知目录。
                if (rootAttributes.isSymbolicLink()) {
                    complete = false;
                    return;
                }
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(
                            Path path, BasicFileAttributes attributes) {
                        if (Thread.currentThread().isInterrupted()) {
                            complete = false;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFile(
                            Path path, BasicFileAttributes attributes) {
                        if (Thread.currentThread().isInterrupted()) {
                            complete = false;
                            return FileVisitResult.TERMINATE;
                        }
                        if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                        if (!firstVisit(path, attributes)) return FileVisitResult.CONTINUE;
                        long bytes = attributes.size();
                        if (!websiteTree) {
                            appCacheBytes = addSafely(appCacheBytes, bytes);
                            cacheFileCount++;
                        } else if (isKnownWebCache(root.relativize(path).toString())) {
                            webCacheBytes = addSafely(webCacheBytes, bytes);
                            cacheFileCount++;
                        } else {
                            // Cookies、Local Storage、IndexedDB、Service Worker 等均只统计，不读正文。
                            siteBytes = addSafely(siteBytes, bytes);
                            siteFileCount++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
                        complete = false;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult postVisitDirectory(Path path, IOException error) {
                        if (error != null) complete = false;
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (NoSuchFileException missing) {
                // 尚未创建或扫描前已被引擎移除的整个根目录，按当前空目录处理。
            } catch (IOException | SecurityException error) {
                complete = false;
            }
        }

        private boolean firstVisit(Path path, BasicFileAttributes attributes) {
            // Android 文件系统通常提供 inode 标识。部分主机（如 Windows）不提供 fileKey，
            // 回退到 isSameFile；按长度分组避免比较明显不是同一文件的路径。
            Object identity = attributes.fileKey();
            if (identity != null) return visitedFiles.add(identity);
            Path absolute = path.toAbsolutePath().normalize();
            if (!visitedFiles.add(absolute)) return false;
            List<Path> sameSize = filesWithoutKey.computeIfAbsent(
                    attributes.size(), ignored -> new ArrayList<>());
            for (Path existing : sameSize) {
                try {
                    if (Files.isSameFile(absolute, existing)) return false;
                } catch (IOException | SecurityException error) {
                    complete = false;
                }
            }
            sameSize.add(absolute);
            return true;
        }

        private long addSafely(long current, long addition) {
            if (addition > Long.MAX_VALUE - current) {
                complete = false;
                return Long.MAX_VALUE;
            }
            return current + addition;
        }
    }

    public static final class Snapshot {
        public final long appCacheBytes;
        public final long webCacheBytes;
        /** app cache 与 WebView 已知资源缓存文件的逻辑字节数，不是整个 App 占用。 */
        public final long cacheBytes;
        /** app_webview 中其余网站及引擎数据；包含登录和网站存储，不属于自动清理对象。 */
        public final long siteBytes;
        public final long limitBytes = SOFT_LIMIT_BYTES;
        public final int cacheFileCount;
        public final int siteFileCount;
        /** 扫描期间若出现读权限/IO错误或停止扫描，此值为 false，大小仅是已观察到的部分。 */
        public final boolean complete;
        /** 本次回调前刚向引擎请求过 clearCache(true)，并非磁盘删除完成承诺。 */
        public final boolean clearRequested;

        private Snapshot(long appCacheBytes, long webCacheBytes, long siteBytes,
                int cacheFileCount, int siteFileCount, boolean complete, boolean clearRequested) {
            this.appCacheBytes = appCacheBytes;
            this.webCacheBytes = webCacheBytes;
            this.cacheBytes = appCacheBytes > Long.MAX_VALUE - webCacheBytes
                    ? Long.MAX_VALUE : appCacheBytes + webCacheBytes;
            this.siteBytes = siteBytes;
            this.cacheFileCount = cacheFileCount;
            this.siteFileCount = siteFileCount;
            this.complete = complete && appCacheBytes <= Long.MAX_VALUE - webCacheBytes;
            this.clearRequested = clearRequested;
        }

        private Snapshot withClearRequested() {
            return new Snapshot(appCacheBytes, webCacheBytes, siteBytes, cacheFileCount,
                    siteFileCount, complete, true);
        }
    }
}
