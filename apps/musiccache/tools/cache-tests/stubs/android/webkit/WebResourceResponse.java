package android.webkit;
import java.io.InputStream;
import java.util.Map;
/** JVM适配器只提供Android响应容器；不会模拟WebView播放器或网页登录。 */
public class WebResourceResponse {
    private final String mimeType;
    private final int statusCode;
    private Map<String, String> headers;
    private InputStream data;
    public WebResourceResponse(String mimeType, String encoding, int statusCode, String reasonPhrase,
                               Map<String, String> headers, InputStream data) {
        if (statusCode < 100 || statusCode > 599 || (statusCode >= 300 && statusCode <= 399)) throw new IllegalArgumentException("status");
        this.mimeType = mimeType; this.statusCode = statusCode; this.headers = headers; this.data = data;
    }
    public int getStatusCode() { return statusCode; }
    public String getMimeType() { return mimeType; }
    public Map<String, String> getResponseHeaders() { return headers; }
    public void setResponseHeaders(Map<String, String> headers) { this.headers = headers; }
    public InputStream getData() { return data; }
    public void setData(InputStream data) { this.data = data; }
}
