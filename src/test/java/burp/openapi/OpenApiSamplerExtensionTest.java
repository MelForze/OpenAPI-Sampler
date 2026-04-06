package burp.openapi;

import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

final class OpenApiSamplerExtensionTest
{
    @BeforeAll
    static void setupMontoyaFactory()
    {
        MontoyaFactoryBootstrap.install();
    }

    @Test
    void initializeRegistersTabContextMenuAndLogsLoadedMessage()
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiSamplerExtension extension = new OpenApiSamplerExtension();

        extension.initialize(ctx.api);

        verify(ctx.extension).setName("OpenAPI Sampler v2.0.13");
        verify(ctx.userInterface).registerSuiteTab(eq("OpenAPI Sampler"), any(Component.class));
        verify(ctx.userInterface).registerContextMenuItemsProvider(any(ContextMenuItemsProvider.class));
        verify(ctx.extension).registerUnloadingHandler(eq(extension));
        verify(ctx.logging).logToOutput(contains("[OpenAPI Sampler] Loaded. Version=2.0.13, Author=MelForze"));
    }

    @Test
    void extensionUnloadedDeregistersAndIsIdempotent()
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiSamplerExtension extension = new OpenApiSamplerExtension();
        extension.initialize(ctx.api);

        extension.extensionUnloaded();
        extension.extensionUnloaded();

        verify(ctx.suiteTabRegistration, times(1)).deregister();
        verify(ctx.contextMenuRegistration, times(1)).deregister();
        verify(ctx.unloadingRegistration, times(1)).deregister();
    }

    @Test
    void serviceEntryAndManifestEntryPointReferenceSamplerExtension() throws Exception
    {
        String serviceEntry = Files.readString(Path.of("src/main/resources/META-INF/services/burp.api.montoya.BurpExtension"));
        String manifest = Files.readString(Path.of("bapp.manifest"));

        assertTrue(serviceEntry.contains("burp.openapi.OpenApiSamplerExtension"));
        assertTrue(manifest.contains("entry_point: burp.openapi.OpenApiSamplerExtension"));
    }
}
