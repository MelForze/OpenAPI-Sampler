package burp.openapi;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * Entry point for Burp Suite using Montoya API.
 */
public final class OpenApiParserExtension implements BurpExtension
{
    public static final String NAME = "OpenAPI Parser";
    public static final String VERSION = "2.0.12";
    public static final String AUTHOR = "MelForze";

    @Override
    public void initialize(MontoyaApi api)
    {
        api.extension().setName(NAME + " v" + VERSION);

        OpenApiParserTab tab = new OpenApiParserTab(api);

        api.userInterface().registerSuiteTab(tab.tabTitle(), tab.uiComponent());
        api.userInterface().registerContextMenuItemsProvider(tab);

        api.logging().logToOutput("[OpenAPI Parser] Loaded. Version=" + VERSION + ", Author=" + AUTHOR);
    }
}
