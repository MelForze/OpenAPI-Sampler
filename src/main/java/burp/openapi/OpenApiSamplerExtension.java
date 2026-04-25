package burp.openapi;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.ExtensionUnloadingHandler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for Burp Suite using Montoya API.
 */
public final class OpenApiSamplerExtension implements BurpExtension, ExtensionUnloadingHandler
{
    public static final String NAME = "OpenAPI Sampler";
    public static final String VERSION = "2.2.0";
    public static final String AUTHOR = "MelForze";

    private final AtomicBoolean unloaded = new AtomicBoolean(false);
    private MontoyaApi api;
    private OpenApiSamplerTab tab;
    private Registration suiteTabRegistration;
    private Registration contextMenuRegistration;
    private Registration unloadingHandlerRegistration;

    @Override
    public void initialize(MontoyaApi api)
    {
        unloaded.set(false);
        this.api = api;
        api.extension().setName(NAME + " v" + VERSION);

        this.tab = new OpenApiSamplerTab(api);
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

        deregister(contextMenuRegistration);
        deregister(suiteTabRegistration);
        deregister(unloadingHandlerRegistration);

        if (tab != null)
        {
            try
            {
                tab.dispose();
            }
            catch (Exception ignored)
            {
                // Swallow unload-time exceptions to avoid blocking Burp unload workflow.
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
        catch (Exception ignored)
        {
            // Ignore deregistration failures during unload.
        }
    }
}
