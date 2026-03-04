package burp.openapi;

import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Component;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

final class OpenApiParserExtensionTest
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
        OpenApiParserExtension extension = new OpenApiParserExtension();

        extension.initialize(ctx.api);

        verify(ctx.extension).setName("OpenAPI Parser v2.0.9");
        verify(ctx.userInterface).registerSuiteTab(eq("OpenAPI Parser"), any(Component.class));
        verify(ctx.userInterface).registerContextMenuItemsProvider(any(ContextMenuItemsProvider.class));
        verify(ctx.logging).logToOutput(contains("[OpenAPI Parser] Loaded. Version=2.0.9, Author=MelForze"));
    }
}
