package com.zhou.musiccache.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.zhou.musiccache.BuildConfig;
import com.zhou.musiccache.playback.PlayerRuntime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 官方网页只负责账号与歌曲来源；声音由原生播放器输出。没有可被网页调用的Java桥。 */
public final class QQWebSource implements PlayerRuntime.SourceResolver {
    public static final String HOME = "https://y.qq.com/n/ryqq_v2/";
    public static final String FAVORITES = HOME + "profile/like/song";
    public interface CatalogCallback {
        void loaded(List<Track> tracks, String note);
        void failed(String message);
    }
    public interface PlaylistCallback {
        void loaded(List<Playlist> playlists);
        void failed(String message);
    }
    private static final String CREATED_PLAYLISTS = HOME + "profile/create";
    private static final String MOBILE_SHARE_PATH = "/n2/m/share/details/taoge.html";
    private static final String MOBILE_PLAYLIST_PATH = "/n3/other/pages/details/playlist.html";
    private static final int PLAYLIST_PAGE_SIZE = 100, PLAYLIST_LIMIT = 1000;
    private final Context context;
    private final PlayerRuntime runtime;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Remembered> recent = new HashMap<>();
    private WebView catalog;
    private int catalogGeneration;
    private static final class Remembered {
        final PlayerRuntime.ResolvedAudio audio; final long until;
        Remembered(PlayerRuntime.ResolvedAudio value) { audio = value; until = android.os.SystemClock.elapsedRealtime() + 300_000; }
    }
    private static final class ResolveJob {
        final String id;
        final CompletableFuture<PlayerRuntime.ResolvedAudio> result = new CompletableFuture<>();
        WebView view;
        volatile boolean clicked;
        ResolveJob(String id) { this.id = id; }
    }
    public QQWebSource(Context context, PlayerRuntime runtime) {
        this.context = context.getApplicationContext(); this.runtime = runtime;
    }
    public void invalidate() { synchronized (recent) { recent.clear(); } }

