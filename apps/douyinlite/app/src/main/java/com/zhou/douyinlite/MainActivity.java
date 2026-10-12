package com.zhou.douyinlite;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 官网负责内容和账号；原生层只管理手势、生命周期和可清理缓存。 */
public class MainActivity extends Activity {
    private static final String RECOMMEND_URL = "https://www.douyin.com/?recommend=1&from_nav=1&is_from_mobile_home=1";
    private static final String FEATURED_URL = "https://www.douyin.com/jingxuan?from_nav=1";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WebView> browsers = new ArrayList<>();
    private final List<Dialog> popups = new ArrayList<>();
    private final Map<WebView, Dialog> popupWindows = new HashMap<>();
    private final Map<WebView, TextView> pageHosts = new HashMap<>();
    private WebView browser;
    private EdgeGestureLayout gestures;
    private CacheController cache;
    private Button speed;
    private Button featuredTab;
    private Button recommendTab;
    private View featuredMark;
    private View recommendMark;
    private TextView error;
    private ProgressBar progress;
    private String mediaScript;
    private String uiScript;
    private String selectedChannel = "recommend";
    private String pendingChannel;
    private boolean pendingFallbackUsed;
    private boolean cleanUi = true;
    private boolean foreground;
    private boolean closing;
    private long boostGeneration;
    private long pageGeneration;
    private long lastTrim;
    private OnBackInvokedCallback backCallback;

