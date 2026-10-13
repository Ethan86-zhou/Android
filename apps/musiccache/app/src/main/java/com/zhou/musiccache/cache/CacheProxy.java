package com.zhou.musiccache.cache;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只接管白名单内的普通音频请求；登录、接口、网页和DRM由官方网页自身处理。 */
public final class CacheProxy {
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private final RangeCache cache;
    private final boolean allowTestLoopback;
    private final Set<HttpURLConnection> activeConnections = Collections.synchronizedSet(new HashSet<>());
    private final Set<NetworkStream> activeStreams = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong networkBytes = new AtomicLong(), hitBytes = new AtomicLong(), blockedRequests = new AtomicLong();
    private final AtomicLong validationRequests = new AtomicLong(), validationBytes = new AtomicLong(), reusedSignedLinks = new AtomicLong();
    private final AtomicLong cancellationEpoch = new AtomicLong();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private volatile boolean offline;
    private volatile Runnable observer;

    public CacheProxy(RangeCache cache) { this(cache, false); }
    /** 此参数只能由debug本地自测入口传true；真实网页入口始终使用默认构造。 */
    public CacheProxy(RangeCache cache, boolean allowTestLoopback) {
        this.cache = Objects.requireNonNull(cache); this.allowTestLoopback = allowTestLoopback;
    }
    public void setObserver(Runnable observer) { this.observer = observer; }
    public boolean isOffline() { return offline; }
    public void setOffline(boolean offline) {
        this.offline = offline;
        if (offline) cancelActive();
        event(offline ? "已切换离线：仅返回完整覆盖的本机区间" : "已恢复联网");
    }
    public void clearAudioCache() throws IOException {
        cancelActive(); cache.clear(); event("已清空音频缓存；网页登录数据保留");
    }
    public void cancelActive() {
        cancellationEpoch.incrementAndGet();
        HttpURLConnection[] connections;
        synchronized (activeConnections) { connections = activeConnections.toArray(new HttpURLConnection[0]); }
        NetworkStream[] streams;
        synchronized (activeStreams) { streams = activeStreams.toArray(new NetworkStream[0]); }
        for (NetworkStream stream : streams) stream.cancelRequested = true;
        // 某些HttpURLConnection实现的disconnect会等待正在read的内部锁，不能在UI线程执行。
        Thread cleanup = new Thread(() -> {
            for (HttpURLConnection connection : connections) connection.disconnect();
            for (NetworkStream stream : streams) try { stream.close(); } catch (IOException ignored) { }
        }, "audio-cache-cancel");
        cleanup.setDaemon(true); cleanup.start();
    }
    public static final class Snapshot {
        public final long diskBytes, networkBytes, hitBytes, blockedRequests, validationRequests, validationBytes, reusedSignedLinks;
        public final boolean offline;
        public final List<RangeCache.Entry> entries;
        public final List<String> events;
        private Snapshot(CacheProxy proxy) {
            diskBytes = proxy.cache.sizeBytes(); networkBytes = proxy.networkBytes.get(); hitBytes = proxy.hitBytes.get();
            blockedRequests = proxy.blockedRequests.get(); offline = proxy.offline; entries = proxy.cache.entries();
            validationRequests = proxy.validationRequests.get(); validationBytes = proxy.validationBytes.get();
            reusedSignedLinks = proxy.reusedSignedLinks.get();
            synchronized (proxy.events) { events = Collections.unmodifiableList(new ArrayList<>(proxy.events)); }
        }
    }
    public Snapshot snapshot() { return new Snapshot(this); }

    /** 原生播放器专用身份。来自代理对象，不读取服务器可伪造的自定义响应头。 */
    public static String playbackVersion(WebResourceResponse response) {
        return response instanceof AudioResponse ? ((AudioResponse) response).playbackVersion : "";
    }

    private static final class AudioResponse extends WebResourceResponse {
        final String playbackVersion;
        AudioResponse(String mime, int status, String reason, Map<String, String> headers,
                      InputStream body, String playbackVersion) {
            super(mime, null, status, reason, headers, body);
            this.playbackVersion = playbackVersion;
        }
    }