    @SuppressLint("SetJavaScriptEnabled")
    public static void configure(WebView view, boolean audioGesture) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(audioGesture);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true);
        s.setUserAgentString(s.getUserAgentString().replaceAll("\\(Linux; Android[^)]*\\)", "(X11; Linux x86_64)")
                .replace(" Version/4.0", "").replace(" Mobile", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    }
    private WebView hiddenBrowser() {
        WebView view = new WebView(context); configure(view, false);
        // 给未附着到Activity的来源页正常布局尺寸；Activity退出也不影响队列取下一首。
        view.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1100, 1800);
        return view;
    }
    private WebView hiddenMobileBrowser() {
        WebView view = new WebView(context);
        // 移动分享页只有 Android UA 才会加载可读取完整歌单的官方页面。
        String mobileUserAgent = WebSettings.getDefaultUserAgent(context);
        configure(view, true);
        view.getSettings().setUserAgentString(mobileUserAgent);
        view.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 420, 900);
        return view;
    }
    public static boolean officialPage(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equals(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null) return false;
            return "y.qq.com".equals(uri.getHost())
                    || ("i.y.qq.com".equals(uri.getHost()) && MOBILE_SHARE_PATH.equals(uri.getPath()))
                    || ("i2.y.qq.com".equals(uri.getHost()) && MOBILE_PLAYLIST_PATH.equals(uri.getPath()));
        }
        catch (RuntimeException e) { return false; }
    }
    public static WebResourceResponse blockedAudio() {
        return new WebResourceResponse("text/plain", "UTF-8", 503, "Native player owns audio",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }
    private static void destroy(WebView view) {
        if (view != null) { view.stopLoading(); view.destroy(); }
    }
    public void favorites(CatalogCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> favorites(callback)); return; }
        int generation = ++catalogGeneration;
        WebView view = beginCatalog(false); view.loadUrl(FAVORITES);
        pollFavoriteId(view, generation, 0, callback);
    }
    public void playlists(PlaylistCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> playlists(callback)); return; }
        int generation = ++catalogGeneration;
        WebView view = beginCatalog(false); view.loadUrl(CREATED_PLAYLISTS);
        pollPlaylists(view, generation, 0, callback);
    }
    public void playlist(String id, CatalogCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> playlist(id, callback)); return; }
        if (!validPlaylistId(id)) { callback.failed("歌单标识无效，请重新读取歌单列表"); return; }
        int generation = ++catalogGeneration;
        WebView view = beginCatalog(true);
        view.loadUrl("https://i.y.qq.com" + MOBILE_SHARE_PATH + "?id=" + id);
        pollPlaylist(new PlaylistJob(id, generation, view, callback), 0);
    }
    private static boolean validPlaylistId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,15}")) return false;
        try { return Long.parseLong(id) <= 9_007_199_254_740_991L; }
        catch (NumberFormatException invalid) { return false; }
    }
    private WebView beginCatalog(boolean mobile) {
        destroy(catalog); catalog = mobile ? hiddenMobileBrowser() : hiddenBrowser();
        catalog.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                return !officialPage(r.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest r) {
                return runtime.proxy.accepts(r.getUrl().toString()) ? blockedAudio() : null;
            }
        });
        return catalog;
    }
    private boolean currentCatalog(WebView view, int generation) {
        return generation == catalogGeneration && catalog == view;
    }
    private void closeCatalog(WebView view, int generation) {
        if (currentCatalog(view, generation)) { catalog = null; destroy(view); }
    }
    private static final String READ_PLAYLISTS = """
            (()=>{
              if(location.origin!=='https://y.qq.com'||location.pathname!=='/n/ryqq_v2/profile/create')return {};
              const clean=x=>(x||'').trim().slice(0,300), result=new Map();
              for(const a of document.querySelectorAll('a[href*="playlist"]')) {
                let u;try{u=new URL(a.href,location.href);}catch(_){continue;}
                const m=u.pathname.match('^/n/ryqq_v2/playlist/([1-9][0-9]{0,15})$');
                if(u.origin!=='https://y.qq.com'||!m)continue;
                const box=a.closest('.playlist__item')||a.parentElement;
                const title=clean(a.title||a.textContent||box?.querySelector('.playlist__title')?.textContent);
                const count=(box?.textContent||'').match(/([0-9]+)\\s*首(?:歌曲)?/);
                const old=result.get(m[1]);
                if(!old||(!old.title&&title))result.set(m[1],{id:m[1],title,count:count?Number(count[1]):-1});
              }
              const text=document.body?.innerText||'';
              return {rows:[...result.values()].slice(0,200),login:/立即登录|登录后|请先登录/.test(text),
                empty:/暂无歌单|没有创建|还没有创建/.test(text)};
            })()
            """;
    private void pollPlaylists(WebView view, int generation, int attempt, PlaylistCallback callback) {
        main.postDelayed(() -> {
            if (!currentCatalog(view, generation)) return;
            if (attempt >= 45) {
                closeCatalog(view, generation); callback.failed("暂时无法读取我的歌单，请确认网页登录后重试"); return;
            }
            view.evaluateJavascript(READ_PLAYLISTS, value -> {
                if (!currentCatalog(view, generation)) return;
                try {
                    JSONObject data = new JSONObject(value); JSONArray rows = data.optJSONArray("rows");
                    if (rows != null && rows.length() > 0) {
                        List<Playlist> lists = new ArrayList<>();
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.getJSONObject(i); String id = row.optString("id");
                            if (validPlaylistId(id)) lists.add(new Playlist(id, row.optString("title"), row.optInt("count", -1)));
                        }
                        if (!lists.isEmpty()) { closeCatalog(view, generation); callback.loaded(Collections.unmodifiableList(lists)); return; }
                    }
                    if (attempt > 8 && data.optBoolean("login")) {
                        closeCatalog(view, generation); callback.failed("请先在 QQ 官方网页完成登录，再刷新我的歌单"); return;
                    }
                    if (attempt > 8 && data.optBoolean("empty")) {
                        closeCatalog(view, generation); callback.loaded(Collections.emptyList()); return;
                    }
                } catch (Exception ignored) { }
                pollPlaylists(view, generation, attempt + 1, callback);
            });
        }, 500);
    }
    private static final String READ_FAVORITE_ID = """
            (()=>{
              if(location.origin!=='https://y.qq.com'||location.pathname!=='/n/ryqq_v2/profile/like/song')return {};
              const e=document.querySelector('.songlist__item');
              let f=e&&e[Object.keys(e).find(k=>k.startsWith('__reactFiber$'))];
              for(let i=0;f&&i<35;i++,f=f.return){
                const u=f.memoizedProps?.userdata;
                if(u?.isStateHost===true&&/^[1-9][0-9]{0,15}$/.test(String(u.myFavDissId||'')))
                  return {id:String(u.myFavDissId)};
              }
              const text=document.body?.innerText||'';
              return {login:/立即登录|登录后|请先登录/.test(text)};
            })()
            """;
    private void pollFavoriteId(WebView view, int generation, int attempt, CatalogCallback callback) {
        main.postDelayed(() -> {
            if (!currentCatalog(view, generation)) return;
            if (attempt >= 45) {
                closeCatalog(view, generation);
                callback.failed("无法确认我的喜欢歌单，请确认登录后重试；未将网页前几首当作完整列表"); return;
            }
            view.evaluateJavascript(READ_FAVORITE_ID, value -> {
                if (!currentCatalog(view, generation)) return;
                try {
                    JSONObject data = new JSONObject(value); String id = data.optString("id");
                    if (validPlaylistId(id)) { closeCatalog(view, generation); playlist(id, callback); return; }
                    if (attempt > 8 && data.optBoolean("login")) {
                        closeCatalog(view, generation); callback.failed("请先在 QQ 官方网页完成登录，再刷新我的喜欢"); return;
                    }
                } catch (Exception ignored) { }
                pollFavoriteId(view, generation, attempt + 1, callback);
            });
        }, 500);
    }
    private static final class PlaylistJob {
        final String id; final int generation; final WebView view; final CatalogCallback callback;
        final LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
        int offset, total = -1, omitted;
        PlaylistJob(String id, int generation, WebView view, CatalogCallback callback) {
            this.id = id; this.generation = generation; this.view = view; this.callback = callback;
        }
    }
    private static final String READ_PLAYLIST_PAGE = """
            (()=>{
              const id='__ID__',offset=__OFFSET__,token='__TOKEN__',key='__musicCachePlaylistResult';
              const allowed=()=>location.origin==='https://i2.y.qq.com'&&
                location.pathname==='/n3/other/pages/details/playlist.html'&&new URL(location.href).searchParams.get('id')===id;
              if(!allowed())return {state:'waiting'};
              const previous=window[key];if(previous?.token===token)return previous;
              if(typeof window.Music?.uajaxAsync!=='function')return {state:'waiting'};
              window[key]={token,state:'pending'};
              Promise.resolve().then(()=>Music.uajaxAsync({comm:{}},[{
                module:'music.srfDissInfo.aiDissInfo',method:'uniform_get_Dissinfo',
                param:{disstid:Number(id),tag:1,userinfo:1,song_begin:offset,song_num:100,orderlist:1}
              }])).then(r=>{
                if(!allowed()||window[key]?.token!==token)return;
                const response=r?.res,request=response?.req_0,data=request?.data;
                if(response?.code!==0||request?.code!==0||data?.code!==0||!Array.isArray(data.songlist))throw new Error();
                const total=Number(data.dirinfo?.songnum);
                if(!Number.isSafeInteger(total)||total<0||data.songlist.length>100)throw new Error();
                const clean=x=>String(x??'').trim().slice(0,300), count=x=>Array.isArray(x)?x.length:(x?1:0);
                const rows=data.songlist.map(s=>({id:clean(s.mid),title:clean(s.title||s.name),
                  artist:clean(Array.isArray(s.singer)?s.singer.map(a=>clean(a.name)).join(' / '):''),
                  album:clean(s.album?.name),durationMs:Number.isFinite(Number(s.interval))?
                    Math.min(86400,Math.max(0,Number(s.interval)))*1000:0})).filter(s=>/^[A-Za-z0-9]{8,32}$/.test(s.id));
                // 页面只留下最小歌曲元数据和计数；完整官网响应、账号信息不挂到window。
                window[key]={token,state:'ready',total,rows,rawCount:data.songlist.length,
                  omitted:count(data.invalid_song)+count(data.filtered_song)+data.songlist.length-rows.length};
              }).catch(()=>{if(allowed()&&window[key]?.token===token)window[key]={token,state:'error'};});
              return window[key];
            })()
            """;
    private void pollPlaylist(PlaylistJob job, int attempt) {
        main.postDelayed(() -> {
            if (!currentCatalog(job.view, job.generation)) return;
            if (attempt >= 60) { finishPlaylist(job, "官网读取超时，未能读取完整歌单"); return; }
            String token = job.generation + "_" + job.offset;
            String script = READ_PLAYLIST_PAGE.replace("__ID__", job.id)
                    .replace("__OFFSET__", Integer.toString(job.offset)).replace("__TOKEN__", token);
            job.view.evaluateJavascript(script, value -> {
                if (!currentCatalog(job.view, job.generation)) return;
                try {
                    JSONObject data = new JSONObject(value); String state = data.optString("state");
                    if ("error".equals(state)) { finishPlaylist(job, "官网未提供完整歌单，请确认登录与访问权限后重试"); return; }
                    if (!"ready".equals(state)) { pollPlaylist(job, attempt + 1); return; }
                    int total = data.optInt("total", -1); JSONArray rows = data.optJSONArray("rows");
                    if (total < 0 || rows == null || !token.equals(data.optString("token"))) {
                        finishPlaylist(job, "官网歌单结构发生变化，未能确认完整列表"); return;
                    }
                    if (job.total >= 0 && job.total != total) { finishPlaylist(job, "读取期间歌单数量发生变化，请刷新后重试"); return; }
                    job.total = total; int before = job.tracks.size();
                    for (int i = 0; i < rows.length() && job.tracks.size() < PLAYLIST_LIMIT; i++) {
                        JSONObject row = rows.getJSONObject(i); String id = row.optString("id");
                        if (id.matches("[A-Za-z0-9]{8,32}")) job.tracks.putIfAbsent(id,
                                new Track(id, row.optString("title"), row.optString("artist"), row.optString("album"), row.optLong("durationMs")));
                    }
                    job.omitted += Math.max(0, data.optInt("omitted"));
                    job.offset += PLAYLIST_PAGE_SIZE;
                    if (job.offset >= total) {
                        finishPlaylist(job, job.tracks.size() == total && job.omitted == 0 ? null
                                : "官网部分条目不可用或重复，未能确认完整歌单"); return;
                    }
                    if (job.offset >= PLAYLIST_LIMIT || job.tracks.size() >= PLAYLIST_LIMIT) {
                        finishPlaylist(job, "达到本版 1000 首读取上限，尚未读取完整歌单"); return;
                    }
                    if (job.tracks.size() == before || data.optInt("rawCount") == 0) {
                        finishPlaylist(job, "官网分页没有返回新歌曲，尚未读取完整歌单"); return;
                    }
                    pollPlaylist(job, 0);
                } catch (Exception ignored) { finishPlaylist(job, "官网歌单数据暂时无法读取，未能确认完整列表"); }
            });
        }, 500);
    }
    private void finishPlaylist(PlaylistJob job, String problem) {
        if (!currentCatalog(job.view, job.generation)) return;
        closeCatalog(job.view, job.generation);
        if (problem != null && job.tracks.isEmpty()) { job.callback.failed(problem); return; }
        String note = problem == null ? "已读取完整歌单 · " + job.tracks.size() + " 首"
                : problem + " · 已读取 " + job.tracks.size() + (job.total >= 0 ? "/" + job.total : "") + " 首";
        job.callback.loaded(Collections.unmodifiableList(new ArrayList<>(job.tracks.values())), note);
    }
    public void search(String query, CatalogCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> search(query, callback)); return; }
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) { callback.failed("请输入歌曲名或歌手"); return; }
        q = q.substring(0, Math.min(120, q.length()));
        int generation = ++catalogGeneration;
        WebView view = beginCatalog(true);
        // 该官方页即使没有歌单id也会初始化Music SDK；不依赖任何用户歌单或凭据。
        view.loadUrl("https://i2.y.qq.com" + MOBILE_PLAYLIST_PATH);
        pollSearch(new SearchJob(q, generation, view, callback), 0);
    }
    // 官网search chunk使用page_num/num_per_page。30已实测可分页；100会出现成功码但空结果，不能放大。
    private static final int SEARCH_PAGE_SIZE = 30, SEARCH_MAX_PAGES = 3;
    private static final class SearchJob {
        final String query; final int generation; final WebView view; final CatalogCallback callback;
        final LinkedHashMap<String, Track> tracks = new LinkedHashMap<>();
        int page = 1, total = -1, omitted;
        SearchJob(String query, int generation, WebView view, CatalogCallback callback) {
            this.query = query; this.generation = generation; this.view = view; this.callback = callback;
        }
    }
    private static final String READ_SEARCH_PAGE = """
            (()=>{
              const query=__QUERY__,page=__PAGE__,token='__TOKEN__',key='__musicCacheSearchResult';
              const allowed=()=>location.origin==='https://i2.y.qq.com'&&
                location.pathname==='/n3/other/pages/details/playlist.html';
              if(!allowed())return {state:'waiting'};
              const previous=window[key];if(previous?.token===token)return previous;
              if(typeof window.Music?.uajaxAsync!=='function')return {state:'waiting'};
              window[key]={token,state:'pending'};
              Promise.resolve().then(()=>Music.uajaxAsync({comm:{}},[{
                module:'music.search.SearchCgiService',method:'DoSearchForQQMusicDesktop',
                param:{remoteplace:'yqq.yqq.yqq',search_type:0,query,page_num:page,num_per_page:30}
              }])).then(r=>{
                if(!allowed()||window[key]?.token!==token)return;
                const response=r?.res,request=response?.req_0,data=request?.data,list=data?.body?.song?.list;
                if(response?.code!==0||request?.code!==0||!Array.isArray(list)||list.length>30)throw new Error();
                const total=Number(data?.meta?.sum);
                if(!Number.isSafeInteger(total)||total<0)throw new Error();
                const clean=x=>String(x??'').trim().slice(0,300);
                const rows=list.map(s=>({id:clean(s.mid),title:clean(s.title||s.name),
                  artist:clean(Array.isArray(s.singer)?s.singer.map(a=>clean(a.name)).join(' / '):''),
                  album:clean(s.album?.name),durationMs:Number.isFinite(Number(s.interval))?
                    Math.min(86400,Math.max(0,Number(s.interval)))*1000:0})).filter(s=>/^[A-Za-z0-9]{8,32}$/.test(s.id));
                // 只保留展示元数据、官网报告总数及状态；不保留完整响应或认证字段。
                window[key]={token,state:'ready',total,rows,rawCount:list.length,omitted:list.length-rows.length};
              }).catch(()=>{if(allowed()&&window[key]?.token===token)window[key]={token,state:'error'};});
              return window[key];
            })()
            """;
    private void pollSearch(SearchJob job, int attempt) {
        main.postDelayed(() -> {
            if (!currentCatalog(job.view, job.generation)) return;
            if (attempt >= 60) { finishSearch(job, "官网搜索读取超时，后续结果未读取"); return; }
            String token = job.generation + "_" + job.page;
            // 查询文本最后替换，避免用户输入恰含占位符时被后续replace改写；JSONObject.quote阻止脚本注入。
            String script = READ_SEARCH_PAGE.replace("__PAGE__", Integer.toString(job.page))
                    .replace("__TOKEN__", token).replace("__QUERY__", JSONObject.quote(job.query));
            job.view.evaluateJavascript(script, value -> {
                if (!currentCatalog(job.view, job.generation)) return;
                try {
                    JSONObject data = new JSONObject(value); String state = data.optString("state");
                    if ("error".equals(state)) { finishSearch(job, "官网搜索暂时不可用，请确认登录后重试；后续结果未读取"); return; }
                    if (!"ready".equals(state)) { pollSearch(job, attempt + 1); return; }
                    int total = data.optInt("total", -1), rawCount = data.optInt("rawCount", -1);
                    JSONArray rows = data.optJSONArray("rows");
                    if (total < 0 || rows == null || rawCount < 0 || rawCount > SEARCH_PAGE_SIZE
                            || !token.equals(data.optString("token"))) {
                        finishSearch(job, "官网搜索结构发生变化，未能确认结果"); return;
                    }
                    if (job.total >= 0 && job.total != total) {
                        finishSearch(job, "读取期间搜索结果数量发生变化，请重新搜索"); return;
                    }
                    job.total = total; int before = job.tracks.size();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i); String id = row.optString("id");
                        if (id.matches("[A-Za-z0-9]{8,32}")) job.tracks.putIfAbsent(id,
                                new Track(id, row.optString("title"), row.optString("artist"), row.optString("album"), row.optLong("durationMs")));
                    }
                    job.omitted += Math.max(0, data.optInt("omitted"));
                    if (job.page * SEARCH_PAGE_SIZE >= total) {
                        finishSearch(job, job.tracks.size() == total && job.omitted == 0 ? null
                                : "官网部分结果重复或缺失，未能确认完整结果"); return;
                    }
                    if (job.tracks.size() == before || rawCount < SEARCH_PAGE_SIZE) {
                        finishSearch(job, "官网分页未返回预期的新歌曲，后续结果未读取"); return;
                    }
                    if (job.page >= SEARCH_MAX_PAGES) { finishSearch(job, null); return; }
                    job.page++; pollSearch(job, 0);
                } catch (Exception ignored) { finishSearch(job, "官网搜索数据无法读取，未能确认结果"); }
            });
        }, 500);
    }
    private void finishSearch(SearchJob job, String problem) {
        if (!currentCatalog(job.view, job.generation)) return;
        closeCatalog(job.view, job.generation);
        if (problem != null && job.tracks.isEmpty()) { job.callback.failed(problem); return; }
        String note;
        if (problem != null) note = problem + " · 已读取 " + job.tracks.size() + " 首";
        else if (job.total == 0) note = "QQ 官方搜索未返回匹配歌曲，可换个关键词";
        else if (job.total > SEARCH_PAGE_SIZE * SEARCH_MAX_PAGES)
            note = "显示前 " + job.tracks.size() + " 首（官网报告 " + job.total + " 条结果）；可用更精确关键词缩小范围";
        else note = "来自 QQ 音乐官方搜索 · " + job.tracks.size() + " 首";
        if (problem != null && job.total >= 0) note += "（官网报告 " + job.total + " 条结果）";
        job.callback.loaded(Collections.unmodifiableList(new ArrayList<>(job.tracks.values())), note);
    }

    @Override public synchronized PlayerRuntime.ResolvedAudio resolve(String songId) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("请在播放器加载线程解析歌曲");
        if (songId == null || !songId.matches("[A-Za-z0-9]{8,32}")) throw new IOException("歌曲标识无效");
        synchronized (recent) {
            Remembered hit = recent.get(songId);
            if (hit != null && hit.until > android.os.SystemClock.elapsedRealtime()) return hit.audio;
        }
        ResolveJob job = new ResolveJob(songId);
        main.post(() -> beginResolve(job));
        try {
            PlayerRuntime.ResolvedAudio result = job.result.get(35, TimeUnit.SECONDS);
            synchronized (recent) {
                recent.put(songId, new Remembered(result));
                if (recent.size() > 40) recent.entrySet().removeIf(e -> e.getValue().until < android.os.SystemClock.elapsedRealtime());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("已取消歌曲加载");
        } catch (Exception e) {
            throw new IOException("QQ 网页未提供可播放音频，请确认登录与歌曲播放权限后重试");
        } finally {
            job.result.cancel(false);
            main.post(() -> { destroy(job.view); job.view = null; });
        }
    }
    private void beginResolve(ResolveJob job) {
        if (job.result.isDone()) return;
        WebView view = hiddenBrowser(); job.view = view;
        final String userAgent = view.getSettings().getUserAgentString();
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) { return !officialPage(r.getUrl().toString()); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (!runtime.proxy.accepts(url)) return null;
                if (job.clicked && !job.result.isDone()) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    headers.put("Referer", "https://y.qq.com/"); headers.put("Origin", "https://y.qq.com"); headers.put("User-Agent", userAgent);
                    job.result.complete(new PlayerRuntime.ResolvedAudio(url, headers));
                }
                // 网页永远不下载音频，避免原生播放器之外的重复传输和双重播放。
                return blockedAudio();
            }
        });
        view.loadUrl(HOME + "songDetail/" + job.id);
        pollResolve(job, 0);
    }
    private void pollResolve(ResolveJob job, int attempt) {
        main.postDelayed(() -> {
            if (job.result.isDone() || job.view == null) return;
            if (attempt > 60) { job.result.completeExceptionally(new IOException("官方网页暂时无法播放")); return; }
            String url = job.view.getUrl();
            if (officialPage(url) && Uri.parse(url).getPath().equals("/n/ryqq_v2/songDetail/" + job.id) && !job.clicked) {
                // 使用官网自己的播放按钮取得当前账号的播放地址，不构造签名或读取Cookie。
                String script = "(()=>{if(location.origin!=='https://y.qq.com'||!location.pathname.endsWith('/songDetail/" + job.id + "'))return false;"
                        + "const b=[...document.querySelectorAll('a.mod_btn_green')].find(x=>x.textContent.trim()==='播放');if(!b)return false;"
                        + "window.open=function(u){const n=new URL(u,location.href);if(n.origin===location.origin&&n.pathname==='/n/ryqq_v2/player'){setTimeout(()=>location.assign(n.href),60);return window;}return null;};b.click();return true;})()";
                job.view.evaluateJavascript(script, value -> { if ("true".equals(value)) job.clicked = true; });
            }
            pollResolve(job, attempt + 1);
        }, 450);
    }
}