    protected boolean isProbe() { return false; }
    protected String startUrl() { return channelUrl(selectedChannel); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        cache = new CacheController(this, isProbe() ? "probe" : null);
        mediaScript = readAsset("media-control.js");
        if (!isProbe()) uiScript = readAsset("ui-cleanup.js");
        String savedChannel = getPreferences(0).getString("selectedChannel", "recommend");
        selectedChannel = "featured".equals(savedChannel) ? "featured" : "recommend";
        if (!isProbe()) pendingChannel = selectedChannel;
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        CookieManager.getInstance().setAcceptCookie(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16,16,20));
        // Android 15+ 强制边到边布局；给系统状态栏、手势栏留空间。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        // 两个频道固定在屏幕正中；其余功能收进角落，不占视频画面。
        FrameLayout tools = new FrameLayout(this);
        LinearLayout channels = new LinearLayout(this);
        channels.setGravity(Gravity.CENTER);
        featuredTab = button("精选", v -> chooseChannel("featured"));
        recommendTab = button("推荐", v -> chooseChannel("recommend"));
        featuredMark = addChannelTab(channels, featuredTab);
        recommendMark = addChannelTab(channels, recommendTab);
        tools.addView(channels,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER));
        Button more = button("⋯", v -> menu());
        more.setTextSize(24); more.setContentDescription("设置与账号");
        tools.addView(more,new FrameLayout.LayoutParams(dp(44),-1,Gravity.END));
        root.addView(tools,new LinearLayout.LayoutParams(-1,dp(44)));
        updateChannelTabs();
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        gestures = new EdgeGestureLayout(this);
        browser = createBrowser();
        gestures.addView(browser,new FrameLayout.LayoutParams(-1,-1));
        speed = button("2×", v -> gestures.resetBoost());
        speed.setContentDescription("恢复正常播放速度");
        speed.setTextColor(Color.rgb(110,240,229));
        speed.setPadding(dp(14),0,dp(14),0);
        GradientDrawable speedBackground = new GradientDrawable();
        speedBackground.setColor(0xDD252529); speedBackground.setCornerRadius(dp(16));
        speed.setBackground(speedBackground); speed.setVisibility(View.GONE);
        FrameLayout.LayoutParams speedLayout = new FrameLayout.LayoutParams(-2,dp(32),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        speedLayout.topMargin = dp(8);
        gestures.addView(speed,speedLayout);
        error = new TextView(this);
        error.setTextColor(Color.WHITE); error.setBackgroundColor(0xEE202027);
        error.setGravity(Gravity.CENTER); error.setTextSize(16);
        error.setText("网页暂时无法打开\n检查网络后，点这里重试");
        error.setVisibility(View.GONE); error.setOnClickListener(v -> browser.reload());
        gestures.addView(error,new FrameLayout.LayoutParams(-1,-1));
        gestures.setListener(new EdgeGestureLayout.Listener() {
            @Override public void onBoost(boolean boosted, boolean locked) { applyBoost(boosted,locked); }
            @Override public void onUnavailable() { toast("当前没有可加速的视频"); }
        });
        root.addView(gestures,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root); root.requestApplyInsets();
        // Android 16 不再把系统返回交给旧 onBackPressed，注册当前平台回调。
        if (Build.VERSION.SDK_INT>=33) {
            backCallback=this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);
        }
        browser.loadUrl(startUrl());
        if (!isProbe() && !getPreferences(0).getBoolean("welcomeSeen", false)) {
            getPreferences(0).edit().putBoolean("welcomeSeen",true).apply();
            new AlertDialog.Builder(this).setTitle("轻刷 · 第一版")
                .setMessage("点顶部精选或推荐，上滑看下一个。\n\n视频画面左右边缘长按：2 倍速；松手恢复。按住向下滑动：锁定 2 倍速；点画面上方的速度提示解除。\n\n登录或管理账号请点右上角 ⋯。视频不另存副本，缓存会定期清理。")
                .setPositiveButton("开始看",(d,w)->{}).show();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createBrowser() {
        WebView web = new WebView(this); browsers.add(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true); s.setJavaScriptCanOpenWindowsAutomatically(false);
        // 手机Chrome当前使用桌面站点；复用相同网页模式，保留官网的推荐流。
        s.setUserAgentString(s.getUserAgentString()
            .replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
            .replace(" Version/4.0", "").replace("; wv", "").replace(" Mobile", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        cache.configure(web);
        web.setDownloadListener((url,ua,cd,mime,len)->toast("第一版仅在线播放，不保存下载文件"));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("https".equalsIgnoreCase(scheme) || "about".equals(scheme)) return false;
                if (r.isForMainFrame() && r.hasGesture()) toast("请在网页内继续操作");
                return true;
            }
            @Override public void onPageStarted(WebView w,String url,android.graphics.Bitmap icon) {
                updateHost(w,url);
                if (w==browser) { pageGeneration++; gestures.setBoostAllowed(false); error.setVisibility(View.GONE); }
            }
            @Override public void onPageFinished(WebView w,String url) {
                if (closing || !browsers.contains(w)) return;
                updateHost(w,url);
                w.evaluateJavascript(mediaScript,value->{
                    if (closing || !browsers.contains(w)) return;
                    // 后台加载完成的网页、被登录窗口盖住的网页也必须暂停。
                    if (!foreground || w!=activeBrowser()) w.evaluateJavascript("window.LightSwipe?.pause()",null);
                });
                if (isOfficialMainDocument(w,url)) {
                    long generation=pageGeneration;
                    w.evaluateJavascript(uiScript,value->{
                        if (closing || generation!=pageGeneration || !isOfficialMainDocument(w,w.getUrl())) return;
                        w.evaluateJavascript("window.LightSwipeUi?.setClean("+cleanUi+")",null);
                        if (cleanUi) fulfillChannelIntent(generation);
                    });
                }
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView w,WebResourceRequest r,android.webkit.WebResourceError e) {
                if (w==browser && r.isForMainFrame()) error.setVisibility(View.VISIBLE);
            }
            @Override public void onReceivedHttpError(WebView w,WebResourceRequest r,android.webkit.WebResourceResponse response) {
                if (w==browser && r.isForMainFrame() && response.getStatusCode()>=400) error.setVisibility(View.VISIBLE);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView w,int value) {
                if (w==browser) { progress.setProgress(value); progress.setVisibility(value==100 ? View.INVISIBLE:View.VISIBLE); }
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onCreateWindow(WebView w,boolean dialog,boolean userGesture,Message message) {
                if (!userGesture || closing) return false;
                gestures.setBoostAllowed(false);
                Dialog popup = new Dialog(MainActivity.this); popups.add(popup);
                LinearLayout content = new LinearLayout(MainActivity.this); content.setOrientation(LinearLayout.VERTICAL);
                WebView child = createBrowser();
                popupWindows.put(child,popup);
                TextView popupHost = new TextView(MainActivity.this);
                popupHost.setText("正在打开网页窗口"); popupHost.setTextColor(Color.WHITE);
                popupHost.setPadding(dp(14),dp(10),dp(14),dp(10));
                pageHosts.put(child,popupHost);
                content.setBackgroundColor(Color.rgb(16,16,20));
                content.addView(popupHost);
                content.addView(button("关闭网页窗口",v->popup.dismiss()));
                content.addView(child,new LinearLayout.LayoutParams(-1,0,1));
                popup.setContentView(content); popup.show();
                if (popup.getWindow()!=null) popup.getWindow().setLayout(-1,-1);
                popup.setOnDismissListener(d->{ browsers.remove(child); popups.remove(popup);
                    popupWindows.remove(child); pageHosts.remove(child);
                    destroyBrowser(child); CookieManager.getInstance().flush();
                    syncPlayback(); });
                if (!foreground) child.onPause();
                syncPlayback();
                WebView.WebViewTransport t=(WebView.WebViewTransport)message.obj;
                t.setWebView(child); message.sendToTarget(); return true;
            }
            @Override public void onCloseWindow(WebView w) {
                Dialog popup=popupWindows.get(w); if (popup!=null) popup.dismiss();
            }
        });
        return web;
    }

    private void updateHost(WebView web,String url) {
        TextView label=pageHosts.get(web); if (label==null) return;
        String domain=Uri.parse(url).getHost();
        boolean official=domain!=null && (domain.equals("douyin.com") || domain.endsWith(".douyin.com"));
        label.setText(official ? "抖音官网" : domain==null ? "验证页" : domain);
    }

    private String readAsset(String name) {
        try (var input = getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192]; int count;
            while ((count=input.read(bytes))!=-1) output.write(bytes,0,count);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception ex) { throw new IllegalStateException("Missing asset: "+name, ex); }
    }

    private View addChannelTab(LinearLayout channels,Button tab) {
        LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        tab.setTextSize(16); tab.setContentDescription(tab.getText());
        item.addView(tab,new LinearLayout.LayoutParams(-1,0,1));
        View mark = new View(this); mark.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(18),dp(3));
        line.bottomMargin=dp(5); item.addView(mark,line);
        channels.addView(item,new LinearLayout.LayoutParams(dp(76),-1));
        return mark;
    }

    private void updateChannelTabs() {
        boolean featured="featured".equals(selectedChannel);
        featuredTab.setSelected(featured); recommendTab.setSelected(!featured);
        featuredTab.setTextColor(featured ? Color.WHITE : 0xFF909096);
        recommendTab.setTextColor(featured ? 0xFF909096 : Color.WHITE);
        featuredTab.setTypeface(Typeface.DEFAULT,featured ? Typeface.BOLD : Typeface.NORMAL);
        recommendTab.setTypeface(Typeface.DEFAULT,featured ? Typeface.NORMAL : Typeface.BOLD);
        featuredMark.setVisibility(featured ? View.VISIBLE : View.INVISIBLE);
        recommendMark.setVisibility(featured ? View.INVISIBLE : View.VISIBLE);
    }

    private static String channelUrl(String channel) {
        return "featured".equals(channel) ? FEATURED_URL : RECOMMEND_URL;
    }

    /** 只有主页面的抖音官网才改布局，登录弹窗和验证域名保持官网原界面。 */
    private boolean isOfficialMainDocument(WebView web,String url) {
        if (isProbe() || web!=browser || url==null) return false;
        Uri uri=Uri.parse(url); String domain=uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && ("douyin.com".equalsIgnoreCase(domain) || "www.douyin.com".equalsIgnoreCase(domain));
    }

    private void rememberChannel(String channel) {
        if (!"featured".equals(channel) && !"recommend".equals(channel)) return;
        selectedChannel=channel; updateChannelTabs();
        getPreferences(0).edit().putString("selectedChannel",channel).apply();
    }

    private void chooseChannel(String channel) {
        gestures.resetBoost(); cleanUi=true; rememberChannel(channel);
        pendingChannel=channel; pendingFallbackUsed=false;
        if (isOfficialMainDocument(browser,browser.getUrl())) fulfillChannelIntent(pageGeneration);
        else { pendingFallbackUsed=true; browser.loadUrl(channelUrl(channel)); }
    }

    /** 仅启动和明确点击产生导航意图；状态轮询不会触发页面重载。 */
    private void fulfillChannelIntent(long generation) {
        if (pendingChannel==null || !cleanUi || !isOfficialMainDocument(browser,browser.getUrl())) return;
        String channel=pendingChannel;
        String script="(function(){var ui=window.LightSwipeUi;"
                +"if(!ui||typeof ui.select!=='function')return false;"
                +"ui.setClean(true);return ui.select('"+channel+"');})()";
        browser.evaluateJavascript(script,value->{
            if (closing || generation!=pageGeneration || !channel.equals(pendingChannel)) return;
            if ("true".equals(value)) pendingChannel=null;
            else if (!pendingFallbackUsed) {
                pendingFallbackUsed=true; browser.loadUrl(channelUrl(channel));
            } else pendingChannel=null;
        });
    }

    private void showAccountPage() {
        gestures.resetBoost(); cleanUi=false; pendingChannel=null;
        if (isOfficialMainDocument(browser,browser.getUrl())) {
            browser.evaluateJavascript("window.LightSwipeUi?.setClean(false)",null);
        } else browser.loadUrl(channelUrl(selectedChannel));
        toast("已显示官网界面，可登录或管理账号；点 ⋯ 返回精简界面");
    }

    private WebView activeBrowser() {
        return browsers.isEmpty() ? null : browsers.get(browsers.size()-1);
    }

    /** 登录可以再开窗口；只恢复最上层窗口，避免两个网页同时播放。 */
    private void syncPlayback() {
        if (closing) return;
        WebView active=foreground ? activeBrowser() : null;
        for (WebView web:browsers) {
            web.evaluateJavascript(web==active ? "window.LightSwipe?.resume()" : "window.LightSwipe?.pause()",null);
        }
    }

    private void destroyBrowser(WebView web) {
        web.stopLoading();
        if (web.getParent() instanceof ViewGroup) ((ViewGroup)web.getParent()).removeView(web);
        web.destroy();
    }

    private void applyBoost(boolean boosted,boolean locked) {
        if (closing) return;
        if (boosted && (!foreground || !popups.isEmpty())) { gestures.resetBoost(); return; }
        long generation=++boostGeneration;
        if (!boosted) speed.setVisibility(View.GONE);
        browser.evaluateJavascript("window.LightSwipe ? window.LightSwipe.setBoost("+boosted+") : null",value->{
            if (generation!=boostGeneration || closing || isFinishing() || isDestroyed()) return;
            if (boosted) {
                try {
                    JSONObject result=new JSONObject(value);
                    if (!result.optBoolean("boosted")) { gestures.resetBoost(); toast("当前视频暂不支持加速"); return; }
                } catch(Exception ex) { gestures.resetBoost(); return; }
                speed.setText(locked ? "2× 已锁定 · 点此恢复" : "2× · 下滑锁定");
                speed.setVisibility(View.VISIBLE);
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!foreground || closing) return;
            long generation=pageGeneration;
            browser.evaluateJavascript("({media:window.LightSwipe?.status(),ui:window.LightSwipeUi?.status()})",value->{
                if (!foreground || closing || generation!=pageGeneration) return;
                try {
                    JSONObject result=new JSONObject(value);
                    JSONObject status=result.getJSONObject("media");
                    gestures.setBoostAllowed(status.optBoolean("available") && !status.optBoolean("paused") && popups.isEmpty());
                    if (status.optBoolean("playing") && popups.isEmpty()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    JSONObject ui=result.optJSONObject("ui");
                    if (ui!=null && pendingChannel==null && isOfficialMainDocument(browser,browser.getUrl())) {
                        String channel=ui.optString("channel","");
                        if (!channel.equals(selectedChannel) && ("featured".equals(channel) || "recommend".equals(channel))) rememberChannel(channel);
                    }
                } catch(Exception ignored) { gestures.setBoostAllowed(false); }
            });
            long now=android.os.SystemClock.elapsedRealtime();
            if (now-lastTrim>30000) { lastTrim=now; cache.trimIfNeeded(browser); }
            handler.postDelayed(this,700);
        }
    };

    private void menu() {
        gestures.resetBoost();
        new AlertDialog.Builder(this).setTitle("轻刷 "+BuildConfig.VERSION_NAME)
            .setItems(new String[]{cleanUi ? "登录 / 账号" : "返回精简界面","刷新当前网页","缓存与网站数据","手势说明"},(d,index)->{
                if (index==0) { if (cleanUi) showAccountPage(); else chooseChannel(selectedChannel); }
                if (index==1) browser.reload();
                if (index==2) showCache();
                if (index==3) new AlertDialog.Builder(this).setTitle("快进手势")
                    .setMessage("视频画面左右边缘长按约 0.4 秒进入 2 倍速，松手恢复原速。\n\n长按生效后向下滑约一个拇指宽度可锁定，点画面上方的速度提示解除。锁定后仍可上滑切视频。\n\n避开顶部标签、底部进度条及屏幕最边缘的系统返回手势。切到后台会暂停视频并解除加速。")
                    .setPositiveButton("知道了",null).show();
            }).show();
    }

    private void showCache() {
        cache.snapshotAsync(s->{ if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this).setTitle("缓存与网站数据")
                .setMessage("可清理缓存："+size(s.cacheBytes)+"\n网站及 WebView 数据："+size(s.siteBytes)
                    +"\n\n正常缓存并复用官网资源，视频不另存副本。每 30 秒检查缓存，超过 1 GiB 软阈值时请求清理；系统可能临时超过阈值。\n\n清理缓存保留登录。网站数据包含登录与站点设置，不计入该阈值。"
                    +(s.complete ? "" : "\n部分目录暂时无法统计。"))
                .setPositiveButton("清理缓存",(d,w)->cache.clear(browser,after->toast("已请求清理；当前缓存 "+size(after.cacheBytes))))
                .setNeutralButton("重置网站数据",(d,w)->confirmReset())
                .setNegativeButton("返回",null).show(); });
    }

    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("退出登录并重置网站数据？")
            .setMessage("这会退出本 App 内的网页登录，并删除本站点保存的设置。手机 Chrome 不受影响。")
            .setPositiveButton("重置并退出登录",(d,w)->{
                gestures.resetBoost(); browser.stopLoading();
                browser.loadUrl("about:blank");
                WebStorage.getInstance().deleteAllData();
                CookieManager.getInstance().removeAllCookies(done->{ CookieManager.getInstance().flush();
                    if (closing || isDestroyed()) return;
                    browser.clearCache(true); browser.clearHistory(); chooseChannel(selectedChannel); });
            }).setNegativeButton("取消",null).show();
    }

    private Button button(String text,View.OnClickListener action) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(12);
        b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0,0,0,0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setOnClickListener(action); return b;
    }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private static String size(long n) { return String.format(Locale.ROOT,"%.1f MiB",n/1048576.0); }

    @Override protected void onResume() { super.onResume(); foreground=true;
        for (WebView w:browsers) w.onResume();
        syncPlayback();
        handler.removeCallbacks(tick); handler.post(tick);
    }
    @Override protected void onPause() {
        foreground=false; handler.removeCallbacks(tick); gestures.setBoostAllowed(false);
        syncPlayback();
        for (WebView w:browsers) w.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush(); cache.trimIfNeeded(browser); super.onPause();
    }
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        if (gestures.isBoostLocked()) { gestures.resetBoost(); return; }
        if (!cleanUi) { chooseChannel(selectedChannel); return; }
        // 官网精选详情使用浮层，并不一定添加浏览器历史；先交给官网关闭浮层。
        long generation=pageGeneration;
        browser.evaluateJavascript("window.LightSwipeUi?.dismissVideo?.() === true",value->{
            if (closing || generation!=pageGeneration || isFinishing() || isDestroyed()) return;
            if (!"true".equals(value)) navigateBack();
        });
    }

    private void navigateBack() {
        if (browser.canGoBack()) browser.goBack();
        else if (isTaskRoot()) moveTaskToBack(true);
        else finish();
    }
    @Override protected void onDestroy() {
        closing=true; foreground=false; boostGeneration++; pageGeneration++;
        if (Build.VERSION.SDK_INT>=33 && backCallback!=null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        handler.removeCallbacksAndMessages(null);
        gestures.setBoostAllowed(false);
        for (Dialog popup:new ArrayList<>(popups)) popup.dismiss();
        for (WebView w:browsers) destroyBrowser(w);
        browsers.clear(); popupWindows.clear(); pageHosts.clear();
        cache.close(); super.onDestroy();
    }
}
