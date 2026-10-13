package com.zhou.douyinlite;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

/** 只存在于 debug：离线视频和官网使用不同进程、不同 WebView 数据目录。 */
public final class ProbeActivity extends MainActivity {
    private static boolean directoryConfigured;

    @Override public void onCreate(Bundle saved) {
        if (!directoryConfigured) {
            // 必须先于 MainActivity 创建 WebView / CookieManager，防止触及真实登录目录。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw new UnsupportedOperationException("离线验证页需要 Android 9 或更新版本");
            }
            WebView.setDataDirectorySuffix("probe");
            directoryConfigured = true;
        }
        super.onCreate(saved);
    }

    @Override protected boolean isProbe() { return true; }

    @Override protected String startUrl() { return "file:///android_asset/probe.html"; }
}
