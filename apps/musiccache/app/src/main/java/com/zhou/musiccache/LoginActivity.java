package com.zhou.musiccache;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zhou.musiccache.player.QQWebSource;
import com.zhou.musiccache.playback.PlayerRuntime;
import java.util.ArrayList;
import java.util.List;

/** 登录在官方网页完成；本应用不读取或导出密码、Cookie。 */
public final class LoginActivity extends Activity {
    private final List<WebView> views=new ArrayList<>();
    private WebView browser;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        Button done=new Button(this);done.setText("完成登录，返回轻听");done.setOnClickListener(v->finish());root.addView(done);
        TextView hint=new TextView(this);hint.setText("QQ 音乐官方网页登录 · 可双指缩放");hint.setPadding(24,10,24,10);root.addView(hint);
        browser=createBrowser(hint);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        browser.loadUrl(QQWebSource.FAVORITES);
    }
    private WebView createBrowser(TextView host){
        WebView view=new WebView(this);views.add(view);QQWebSource.configure(view,true);
        view.getSettings().setSupportMultipleWindows(true);view.getSettings().setBuiltInZoomControls(true);view.getSettings().setDisplayZoomControls(false);
        view.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest r){return PlayerRuntime.get(LoginActivity.this).proxy.accepts(r.getUrl().toString())?QQWebSource.blockedAudio():null;}
            @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest r){
                Uri u=r.getUrl();if("https".equals(u.getScheme())||"about".equals(u.getScheme()))return false;
                if(r.isForMainFrame()&&r.hasGesture())new AlertDialog.Builder(LoginActivity.this).setTitle("打开登录应用？")
                        .setMessage("QQ 官方页面请求打开外部应用以完成登录。")
                        .setNegativeButton("取消",null).setPositiveButton("打开",(d,k)->{try{startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception ignored){}}).show();
                return true;
            }
            @Override public void onPageFinished(WebView w,String url){host.setText("官方登录页 · "+Uri.parse(url).getHost());CookieManager.getInstance().flush();}
        });
        view.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView w,boolean dialog,boolean gesture,Message result){
                if(!gesture)return false;
                Dialog popup=new Dialog(LoginActivity.this);LinearLayout root=new LinearLayout(LoginActivity.this);root.setOrientation(LinearLayout.VERTICAL);
                Button back=new Button(LoginActivity.this);back.setText("返回官方音乐页");back.setOnClickListener(v->popup.dismiss());root.addView(back);
                TextView popupHost=new TextView(LoginActivity.this);root.addView(popupHost);
                WebView child=createBrowser(popupHost);root.addView(child,new LinearLayout.LayoutParams(-1,0,1));popup.setContentView(root);popup.show();
                if(popup.getWindow()!=null)popup.getWindow().setLayout(-1,-1);
                popup.setOnDismissListener(d->{views.remove(child);child.destroy();CookieManager.getInstance().flush();});
                ((WebView.WebViewTransport)result.obj).setWebView(child);result.sendToTarget();return true;
            }
        });return view;
    }
    @Override public void onBackPressed(){if(browser.canGoBack())browser.goBack();else super.onBackPressed();}
    @Override protected void onPause(){CookieManager.getInstance().flush();super.onPause();}
    @Override protected void onDestroy(){for(WebView v:views){v.stopLoading();v.destroy();}views.clear();super.onDestroy();}
}
