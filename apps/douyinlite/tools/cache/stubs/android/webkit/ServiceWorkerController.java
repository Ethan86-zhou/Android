package android.webkit;
public final class ServiceWorkerController {
    private static final ServiceWorkerController INSTANCE=new ServiceWorkerController();
    private final ServiceWorkerWebSettings settings=new ServiceWorkerWebSettings();
    public static ServiceWorkerController getInstance() { return INSTANCE; }
    public ServiceWorkerWebSettings getServiceWorkerWebSettings() { return settings; }
}
