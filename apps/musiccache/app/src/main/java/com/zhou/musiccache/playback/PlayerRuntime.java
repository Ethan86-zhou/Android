package com.zhou.musiccache.playback;

import android.content.Context;

import com.zhou.musiccache.cache.CacheProxy;
import com.zhou.musiccache.cache.RangeCache;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 同一进程的网页与原生播放器共用唯一缓存索引；不另建缓存、不移动旧音频。 */
public final class PlayerRuntime {
    private static volatile PlayerRuntime instance;
    public final CacheProxy proxy;
    private volatile SourceResolver resolver;

    public interface SourceResolver {
        /** 在播放器加载线程调用；网页登录/权限解析由应用实现，不能在主线程阻塞等待自身。 */
        ResolvedAudio resolve(String songId) throws IOException;
    }

    /** 签名地址及请求凭据只存于内存，禁止写入MediaItem、日志或持久化队列。 */
    public static final class ResolvedAudio {
        public final String url;
        public final Map<String, String> headers;
        public ResolvedAudio(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                    headers == null ? Collections.emptyMap() : headers));
        }
    }

    private PlayerRuntime(Context context) {
        try {
            proxy = new CacheProxy(new RangeCache(new File(context.getFilesDir(), "audio-cache")));
        } catch (IOException error) {
            // 文件路径及系统异常无需扩散到播放器日志。
            throw new IllegalStateException("无法打开音频缓存");
        }
    }

    public static PlayerRuntime get(Context context) {
        PlayerRuntime current = instance;
        if (current == null) {
            synchronized (PlayerRuntime.class) {
                current = instance;
                if (current == null) instance = current = new PlayerRuntime(context.getApplicationContext());
            }
        }
        return current;
    }

    public void setResolver(SourceResolver resolver) { this.resolver = resolver; }

    ResolvedAudio resolve(String songId) throws IOException {
        SourceResolver current = resolver;
        if (current == null) throw new IOException("请先打开应用并登录QQ音乐");
        return current.resolve(songId);
    }
}
