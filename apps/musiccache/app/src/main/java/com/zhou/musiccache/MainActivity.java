package com.zhou.musiccache;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zhou.musiccache.cache.CacheProxy;
import com.zhou.musiccache.cache.RangeCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 界面只操作浏览器和缓存状态；账号密码始终由官方页面与 WebView 处理。
 * 真实网页不注入 JavaScript 桥。自产音频自测使用独立浏览器目录，防止触碰用户登录。
 */
public class MainActivity extends Activity {
    private static final String HOME = "https://y.qq.com/n/ryqq_v2/";
    private static final String PLAYER = "https://y.qq.com/n/ryqq_v2/player";
    private static final int INK = Color.rgb(28, 48, 44);
    private static final int GREEN = Color.rgb(19, 125, 105);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WebView> browsers = new ArrayList<>();
    private final Map<WebView, Dialog> popups = new IdentityHashMap<>();
    private final Map<WebView, TextView> popupHosts = new IdentityHashMap<>();
    private WebView browser;
    private CacheProxy proxy;
    private TextView stats, host, modeHint;
    private Button offlineButton;
    private ProgressBar progress;
    private boolean selftest;
    private String testRun, testPhase;
    private File testDirectory;
    private boolean destroyed;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateStats();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selftest = BuildConfig.DEBUG && isSelfTestActivity();
        if (selftest) {
            if (Build.VERSION.SDK_INT < 28) {
                TextView unsupported = new TextView(this);
                unsupported.setText(R.string.selftest_unsupported);
                setContentView(unsupported); return;
            }
            testRun = getIntent().getStringExtra("selftest_run");
            testPhase = getIntent().getStringExtra("selftest_phase");
            if (testRun == null || !testRun.matches("[A-Za-z0-9_-]{1,64}")
                    || testPhase == null || !testPhase.matches("online|offline|partial_offline")) {
                finish(); return;
            }
            // 自测在专用进程内启动；每轮独立目录，进程退出后验证持久化。
            if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("selftest_" + testRun);
            testDirectory = new File(getFilesDir(), "selftest/" + testRun);
        }
        try {
            proxy = selftest ? new CacheProxy(new RangeCache(new File(testDirectory, "cache")), true)
                    : com.zhou.musiccache.playback.PlayerRuntime.get(this).proxy;
        } catch (Exception exception) {
            TextView failure = new TextView(this);
            failure.setText("暂时无法建立音频缓存，请检查手机剩余空间后重新打开。");
            failure.setPadding(28, 80, 28, 28); setContentView(failure); return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        buildUi();
        CookieManager.getInstance().setAcceptCookie(true);
        ServiceWorkerController controller = ServiceWorkerController.getInstance();
        controller.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                WebResourceResponse response = proxy.intercept(request);
                return response != null ? response : (proxy.isOffline() ? unavailable() : null);
            }
        });
        handler.post(ticker);
        if (selftest) startSelfTest();
        else {
            browser.loadUrl(HOME);
            if (!getPreferences(MODE_PRIVATE).getBoolean("introduced", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("introduced", true).apply();
                showHelp();
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 246));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout heading = new LinearLayout(this);
        heading.setPadding(dp(16), dp(10), dp(12), dp(4));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(selftest ? "缓存引擎 · 隔离自测" : "网页音乐 · 缓存实验", 19, INK);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        Button help = button("说明", v -> showHelp());
        heading.addView(help, new LinearLayout.LayoutParams(dp(64), dp(38)));
        root.addView(heading);
        LinearLayout state = new LinearLayout(this);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(16), 0, dp(12), dp(4));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        stats = label("已保存 0 B · 完整 0 个音频", 14, INK);
        modeHint = label("联网认证 · 优先复用已缓存音频", 11, GREEN);
        lines.addView(stats); lines.addView(modeHint);
        state.addView(lines, new LinearLayout.LayoutParams(0, dp(43), 1));
        offlineButton = button("严格离线", v -> setOffline(!proxy.isOffline()));
        state.addView(offlineButton, new LinearLayout.LayoutParams(dp(105), dp(42)));
        root.addView(state);
        host = label("官方网页：y.qq.com", 11, Color.rgb(94, 112, 106));
        host.setPadding(dp(16), 0, dp(12), dp(5)); root.addView(host);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        browser = createBrowser();
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(8), dp(5), dp(8), dp(5));
        addTool(bottom, "首页", v -> { setOffline(false); browser.loadUrl(HOME); });
        addTool(bottom, "播放页", v -> browser.loadUrl(PLAYER));
        addTool(bottom, "刷新", v -> browser.reload());
        addTool(bottom, "缓存详情", v -> showCache());
        root.addView(bottom, new LinearLayout.LayoutParams(-1, dp(55)));
        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createBrowser() {
        WebView view = new WebView(this);
        browsers.add(view);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(!selftest);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 音频代理返回 no-store 防止 WebView 偷存第二份；保留请求本身的正常缓存语义。
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (!selftest) {
            // QQ 音乐的桌面网页保留浏览器播放入口；用户仍可双指缩放。
            settings.setUserAgentString(settings.getUserAgentString()
                    .replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                    .replace(" Version/4.0", "").replace(" Mobile", ""));
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, !selftest);
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest request) {
                WebResourceResponse response = proxy.intercept(request);
                return response != null ? response : (proxy.isOffline() ? unavailable() : null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("https".equalsIgnoreCase(scheme)) return false;
                if (selftest && "http".equals(scheme)
                        && "127.0.0.1".equals(request.getUrl().getHost())) return false;
                if ("about".equals(scheme)) return false;
                if (request.isForMainFrame() && request.hasGesture()) askExternal(request.getUrl());
                return true;
            }
            @Override public void onPageFinished(WebView w, String url) {
                if (w == browser) {
                    Uri u = Uri.parse(url);
                    host.setText(getString(proxy.isOffline() ? R.string.offline_host : R.string.current_host,
                            u.getHost() == null ? "本地验证页" : u.getHost()));
                }
                TextView popupHost = popupHosts.get(w);
                if (popupHost != null) popupHost.setText(getString(R.string.current_host, Uri.parse(url).getHost()));
                if (!selftest) CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView w, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) modeHint.setText(proxy.isOffline()
                        ? "此页面尚不能离线打开，可恢复联网后再试" : "网页加载遇到问题，可点击刷新");
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView w, int newProgress) {
                if (w == browser) { progress.setProgress(newProgress); progress.setVisibility(newProgress == 100 ? View.INVISIBLE : View.VISIBLE); }
            }
            @Override public boolean onCreateWindow(WebView w, boolean isDialog, boolean userGesture, Message message) {
                if (!userGesture || selftest) return false;
                Dialog dialog = new Dialog(MainActivity.this);
                LinearLayout content = new LinearLayout(MainActivity.this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setBackgroundColor(Color.rgb(245, 247, 246));
                LinearLayout popupTools = new LinearLayout(MainActivity.this);
                Button close = button("返回音乐网页", v -> dialog.dismiss());
                popupTools.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1));
                Button popupOffline = button(proxy.isOffline() ? "恢复联网" : "严格离线", null);
                popupOffline.setOnClickListener(v -> {
                    setOffline(!proxy.isOffline());
                    popupOffline.setText(proxy.isOffline() ? "恢复联网" : "严格离线");
                });
                popupTools.addView(popupOffline, new LinearLayout.LayoutParams(0, dp(48), 1));
                popupTools.addView(button("缓存详情", v -> showCache()), new LinearLayout.LayoutParams(0, dp(48), 1));
                content.addView(popupTools);
                TextView popupHost = label("新网页窗口", 12, INK);
                popupHost.setPadding(dp(14), dp(6), dp(10), dp(6)); content.addView(popupHost);
                WebView popup = createBrowser();
                popups.put(popup, dialog); popupHosts.put(popup, popupHost);
                content.addView(popup, new LinearLayout.LayoutParams(-1, 0, 1));
                dialog.setContentView(content); dialog.show();
                if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
                dialog.setOnDismissListener(d -> {
                    browsers.remove(popup); popups.remove(popup); popupHosts.remove(popup);
                    popup.destroy(); CookieManager.getInstance().flush();
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) message.obj;
                transport.setWebView(popup); message.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window) {
                Dialog dialog = popups.get(window);
                if (dialog != null) dialog.dismiss();
            }
        });
        return view;
    }

    private void setOffline(boolean offline) {
        proxy.setOffline(offline);
        if (offline) proxy.cancelActive();
        for (WebView view : browsers) {
            if (offline) view.stopLoading();
            view.getSettings().setBlockNetworkLoads(offline);
            view.setNetworkAvailable(!offline);
        }
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(offline);
        offlineButton.setText(offline ? "恢复联网" : "严格离线");
        modeHint.setText(offline ? "严格离线 · 网页认证也会暂停" : "联网认证 · 优先复用已缓存音频");
        modeHint.setTextColor(offline ? Color.rgb(166, 91, 29) : GREEN);
        updateStats();
    }

    private void updateStats() {
        if (proxy == null || stats == null) return;
        CacheProxy.Snapshot snapshot = proxy.snapshot();
        int complete = 0;
        for (RangeCache.Entry entry : snapshot.entries) if (entry.complete) complete++;
        stats.setText(getString(R.string.cache_summary, size(snapshot.diskBytes), complete));
        if (!selftest) {
            try { writeJson(new File(getFilesDir(), "status.json"), snapshotJson(snapshot)
                    .put("offline", proxy.isOffline()).put("webHost", browser.getUrl() == null ? "" : Uri.parse(browser.getUrl()).getHost())); }
            catch (Exception ignored) { /* 诊断写入失败不能影响网页播放。 */ }
        }
    }

    private void showCache() {
        CacheProxy.Snapshot s = proxy.snapshot();
        StringBuilder text = new StringBuilder();
        text.append("仅统计 App 确认保存的音频\n\n")
                .append("保存：").append(size(s.diskBytes)).append("\n")
                .append("本次从缓存读取：").append(size(s.hitBytes)).append("\n")
                .append("本次歌曲下载：").append(size(s.networkBytes)).append("\n")
                .append("缓存校验：").append(size(s.validationBytes)).append(" / ").append(s.validationRequests).append(" 次\n")
                .append("新链接复用缓存：").append(s.reusedSignedLinks).append(" 次\n")
                .append("离线未命中：").append(s.blockedRequests).append(" 次\n\n");
        if (s.entries.isEmpty()) text.append("还没有可保存的音频。\n请登录官方网页并播放一首歌；若仍为 0，可能未识别音频或响应不允许缓存。\n");
        for (RangeCache.Entry e : s.entries) {
            text.append(e.complete ? "● 完整覆盖" : "◐ 仅部分片段")
                    .append("  ").append(size(e.cachedBytes)).append(" / ")
                    .append(e.totalBytes > 0 ? size(e.totalBytes) : "总量待确认").append("\n")
                    .append(e.label).append("\n")
                    .append(e.fresh ? (e.heuristic ? "默认保留期：24 小时" : "保留期来自服务器") : "原记录已过期，需联网确认")
                    .append("\n\n");
            if (!e.canCombineRanges && !e.complete) text.append("服务器未给出稳定的文件版本标识，暂时只保留一个响应片段。\n\n");
        }
        text.append("默认允许官方网页联网认证。播放链接变化时，用小请求确认仍是同一版本，再复用完整缓存。无可靠版本标识或内容变化时仍需下载。以上字节只统计音频正文与校验正文，不包含网页、认证及请求头流量。\n\n严格离线会同时阻止网页认证，不保证刷新或切歌后还能播放。\n\n最近记录\n");
        for (String event : s.events) text.append(event).append("\n");
        TextView detail = label(text.toString(), 14, INK);
        detail.setPadding(dp(20), dp(12), dp(20), dp(12));
        detail.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(detail);
        new AlertDialog.Builder(this).setTitle("音频缓存详情").setView(scroll)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清理音频", (d, which) -> new AlertDialog.Builder(this)
                        .setTitle("清理本 App 的音频缓存？")
                        .setMessage("会删除保存的音频片段。官方网页登录状态保留。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清理", (a, b) -> {
                            new Thread(() -> {
                                try { proxy.clearAudioCache(); }
                                catch (java.io.IOException error) {
                                    runOnUiThread(() -> Toast.makeText(this, "音频缓存未能完全清理，请重试", Toast.LENGTH_LONG).show());
                                }
                                runOnUiThread(this::updateStats);
                            }, "clear-audio").start();
                        }).show()).show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("先登录，再验证")
                .setMessage("1. 在下方 QQ 音乐官方网页登录并选歌，页面可双指放大。\n\n2. 首次正常播放会保存实际收到的音频。缓存完整后，再次播放优先从本机读取。\n\n3. 默认保持联网，允许 QQ 网页认证。播放链接变化时，先用小请求确认音频版本；确认一致后复用缓存，无需重新下载整首。打开“缓存详情”查看歌曲下载量、校验量和复用次数。\n\n“严格离线”仅用于实验，也会阻止官方网页认证，可能无法刷新或切歌。它只限制本 App，不修改手机网络。\n\n登录数据只保存在本 App 浏览器，与 Chrome 分开；不会读取或导出账号密码。")
                .setPositiveButton("打开官方网页", null).show();
    }

    private void askExternal(Uri uri) {
        if (selftest) return;
        new AlertDialog.Builder(this).setTitle("网页请求打开其他应用")
                .setMessage("如果正在使用 QQ 快捷登录，可继续打开对应应用。")
                .setNegativeButton("留在网页", null)
                .setPositiveButton("继续", (d, w) -> {
                    try {
                        Intent intent = "intent".equals(uri.getScheme())
                                ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                : new Intent(Intent.ACTION_VIEW, uri);
                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                        intent.setComponent(null); intent.setSelector(null);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) { Toast.makeText(this, "没有找到可以打开的应用，请使用网页登录", Toast.LENGTH_LONG).show(); }
                }).show();
    }

    @SuppressLint("AddJavascriptInterface")
    private void startSelfTest() {
        try {
            String requested = getIntent().getStringExtra("selftest_url");
            if (requested != null) {
                Uri u = Uri.parse(requested);
                if (!"http".equals(u.getScheme()) || !"127.0.0.1".equals(u.getHost()) || u.getPort() != 8765)
                    throw new IllegalArgumentException("Invalid test origin");
            }
            String html;
            try (java.io.InputStream stream = getAssets().open("fixture.html")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int n;
                while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
                html = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            html = html.replace("__SELFTEST_RUN_JSON__", JSONObject.quote(testRun))
                    .replace("__SELFTEST_PHASE_JSON__", JSONObject.quote(testPhase))
                    .replace("__SELFTEST_BASE_JSON__", JSONObject.quote("http://127.0.0.1:8765"));
            browser.addJavascriptInterface(new TestReporter(), "MusicCacheSelfTest");
            setOffline(!"online".equals(testPhase));
            browser.loadDataWithBaseURL("http://127.0.0.1:8765/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            modeHint.setText(getString(R.string.selftest_failure, e.getClass().getSimpleName()));
        }
    }

    private final class TestReporter {
        @JavascriptInterface public String snapshot() {
            if (!selftest || !BuildConfig.DEBUG) return "{}";
            try { return snapshotJson(proxy.snapshot()).toString(); }
            catch (Exception ignored) { return "{}"; }
        }
        @JavascriptInterface public void report(String report) {
            if (!selftest || !BuildConfig.DEBUG || report == null || report.length() > 100000) return;
            try {
                JSONObject json = new JSONObject(report);
                json.put("engine", snapshotJson(proxy.snapshot()));
                json.put("pid", android.os.Process.myPid()).put("nativeOffline", proxy.isOffline());
                writeJson(new File(testDirectory, testPhase + ".json"), json);
                runOnUiThread(MainActivity.this::onSelfTestReported);
            } catch (Exception ignored) { }
        }
    }

    protected boolean isSelfTestActivity() { return false; }
    protected void onSelfTestReported() { }

    private static JSONObject snapshotJson(CacheProxy.Snapshot s) throws Exception {
        JSONArray entries = new JSONArray();
        for (RangeCache.Entry e : s.entries) {
            JSONArray spans = new JSONArray();
            for (RangeCache.Span span : e.spans) spans.put(new JSONArray().put(span.start).put(span.endInclusive));
            entries.put(new JSONObject().put("key", e.key)
                    .put("label", e.label).put("cachedBytes", e.cachedBytes)
                    .put("totalBytes", e.totalBytes).put("complete", e.complete)
                    .put("fresh", e.fresh).put("canCombineRanges", e.canCombineRanges).put("spans", spans));
        }
        return new JSONObject().put("appVersion", BuildConfig.VERSION_NAME)
                .put("diskBytes", s.diskBytes).put("networkBytes", s.networkBytes)
                .put("hitBytes", s.hitBytes).put("blockedRequests", s.blockedRequests)
                .put("validationBytes", s.validationBytes).put("validationRequests", s.validationRequests)
                .put("reusedSignedLinks", s.reusedSignedLinks)
                .put("entries", entries).put("events", new JSONArray(s.events));
    }

    private static void writeJson(File file, JSONObject json) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("mkdir");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static WebResourceResponse unavailable() {
        return new WebResourceResponse("text/plain", "UTF-8", 504, "Offline cache miss",
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream("Offline: no cached response".getBytes(StandardCharsets.UTF_8)));
    }

    private TextView label(String value, int sp, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); return view;
    }
    private Button button(String title, View.OnClickListener action) {
        Button button = new Button(this); button.setText(title); button.setTextSize(13);
        button.setTextColor(GREEN); button.setAllCaps(false); button.setPadding(dp(5), 0, dp(5), 0);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(229, 241, 235));
        background.setCornerRadius(dp(12)); button.setBackground(background);
        button.setOnClickListener(action); button.setContentDescription(title); return button;
    }
    private void addTool(LinearLayout row, String title, View.OnClickListener action) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button(title, action), lp);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String size(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.US, "%.0f KB", value / 1024.0);
        return String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024));
    }
    @Override public void onBackPressed() {
        if (browser != null && browser.canGoBack()) browser.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() { super.onPause(); if (!selftest) CookieManager.getInstance().flush(); }
    @Override protected void onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null);
        if (selftest && proxy != null) proxy.cancelActive();
        for (WebView view : browsers) { view.stopLoading(); view.destroy(); }
        browsers.clear(); super.onDestroy();
    }
}
