package android.content;
import java.io.File;
/** JVM仅提供缓存目录，不模拟Android生命周期。 */
public abstract class Context {
    public Context getApplicationContext() { return this; }
    public abstract File getFilesDir();
}
