package androidx.media3.datasource;
import java.io.IOException;
public final class DataSourceException extends IOException {
    public final int reason;
    public DataSourceException(String message,int reason) { super(message); this.reason=reason; }
}
