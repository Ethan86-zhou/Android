package com.zhou.douyinlite;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
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
import android.widget.SeekBar;
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
    private LinearLayout playerBar;
    private Button playback;
    private PlaybackSeekBar videoProgress;
    private TextView seekTime;
    private TextView playbackMessage;
    private long playbackMessageSince;
    private String playbackMessageKind = "";
    private boolean playerContextActive;
    private boolean mediaAvailable;
    private boolean mediaSeekable;
    private long currentMediaId;
    private double mediaDuration;
    private boolean draggingSeek;
    private boolean seekDragCancelled;
    private boolean ignoreSeekTouch;
    private long dragMediaId;
    private long dragPageGeneration;
    private double dragDuration;
    private long playerControlGeneration;
    private String mediaScript;
    private String uiScript;
    private String selectedChannel = "recommend";
    private String pendingChannel;
    private boolean pendingFallbackUsed;
    private long channelIntentGeneration;
    private long channelIntentMinPageGeneration;
    private boolean pageLoading = true;
    private boolean pageCommitted;
    private boolean openingTimedOut;
    private boolean cleanUi = true;
    private boolean foreground;
    private boolean closing;
    private long boostGeneration;
    private long pageGeneration;
    private OnBackInvokedCallback backCallback;
    private final Runnable channelTimeout = () -> {
        if (closing || pendingChannel==null || !foreground || !popups.isEmpty()) return;
        cancelChannelIntent(); openingTimedOut=true;
        // 保留官网继续加载的机会；轻提示可点重试，不遮住整页。
        updatePlaybackMessage(new JSONObject(),false);
    };

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
        seekTime = new TextView(this);
        seekTime.setTextColor(Color.WHITE); seekTime.setTextSize(13);
        seekTime.setGravity(Gravity.CENTER); seekTime.setPadding(dp(12),0,dp(12),0);
        seekTime.setContentDescription("拖动进度时间");
        GradientDrawable timeBackground = new GradientDrawable();
        timeBackground.setColor(0xDD252529); timeBackground.setCornerRadius(dp(16));
        seekTime.setBackground(timeBackground); seekTime.setVisibility(View.GONE);
        FrameLayout.LayoutParams timeLayout = new FrameLayout.LayoutParams(-2,dp(32),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        timeLayout.bottomMargin=dp(48);
        gestures.addView(seekTime,timeLayout);
        playbackMessage = new TextView(this);
        playbackMessage.setContentDescription("视频缓冲状态");
        playbackMessage.setTextColor(Color.WHITE); playbackMessage.setTextSize(14);
        playbackMessage.setGravity(Gravity.CENTER); playbackMessage.setPadding(dp(16),dp(10),dp(16),dp(10));
        GradientDrawable messageBackground = new GradientDrawable();
        messageBackground.setColor(0xDE252529); messageBackground.setCornerRadius(dp(18));
        playbackMessage.setBackground(messageBackground); playbackMessage.setVisibility(View.GONE);
        playbackMessage.setOnClickListener(v->{ if (openingTimedOut) reloadChannel(); });
        playbackMessage.setClickable(false);
        gestures.addView(playbackMessage,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        error = new TextView(this);
        error.setTextColor(Color.WHITE); error.setBackgroundColor(0xEE202027);
        error.setGravity(Gravity.CENTER); error.setTextSize(16);
        error.setText("网页暂时无法打开\n检查网络后，点这里重试");
        error.setVisibility(View.GONE); error.setOnClickListener(v -> reloadChannel());
        gestures.addView(error,new FrameLayout.LayoutParams(-1,-1));
        gestures.setListener(new EdgeGestureLayout.Listener() {
            @Override public void onBoost(boolean boosted, boolean locked) { applyBoost(boosted,locked); }
            @Override public void onUnavailable() { toast("当前没有可加速的视频"); }
        });
        root.addView(gestures,new LinearLayout.LayoutParams(-1,0,1));
        createPlayerBar(root);
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
            new AlertDialog.Builder(this).setTitle("轻刷")
                .setMessage("点顶部精选或推荐，上滑看下一个。\n\n视频画面左右边缘长按：2 倍速；松手恢复。按住向下滑动：锁定 2 倍速；点画面上方的速度提示解除。\n\n登录或管理账号请点右上角 ⋯。视频不另存副本，App 不会自动清空缓存。")
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
        web.setDownloadListener((url,ua,cd,mime,len)->toast("仅在线播放，不另存视频"));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                if ("https".equalsIgnoreCase(scheme) || "about".equals(scheme)) return false;
                if (r.isForMainFrame() && r.hasGesture()) toast("请在网页内继续操作");
                return true;
            }
            @Override public void onPageStarted(WebView w,String url,android.graphics.Bitmap icon) {
                updateHost(w,url);
                if (w==browser) {
                    pageGeneration++; pageLoading=true; pageCommitted=false; resetPlayerControls();
                    cache.updateUsage(browser,foreground,true);
                    gestures.setBoostAllowed(false); error.setVisibility(View.GONE);
                }
            }
            @Override public void onPageFinished(WebView w,String url) {
                if (closing || !browsers.contains(w)) return;
                updateHost(w,url);
                if (w==browser) { pageLoading=false; pageCommitted=true; }
                installPageControllers(w,url);
                CookieManager.getInstance().flush();
            }
            @Override public void onPageCommitVisible(WebView w,String url) {
                // 首屏内容可见时就安装布局/媒体控制，后续资源完成时仅作幂等兜底。
                if (w==browser) pageCommitted=true;
                installPageControllers(w,url);
            }
            @Override public void onReceivedError(WebView w,WebResourceRequest r,android.webkit.WebResourceError e) {
                if (w==browser && r.isForMainFrame()) { pageLoading=false; error.setVisibility(View.VISIBLE); }
            }
            @Override public void onReceivedHttpError(WebView w,WebResourceRequest r,android.webkit.WebResourceResponse response) {
                if (w==browser && r.isForMainFrame() && response.getStatusCode()>=400) {
                    pageLoading=false; error.setVisibility(View.VISIBLE);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView w,int value) {
                if (w==browser) { progress.setProgress(value); progress.setVisibility(value==100 ? View.INVISIBLE:View.VISIBLE); }
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onCreateWindow(WebView w,boolean dialog,boolean userGesture,Message message) {
                if (!userGesture || closing) return false;
                cache.updateUsage(browser,foreground,true);
                handler.removeCallbacks(channelTimeout);
                browser.evaluateJavascript("window.LightSwipeUi?.cancelNavigation?.()",null);
                resetPlayerControls();
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
                    syncPlayback();
                    if (pendingChannel!=null) { armChannelTimeout(); fulfillChannelIntent(pageGeneration); }
                });
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

    private void installPageControllers(WebView web,String url) {
        if (closing || !browsers.contains(web)) return;
        web.evaluateJavascript("if(!window.LightSwipe){"+mediaScript+"}",value->{
            if (closing || !browsers.contains(web)) return;
            if (!foreground || web!=activeBrowser()) web.evaluateJavascript("window.LightSwipe?.pause()",null);
        });
        if (isOfficialMainDocument(web,url)) {
            long generation=pageGeneration;
            web.evaluateJavascript("if(!window.LightSwipeUi){"+uiScript+"}",value->{
                if (closing || generation!=pageGeneration || !isOfficialMainDocument(web,web.getUrl())) return;
                web.evaluateJavascript("window.LightSwipeUi?.setClean("+cleanUi+")",null);
                if (cleanUi && popups.isEmpty()) fulfillChannelIntent(generation);
            });
        }
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
        resetPlayerControls(); gestures.resetBoost(); cleanUi=true; rememberChannel(channel);
        beginChannelIntent(channel);
        cache.updateUsage(browser,foreground,true);
        if (isOfficialMainDocument(browser,browser.getUrl())) fulfillChannelIntent(pageGeneration);
        else { pendingFallbackUsed=true; browser.loadUrl(channelUrl(channel)); }
    }

    private void beginChannelIntent(String channel) {
        pendingChannel=channel; pendingFallbackUsed=false; channelIntentGeneration++;
        channelIntentMinPageGeneration=pageGeneration;
        openingTimedOut=false; armChannelTimeout();
    }

    private void armChannelTimeout() {
        handler.removeCallbacks(channelTimeout);
        if (pendingChannel!=null && foreground && popups.isEmpty()) handler.postDelayed(channelTimeout,15_000);
    }

    private void cancelChannelIntent() {
        pendingChannel=null; channelIntentGeneration++; openingTimedOut=false;
        handler.removeCallbacks(channelTimeout);
        browser.evaluateJavascript("window.LightSwipeUi?.cancelNavigation?.()",null);
    }

    /** 仅启动和明确点击产生导航意图；状态轮询不会触发页面重载。 */
    private void fulfillChannelIntent(long generation) {
        if (pendingChannel==null || !cleanUi || !isOfficialMainDocument(browser,browser.getUrl())) return;
        String channel=pendingChannel;
        long intent=channelIntentGeneration;
        String script="(function(){var ui=window.LightSwipeUi;"
                +"if(!ui||typeof ui.select!=='function')return false;"
                +"ui.setClean(true);return ui.select('"+channel+"');})()";
        browser.evaluateJavascript(script,value->{
            if (closing || generation!=pageGeneration || intent!=channelIntentGeneration || !channel.equals(pendingChannel)) return;
            // true 只确认官网接收意图，tick 观察到目标布局就绪才结束等待。
            if (!"true".equals(value) && !pendingFallbackUsed) {
                pendingFallbackUsed=true; browser.loadUrl(channelUrl(channel));
            }
        });
    }

    private void reloadChannel() {
        if (cleanUi && !isProbe()) {
            beginChannelIntent(selectedChannel);
            // reload 尚未开始时旧页面仍可返回状态，不能把旧布局当作刷新已完成。
            channelIntentMinPageGeneration=pageGeneration+1;
        }
        gestures.resetBoost(); resetPlayerControls(); pageLoading=true;
        cache.updateUsage(browser,foreground,true);
        browser.reload();
    }

    private void showAccountPage() {
        resetPlayerControls(); gestures.resetBoost(); cleanUi=false; cancelChannelIntent();
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

    /** 固定预留底栏高度，加载、换片和切频道均不再改变 WebView 视口。 */
    @SuppressLint("ClickableViewAccessibility") // 正常触摸由 SeekBar 处理；仅吞掉已取消拖动的后续事件。
    private void createPlayerBar(LinearLayout root) {
        playerBar = new LinearLayout(this); playerBar.setGravity(Gravity.CENTER_VERTICAL);
        playerBar.setBackgroundColor(Color.rgb(16,16,20)); playerBar.setVisibility(View.INVISIBLE);
        playback = button("▶",v->togglePlayback()); playback.setTextSize(18);
        playback.setContentDescription("播放视频"); playback.setEnabled(false);
        playerBar.addView(playback,new LinearLayout.LayoutParams(dp(44),dp(44)));
        videoProgress = new PlaybackSeekBar(this); videoProgress.setMax(10000);
        videoProgress.setContentDescription("视频进度"); videoProgress.setEnabled(false);
        videoProgress.setMinimumHeight(dp(44)); videoProgress.setPadding(dp(10),0,dp(12),0);
        videoProgress.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        videoProgress.setSecondaryProgressTintList(ColorStateList.valueOf(0xFF898991));
        videoProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF45454B));
        GradientDrawable thumb = new GradientDrawable(); thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE); thumb.setSize(dp(8),dp(8));
        videoProgress.setThumb(thumb); videoProgress.setThumbOffset(dp(4));
        videoProgress.setSplitTrack(false);
        videoProgress.setOnTouchListener((view,event)->{
            int action=event.getActionMasked();
            if (action==MotionEvent.ACTION_DOWN) {
                ignoreSeekTouch=false; seekDragCancelled=false;
            } else if (action==MotionEvent.ACTION_CANCEL || event.getPointerCount()>1
                    || action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_POINTER_UP) {
                cancelSeekDrag();
                seekDragCancelled=true; ignoreSeekTouch=true;
            }
            if (ignoreSeekTouch) {
                if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) ignoreSeekTouch=false;
                return true;
            }
            return false;
        });
        videoProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                if (!fromUser) return;
                if (draggingSeek && !seekDragCancelled) showSeekTime(value);
                else if (!draggingSeek && startSeek(false)) finishSeek(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (!startSeek(true)) {
                    seekDragCancelled=true; ignoreSeekTouch=true; return;
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) { finishSeek(bar.getProgress()); }
        });
        playerBar.addView(videoProgress,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(playerBar,new LinearLayout.LayoutParams(-1,dp(44)));
    }

    /** 自然播放不连续播报进度变化；用户操作、焦点事件和节点当前 range 保持系统行为。 */
    private static final class PlaybackSeekBar extends SeekBar {
        private boolean playbackProgressUpdate;

        PlaybackSeekBar(Context context) { super(context); }

        void setPlaybackProgress(int value) {
            if (getProgress()==value) return;
            playbackProgressUpdate=true;
            try { super.setProgress(value); }
            finally { playbackProgressUpdate=false; }
        }

        @Override public void sendAccessibilityEventUnchecked(AccessibilityEvent event) {
            if (Build.VERSION.SDK_INT>=30 && playbackProgressUpdate && !isAccessibilityFocused()
                    && event.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && event.getContentChangeTypes()==AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION) {
                event.recycle(); return;
            }
            super.sendAccessibilityEventUnchecked(event);
        }
    }

    private boolean startSeek(boolean showTime) {
        if (!canControlPlayer() || !mediaSeekable || currentMediaId<=0
                || !Double.isFinite(mediaDuration) || mediaDuration<=0) return false;
        playerControlGeneration++; draggingSeek=true; seekDragCancelled=false;
        cache.updateUsage(browser,foreground,true);
        dragMediaId=currentMediaId; dragPageGeneration=pageGeneration; dragDuration=mediaDuration;
        if (showTime) showSeekTime(videoProgress.getProgress());
        return true;
    }

    private boolean canControlPlayer() {
        return !closing && foreground && cleanUi && popups.isEmpty() && playerContextActive;
    }

    private void showSeekTime(int progressValue) {
        seekTime.setText(String.format(Locale.ROOT,"%s / %s",
                playTime(dragDuration*progressValue/10000.0),playTime(dragDuration)));
        seekTime.setVisibility(View.VISIBLE);
    }

    private static String playTime(double value) {
        long seconds=(long)Math.max(0,Double.isFinite(value) ? value : 0);
        if (seconds>=3600) return String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
        return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }

    /** 取消时先清原生状态，再给 SeekBar CANCEL；onStopTrackingTouch 因而不会提交进度。 */
    private void cancelSeekDrag() {
        if (!draggingSeek) return;
        draggingSeek=false; seekDragCancelled=true; ignoreSeekTouch=true;
        playerControlGeneration++; seekTime.setVisibility(View.GONE);
        long now=android.os.SystemClock.uptimeMillis();
        MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
        try { videoProgress.onTouchEvent(cancel); } finally { cancel.recycle(); }
        videoProgress.setPressed(false);
    }

    private void resetPlayerControls() {
        cancelSeekDrag(); playerControlGeneration++;
        playerContextActive=false; mediaAvailable=false; mediaSeekable=false;
        currentMediaId=0; mediaDuration=0;
        if (playerBar!=null) playerBar.setVisibility(View.INVISIBLE);
        if (playbackMessage!=null) playbackMessage.setVisibility(View.GONE);
        playbackMessageSince=0; playbackMessageKind="";
        if (seekTime!=null) seekTime.setVisibility(View.GONE);
        if (videoProgress!=null) { videoProgress.setEnabled(false); videoProgress.setProgress(0); videoProgress.setSecondaryProgress(0); }
        if (playback!=null) playback.setEnabled(false);
    }

    private void updatePlayerControls(JSONObject media,JSONObject ui) {
        boolean context=foreground && cleanUi && popups.isEmpty()
                && (isProbe() ? media.optBoolean("available") : ui!=null && ui.optBoolean("playerActive"));
        long id=media.optLong("mediaId",0);
        double duration=media.optDouble("duration",Double.NaN);
        boolean available=media.optBoolean("available") && id>0;
        boolean seekable=available && media.optBoolean("seekable") && Double.isFinite(duration) && duration>0;
        if (draggingSeek && (!context || !seekable || id!=dragMediaId || pageGeneration!=dragPageGeneration)) cancelSeekDrag();
        playerContextActive=context; mediaAvailable=available; mediaSeekable=seekable;
        currentMediaId=id; mediaDuration=duration;
        playerBar.setVisibility(context ? View.VISIBLE : View.INVISIBLE);
        playback.setEnabled(context && available);
        boolean playing=media.optBoolean("playbackRequested",media.optBoolean("playing"));
        String icon=playing ? "Ⅱ" : "▶";
        String description=playing ? "暂停视频" : "播放视频";
        if (!icon.contentEquals(playback.getText())) playback.setText(icon);
        if (!description.equals(playback.getContentDescription())) playback.setContentDescription(description);
        videoProgress.setEnabled(context && seekable);
        if (!draggingSeek) {
            double current=media.optDouble("currentTime",0);
            double fraction=seekable && Double.isFinite(current) ? Math.max(0,Math.min(1,current/duration)) : 0;
            videoProgress.setPlaybackProgress((int)Math.round(fraction*10000));
            double buffered=media.optDouble("bufferedFraction",0);
            videoProgress.setSecondaryProgress((int)Math.round(Math.max(0,Math.min(1,buffered))*10000));
            seekTime.setVisibility(View.GONE);
        }
        updatePlaybackMessage(media,context);
    }

    private void updatePlaybackMessage(JSONObject media,boolean context) {
        String message="";
        if (foreground && cleanUi && popups.isEmpty() && !draggingSeek && error.getVisibility()!=View.VISIBLE
                && (isProbe() || isOfficialMainDocument(browser,browser.getUrl()))) {
            if (openingTimedOut) message="打开较慢，点此重试";
            else if (pendingChannel!=null || (pageLoading && !media.optBoolean("available"))) message="正在打开…";
            else if (context && media.optBoolean("mediaError")) message="视频暂时无法播放";
            else if (context && (!media.optBoolean("available") || media.optBoolean("buffering"))) message="正在缓冲…";
        }
        if (!message.equals(playbackMessageKind)) {
            playbackMessageKind=message; playbackMessageSince=android.os.SystemClock.uptimeMillis();
            playbackMessage.setVisibility(View.GONE);
        }
        playbackMessage.setClickable(openingTimedOut);
        if (message.isEmpty()) playbackMessage.setVisibility(View.GONE);
        else if (android.os.SystemClock.uptimeMillis()-playbackMessageSince>=600) {
            if (!message.contentEquals(playbackMessage.getText())) playbackMessage.setText(message);
            playbackMessage.setVisibility(View.VISIBLE);
        }
    }

    private void togglePlayback() {
        if (!canControlPlayer() || !mediaAvailable || currentMediaId<=0) return;
        cache.updateUsage(browser,foreground,true);
        long generation=pageGeneration, id=currentMediaId, controls=++playerControlGeneration;
        String script="(function(){var media=window.LightSwipe;"
                +"if(!media||media.status().mediaId!=="+id+"||typeof media.togglePlayback!=='function')return null;"
                +"return {media:media.togglePlayback(),ui:window.LightSwipeUi?.status()};})()";
        browser.evaluateJavascript(script,value->applyPlayerAction(value,generation,id,controls));
    }

    private void finishSeek(int progressValue) {
        if (!draggingSeek) return;
        draggingSeek=false; seekTime.setVisibility(View.GONE);
        long generation=dragPageGeneration, id=dragMediaId, controls=++playerControlGeneration;
        if (seekDragCancelled || !canControlPlayer() || !mediaSeekable
                || generation!=pageGeneration || id<=0 || id!=currentMediaId) return;
        String fraction=String.format(Locale.ROOT,"%.6f",Math.max(0,Math.min(10000,progressValue))/10000.0);
        String script="(function(){var media=window.LightSwipe;"
                +"if(!media||typeof media.seek!=='function')return null;"
                +"return {media:media.seek("+fraction+","+id+"),ui:window.LightSwipeUi?.status()};})()";
        browser.evaluateJavascript(script,value->applyPlayerAction(value,generation,id,controls));
    }

    private void applyPlayerAction(String value,long generation,long id,long controls) {
        if (!canControlPlayer() || generation!=pageGeneration || controls!=playerControlGeneration || id!=currentMediaId) return;
        try {
            JSONObject result=new JSONObject(value);
            updatePlayerControls(result.getJSONObject("media"),result.optJSONObject("ui"));
        } catch(Exception ignored) { /* 官网可能刚换片；下一次既有状态检查会更新控件。 */ }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!foreground || closing) return;
            long generation=pageGeneration;
            long controls=playerControlGeneration;
            long intent=channelIntentGeneration;
            browser.evaluateJavascript("({media:window.LightSwipe?.status(),ui:window.LightSwipeUi?.status()})",value->{
                if (!foreground || closing || generation!=pageGeneration) return;
                try {
                    JSONObject result=new JSONObject(value);
                    JSONObject status=result.optJSONObject("media");
                    boolean mediaApiReady=status!=null;
                    if (status==null) status=new JSONObject();
                    gestures.setBoostAllowed(status.optBoolean("available") && !status.optBoolean("paused") && popups.isEmpty());
                    if (status.optBoolean("playbackRequested",status.optBoolean("playing")) && popups.isEmpty()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    JSONObject ui=result.optJSONObject("ui");
                    if (intent==channelIntentGeneration && pageCommitted && generation>=channelIntentMinPageGeneration
                            && pendingChannel!=null && ui!=null && ui.optBoolean("layoutReady")
                            && !ui.optBoolean("navigationPending") && !ui.optBoolean("detailOpen")
                            && pendingChannel.equals(ui.optString("channel"))) {
                        pendingChannel=null; handler.removeCallbacks(channelTimeout);
                    }
                    if (openingTimedOut && ui!=null && ui.optBoolean("layoutReady")
                            && selectedChannel.equals(ui.optString("channel"))) openingTimedOut=false;
                    if (controls==playerControlGeneration) updatePlayerControls(status,ui);
                    cache.updateUsage(browser,foreground,!mediaApiReady || pageLoading || pendingChannel!=null || draggingSeek
                            || status.optBoolean("playbackRequested",status.optBoolean("playing")) || status.optBoolean("buffering")
                            || (ui!=null && ui.optBoolean("playerActive") && !status.optBoolean("available")) || !popups.isEmpty());
                    if (!cleanUi && ui!=null && pendingChannel==null && isOfficialMainDocument(browser,browser.getUrl())) {
                        String channel=ui.optString("channel","");
                        if (!channel.equals(selectedChannel) && ("featured".equals(channel) || "recommend".equals(channel))) rememberChannel(channel);
                    }
                } catch(Exception ignored) {
                    gestures.setBoostAllowed(false);
                    if (controls==playerControlGeneration) resetPlayerControls();
                    cache.updateUsage(browser,foreground,true);
                }
            });
            handler.postDelayed(this,700);
        }
    };

    private void menu() {
        cancelSeekDrag(); gestures.resetBoost();
        new AlertDialog.Builder(this).setTitle("轻刷 "+BuildConfig.VERSION_NAME)
            .setItems(new String[]{cleanUi ? "登录 / 账号" : "返回精简界面","刷新当前网页","缓存与网站数据","手势说明"},(d,index)->{
                if (index==0) { if (cleanUi) showAccountPage(); else chooseChannel(selectedChannel); }
                if (index==1) reloadChannel();
                if (index==2) showCache();
                if (index==3) new AlertDialog.Builder(this).setTitle("快进手势")
                    .setMessage("视频画面左右边缘长按约 0.4 秒进入 2 倍速，松手恢复原速。\n\n长按生效后向下滑约一个拇指宽度可锁定，点画面上方的速度提示解除。锁定后仍可上滑切视频。\n\n避开顶部标签、底部进度条及屏幕最边缘的系统返回手势。切到后台会暂停视频并解除加速。")
                    .setPositiveButton("知道了",null).show();
            }).show();
    }

    private void showCache() {
        cache.snapshotAsync(s->{ if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this).setTitle("缓存与网站数据")
                .setMessage("可清理缓存："+size(s.cacheBytes)+"\n网站与浏览器数据："+size(s.siteBytes)
                    +"\n\n1 GiB 是提醒线。资源缓存由系统管理，App 不会因超额、暂停或切到后台而自动清空。"
                    +(s.overLimit ? "\n当前已超过提醒线，可按需手动清理。" : "")
                    +"\n\n正常复用官网资源，视频不另存副本。只有点“清理缓存”才会主动清理，之后部分内容需要重新缓冲；登录保留，网站数据另行统计。"
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

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        // 网页内点播放/上滑也会先经过 Activity，及时取消尚未完成的空闲清理。
        if (!closing && cache!=null && browser!=null) cache.updateUsage(browser,foreground,true);
    }

    @Override protected void onResume() { super.onResume(); foreground=true;
        cache.updateUsage(browser,true,true);
        armChannelTimeout();
        for (WebView w:browsers) w.onResume();
        syncPlayback();
        handler.removeCallbacks(tick); handler.post(tick);
    }
    @Override protected void onPause() {
        foreground=false; resetPlayerControls(); handler.removeCallbacks(tick); gestures.setBoostAllowed(false);
        handler.removeCallbacks(channelTimeout);
        syncPlayback();
        for (WebView w:browsers) w.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CookieManager.getInstance().flush(); cache.updateUsage(browser,false,false); super.onPause();
    }
    @Override public void onBackPressed() { handleBack(); }

    private void handleBack() {
        cancelSeekDrag();
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
        resetPlayerControls(); cancelChannelIntent();
        if (browser.canGoBack()) {
            android.webkit.WebBackForwardList history=browser.copyBackForwardList();
            String previous=history.getItemAtIndex(history.getCurrentIndex()-1).getUrl();
            Uri destination=Uri.parse(previous);
            String host=destination.getHost(),path=destination.getPath();
            if ("douyin.com".equalsIgnoreCase(host) || "www.douyin.com".equalsIgnoreCase(host)) {
                String channel=path!=null && (path.equals("/jingxuan") || path.startsWith("/jingxuan/"))
                        ? "featured" : "1".equals(destination.getQueryParameter("recommend")) ? "recommend" : null;
                if (channel!=null) { rememberChannel(channel); beginChannelIntent(channel); pendingFallbackUsed=true; }
            }
            cache.updateUsage(browser,foreground,true); browser.goBack();
        }
        else if (isTaskRoot()) moveTaskToBack(true);
        else finish();
    }
    @Override protected void onDestroy() {
        closing=true; foreground=false; boostGeneration++; pageGeneration++;
        resetPlayerControls();
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
