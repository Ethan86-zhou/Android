package android.webkit;
public final class WebView {
    private final WebSettings settings=new WebSettings();
    public int clearCalls;
    public boolean includeDiskFiles;
    public Runnable onClear;
    public WebSettings getSettings() { return settings; }
    public void clearCache(boolean disk) {
        clearCalls++; includeDiskFiles=disk;
        if(onClear!=null) onClear.run();
    }
}
