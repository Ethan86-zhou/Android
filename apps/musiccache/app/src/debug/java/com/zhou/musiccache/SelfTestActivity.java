package com.zhou.musiccache;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** 仅调试构建：测试进程自行退出，真实网页登录进程始终不参与测试重启。 */
public final class SelfTestActivity extends MainActivity {
    private boolean exitScheduled;
    private volatile boolean nativeProbeFinished = true;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // 跟随父类的隔离运行约束；只在联网首轮生成容量探针数据。
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 28 || isFinishing()) return;
        String run = getIntent().getStringExtra("selftest_run");
        if (run == null || !run.matches("[A-Za-z0-9_-]{1,64}")
                || !"online".equals(getIntent().getStringExtra("selftest_phase"))) return;
        String requested = getIntent().getStringExtra("selftest_url");
        if (requested != null) {
            Uri uri = Uri.parse(requested);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() != 8765) return;
        }
        CacheCapacityProbe.run(getFilesDir(), run);
        nativeProbeFinished = false;
        new Thread(() -> {
            try { com.zhou.musiccache.playback.PlaybackVersionProbe.run(getFilesDir(), run); }
            finally { nativeProbeFinished = true; }
        }, "native-playback-version-probe").start();
    }
    @Override protected boolean isSelfTestActivity() { return true; }
    @Override protected void onSelfTestReported() {
        if (exitScheduled) return;
        if (!nativeProbeFinished) {
            new Handler(Looper.getMainLooper()).postDelayed(this::onSelfTestReported, 100);
            return;
        }
        exitScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            finishAndRemoveTask();
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    android.os.Process.killProcess(android.os.Process.myPid()), 400);
        }, 6000);
    }
}
