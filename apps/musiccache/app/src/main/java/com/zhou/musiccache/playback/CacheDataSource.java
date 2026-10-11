package com.zhou.musiccache.playback;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import com.zhou.musiccache.cache.CacheProxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Media3读取的是普通HTTP字节；绝不能使用WebView的available/skip适配入口。 */
@UnstableApi
public final class CacheDataSource extends BaseDataSource {
    private static final long CHUNK_BYTES = 256L * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Pattern UNSATISFIED_RANGE = Pattern.compile("bytes \\*/(\\d+)");
    private final CacheProxy proxy;
    private final PlayerRuntime.SourceResolver resolver;
    private Uri publicUri;
    private PlayerRuntime.ResolvedAudio resolved;
    private Map<String, String> requestHeaders;
    private Map<String, List<String>> publicHeaders = Collections.emptyMap();
    private InputStream body;
    private long position, requestRemaining, remaining, chunkRemaining, resourceLength;
    private String firstEtag, firstMime;
    private boolean started, terminalStream;

    public static final class Factory implements DataSource.Factory {
        private final PlayerRuntime runtime;
        public Factory(Context context) { this(PlayerRuntime.get(context)); }
        public Factory(PlayerRuntime runtime) { this.runtime = runtime; }
        @Override public DataSource createDataSource() {
            return new CacheDataSource(runtime.proxy, runtime::resolve);
        }
    }

    // 包内注入仅用于JVM契约测试；生产Factory只能使用应用单例及其严格QQ白名单。
    CacheDataSource(CacheProxy proxy, PlayerRuntime.SourceResolver resolver) {
        // 混合本地/网络源不参与带宽估计，真实流量只由CacheProxy逐字节统计。
        super(false); this.proxy = proxy; this.resolver = resolver;
    }

    public static boolean isSongId(String songId) {
        return songId != null && songId.matches("[A-Za-z0-9_-]{1,64}");
    }

    public static String songId(Uri uri) throws IOException {
        try {
            URI value = new URI(uri.toString());
            String path = value.getRawPath();
            if (!"qqmusic".equals(value.getScheme()) || !"song".equals(value.getHost())
                    || value.getPort() != -1 || value.getRawUserInfo() != null
                    || value.getRawQuery() != null || value.getRawFragment() != null
                    || path == null || !path.startsWith("/") || !isSongId(path.substring(1))) {
                throw new IOException("播放器只接受QQ音乐歌曲编号");
            }
            return path.substring(1);
        } catch (URISyntaxException | NullPointerException error) {
            throw new IOException("歌曲编号无效");
        }
    }

    @Override public long open(DataSpec spec) throws IOException {
        if (publicUri != null) throw new IOException("音频源尚未关闭");
        String song = songId(spec.uri);
        if (spec.httpMethod != DataSpec.HTTP_METHOD_GET || spec.httpBody != null || spec.position < 0
                || (spec.length != C.LENGTH_UNSET && spec.length <= 0)) {
            throw new IOException("不支持这个音频读取请求");
        }
        publicUri = spec.uri;
        position = spec.position; requestRemaining = spec.length; remaining = C.LENGTH_UNSET;
        resourceLength = C.LENGTH_UNSET; firstEtag = null; firstMime = null;
        transferInitializing(spec);
        try {
            resolved = resolver.resolve(song);
            if (resolved == null || resolved.url == null || !proxy.accepts(resolved.url)) {
                throw new IOException("歌曲未返回受支持的QQ音频地址");
            }
            requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            copyRequestHeaders(resolved.headers);
            copyRequestHeaders(spec.httpRequestHeaders);
            openChunk();
            started = true; transferStarted(spec);
            // 有界请求的返回值按Media3契约保留原length；read仍允许在真实文件末尾结束。
            return spec.length == C.LENGTH_UNSET ? remaining : spec.length;
        } catch (IOException error) {
            cleanupBody();
            if (error instanceof DataSourceException) throw error;
            // resolver/HTTP异常可能带签名URL；不保留原消息、cause或suppressed异常。
            throw new IOException("音频打开失败，请检查登录和网络");
        } catch (RuntimeException error) {
            cleanupBody(); throw new IOException("音频源不可用");
        }
    }

