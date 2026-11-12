package burp.openapi;

import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.IScanQueueItem;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
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

        verify(ctx.extension).setName("OpenAPI Sampler v2.2.1");
        verify(ctx.userInterface).registerSuiteTab(eq("OpenAPI Sampler"), any(Component.class));
        verify(ctx.userInterface).registerContextMenuItemsProvider(any(ContextMenuItemsProvider.class));
        verify(ctx.extension).registerUnloadingHandler(eq(extension));
        verify(ctx.logging).logToOutput(contains("[OpenAPI Sampler] Loaded. Version=2.2.1, Author=MelForze"));
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
    void unloadLogsDeregistrationFailuresAndContinuesCleanup()
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiSamplerExtension extension = new OpenApiSamplerExtension();
        extension.initialize(ctx.api);
        RuntimeException failure = new IllegalStateException("deregister failed");
        doThrow(failure).when(ctx.contextMenuRegistration).deregister();

        extension.extensionUnloaded();
        extension.extensionUnloaded();

        verify(ctx.logging).logToError(contains("Unable to deregister"), eq(failure));
        verify(ctx.suiteTabRegistration).deregister();
        verify(ctx.unloadingRegistration).deregister();
        verify(ctx.contextMenuRegistration).deregister();
    }

    @Test
    void multiApiEntryPointWiresLegacyActiveQueueAndUnloadsWithoutCancellingScans() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiSamplerExtension extension = new OpenApiSamplerExtension();
        IScanQueueItem item = mock(IScanQueueItem.class);
        when(ctx.activeScanCallbacks.doActiveScan(anyString(), anyInt(), anyBoolean(), any(byte[].class))).thenReturn(item);
        // This is the documented Burp multi-API initialization order.
        extension.registerExtenderCallbacks(ctx.activeScanCallbacks);
        extension.initialize(ctx.api);
        ArgumentCaptor<ContextMenuItemsProvider> provider = ArgumentCaptor.forClass(ContextMenuItemsProvider.class);
        verify(ctx.userInterface).registerContextMenuItemsProvider(provider.capture());
        OpenApiSamplerTab tab = (OpenApiSamplerTab) provider.getValue();
        Field queueField = OpenApiSamplerTab.class.getDeclaredField("activeScanQueue");
        queueField.setAccessible(true);
        ActiveScanQueue queue = (ActiveScanQueue) queueField.get(tab);
        try
        {
            queue.submit(HttpRequest.httpRequestFromUrl("https://scan.example:8443/check"));
            verify(ctx.activeScanCallbacks).doActiveScan(eq("scan.example"), eq(8443), eq(true), any(byte[].class));
            verify(ctx.scanner, never()).startAudit(any());
        }
        finally
        {
            extension.extensionUnloaded();
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> queue.submit(HttpRequest.httpRequestFromUrl("https://scan.example/check")));
        verifyNoInteractions(item);
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
