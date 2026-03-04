package burp.openapi;

import burp.api.montoya.core.Range;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class OpenApiParserTabTest
{
    @BeforeAll
    static void setupMontoyaFactory()
    {
        MontoyaFactoryBootstrap.install();
    }

    @Test
    void basicTabMetadataAndComponentExist()
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);
        assertEquals("OpenAPI Parser", tab.tabTitle());
        assertNotNull(tab.uiComponent());
    }

    @Test
    void provideMenuItemsReturnsEmptyForIrrelevantEvents()
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);
        assertTrue(tab.provideMenuItems((ContextMenuEvent) null).isEmpty());

        ContextMenuEvent event = mock(ContextMenuEvent.class);
        when(event.isFromTool(ToolType.TARGET)).thenReturn(false);
        assertTrue(tab.provideMenuItems(event).isEmpty());
    }

    @Test
    void provideMenuItemsDetectsOpenApiUrlAndBody()
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        HttpRequestResponse urlResponse = mock(HttpRequestResponse.class);
        HttpRequest request = mock(HttpRequest.class);
        when(request.url()).thenReturn("https://api.example/openapi.json");
        when(urlResponse.request()).thenReturn(request);
        when(urlResponse.response()).thenReturn(null);

        ContextMenuEvent urlEvent = mock(ContextMenuEvent.class);
        when(urlEvent.isFromTool(ToolType.TARGET)).thenReturn(true);
        when(urlEvent.selectedRequestResponses()).thenReturn(List.of(urlResponse));
        assertEquals(1, tab.provideMenuItems(urlEvent).size());

        HttpRequestResponse bodyResponse = mock(HttpRequestResponse.class);
        HttpRequest noSpecRequest = mock(HttpRequest.class);
        when(noSpecRequest.url()).thenReturn("https://api.example/index.html");
        HttpResponse response = mock(HttpResponse.class);
        when(response.bodyToString()).thenReturn("""
                openapi: 3.0.1
                paths:
                  /users:
                    get:
                      summary: list
                """);
        when(bodyResponse.request()).thenReturn(noSpecRequest);
        when(bodyResponse.response()).thenReturn(response);

        ContextMenuEvent bodyEvent = mock(ContextMenuEvent.class);
        when(bodyEvent.isFromTool(ToolType.TARGET)).thenReturn(true);
        when(bodyEvent.selectedRequestResponses()).thenReturn(List.of(bodyResponse));
        assertEquals(1, tab.provideMenuItems(bodyEvent).size());
    }

    @Test
    void parseSpecAcceptsValidInputAndRejectsInvalidInput() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        String valid = """
                openapi: 3.0.3
                info:
                  title: Sample
                  version: 1.0.0
                paths:
                  /users:
                    get:
                      operationId: listUsers
                      responses:
                        '200':
                          description: ok
                """;

        Object parseOutcome = invoke(tab, "parseSpec",
                new Class<?>[]{String.class, String.class, String.class, boolean.class},
                valid, "inline", "inline", false);
        assertNotNull(parseOutcome);

        Exception thrown = null;
        try
        {
            invoke(tab, "parseSpec",
                    new Class<?>[]{String.class, String.class, String.class, boolean.class},
                    "not-openapi", "inline", "inline", false);
        }
        catch (Exception ex)
        {
            thrown = ex;
        }
        assertNotNull(thrown);
        assertTrue(Utils.nonBlank(thrown.getMessage()));
    }

    @Test
    void helperMethodsForRangesAndPathParsingWork() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        int idx = (int) invoke(tab, "firstAlphaNumericIndex", new Class<?>[]{String.class}, "  !!abc");
        assertEquals(4, idx);

        @SuppressWarnings("unchecked")
        List<Range> deduped = (List<Range>) invoke(tab, "dedupeRanges", new Class<?>[]{List.class},
                List.of(Range.range(1, 3), Range.range(1, 3), Range.range(3, 3), Range.range(5, 8)));
        assertEquals(2, deduped.size());
        assertEquals(1, deduped.get(0).startIndexInclusive());
        assertEquals(8, deduped.get(1).endIndexExclusive());

        HttpRequest requestWithBody = HttpRequest.httpRequestFromUrl("https://api.example/users")
                .withMethod("POST")
                .withBody("{\"name\":\"alice\"}");
        Range bodyRange = (Range) invoke(tab, "firstBodyValueRange", new Class<?>[]{HttpRequest.class}, requestWithBody);
        assertNotNull(bodyRange);
        assertTrue(bodyRange.endIndexExclusive() > bodyRange.startIndexInclusive());

        HttpRequest pathRequest = HttpRequest.httpRequest("GET /users/123?x=1 HTTP/1.1\r\nHost: api.example\r\n\r\n");
        Range pathRange = (Range) invoke(tab, "firstPathSegmentRange", new Class<?>[]{HttpRequest.class}, pathRequest);
        assertNotNull(pathRange);
    }

    @Test
    void toHostScopeUrlUsesHttpServiceWhenAvailableAndFallsBackToUrl() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        HttpRequest withService = mock(HttpRequest.class);
        HttpService service = mock(HttpService.class);
        when(withService.httpService()).thenReturn(service);
        when(service.host()).thenReturn("api.example");
        when(service.secure()).thenReturn(true);
        when(service.port()).thenReturn(8443);
        when(withService.url()).thenReturn("https://api.example:8443/path");

        String hostScope = (String) invoke(tab, "toHostScopeUrl", new Class<?>[]{HttpRequest.class}, withService);
        assertEquals("https://api.example:8443/", hostScope);

        HttpRequest fallback = mock(HttpRequest.class);
        when(fallback.httpService()).thenReturn(null);
        when(fallback.url()).thenReturn("http://plain.example:8080/path");

        String fallbackScope = (String) invoke(tab, "toHostScopeUrl", new Class<?>[]{HttpRequest.class}, fallback);
        assertEquals("http://plain.example:8080/", fallbackScope);
    }

    @Test
    void formatParseErrorsProvidesFallbackText() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        String fromNull = (String) invoke(tab, "formatParseErrors", new Class<?>[]{SwaggerParseResult.class}, (Object) null);
        assertTrue(fromNull.contains("no model"));

        SwaggerParseResult parseResult = new SwaggerParseResult();
        parseResult.setMessages(List.of("line1", "line2"));
        String fromMessages = (String) invoke(tab, "formatParseErrors", new Class<?>[]{SwaggerParseResult.class}, parseResult);
        assertEquals("line1\nline2", fromMessages);
    }

    @Test
    void selectedServerFiltersVisibleRows() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        OpenApiParserTable table = (OpenApiParserTable) field(tab, "table");
        @SuppressWarnings("unchecked")
        JComboBox<String> selector = (JComboBox<String>) field(tab, "serverSelector");

        model.load(spec("https://one.example", "/users"), "https://one.example/openapi.json");
        model.load(spec("https://two.example", "/orders"), "https://two.example/openapi.json");

        invoke(tab, "refreshServerSelector", new Class<?>[]{});
        invoke(tab, "applyFilter", new Class<?>[]{});
        assertEquals(2, table.visibleOperations().size());

        selector.setSelectedItem("https://one.example");
        invoke(tab, "applyFilter", new Class<?>[]{});
        assertEquals(1, table.visibleOperations().size());
        assertEquals("/users", table.visibleOperations().get(0).path());
    }

    @Test
    void extractHelpersReturnSafeDefaultsWhenDataMissing() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        HttpRequestResponse requestResponse = mock(HttpRequestResponse.class);
        when(requestResponse.request()).thenReturn(null);
        when(requestResponse.response()).thenReturn(null);

        String extractedUrl = (String) invoke(tab, "extractRequestUrl", new Class<?>[]{HttpRequestResponse.class}, requestResponse);
        String extractedBody = (String) invoke(tab, "extractResponseBody", new Class<?>[]{HttpRequestResponse.class}, requestResponse);

        assertEquals("", extractedUrl);
        assertEquals("", extractedBody);

        String selectedServer = (String) invoke(tab, "selectedServer", new Class<?>[]{});
        assertEquals("(Operation default)", selectedServer);
    }

    private OpenAPI spec(String server, String path)
    {
        return new OpenAPI()
                .servers(List.of(new Server().url(server)))
                .paths(new Paths().addPathItem(path, new PathItem().get(new Operation().summary(path))));
    }

    private Object field(Object target, String fieldName) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private Object invoke(Object target, String methodName, Class<?>[] types, Object... args) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        try
        {
            return method.invoke(target, args);
        }
        catch (java.lang.reflect.InvocationTargetException ex)
        {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception)
            {
                throw exception;
            }
            throw ex;
        }
    }
}