    private void copyRequestHeaders(Map<String, String> headers) throws IOException {
        for (Map.Entry<String, String> item : headers.entrySet()) {
            String key = item.getKey(), value = item.getValue();
            if (key == null || value == null || !key.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IOException("音频请求头无效");
            }
            if (!key.equalsIgnoreCase("Range") && !key.equalsIgnoreCase("Content-Length")
                    && !key.equalsIgnoreCase("Accept-Encoding")) requestHeaders.put(key, value);
        }
    }

    private void openChunk() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("音频读取已取消");
        long count = requestRemaining == C.LENGTH_UNSET ? CHUNK_BYTES : Math.min(CHUNK_BYTES, requestRemaining);
        if (resourceLength != C.LENGTH_UNSET) count = Math.min(count, resourceLength - position);
        if (count == 0) { remaining = 0; return; }
        long requestedEnd = position > Long.MAX_VALUE - (count - 1) ? Long.MAX_VALUE : position + count - 1;
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(requestHeaders);
        headers.put("Range", "bytes=" + position + "-" + requestedEnd);
        WebResourceResponse response = proxy.intercept(resolved.url, "GET", headers);
        if (response == null) throw failure("音频来源未获允许", PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);
        body = response.getData();
        Map<String, String> responseHeaders = response.getResponseHeaders();
        int status = response.getStatusCode();
        if (status == 416) {
            Matcher match = UNSATISFIED_RANGE.matcher(header(responseHeaders, "Content-Range"));
            if (match.matches() && position == number(match.group(1))) {
                cleanupBody(); remaining = 0; chunkRemaining = 0; resourceLength = position;
                return;
            }
            throw failure("歌曲读取位置超出文件", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        if (status != 200 && status != 206) {
            throw failure("歌曲服务器返回 " + status, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);
        }
        if (body == null) throw failure("音频正文为空", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        String encoding = header(responseHeaders, "Content-Encoding");
        if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")) {
            throw failure("不支持压缩音频传输", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        String mime = response.getMimeType() == null ? "" : response.getMimeType().toLowerCase(Locale.ROOT);
        if (!(mime.startsWith("audio/") || mime.equals("application/octet-stream"))) {
            throw failure("服务器未返回音频", PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE);
        }
        String etag = header(responseHeaders, "ETag");
        if (firstMime != null && (!firstMime.equals(mime) || (isStrong(firstEtag) && !firstEtag.equals(etag)))) {
            throw failure("读取期间歌曲版本发生变化", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        if (firstMime == null) { firstMime = mime; firstEtag = etag; }
        String rawLength = header(responseHeaders, "Content-Length");
        long length = rawLength.isEmpty() ? C.LENGTH_UNSET : number(rawLength);
        if (!rawLength.isEmpty() && length < 0) throw failure("音频长度无效", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        if (status == 206) {
            Matcher match = CONTENT_RANGE.matcher(header(responseHeaders, "Content-Range"));
            if (!match.matches()) throw failure("音频区间无效", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            long start = number(match.group(1)), end = number(match.group(2)), total = number(match.group(3));
            if (start != position || end < start || total <= end || total <= 0
                    || end != Math.min(requestedEnd, total - 1)
                    || (length != C.LENGTH_UNSET && length != end - start + 1)) {
                throw failure("音频区间与请求不一致", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            }
            setResourceLength(total);
            chunkRemaining = end - start + 1; terminalStream = false;
        } else {
            // 服务器忽略Range时读丢真实前缀，不能把0起点字节冒充所请求的位置。
            if (length != C.LENGTH_UNSET) {
                setResourceLength(length);
                if (position > length) throw failure("歌曲读取位置超出文件", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            } else if (resourceLength != C.LENGTH_UNSET) {
                throw failure("读取期间歌曲长度不再明确", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            }
            skipPrefix(position);
            chunkRemaining = length == C.LENGTH_UNSET ? C.LENGTH_UNSET : length - position;
            terminalStream = true;
        }
        remaining = resourceLength == C.LENGTH_UNSET ? requestRemaining
                : requestRemaining == C.LENGTH_UNSET ? resourceLength - position : Math.min(requestRemaining, resourceLength - position);
        TreeMap<String, List<String>> safe = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : new String[] {"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
            String value = header(responseHeaders, name);
            if (!value.isEmpty()) safe.put(name, Collections.singletonList(value));
        }
        publicHeaders = Collections.unmodifiableMap(safe);
    }

    private void setResourceLength(long length) throws IOException {
        if (resourceLength != C.LENGTH_UNSET && resourceLength != length) {
            throw failure("读取期间歌曲长度发生变化", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        resourceLength = length;
    }

    private void skipPrefix(long count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("音频读取已取消");
            int read = body.read(buffer, 0, (int) Math.min(count, buffer.length));
            if (read < 0) throw failure("歌曲读取位置超出文件", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            if (read == 0) throw failure("音频正文未前进", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            count -= read;
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (!started) throw new IOException("音频源未打开");
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        try {
            if (body == null) openChunk();
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            int maximum = length;
            if (remaining != C.LENGTH_UNSET) maximum = (int) Math.min(maximum, remaining);
            if (chunkRemaining != C.LENGTH_UNSET) maximum = (int) Math.min(maximum, chunkRemaining);
            int read = body.read(buffer, offset, maximum);
            if (read < 0) {
                if (chunkRemaining > 0 || (terminalStream && remaining > 0 && resourceLength != C.LENGTH_UNSET)) {
                    throw failure("音频传输提前结束", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
                }
                remaining = 0; cleanupBody(); return C.RESULT_END_OF_INPUT;
            }
            if (read == 0) throw failure("音频正文未前进", PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            position += read;
            if (requestRemaining != C.LENGTH_UNSET) requestRemaining -= read;
            if (remaining != C.LENGTH_UNSET) remaining -= read;
            if (chunkRemaining != C.LENGTH_UNSET) chunkRemaining -= read;
            bytesTransferred(read);
            if (chunkRemaining == 0) cleanupBody();
            return read;
        } catch (IOException error) {
            cleanupBody();
            if (error instanceof DataSourceException) throw error;
            throw new IOException("音频读取失败，请重试");
        } catch (RuntimeException error) {
            cleanupBody(); throw new IOException("音频读取失败");
        }
    }

    @Override public Uri getUri() { return publicUri; }
    @Override public Map<String, List<String>> getResponseHeaders() { return publicHeaders; }

    @Override public void close() {
        cleanupBody(); resolved = null; requestHeaders = null; publicUri = null;
        publicHeaders = Collections.emptyMap(); firstEtag = null; firstMime = null;
        if (started) { started = false; transferEnded(); }
    }

    private void cleanupBody() {
        InputStream input = body; body = null;
        if (input != null) try { input.close(); } catch (IOException ignored) { }
    }

    private static DataSourceException failure(String message, int reason) { return new DataSourceException(message, reason); }
    private static long number(String value) { try { return Long.parseLong(value); } catch (NumberFormatException error) { return -1; } }
    private static String header(Map<String, String> headers, String name) {
        if (headers != null) for (Map.Entry<String, String> item : headers.entrySet()) {
            if (name.equalsIgnoreCase(item.getKey())) return item.getValue() == null ? "" : item.getValue().trim();
        }
        return "";
    }
    private static boolean isStrong(String value) { return value != null && value.startsWith("\"") && value.endsWith("\""); }
}
