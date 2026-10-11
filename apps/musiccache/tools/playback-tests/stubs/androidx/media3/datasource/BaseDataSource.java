package androidx.media3.datasource;
/** 仅核对数据传输事件顺序；真实Media3 API兼容性由Android编译验证。 */
public abstract class BaseDataSource implements DataSource {
    public int transferStarts, transferEnds;
    public long transferred;
    private boolean started;
    protected BaseDataSource(boolean isNetwork) { }
    protected final void transferInitializing(DataSpec spec) { }
    protected final void transferStarted(DataSpec spec) { if(started)throw new AssertionError("duplicate start"); started=true; transferStarts++; }
    protected final void bytesTransferred(int count) { if(!started)throw new AssertionError("bytes before start"); transferred+=count; }
    protected final void transferEnded() { if(!started)throw new AssertionError("end before start"); started=false; transferEnds++; }
}
