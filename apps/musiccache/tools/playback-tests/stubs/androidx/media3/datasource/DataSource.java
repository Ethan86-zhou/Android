package androidx.media3.datasource;
import android.net.Uri;
import java.io.IOException;
import java.util.List;
import java.util.Map;
public interface DataSource {
    interface Factory { DataSource createDataSource(); }
    long open(DataSpec spec) throws IOException;
    int read(byte[] buffer,int offset,int length) throws IOException;
    Uri getUri();
    Map<String,List<String>> getResponseHeaders();
    void close() throws IOException;
}