    public WebResourceResponse intercept(WebResourceRequest request) {
        WebResourceResponse response = intercept(request.getUrl().toString(), request.getMethod(), request.getRequestHeaders());
        int originalStatus = response == null ? 0 : response.getStatusCode();
        WebResourceResponse adapted = adaptForWebView(response, RangeCache.header(request.getRequestHeaders(), "Range"));
        if (adapted != null && originalStatus != adapted.getStatusCode()) {
            event("WebView拒绝无法确认长度或区间的音频响应（原状态 " + originalStatus + "）");
        }
        return adapted;
    }

    static WebResourceResponse adaptForWebView(WebResourceResponse response, String range) {
        if (response == null || response.getData() == null) return response;
        range = range == null ? "" : range.trim();
        boolean hasRange = !range.isEmpty();
        Map<String, String> headers = response.getResponseHeaders();
        long bodyLength = parseLength(RangeCache.header(headers, "Content-Length"));
        long representationLength = bodyLength, skip = 0;
        if (hasRange && response.getStatusCode() != 206) {
            if (response.getStatusCode() == 200) return webViewFailure(response, range, "服务器忽略了音频区间请求");
            // 错误正文与原音频Range无关。返回可读空正文，保留403/416/504等真实状态。
            return emptyWebViewResponse(response, range);
        }
        if (bodyLength < 0 || bodyLength > Integer.MAX_VALUE) {
            return webViewFailure(response, range, "音频响应长度无法通过WebView验证");
        }
        if (hasRange) {
            Matcher contentRange = CONTENT_RANGE.matcher(RangeCache.header(headers, "Content-Range"));
            if (contentRange.matches()) {
                try {
                    long start = Long.parseLong(contentRange.group(1));
                    long end = Long.parseLong(contentRange.group(2));
                    long total = Long.parseLong(contentRange.group(3));
                    long[] requested = RangeCache.parseRange(range, total);
                    if (requested != null && start == requested[0] && end == requested[1] && end < total && start >= 0
                            && total <= Integer.MAX_VALUE && bodyLength == end - start + 1) {
                        representationLength = total;
                        skip = start;
                    } else {
                        // 不猜测不一致的206正文相对位置，拒绝把不同区间伪装成请求的字节。
                        return webViewFailure(response, range, "服务器响应区间与请求不一致");
                    }
                } catch (NumberFormatException invalid) {
                    return webViewFailure(response, range, "服务器响应区间无效");
                }
            } else {
                return webViewFailure(response, range, "服务器未提供有效响应区间");
            }
        }
        // Chromium在Seek后生成Content-Length/MIME，再用AddHeader追加应用头。
        // 不能再次提供这两项，否则会出现"65536, 65536"；唯一长度由已验证的Seek结果生成。
        response.setResponseHeaders(webViewHeaders(headers, false));
        response.setData(new WebViewRangeInputStream(response.getData(), representationLength, skip, bodyLength));
        return response;
    }

