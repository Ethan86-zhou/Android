package com.zhou.douyinlite;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 只验证独立临时目录的统计和安全边界；不操作手机、登录或真实 WebView。 */
public final class CacheInventoryTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("douyin-cache-check-");
        try {
            Path cache = temporary.resolve("cache");
            Path web = temporary.resolve("app_webview");
            CacheController.Snapshot empty = CacheController.inspect(cache.toFile(), web.toFile());
            equal(0, empty.cacheBytes, "missing directories have no cache");
            equal(0, empty.siteBytes, "missing directories have no site data");
            check(empty.complete, "missing directories are a complete empty scan");
            equal(1024L * 1024 * 1024, empty.limitBytes, "reported 1 GiB soft threshold");

            write(cache.resolve("WebView/Default/HTTP Cache/data_0"), 11);
            write(web.resolve("Default/Cache/data_1"), 13);
            write(web.resolve("Default/Code Cache/js/code"), 17);
            write(web.resolve("GPUCache/data_2"), 19);
            write(web.resolve("Default/Cookies"), 23);
            write(web.resolve("Default/Local Storage/leveldb/000001.log"), 29);
            write(web.resolve("Default/IndexedDB/test/blob"), 31);
            write(web.resolve("Default/Service Worker/CacheStorage/test/body"), 37);
            write(web.resolve("Default/CacheStorage/private"), 41);
            write(web.resolve("Default/Cache-backup/private"), 43);
            CacheController.Snapshot snapshot = CacheController.inspect(cache.toFile(), web.toFile());
            equal(11, snapshot.appCacheBytes, "Android app cache tree counted once");
            equal(49, snapshot.webCacheBytes, "known WebView resource caches counted");
            equal(60, snapshot.cacheBytes, "cache categories add up");
            equal(204, snapshot.siteBytes, "protected website data remains separate");
            equal(4, snapshot.cacheFileCount, "cache file count");
            equal(6, snapshot.siteFileCount, "website file count");
            check(snapshot.complete, "ordinary scan complete");
            check(!snapshot.clearRequested, "inventory does not claim cache deletion");
            check(CacheController.isKnownWebCache("Default\\Code Cache\\js\\code"),
                    "Windows separator accepted by inventory tests");
            check(!CacheController.isKnownWebCache("Default/CacheStorage/a"),
                    "CacheStorage is website data");
            check(!CacheController.isKnownWebCache("Default/Cache-backup/a"),
                    "prefix is not a directory match");
            check(!CacheController.isKnownWebCache("Default/Cookies"), "cookies protected");
            check(!CacheController.isKnownWebCache("Default/Local Storage/a"), "local storage protected");
            check(!CacheController.isKnownWebCache("Default/IndexedDB/a"), "IndexedDB protected");

            equalPath(web, CacheController.profileWebViewDirectory(temporary.toFile(), null).toPath(),
                    "null suffix selects production WebView directory");
            equalPath(temporary.resolve("app_webview_probe"),
                    CacheController.profileWebViewDirectory(temporary.toFile(), "probe").toPath(),
                    "probe suffix selects isolated WebView directory");
            equalPath(cache, CacheController.profileCacheDirectory(cache.toFile(), null).toPath(),
                    "default constructor keeps application cache root");
            equalPath(cache.resolve("webview_probe"),
                    CacheController.profileCacheDirectory(cache.toFile(), "probe").toPath(),
                    "probe cache avoids scanning production cache");
            equalPath(temporary.resolve("app_webview_Probe-1_x"),
                    CacheController.profileWebViewDirectory(temporary.toFile(), "Probe-1_x").toPath(),
                    "safe mixed-case suffix accepted");
            for (String invalid : new String[] { "", ".", "..", "../main", "a/b", "a\\b", "probe x", "探针" }) {
                boolean rejected = false;
                try {
                    CacheController.profileWebViewDirectory(temporary.toFile(), invalid);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                check(rejected, "unsafe or empty suffix is rejected: " + invalid);
            }
            Path probeCache = CacheController.profileCacheDirectory(cache.toFile(), "probe").toPath();
            Path probeWeb = CacheController.profileWebViewDirectory(temporary.toFile(), "probe").toPath();
            write(probeCache.resolve("Default/HTTP Cache/test"), 5);
            write(probeWeb.resolve("Default/Cookies"), 7);
            CacheController.Snapshot probe = CacheController.inspect(probeCache.toFile(), probeWeb.toFile());
            equal(5, probe.cacheBytes, "probe inventory excludes production cache");
            equal(7, probe.siteBytes, "probe inventory excludes production login data");
            equal(23, Files.size(web.resolve("Default/Cookies")), "probe inventory preserves production cookies");
            // 后续主配置检查依然使用最初 fixture，避免把探针缓存混入主配置断言。
            Files.delete(probeCache.resolve("Default/HTTP Cache/test"));

            CacheController.Snapshot repeatedRoot = CacheController.inspect(cache.toFile(), cache.toFile());
            equal(11, repeatedRoot.cacheBytes, "overlapping roots do not double count");
            equal(0, repeatedRoot.siteBytes, "duplicate root not reclassified as website data");

            Path hardlink = cache.resolve("alias");
            Files.createLink(hardlink, cache.resolve("WebView/Default/HTTP Cache/data_0"));
            CacheController.Snapshot linked = CacheController.inspect(cache.toFile(), web.toFile());
            equal(60, linked.cacheBytes, "hardlink inode counted once");
            equal(4, linked.cacheFileCount, "hardlink does not inflate file count");

            Path huge = cache.resolve("threshold-probe");
            try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
                file.setLength(CacheController.SOFT_LIMIT_BYTES);
            }
            CacheController.Snapshot aboveThreshold = CacheController.inspect(cache.toFile(), web.toFile());
            check(aboveThreshold.cacheBytes > aboveThreshold.limitBytes, "soft threshold crossing detected");
            equal(204, aboveThreshold.siteBytes, "cache pressure does not change site data");
            check(Files.exists(web.resolve("Default/Cookies")), "inventory preserves cookies");
            equal(23, Files.size(web.resolve("Default/Cookies")), "cookie bytes preserved");
            Files.delete(huge);

            // Windows 未开启开发者模式时创建符号链接可能需要权限；支持时额外验证不跟随。
            boolean symlinkSupported = true;
            Path outside = temporary.resolve("outside");
            write(outside.resolve("private"), 71);
            try {
                Files.createSymbolicLink(cache.resolve("external-link"), outside);
            } catch (java.nio.file.FileSystemException | UnsupportedOperationException error) {
                symlinkSupported = false;
                System.out.println("SKIP: symbolic-link creation is unavailable on this host");
            }
            if (symlinkSupported) {
                CacheController.Snapshot symlink = CacheController.inspect(cache.toFile(), web.toFile());
                equal(60, symlink.cacheBytes, "symlink target outside allowed trees is not read");
                CacheController.Snapshot rootLink = CacheController.inspect(
                        cache.resolve("external-link").toFile(), web.toFile());
                equal(49, rootLink.cacheBytes, "symbolic-link root is not followed");
                check(!rootLink.complete, "skipped symbolic-link root is reported incomplete");
            }
            System.out.println("PASS: " + assertions + " cache inventory checks");
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void write(Path path, int bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[bytes]);
    }

    private static void equal(long expected, long actual, String message) {
        check(expected == actual, message + ": expected " + expected + ", actual " + actual);
    }

    private static void equalPath(Path expected, Path actual, String message) {
        check(expected.toAbsolutePath().normalize().equals(actual.toAbsolutePath().normalize()), message);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
