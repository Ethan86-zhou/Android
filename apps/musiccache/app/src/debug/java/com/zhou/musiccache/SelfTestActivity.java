package com.zhou.musiccache;

import android.os.Handler;
import android.os.Looper;

/** 仅调试构建：测试进程自行退出，真实网页登录进程始终不参与测试重启。 */
public final class SelfTestActivity extends MainActivity {
    private boolean exitScheduled;
    @Override protected boolean isSelfTestActivity() { return true; }
    @Override protected void onSelfTestReported() {
        if (exitScheduled) return;
        exitScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            finishAndRemoveTask();
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    android.os.Process.killProcess(android.os.Process.myPid()), 400);
        }, 6000);
    }
}
