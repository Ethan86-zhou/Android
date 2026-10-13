package com.zhou.douyinlite;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.ServiceWorkerController;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 直接运行真实 Controller；framework 替身只提供可控时钟、消息队列与 clearCache 调用计数。 */
public final class CacheControllerTest {
    private static int assertions;
    private static final Field CACHED;
    static {
        try {
            CACHED=CacheController.class.getDeclaredField("cachedSnapshot");
            CACHED.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("lightswipe-manual-cache-check-");
        CacheController controller=null;
        try {
            Path resource=root.resolve("cache/HTTP Cache/buffered-video");
            Path cookie=root.resolve("app_webview/Default/Cookies");
            Files.createDirectories(resource.getParent());
            Files.createDirectories(cookie.getParent());
            Files.write(cookie,new byte[] {1,2,3,4,5,6,7});
            long oversized=CacheController.SOFT_LIMIT_BYTES+4096;
            setLength(resource,oversized);
            WebView web=new WebView();
            controller=new CacheController(new Context(root.toFile()));
            CacheController cache=controller;
            cache.configure(web);
            check(web.getSettings().cacheMode==WebSettings.LOAD_DEFAULT,"normal WebView cache reuse retained");
            check(ServiceWorkerController.getInstance().getServiceWorkerWebSettings().cacheMode==WebSettings.LOAD_DEFAULT,
                    "normal service worker cache reuse retained");

            cache.updateUsage(web,true,true);
            advance(60_000);
            check(cached(cache)==null,"active playback skips periodic inventory");
            check(web.clearCalls==0,"active playback never clears resources");
            cache.updateUsage(web,true,false);
            advance(15_000);
            await(()->cached(cache)!=null);
            CacheController.Snapshot first=cached(cache);
            check(first.complete && first.overLimit && first.cacheBytes==oversized,"paused automatic observation sees over 1 GiB");
            check(!first.clearRequested && web.clearCalls==0,"paused over-limit observation does not call clearCache");
            check(Files.size(resource)==oversized,"paused observation preserves buffered fixture bytes");

            cache.updateUsage(web,false,false);
            advance(120_000);
            await(()->cached(cache)!=first);
            check(cached(cache).overLimit && web.clearCalls==0,"background over-limit observation does not call clearCache");
            check(Files.size(resource)==oversized && Files.size(cookie)==7,"background observation preserves resource and login fixtures");
            CacheController.Snapshot background=cached(cache);
            cache.trimIfNeeded(web);
            advance(120_000);
            await(()->cached(cache)!=background);
            check(web.clearCalls==0,"legacy trim hint remains observation-only above threshold");

            CacheController.Snapshot beforePlayback=cached(cache);
            cache.updateUsage(web,true,true);
            advance(1_000_000);
            check(cached(cache)==beforePlayback && web.clearCalls==0,"resumed playback cancels pending observation without clearing");

            // 模拟引擎执行显式清理后的大小变化，用于检查旧快照失效，不模拟真实网络缓存行为。
            web.onClear=()->setLength(resource,4);
            AtomicReference<CacheController.Snapshot> afterClear=new AtomicReference<>();
            cache.clear(web,afterClear::set);
            check(web.clearCalls==1 && web.includeDiskFiles,"explicit clear is the sole disk-cache API call");
            check(cached(cache)==null,"manual clear invalidates old cached inventory immediately");
            advance(500);
            await(()->afterClear.get()!=null);
            check(afterClear.get().clearRequested && afterClear.get().cacheBytes==4 && !afterClear.get().overLimit,
                    "manual clear callback observes fresh data instead of 15-second cached value");
            check(Files.size(cookie)==7,"manual cache clear does not invoke login-data deletion");

            setLength(resource,oversized);
            cache.updateUsage(web,false,false);
            CacheController.Snapshot afterManual=cached(cache);
            advance(599_000);
            check(cached(cache)==afterManual,"post-manual-clear backoff avoids needless periodic scans");
            advance(500);
            await(()->cached(cache)!=afterManual);
            check(cached(cache).overLimit && web.clearCalls==1,"exceeding threshold after manual clear never causes automatic re-clear");

            cache.close();
            cache.updateUsage(web,false,false);
            cache.clear(web);
            AtomicReference<CacheController.Snapshot> closedCallback=new AtomicReference<>();
            cache.snapshotAsync(closedCallback::set);
            advance(1_000_000);
            check(web.clearCalls==1 && closedCallback.get()==null,"closed controller rejects clearing and late callbacks");
            check(Files.size(resource)==oversized && Files.size(cookie)==7,"all automatic paths leave buffered and login fixtures intact");
            System.out.println("PASS: "+assertions+" cache controller manual-only clearing checks");
        } finally {
            if(controller!=null) controller.close();
            try(var paths=Files.walk(root)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static CacheController.Snapshot cached(CacheController controller) {
        try { return (CacheController.Snapshot)CACHED.get(controller); }
        catch(IllegalAccessException e) { throw new AssertionError(e); }
    }
    private static void setLength(Path path,long length) {
        try(RandomAccessFile file=new RandomAccessFile(path.toFile(),"rw")) { file.setLength(length); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private static void advance(long millis) {
        SystemClock.advance(millis);
        Handler.drainDue();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) {
            Handler.drainDue();
            Thread.sleep(5);
        }
        check(condition.getAsBoolean(),"asynchronous inventory completes");
    }
    private static void check(boolean value,String message) {
        assertions++;
        if(!value) throw new AssertionError(message);
    }
}
