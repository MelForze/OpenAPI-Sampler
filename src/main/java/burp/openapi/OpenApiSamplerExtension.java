package burp.openapi;

import burp.IBurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.ExtensionUnloadingHandler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Montoya entry point with the public legacy callback needed for extension-driven active scan submissions.
 */
public final class OpenApiSamplerExtension implements BurpExtension, IBurpExtender, ExtensionUnloadingHandler
{
    public static final String NAME = "OpenAPI Sampler";
    public static final String VERSION = "2.2.1";
    public static final String AUTHOR = "MelForze";

    private final AtomicBoolean unloaded = new AtomicBoolean(false);
    private final ActiveScanQueue activeScanQueue = new ActiveScanQueue();
    private MontoyaApi api;
    private OpenApiSamplerTab tab;
    private Registration suiteTabRegistration;
    private Registration contextMenuRegistration;
    private Registration unloadingHandlerRegistration;

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks)
    {
        // Burp injects legacy callbacks before Montoya for a multi-API extension.
        activeScanQueue.initialize(callbacks);
    }

    @Override
    public void initialize(MontoyaApi api)
    {
        unloaded.set(false);
        this.api = api;
        api.extension().setName(NAME + " v" + VERSION);

        this.tab = new OpenApiSamplerTab(api, null, activeScanQueue);
        this.suiteTabRegistration = api.userInterface().registerSuiteTab(tab.tabTitle(), tab.uiComponent());
        this.contextMenuRegistration = api.userInterface().registerContextMenuItemsProvider(tab);
        this.unloadingHandlerRegistration = api.extension().registerUnloadingHandler(this);

        api.logging().logToOutput("[OpenAPI Sampler] Loaded. Version=" + VERSION + ", Author=" + AUTHOR);
    }

    @Override
    public void extensionUnloaded()
    {
        unloadSafely();
    }

    private void unloadSafely()
    {
        if (!unloaded.compareAndSet(false, true))
        {
            return;
        }

        activeScanQueue.close();
        deregister(contextMenuRegistration);
        deregister(suiteTabRegistration);
        deregister(unloadingHandlerRegistration);

        if (tab != null)
        {
            try
            {
                tab.dispose();
            }
            catch (Exception ex)
            {
                api.logging().logToError("[OpenAPI Sampler] Unable to dispose tab during unload", ex);
            }
        }

        unloadingHandlerRegistration = null;
        contextMenuRegistration = null;
        suiteTabRegistration = null;
        tab = null;
        api = null;
    }

    private void deregister(Registration registration)
    {
        if (registration == null)
        {
            return;
        }
        try
        {
            if (registration.isRegistered())
            {
                registration.deregister();
            }
        }
        catch (Exception ex)
        {
            api.logging().logToError("[OpenAPI Sampler] Unable to deregister during unload", ex);
        }
    }
}