    private static WebResourceResponse webViewFailure(WebResourceResponse original, String range, String detail) {
        try { original.getData().close(); } catch (IOException ignored) { }
        return emptyWebViewResponse(error(502, "Bad Gateway", detail), range);
    }
    private static WebResourceResponse emptyWebViewResponse(WebResourceResponse response, String range) {
        try { response.getData().close(); } catch (IOException ignored) { }
        response.setResponseHeaders(webViewHeaders(response.getResponseHeaders(), true));
        response.setData(new WebViewRangeInputStream(new ByteArrayInputStream(new byte[0]), 0,
                explicitRangeStart(range), 0));
        return response;
    }
    private static Map<String, String> webViewHeaders(Map<String, String> headers, boolean emptied) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Content-Type")) continue;
            if (emptied && (name.equalsIgnoreCase("Content-Encoding") || name.equalsIgnoreCase("Content-MD5")
                    || name.equalsIgnoreCase("Digest") || name.equalsIgnoreCase("ETag"))) continue;
            result.put(name, header.getValue());
        }
        return result;
    }

    private static long explicitRangeStart(String range) {
        if (!range.startsWith("bytes=")) return 0;
        int dash = range.indexOf('-', 6);
        if (dash <= 6 || range.indexOf(',') >= 0) return 0;
        return Math.max(0, parseLength(range.substring(6, dash)));
    }
    private static long parseLength(String text) {
        try { long result = Long.parseLong(text); return result >= 0 ? result : -1; }
        catch (NumberFormatException invalid) { return -1; }
    }

    /** 普通HTTP/缓存核心入口；WebView独有的流初始化寻址适配仅位于WebResourceRequest重载。 */
    public WebResourceResponse intercept(String url, String method, Map<String, String> requestHeaders) {
        if (!accepts(url)) return null;
        String label = RangeCache.keyForUrl(url).substring(0, 10);
        if (!"GET".equalsIgnoreCase(method)) return error(405, "Method Not Allowed", "音频代理只支持 GET");
        String range = RangeCache.header(requestHeaders, "Range");
        if (range.isEmpty()) range = null;
        String requestControl = RangeCache.header(requestHeaders, "Cache-Control").toLowerCase(Locale.ROOT);
        boolean forceNetwork = requestControl.contains("no-cache") || requestControl.contains("no-store") || requestControl.contains("max-age=0")
                || RangeCache.header(requestHeaders, "Pragma").toLowerCase(Locale.ROOT).contains("no-cache");
        // 未实现条件请求求值，不能用普通缓存命中擅自回答If-Range/If-Match等条件。
        for (String conditional : new String[] {"If-Range", "If-None-Match", "If-Modified-Since", "If-Match", "If-Unmodified-Since"}) {
            if (!RangeCache.header(requestHeaders, conditional).isEmpty()) forceNetwork = true;
        }
        try {
            String resourceKey = RangeCache.resourceKeyForUrl(url, allowTestLoopback);
            RangeCache.CachedResponse cached = forceNetwork ? null : cache.open(url, range, resourceKey);
            if (cached != null) {
                return cachedResponse(cached, label);
            }
            if (offline) {
                blockedRequests.incrementAndGet(); event("离线缺段或已过期 " + label);
                return error(504, "Gateway Timeout", "离线缓存未完整覆盖这个请求");
            }
            if (!forceNetwork && cache.hasReusableResource(resourceKey)) {
                ProbeResult probe = validateSignedLink(url, requestHeaders, label, resourceKey);
                if (probe.failure != null) return probe.failure;
                if (probe.reused) {
                    cached = cache.open(url, range, resourceKey);
                    if (cached != null) return cachedResponse(cached, label);
                }
            }
            return fetch(url, requestHeaders, label);
        } catch (IOException | RuntimeException error) {
            // 异常消息可能包含签名 URL；只记录异常类型，不记录消息或堆栈。
            event("音频请求失败 " + label + "（" + error.getClass().getSimpleName() + "）");
            return error(502, "Bad Gateway", "音频请求未完成，请联网后重试");
        }
    }

    private WebResourceResponse cachedResponse(RangeCache.CachedResponse cached, String label) {
        event("缓存命中 " + label + "，范围 " + cached.start + "–" + cached.endInclusive);
        InputStream counted = new FilterInputStream(cached.body) {
            @Override public int read() throws IOException {
                int value = in.read(); if (value >= 0) hitBytes.incrementAndGet(); return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = in.read(bytes, offset, length); if (count > 0) hitBytes.addAndGet(count); return count;
            }
        };
        return new AudioResponse(cached.mimeType, cached.statusCode,
                cached.statusCode == 206 ? "Partial Content" : "OK", cached.headers, counted, cached.playbackVersion);
    }

    private static final class ProbeResult {
        final boolean reused;
        final WebResourceResponse failure;
        ProbeResult(boolean reused, WebResourceResponse failure) { this.reused = reused; this.failure = failure; }
    }

    /** 新签名必须在当前URL真实联网成功：不复用HTTP缓存、不跟随跳转、只请求首字节验证权限及版本。 */
    private ProbeResult validateSignedLink(String url, Map<String, String> requestHeaders, String label, String resourceKey) throws IOException {
        long epoch = cancellationEpoch.get();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
        connection.setConnectTimeout(12000); connection.setReadTimeout(18000); connection.setRequestMethod("GET");
        for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
            String name = header.getKey();
            if (name == null || name.equalsIgnoreCase("Range") || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Connection")
                    || name.equalsIgnoreCase("Accept-Encoding") || name.equalsIgnoreCase("Content-Length") || name.startsWith(":")) continue;
            connection.setRequestProperty(name, header.getValue());
        }
        connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("Range", "bytes=0-0");
        activeConnections.add(connection);
        try {
            if (offline || epoch != cancellationEpoch.get()) return new ProbeResult(false, offlineError());
            validationRequests.incrementAndGet();
            int status = connection.getResponseCode();
            if (offline || epoch != cancellationEpoch.get()) return new ProbeResult(false, offlineError());
            Map<String, String> headers = responseHeaders(connection);
            if (status != 206) {
                cache.rejectAuthorization(url);
                event("新链接验证失败 " + label + "（状态 " + status + "），未使用旧音频");
                int reported = status >= 400 && status <= 599 ? status : 502;
                return new ProbeResult(false, validationFailure(reported, "新播放链接未通过权限验证", headers));
            }
            Matcher match = CONTENT_RANGE.matcher(RangeCache.header(headers, "Content-Range"));
            long total = -1;
            if (match.matches() && match.group(1).equals("0") && match.group(2).equals("0")) {
                try { total = Long.parseLong(match.group(3)); } catch (NumberFormatException ignored) { }
            }
            String mime = RangeCache.header(headers, "Content-Type");
            int semicolon = mime.indexOf(';'); if (semicolon >= 0) mime = mime.substring(0, semicolon).trim();
            if (total <= 0 || connection.getContentLengthLong() != 1
                    || !(mime.toLowerCase(Locale.ROOT).startsWith("audio/") || mime.equalsIgnoreCase("application/octet-stream"))) {
                cache.rejectAuthorization(url);
                event("新链接验证响应无效 " + label + "，未使用旧音频");
                return new ProbeResult(false, validationFailure(502, "新链接未返回准确的一字节音频区间", headers));
            }
            try (InputStream body = connection.getInputStream()) {
                int first = body.read(); if (first >= 0) validationBytes.incrementAndGet();
                int extra = body.read(); if (extra >= 0) validationBytes.incrementAndGet();
                if (first < 0 || extra >= 0) {
                    cache.rejectAuthorization(url);
                    return new ProbeResult(false, validationFailure(502, "新链接验证响应不完整", headers));
                }
            }
            if (offline || epoch != cancellationEpoch.get()) return new ProbeResult(false, offlineError());
            boolean reused = cache.reuseValidated(url, resourceKey, mime, total, headers);
            if (reused) {
                if (cache.isAlias(url)) reusedSignedLinks.incrementAndGet();
                event("新链接已验证 " + label + "：仅获取1字节，继续读取已有音频");
            } else event("新链接版本或缓存策略不同 " + label + "，获取当前授权音频");
            return new ProbeResult(reused, null);
        } finally {
            activeConnections.remove(connection); connection.disconnect();
        }
    }

    private static WebResourceResponse validationFailure(int status, String text, Map<String, String> originHeaders) {
        WebResourceResponse response = error(status, reasonFor(status), text);
        Map<String, String> headers = new LinkedHashMap<>(response.getResponseHeaders());
        for (String name : new String[] {"Access-Control-Allow-Origin", "Access-Control-Allow-Credentials", "Access-Control-Expose-Headers"}) {
            String value = RangeCache.header(originHeaders, name); if (!value.isEmpty()) headers.put(name, value);
        }
        response.setResponseHeaders(headers); return response;
    }

    public boolean accepts(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null) return false;
            String path = uri.getPath();
            if (path == null) return false;
            if (allowTestLoopback && "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 8765) {
                return path.matches("/media/[A-Za-z0-9_-]+\\.wav") &&
                    (uri.getRawQuery() == null || uri.getRawQuery().matches("(?:run|variant)=[A-Za-z0-9_-]{1,96}(?:&(?:run|variant)=[A-Za-z0-9_-]{1,96})*"));
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.endsWith(".stream.qqmusic.qq.com") || host.length() <= ".stream.qqmusic.qq.com".length()) return false;
            return path.toLowerCase(Locale.ROOT).matches(".*\\.(m4a|mp3|aac|flac|ogg)");
        } catch (URISyntaxException error) { return false; }
    }

    private WebResourceResponse fetch(String url, Map<String, String> requestHeaders, String label) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12000); connection.setReadTimeout(18000); connection.setUseCaches(false);
        connection.setRequestMethod("GET");
        for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
            String name = header.getKey();
            if (name == null || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Connection") ||
                name.equalsIgnoreCase("Accept-Encoding") || name.equalsIgnoreCase("Content-Length") || name.startsWith(":")) continue;
            connection.setRequestProperty(name, header.getValue());
        }
        connection.setRequestProperty("Accept-Encoding", "identity");
        activeConnections.add(connection);
        boolean handedOff = false;
        RangeCache.WriteSession pendingCapture = null;
        NetworkStream pendingStream = null;
        try {
            if (offline) return offlineError();
            int status = connection.getResponseCode();
            if (offline) return offlineError();
            // WebResourceResponse不能可靠表达3xx；拒绝重定向，避免白名单和登录凭据跨域逃逸。
            if (status >= 300 && status < 400) {
                event("音频重定向被拒绝 " + label); return error(502, "Bad Gateway", "验证器不代理重定向音频");
            }
            Map<String, String> originalHeaders = responseHeaders(connection);
            if (status == 401 || status == 403 || status == 404) cache.rejectAuthorization(url);
            boolean requestNoStore = RangeCache.header(requestHeaders, "Cache-Control").toLowerCase(Locale.ROOT).contains("no-store");
            if (requestNoStore || RangeCache.expiryFor(originalHeaders, System.currentTimeMillis()) <= System.currentTimeMillis()) cache.invalidate(url);
            String mime = RangeCache.header(originalHeaders, "Content-Type");
            int semicolon = mime.indexOf(';'); if (semicolon >= 0) mime = mime.substring(0, semicolon).trim();
            if (mime.isEmpty()) mime = "application/octet-stream";
            long length = connection.getContentLengthLong();
            long total = length, start = 0;
            boolean validRange = status == 200;
            if (status == 206) {
                Matcher match = CONTENT_RANGE.matcher(RangeCache.header(originalHeaders, "Content-Range"));
                if (match.matches()) {
                    try {
                        start = Long.parseLong(match.group(1)); long end = Long.parseLong(match.group(2)); total = Long.parseLong(match.group(3));
                        validRange = start >= 0 && end >= start && end < total && length == end - start + 1;
                    } catch (NumberFormatException ignored) { validRange = false; }
                }
            }
            String lowerMime = mime.toLowerCase(Locale.ROOT);
            boolean audioMime = lowerMime.startsWith("audio/") || lowerMime.equals("application/octet-stream");
            if ((status == 200 || status == 206) && audioMime && validRange && length > 0 && !requestNoStore) {
                pendingCapture = cache.begin(url, mime, total, start, length, originalHeaders,
                        RangeCache.resourceKeyForUrl(url, allowTestLoopback));
                if (pendingCapture == null) event("仅播放、不新增离线缓存 " + label + "（响应策略或容量限制）");
                else event("网络读取 " + label + "，范围 " + start + "–" + (start + length - 1));
            } else {
                event("未缓存响应 " + label + "（状态 " + status + " 或长度未知）");
            }
            InputStream upstream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (upstream == null) upstream = new ByteArrayInputStream(new byte[0]);
            NetworkStream stream = new NetworkStream(upstream, connection, pendingCapture, length, label);
            pendingStream = stream; pendingCapture = null;
            activeStreams.add(stream);
            Map<String, String> response = new LinkedHashMap<>(originalHeaders);
            // 本实验的持久化由RangeCache独占；防止WebView另有不可观测的磁盘音频副本。
            response.put("Cache-Control", "no-store");
            String reason = reasonFor(status);
            WebResourceResponse result = new AudioResponse(mime, status, reason, response, stream,
                    RangeCache.playbackVersion(originalHeaders));
            handedOff = true;
            return result;
        } finally {
            if (!handedOff) {
                if (pendingCapture != null) pendingCapture.close();
                if (pendingStream != null) pendingStream.close();
                activeConnections.remove(connection); connection.disconnect();
            }
        }
    }

    private final class NetworkStream extends FilterInputStream {
        private final HttpURLConnection connection;
        private volatile RangeCache.WriteSession capture;
        private final long expected;
        private final String label;
        private long received;
        private volatile boolean closed;
        private volatile boolean cancelRequested;
        NetworkStream(InputStream input, HttpURLConnection connection, RangeCache.WriteSession capture, long expected, String label) {
            super(input); this.connection = connection; this.capture = capture; this.expected = expected; this.label = label;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (closed || cancelRequested || offline) { close(); throw new IOException("Audio connection cancelled"); }
            try {
                int read = in.read(bytes, offset, count);
                if (read > 0) networkBytes.addAndGet(read);
                if (cancelRequested || offline) throw new IOException("Audio connection cancelled");
                if (read > 0) {
                    received += read;
                    RangeCache.WriteSession target = capture;
                    if (target != null) {
                        try {
                            target.append(bytes, offset, read);
                            if (received == expected) {
                                boolean saved = target.finish(); capture = null;
                                event(saved ? "已保存完整响应区间 " + label : "响应区间未提交 " + label);
                            }
                        } catch (IOException storageError) {
                            target.close(); capture = null; event("音频缓存写入失败 " + label);
                        }
                    }
                } else if (read == -1 && capture != null) {
                    RangeCache.WriteSession target = detachCapture();
                    if (target != null) target.close();
                    event("响应提前结束，未保存该区间 " + label);
                }
                return read;
            } catch (IOException error) { close(); throw error; }
        }
        @Override public void close() throws IOException {
            synchronized (this) { if (closed) return; closed = true; }
            RangeCache.WriteSession target = detachCapture();
            try { if (target != null) { target.close(); event("读取中止，未保存该区间 " + label); } }
            finally {
                try { in.close(); } finally {
                    connection.disconnect(); activeConnections.remove(connection); activeStreams.remove(this);
                }
            }
        }
        private synchronized RangeCache.WriteSession detachCapture() {
            RangeCache.WriteSession target = capture; capture = null; return target;
        }
    }

    private WebResourceResponse offlineError() {
        blockedRequests.incrementAndGet(); return error(504, "Gateway Timeout", "已离线，网络请求已取消");
    }
    private static WebResourceResponse error(int status, String reason, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Length", Integer.toString(bytes.length)); headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, headers, new ByteArrayInputStream(bytes));
    }
    private static Map<String, String> responseHeaders(HttpURLConnection connection) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> item : connection.getHeaderFields().entrySet()) {
            String name = item.getKey();
            if (name == null || name.equalsIgnoreCase("Transfer-Encoding") || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Keep-Alive") || item.getValue() == null) continue;
            String canonical = name.equalsIgnoreCase("Cache-Control") ? "Cache-Control" : name;
            result.put(canonical, String.join(", ", item.getValue()));
        }
        return result;
    }
    private static String reasonFor(int status) {
        switch (status) {
            case 200: return "OK"; case 206: return "Partial Content"; case 400: return "Bad Request";
            case 401: return "Unauthorized"; case 403: return "Forbidden"; case 404: return "Not Found";
            case 416: return "Range Not Satisfiable"; case 500: return "Internal Server Error";
            case 503: return "Service Unavailable"; default: return "HTTP Response";
        }
    }
    private void event(String message) {
        synchronized (events) { events.addFirst(message); while (events.size() > 24) events.removeLast(); }
        Runnable listener = observer;
        if (listener != null) listener.run();
    }
}
