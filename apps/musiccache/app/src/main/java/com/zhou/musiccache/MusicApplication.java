package com.zhou.musiccache;

import android.app.Application;
import android.os.Build;
import com.zhou.musiccache.player.QQWebSource;
import com.zhou.musiccache.playback.PlayerRuntime;

/** 正常播放器与后台服务共享来源和缓存；隔离自测进程不创建正常WebView。 */
public final class MusicApplication extends Application {
    private QQWebSource source;
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":cachetest")) return;
        PlayerRuntime runtime = PlayerRuntime.get(this);
        source = new QQWebSource(this, runtime); runtime.setResolver(source);
    }
    public QQWebSource source() { return source; }
}
