package burp.openapi;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.repeater.Repeater;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void urlListParsingHelpersWork() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        String normalizedQuoted = (String) invoke(tab, "normalizeToken", new Class<?>[]{String.class}, "\"api.example/openapi.json\"");
        assertEquals("api.example/openapi.json", normalizedQuoted);

        String normalizedComment = (String) invoke(tab, "normalizeToken", new Class<?>[]{String.class}, "api.example/openapi.json # note");
        assertEquals("api.example/openapi.json", normalizedComment);

        @SuppressWarnings("unchecked")
        List<String> tokens = (List<String>) invoke(tab, "csvTokens", new Class<?>[]{String.class}, "svc;https://api.example/openapi.json;note");
        assertEquals(List.of("svc", "https://api.example/openapi.json", "note"), tokens);

        boolean bareHost = (boolean) invoke(tab, "looksLikeBareUrlCandidate", new Class<?>[]{String.class}, "api.example/openapi.json");
        assertTrue(bareHost);

        boolean relative = (boolean) invoke(tab, "looksLikeBareUrlCandidate", new Class<?>[]{String.class}, "/openapi.json");
        assertFalse(relative);
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
        JComboBox<?> sourceSelector = (JComboBox<?>) field(tab, "sourceSelector");
        @SuppressWarnings("unchecked")
        JComboBox<String> selector = (JComboBox<String>) field(tab, "serverSelector");

        model.load(spec("https://one.example", "/users"), "https://one.example/openapi.json", "one");
        model.load(spec("https://two.example", "/orders"), "https://two.example/openapi.json", "two");

        invoke(tab, "refreshSourceSelector", new Class<?>[]{});
        invoke(tab, "refreshServerSelector", new Class<?>[]{});
        invoke(tab, "applyFilter", new Class<?>[]{});
        assertEquals(2, table.visibleOperations().size());

        selector.setSelectedItem("https://one.example");
        invoke(tab, "applyFilter", new Class<?>[]{});
        assertEquals(1, table.visibleOperations().size());
        assertEquals("/users", table.visibleOperations().get(0).path());

        sourceSelector.setSelectedIndex(2);
        invoke(tab, "refreshServerSelector", new Class<?>[]{});
        invoke(tab, "applyFilter", new Class<?>[]{});
        assertEquals(1, table.visibleOperations().size());
        assertEquals("/orders", table.visibleOperations().get(0).path());
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

    @Test
    void uiStateIsPersistedAndRestored() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab first = new OpenApiParserTab(ctx.api);

        JTextField urlField = (JTextField) field(first, "urlField");
        JTextField filterField = (JTextField) field(first, "filterField");
        @SuppressWarnings("unchecked")
        JComboBox<String> selector = (JComboBox<String>) field(first, "serverSelector");

        urlField.setText("https://state.example/openapi.json");
        filterField.setText("users");
        selector.setSelectedItem("(Operation default)");
        invoke(first, "persistUiState", new Class<?>[]{});
        assertEquals("https://state.example/openapi.json", ctx.extensionData.getString("ui.urlField"));
        assertEquals("users", ctx.extensionData.getString("ui.filterField"));
        assertEquals("", ctx.extensionData.getString("ui.source"));

        OpenApiParserTab restored = new OpenApiParserTab(ctx.api);
        JTextField restoredUrl = (JTextField) field(restored, "urlField");
        JTextField restoredFilter = (JTextField) field(restored, "filterField");

        assertEquals("https://state.example/openapi.json", restoredUrl.getText());
        assertTrue(restoredFilter.getText().isEmpty() || "users".equals(restoredFilter.getText()));
    }

    @Test
    void exportDocumentIncludesSourceAndAllFormats() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        model.load(spec("https://export.example", "/users"), "https://export.example/openapi.json", "export-source");

        invoke(tab, "refreshSourceSelector", new Class<?>[]{});
        invoke(tab, "refreshServerSelector", new Class<?>[]{});
        invoke(tab, "applyFilter", new Class<?>[]{});

        @SuppressWarnings("unchecked")
        Object export = invoke(
                tab,
                "buildExportDocument",
                new Class<?>[]{List.class},
                List.of(model.operations().get(0))
        );

        String content = String.valueOf(recordValue(export, "content"));
        assertTrue(content.contains("Source: export-source"));
        assertTrue(content.contains("[RAW HTTP]"));
        assertTrue(content.contains("[CURL]"));
        assertTrue(content.contains("[PYTHON REQUESTS]"));
    }

    @Test
    void retryableFetchErrorDetectionWorks() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        boolean timeout = (boolean) invoke(tab, "isRetryableFetchError", new Class<?>[]{IOException.class}, new IOException("connection timeout"));
        boolean tooMany = (boolean) invoke(tab, "isRetryableFetchError", new Class<?>[]{IOException.class}, new IOException("HTTP 429 returned"));
        boolean canceled = (boolean) invoke(tab, "isRetryableFetchError", new Class<?>[]{IOException.class}, new IOException("Canceled by user"));
        boolean badRequest = (boolean) invoke(tab, "isRetryableFetchError", new Class<?>[]{IOException.class}, new IOException("HTTP 400 returned"));

        assertTrue(timeout);
        assertTrue(tooMany);
        assertFalse(canceled);
        assertFalse(badRequest);
    }

    @Test
    void bestDecodedCandidatePrefersReadableCyrillicText() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);
        String mojibake = "ÐÑÐ¸Ð²ÐµÑ";
        String readable = "Привет";

        @SuppressWarnings("unchecked")
        String best = (String) invoke(
                tab,
                "bestDecodedCandidate",
                new Class<?>[]{List.class, String.class},
                List.of(mojibake, readable),
                mojibake
        );

        assertEquals(readable, best);
    }

    @Test
    void decodeScorePenalizesCp1251Utf8MojibakePattern() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);
        String mojibake = "РџСЂРёРІРµС‚";
        String readable = "Привет";

        int mojibakeScore = (Integer) invoke(tab, "decodeScore", new Class<?>[]{String.class}, mojibake);
        int readableScore = (Integer) invoke(tab, "decodeScore", new Class<?>[]{String.class}, readable);

        assertTrue(readableScore > mojibakeScore);
    }

    @Test
    void readSpecFileContentHandlesWindows1251RussianSummary() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);
        Path file = Files.createTempFile("openapi-ru-", ".json");
        try
        {
            String spec = "{\"swagger\":\"2.0\",\"paths\":{\"/x\":{\"get\":{\"summary\":\"Привет\"}}}}";
            Files.write(file, spec.getBytes(Charset.forName("windows-1251")));

            String decoded = (String) invoke(tab, "readSpecFileContent", new Class<?>[]{Path.class}, file);
            assertTrue(decoded.contains("Привет"));
        }
        finally
        {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void autoIncludeSpecHostInScopeIncludesWhenMissingAndSkipsWhenAlreadyInScope() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);

        when(ctx.scope.isInScope("https://api.example/")).thenReturn(false);
        invoke(tab, "autoIncludeSpecHostInScope", new Class<?>[]{String.class}, "https://api.example/openapi.json");
        verify(ctx.scope).includeInScope("https://api.example/");

        when(ctx.scope.isInScope("https://already.example/")).thenReturn(true);
        invoke(tab, "autoIncludeSpecHostInScope", new Class<?>[]{String.class}, "https://already.example/openapi.json");
        verify(ctx.scope, never()).includeInScope("https://already.example/");

        invoke(tab, "autoIncludeSpecHostInScope", new Class<?>[]{String.class}, "file:/tmp/openapi.yaml");
        verify(ctx.scope, never()).includeInScope("file:/tmp/");
    }

    @Test
    void urlListLineParsingSupportsCommentsCsvAndBareHosts() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        Object blank = invoke(tab, "parseUrlLine", new Class<?>[]{String.class}, "   ");
        assertFalse((Boolean) recordValue(blank, "accepted"));

        Object comment = invoke(tab, "parseUrlLine", new Class<?>[]{String.class}, "#comment");
        assertFalse((Boolean) recordValue(comment, "accepted"));

        Object direct = invoke(tab, "parseUrlLine", new Class<?>[]{String.class}, "https://api.example/openapi.json");
        assertTrue((Boolean) recordValue(direct, "accepted"));
        assertEquals("https://api.example/openapi.json", recordValue(direct, "url"));
        assertFalse((Boolean) recordValue(direct, "normalized"));

        Object csv = invoke(tab, "parseUrlLine", new Class<?>[]{String.class}, "svc, https://api.csv.example/openapi.json, note");
        assertTrue((Boolean) recordValue(csv, "accepted"));
        assertEquals("https://api.csv.example/openapi.json", recordValue(csv, "url"));
        assertTrue((Boolean) recordValue(csv, "normalized"));

        Object bare = invoke(tab, "parseUrlLine", new Class<?>[]{String.class}, "api.no-scheme.example/openapi.yaml");
        assertTrue((Boolean) recordValue(bare, "accepted"));
        assertEquals("https://api.no-scheme.example/openapi.yaml", recordValue(bare, "url"));
        assertTrue((Boolean) recordValue(bare, "normalized"));

        Object invalid = invoke(tab, "parseUrlLine", new Class<?>[]{String.class}, "/v3/api-docs");
        assertFalse((Boolean) recordValue(invalid, "accepted"));
    }

    @Test
    void urlListBatchParsingDeduplicatesAndCounts() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);
        @SuppressWarnings("unchecked")
        Object parsed = invoke(tab, "parseUrlList", new Class<?>[]{List.class}, List.of(
                "",
                "# comment",
                "https://api.example/openapi.json",
                "api.example/openapi.json",
                "svc,https://api.example/openapi.json,note",
                "bad"
        ));

        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) recordValue(parsed, "urls");
        assertEquals(1, urls.size());
        assertEquals("https://api.example/openapi.json", urls.get(0));
        assertEquals(6, recordValue(parsed, "totalLines"));
        assertEquals(2, recordValue(parsed, "normalizedCount"));

        @SuppressWarnings("unchecked")
        List<String> skipNotes = (List<String>) recordValue(parsed, "skipNotes");
        assertEquals(3, skipNotes.size());
    }

    @Test
    void buildCandidateSpecUrlsAddsSwaggerFallbackEndpoints() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) invoke(
                tab,
                "buildCandidateSpecUrls",
                new Class<?>[]{String.class},
                "https://api.example.com/swagger/index.html"
        );

        assertTrue(candidates.contains("https://api.example.com/swagger/index.html"));
        assertTrue(candidates.contains("https://api.example.com/v3/api-docs"));
        assertTrue(candidates.contains("https://api.example.com/v2/api-docs"));
        assertTrue(candidates.contains("https://api.example.com/openapi.json"));
        assertTrue(candidates.contains("https://api.example.com/swagger/v1/swagger.json"));
    }

    @Test
    void buildCandidateSpecUrlsReadsQueryUrlAndConfigUrl() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) invoke(
                tab,
                "buildCandidateSpecUrls",
                new Class<?>[]{String.class},
                "https://docs.example.com/swagger/index.html?url=%2Fv3%2Fapi-docs&configUrl=%2Fv3%2Fapi-docs%2Fswagger-config"
        );

        assertTrue(candidates.contains("https://docs.example.com/v3/api-docs"));
        assertTrue(candidates.contains("https://docs.example.com/v3/api-docs/swagger-config"));
    }

    @Test
    void extractReferencedUrlsFindsSwaggerUiUrlFields() throws Exception
    {
        OpenApiParserTab tab = new OpenApiParserTab(TestApiFactory.apiContext().api);

        String html = """
                <script>
                  window.ui = SwaggerUIBundle({
                    url: "/v3/api-docs",
                    configUrl: "/v3/api-docs/swagger-config",
                    urls: [{ url: "https://alt.example.com/openapi.json", name: "alt" }]
                  });
                </script>
                """;

        @SuppressWarnings("unchecked")
        List<String> discovered = (List<String>) invoke(
                tab,
                "extractReferencedUrls",
                new Class<?>[]{String.class, String.class},
                "https://docs.example.com/swagger/index.html",
                html
        );

        assertTrue(discovered.contains("https://docs.example.com/v3/api-docs"));
        assertTrue(discovered.contains("https://docs.example.com/v3/api-docs/swagger-config"));
        assertTrue(discovered.contains("https://alt.example.com/openapi.json"));
    }

    @Test
    void sendAllVisibleActionQueuesAllVisibleRowsToRepeater() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");

        model.load(spec("https://one.example", "/users"), "https://one.example/openapi.json");
        model.load(spec("https://two.example", "/orders"), "https://two.example/openapi.json");
        invoke(tab, "refreshServerSelector", new Class<?>[]{});
        invoke(tab, "applyFilter", new Class<?>[]{});

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_VISIBLE_TO_REPEATER,
                List.of()
        );

        Repeater repeater = ctx.repeater;
        verify(repeater, times(2)).sendToRepeater(any(HttpRequest.class), contains("OpenAPI Parser / All /"));
    }

    @Test
    void activeScanTaskIsCreatedOnceAndReusedOnSecondSend() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        model.load(spec("https://scan.example", "/users"), "https://scan.example/openapi.json");

        Audit audit = mock(Audit.class);
        CountDownLatch latch = new CountDownLatch(2);
        when(ctx.scanner.startAudit(any())).thenReturn(audit);
        when(audit.statusMessage()).thenReturn("running");
        when(audit.requestCount()).thenReturn(0);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(audit).addRequest(any(HttpRequest.class));

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );
        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        verify(ctx.scanner, times(1)).startAudit(any());
        verify(audit, times(2)).addRequest(any(HttpRequest.class));
    }

    @Test
    void activeScanTaskIsRecreatedWhenCachedTaskIsUnavailableBeforeQueue() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        model.load(spec("https://scan.example", "/users"), "https://scan.example/openapi.json");

        Audit firstAudit = mock(Audit.class);
        Audit secondAudit = mock(Audit.class);
        CountDownLatch firstLatch = new CountDownLatch(1);
        CountDownLatch secondLatch = new CountDownLatch(1);
        when(ctx.scanner.startAudit(any())).thenReturn(firstAudit, secondAudit);
        when(firstAudit.statusMessage()).thenThrow(new RuntimeException("task removed"));
        when(secondAudit.statusMessage()).thenReturn("running");
        when(secondAudit.requestCount()).thenReturn(0);

        doAnswer(invocation -> {
            firstLatch.countDown();
            return null;
        }).when(firstAudit).addRequest(any(HttpRequest.class));
        doAnswer(invocation -> {
            secondLatch.countDown();
            return null;
        }).when(secondAudit).addRequest(any(HttpRequest.class));

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );
        assertTrue(firstLatch.await(2, TimeUnit.SECONDS));

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );

        assertTrue(secondLatch.await(2, TimeUnit.SECONDS));
        verify(ctx.scanner, times(2)).startAudit(any());
        verify(firstAudit, times(1)).addRequest(any(HttpRequest.class));
        verify(secondAudit, times(1)).addRequest(any(HttpRequest.class));
    }

    @Test
    void addRequestFailureWithUnavailableTaskRecreatesAndRetries() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        model.load(spec("https://scan.example", "/users"), "https://scan.example/openapi.json");

        Audit firstAudit = mock(Audit.class);
        Audit secondAudit = mock(Audit.class);
        CountDownLatch latch = new CountDownLatch(1);

        when(ctx.scanner.startAudit(any())).thenReturn(firstAudit, secondAudit);
        doAnswer(invocation -> {
            throw new RuntimeException("add failed");
        }).when(firstAudit).addRequest(any(HttpRequest.class));
        when(firstAudit.statusMessage()).thenThrow(new RuntimeException("task deleted"));
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(secondAudit).addRequest(any(HttpRequest.class));

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(waitForStatus(tab, text -> text.contains("recreated=1") && text.contains("retried=1")));
        verify(ctx.scanner, times(2)).startAudit(any());
        verify(firstAudit, times(1)).addRequest(any(HttpRequest.class));
        verify(secondAudit, times(1)).addRequest(any(HttpRequest.class));
    }

    @Test
    void addRequestFailureWithUsableTaskDoesNotRecreateAndCountsFailure() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        model.load(spec("https://scan.example", "/users"), "https://scan.example/openapi.json");

        Audit audit = mock(Audit.class);
        CountDownLatch latch = new CountDownLatch(1);
        when(ctx.scanner.startAudit(any())).thenReturn(audit);
        doAnswer(invocation -> {
            latch.countDown();
            throw new RuntimeException("add failed");
        }).when(audit).addRequest(any(HttpRequest.class));
        when(audit.statusMessage()).thenReturn("running");
        when(audit.requestCount()).thenReturn(0);

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(waitForStatus(tab, text -> text.contains("failed=1") && text.contains("recreated=0")));
        verify(ctx.scanner, times(1)).startAudit(any());
        verify(audit, times(1)).addRequest(any(HttpRequest.class));
    }

    @Test
    void activeAndPassiveScanTasksAreIndependent() throws Exception
    {
        TestApiFactory.ApiContext ctx = TestApiFactory.apiContext();
        OpenApiParserTab tab = new OpenApiParserTab(ctx.api);
        OpenApiParserModel model = (OpenApiParserModel) field(tab, "model");
        model.load(spec("https://scan.example", "/users"), "https://scan.example/openapi.json");

        Audit activeAudit = mock(Audit.class);
        Audit passiveAudit = mock(Audit.class);
        CountDownLatch activeLatch = new CountDownLatch(2);
        CountDownLatch passiveLatch = new CountDownLatch(1);

        when(ctx.scanner.startAudit(any())).thenReturn(activeAudit, passiveAudit);
        when(activeAudit.statusMessage()).thenReturn("running");
        when(activeAudit.requestCount()).thenReturn(0);
        when(passiveAudit.statusMessage()).thenReturn("running");
        when(passiveAudit.requestCount()).thenReturn(0);
        doAnswer(invocation -> {
            activeLatch.countDown();
            return null;
        }).when(activeAudit).addRequest(any(HttpRequest.class));
        doAnswer(invocation -> {
            passiveLatch.countDown();
            return null;
        }).when(passiveAudit).addRequest(any(HttpRequest.class));

        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );
        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_PASSIVE_SCAN,
                List.of(model.operations().get(0))
        );
        invoke(
                tab,
                "onSelectionAction",
                new Class<?>[]{OpenApiParserTable.SelectionAction.class, List.class},
                OpenApiParserTable.SelectionAction.SEND_SELECTED_TO_ACTIVE_SCAN,
                List.of(model.operations().get(0))
        );

        assertTrue(activeLatch.await(2, TimeUnit.SECONDS));
        assertTrue(passiveLatch.await(2, TimeUnit.SECONDS));
        verify(ctx.scanner, times(2)).startAudit(any());
        verify(activeAudit, times(2)).addRequest(any(HttpRequest.class));
        verify(passiveAudit, times(1)).addRequest(any(HttpRequest.class));
    }

    @Test
    void topBulkButtonsAreRemovedFromTabFields()
    {
        assertThrows(NoSuchFieldException.class, () -> OpenApiParserTab.class.getDeclaredField("generateAllButton"));
        assertThrows(NoSuchFieldException.class, () -> OpenApiParserTab.class.getDeclaredField("deleteSelectedButton"));
        assertThrows(NoSuchFieldException.class, () -> OpenApiParserTab.class.getDeclaredField("repeaterSelectedButton"));
        assertThrows(NoSuchFieldException.class, () -> OpenApiParserTab.class.getDeclaredField("intruderSelectedButton"));
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

    private Object recordValue(Object record, String accessor) throws Exception
    {
        Method accessorMethod = record.getClass().getDeclaredMethod(accessor);
        accessorMethod.setAccessible(true);
        return accessorMethod.invoke(record);
    }

    private boolean waitForStatus(OpenApiParserTab tab, Predicate<String> predicate) throws Exception
    {
        long deadline = System.currentTimeMillis() + 2_000L;
        JLabelHolder labelHolder = new JLabelHolder((javax.swing.JLabel) field(tab, "statusLabel"));
        while (System.currentTimeMillis() < deadline)
        {
            String text = labelHolder.text();
            if (predicate.test(text))
            {
                return true;
            }
            Thread.sleep(20L);
        }
        return predicate.test(labelHolder.text());
    }

    private static final class JLabelHolder
    {
        private final javax.swing.JLabel label;

        private JLabelHolder(javax.swing.JLabel label)
        {
            this.label = label;
        }

        private String text()
        {
            String text = label.getText();
            return text == null ? "" : text;
        }
    }
}
