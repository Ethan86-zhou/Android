package android.content;
import java.io.File;
public final class Context {
    private final File root;
    public Context(File root) { this.root=root; }
    public Context getApplicationContext() { return this; }
    public File getCacheDir() { return new File(root,"cache"); }
    public File getDataDir() { return root; }
}
