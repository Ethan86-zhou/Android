package androidx.media3.datasource;
import android.net.Uri;
import java.util.Map;
public final class DataSpec {
    public static final int HTTP_METHOD_GET = 1;
    public final Uri uri;
    public final long position, length;
    public final int httpMethod;
    public final byte[] httpBody;
    public final Map<String,String> httpRequestHeaders;
    public DataSpec(Uri uri, long position, long length) { this(uri, position, length, 1, null, Map.of()); }
    public DataSpec(Uri uri, long position, long length, int method, byte[] body, Map<String,String> headers) {
        this.uri=uri; this.position=position; this.length=length; this.httpMethod=method; this.httpBody=body; this.httpRequestHeaders=headers;
    }
}
