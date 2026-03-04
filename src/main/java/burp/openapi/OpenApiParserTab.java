package burp.openapi;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Range;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.scanner.AuditConfiguration;
import burp.api.montoya.scanner.BuiltInAuditConfiguration;
import burp.api.montoya.scanner.audit.Audit;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main UI tab and behavior controller.
 */
public final class OpenApiParserTab implements ContextMenuItemsProvider
{
    private static final String TAB_TITLE = "OpenAPI Parser";
    private static final String EXTENSION_PREFIX = "[OpenAPI Parser] ";
    private static final String DEFAULT_SERVER_ITEM = "(Operation default)";

    private final MontoyaApi api;
    private final JPanel rootPanel;
    private final OpenApiParserModel model;
    private final OpenApiParserTable table;
    private final RequestGenerator requestGenerator;
    private final ExecutorService workerPool;

    private final JButton loadFileButton;
    private final JButton fetchButton;
    private final JButton generateAllButton;
    private final JButton deleteSelectedButton;
    private final JButton repeaterSelectedButton;
    private final JButton intruderSelectedButton;
    private final JTextField urlField;
    private final JTextField filterField;
    private final JComboBox<String> serverSelector;
    private final JLabel statusLabel;
    private final JLabel scannerStatusLabel;
    private final JLabel requestPreviewLabel;
    private final HttpRequestEditor requestPreviewEditor;
    private final Set<String> autoIncludedHosts = ConcurrentHashMap.newKeySet();

    private volatile boolean loadingInProgress;
    private volatile OpenApiParserModel.OperationContext previewOperationContext;

    public OpenApiParserTab(MontoyaApi api)
    {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.model = new OpenApiParserModel();
        this.table = new OpenApiParserTable();
        this.requestGenerator = new RequestGenerator();
        this.workerPool = Executors.newFixedThreadPool(2, runnable -> {
            Thread worker = new Thread(runnable, "openapi-parser-worker");
            worker.setDaemon(true);
            return worker;
        });

        this.rootPanel = new JPanel(new BorderLayout(8, 8));
        this.rootPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        this.loadFileButton = new JButton("Load from file");
        this.urlField = new JTextField(48);
        this.fetchButton = new JButton("Fetch");

        this.serverSelector = new JComboBox<>();
        this.serverSelector.addItem(DEFAULT_SERVER_ITEM);

        this.filterField = new JTextField(32);
        this.generateAllButton = new JButton("Generate all requests");
        this.deleteSelectedButton = new JButton("Delete selected");
        this.repeaterSelectedButton = new JButton("Selected -> Repeater");
        this.intruderSelectedButton = new JButton("Selected -> Intruder");

        this.statusLabel = new JLabel("Load an OpenAPI specification to begin.");
        this.scannerStatusLabel = new JLabel("Scanner tasks: idle");
        this.requestPreviewLabel = new JLabel("Request preview: select an operation.");
        this.requestPreviewEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY);
        this.requestPreviewEditor.setRequest(previewPlaceholderRequest());

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        JPanel loadRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        loadRow.add(loadFileButton);
        loadRow.add(new JLabel("Load from URL:"));
        loadRow.add(urlField);
        loadRow.add(fetchButton);

        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        optionsRow.add(new JLabel("Server:"));
        optionsRow.add(serverSelector);
        optionsRow.add(new JLabel("Filter:"));
        optionsRow.add(filterField);
        optionsRow.add(generateAllButton);

        JPanel bulkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bulkRow.add(deleteSelectedButton);
        bulkRow.add(repeaterSelectedButton);
        bulkRow.add(intruderSelectedButton);

        controls.add(loadRow);
        controls.add(optionsRow);
        controls.add(bulkRow);

        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
        statusPanel.add(statusLabel);

        JPanel previewPanel = new JPanel(new BorderLayout(6, 6));
        previewPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        previewPanel.add(requestPreviewLabel, BorderLayout.NORTH);
        previewPanel.add(requestPreviewEditor.uiComponent(), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, table, previewPanel);
        splitPane.setResizeWeight(0.68d);
        splitPane.setContinuousLayout(true);

        this.rootPanel.add(controls, BorderLayout.NORTH);
        this.rootPanel.add(splitPane, BorderLayout.CENTER);
        this.rootPanel.add(statusPanel, BorderLayout.SOUTH);

        table.setRowActionListener(this::onRowAction);
        table.setSelectionActionListener(this::onSelectionAction);
        table.setSelectionChangedListener(this::onTableSelectionChanged);

        loadFileButton.addActionListener(e -> onLoadFromFile());
        fetchButton.addActionListener(e -> fetchAndLoadFromUrl(urlField.getText()));
        generateAllButton.addActionListener(e -> onGenerateAllRequests());
        deleteSelectedButton.addActionListener(e -> onDeleteSelected());
        repeaterSelectedButton.addActionListener(e -> onSendSelectedToRepeater());
        intruderSelectedButton.addActionListener(e -> onSendSelectedToIntruder());
        serverSelector.addActionListener(e -> applyFilter());

        filterField.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                applyFilter();
            }
        });
    }

    public String tabTitle()
    {
        return TAB_TITLE;
    }

    public Component uiComponent()
    {
        return rootPanel;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event)
    {
        if (event == null)
        {
            return Collections.emptyList();
        }

        if (!event.isFromTool(ToolType.TARGET))
        {
            return Collections.emptyList();
        }

        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected == null || selected.isEmpty())
        {
            return Collections.emptyList();
        }

        HttpRequestResponse first = selected.get(0);
        String requestUrl = extractRequestUrl(first);
        String responseBody = extractResponseBody(first);

        boolean hasOpenApiUrl = Utils.looksLikeOpenApiUrl(requestUrl);
        boolean hasOpenApiBody = Utils.looksLikeOpenApiSpec(responseBody);

        if (!hasOpenApiUrl && !hasOpenApiBody)
        {
            return Collections.emptyList();
        }

        JMenuItem menuItem = new JMenuItem("Send to OpenAPI Parser");
        menuItem.addActionListener(e -> {
            if (hasOpenApiBody)
            {
                parseAndDisplaySpecAsync(responseBody, "context response", requestUrl, false, "Parsing context response...");
                return;
            }

            if (hasOpenApiUrl)
            {
                urlField.setText(requestUrl);
                fetchAndLoadFromUrl(requestUrl);
            }
        });

        return List.of(menuItem);
    }

    private void onLoadFromFile()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select OpenAPI / Swagger file");
        chooser.setFileFilter(new FileNameExtensionFilter("OpenAPI files (*.json, *.yaml, *.yml)", "json", "yaml", "yml"));

        int result = chooser.showOpenDialog(rootPanel);
        if (result != JFileChooser.APPROVE_OPTION)
        {
            return;
        }

        Path path = chooser.getSelectedFile().toPath();

        runBackgroundLoad("Parsing OpenAPI file...", () -> {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parseSpec(content, path.getFileName().toString(), path.toUri().toString(), true);
        }, "File load");
    }

    private void fetchAndLoadFromUrl(String url)
    {
        if (Utils.isBlank(url))
        {
            showError("Validation error", "Please enter a URL.");
            return;
        }

        final String normalizedUrl = url.trim();
        runBackgroundLoad("Fetching OpenAPI from URL...", () -> {
            URI.create(normalizedUrl);

            HttpRequest specRequest = HttpRequest.httpRequestFromUrl(normalizedUrl)
                    .withMethod("GET")
                    .withAddedHeader("Accept", "application/json, application/yaml, text/yaml, */*");

            RequestOptions requestOptions = RequestOptions.requestOptions()
                    .withResponseTimeout(25_000)
                    .withRedirectionMode(RedirectionMode.ALWAYS);

            HttpRequestResponse response = api.http().sendRequest(specRequest, requestOptions);
            if (!response.hasResponse() || response.response() == null)
            {
                throw new IOException("No response received from " + normalizedUrl);
            }

            short statusCode = response.response().statusCode();
            if (statusCode < 200 || statusCode > 299)
            {
                throw new IOException("HTTP " + statusCode + " returned from " + normalizedUrl);
            }

            return parseSpec(response.response().bodyToString(), normalizedUrl, normalizedUrl, true);
        }, "Fetch");
    }

    private void parseAndDisplaySpecAsync(
            String rawSpec,
            String sourceLabel,
            String sourceLocation,
            boolean preferLocationParsing,
            String statusMessage)
    {
        if (Utils.isBlank(rawSpec) && !preferLocationParsing)
        {
            showError("Parse error", "Specification content is empty.");
            return;
        }

        runBackgroundLoad(statusMessage, () -> parseSpec(rawSpec, sourceLabel, sourceLocation, preferLocationParsing), "Parse");
    }

    private ParseOutcome parseSpec(String rawSpec, String sourceLabel, String sourceLocation, boolean preferLocationParsing) throws Exception
    {
        log("Parsing spec from " + sourceLabel);

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setFlatten(false);

        OpenAPIV3Parser parser = new OpenAPIV3Parser();
        SwaggerParseResult parseResult = null;

        // Parse content first to avoid a second network pull when URL content is already fetched.
        if (Utils.nonBlank(rawSpec))
        {
            parseResult = parser.readContents(rawSpec, new ArrayList<>(), options);
        }

        // Fallback to location parsing (supports relative external refs).
        if ((parseResult == null || parseResult.getOpenAPI() == null)
                && preferLocationParsing
                && Utils.nonBlank(sourceLocation)
                && !Utils.looksLikeHttpUrl(sourceLocation))
        {
            parseResult = parser.readLocation(sourceLocation, new ArrayList<>(), options);
        }

        if (parseResult == null || parseResult.getOpenAPI() == null)
        {
            String message = formatParseErrors(parseResult);
            throw new IllegalStateException(message);
        }

        return new ParseOutcome(parseResult.getOpenAPI(), parseResult, sourceLabel, sourceLocation);
    }

    private void runBackgroundLoad(String statusMessage, CheckedSupplier<ParseOutcome> task, String actionLabel)
    {
        if (loadingInProgress)
        {
            statusLabel.setText("OpenAPI loading is already in progress...");
            return;
        }

        loadingInProgress = true;
        setLoadingState(true, statusMessage);

        CompletableFuture
                .supplyAsync(() -> {
                    try
                    {
                        return task.get();
                    }
                    catch (Exception ex)
                    {
                        throw new CompletionException(ex);
                    }
                }, workerPool)
                .whenComplete((outcome, throwable) -> SwingUtilities.invokeLater(() -> {
                    loadingInProgress = false;
                    setLoadingState(false, null);

                    if (throwable != null)
                    {
                        Throwable root = unwrap(throwable);
                        logError(actionLabel + " failed: " + root.getMessage(), toException(root));
                        showError(actionLabel + " error", "Unable to complete operation:\n" + root.getMessage());
                        return;
                    }

                    applyParseOutcome(outcome);
                }));
    }

    private void applyParseOutcome(ParseOutcome outcome)
    {
        int previousCount = model.operations().size();
        model.load(outcome.openAPI(), outcome.sourceLocation());
        refreshServerSelector();
        applyFilter();
        autoIncludeSpecHostInScope(outcome.sourceLocation());

        int totalCount = model.operations().size();
        int addedCount = Math.max(0, totalCount - previousCount);
        statusLabel.setText("Loaded from " + outcome.sourceLabel()
                + ": +" + addedCount + " operation(s), total " + totalCount + ".");

        if (outcome.parseResult().getMessages() != null && !outcome.parseResult().getMessages().isEmpty())
        {
            for (String warning : outcome.parseResult().getMessages())
            {
                log("Parser warning: " + warning);
            }
        }

        log("Loaded operations: +" + addedCount + ", total=" + totalCount);
    }

    private void autoIncludeSpecHostInScope(String sourceLocation)
    {
        if (!Utils.looksLikeHttpUrl(sourceLocation))
        {
            return;
        }

        String scopeTarget = "";
        try
        {
            URI uri = URI.create(sourceLocation);
            if (Utils.nonBlank(uri.getScheme()) && Utils.nonBlank(uri.getAuthority()))
            {
                scopeTarget = uri.getScheme() + "://" + uri.getAuthority() + "/";
            }
        }
        catch (Exception ex)
        {
            logError("Unable to parse source location for scope include: " + ex.getMessage(), ex);
            return;
        }

        if (Utils.isBlank(scopeTarget))
        {
            return;
        }

        if (isUrlInScope(scopeTarget))
        {
            autoIncludedHosts.add(scopeTarget);
            return;
        }

        try
        {
            includeInScopeOnEdt(scopeTarget);
            autoIncludedHosts.add(scopeTarget);
            log("Auto-included spec host in scope: " + scopeTarget);
        }
        catch (Exception ex)
        {
            autoIncludedHosts.remove(scopeTarget);
            logError("Failed to auto-include spec host in scope: " + ex.getMessage(), ex);
        }
    }

    private void setLoadingState(boolean loading, String statusText)
    {
        loadFileButton.setEnabled(!loading);
        fetchButton.setEnabled(!loading);
        generateAllButton.setEnabled(!loading);
        deleteSelectedButton.setEnabled(!loading);
        repeaterSelectedButton.setEnabled(!loading);
        intruderSelectedButton.setEnabled(!loading);

        if (loading && Utils.nonBlank(statusText))
        {
            statusLabel.setText(statusText);
        }
    }

    private Throwable unwrap(Throwable throwable)
    {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null)
        {
            current = current.getCause();
        }
        return current;
    }

    private Exception toException(Throwable throwable)
    {
        if (throwable instanceof Exception ex)
        {
            return ex;
        }
        return new Exception(throwable);
    }

    private String formatParseErrors(SwaggerParseResult parseResult)
    {
        if (parseResult == null || parseResult.getMessages() == null || parseResult.getMessages().isEmpty())
        {
            return "OpenAPI parser returned no model and no details.";
        }

        return String.join("\n", parseResult.getMessages());
    }

    private void refreshServerSelector()
    {
        String previous = (String) serverSelector.getSelectedItem();

        serverSelector.removeAllItems();
        serverSelector.addItem(DEFAULT_SERVER_ITEM);

        for (String server : model.availableServers())
        {
            serverSelector.addItem(server);
        }

        if (previous != null)
        {
            for (int i = 0; i < serverSelector.getItemCount(); i++)
            {
                if (previous.equals(serverSelector.getItemAt(i)))
                {
                    serverSelector.setSelectedIndex(i);
                    return;
                }
            }
        }

        serverSelector.setSelectedIndex(0);
    }

    private void applyFilter()
    {
        List<OpenApiParserModel.OperationContext> filtered = model.filter(filterField.getText(), selectedServer());
        table.setOperations(filtered);

        statusLabel.setText("Showing " + filtered.size() + " / " + model.operations().size() + " operation(s)");
        refreshRequestPreviewFromSelection();
    }

    private void onTableSelectionChanged(OpenApiParserModel.OperationContext operationContext)
    {
        if (operationContext == null)
        {
            setPreviewPlaceholder("Request preview: select an operation.");
            return;
        }

        updateRequestPreview(operationContext);
    }

    private void refreshRequestPreviewFromSelection()
    {
        OpenApiParserModel.OperationContext selectedOperation = table.firstSelectedOperation();
        if (selectedOperation == null)
        {
            setPreviewPlaceholder("Request preview: select an operation.");
            return;
        }
        updateRequestPreview(selectedOperation);
    }

    private void updateRequestPreview(OpenApiParserModel.OperationContext operationContext)
    {
        try
        {
            HttpRequest request = requestGenerator.generate(operationContext, selectedServer(), model.availableServers());
            requestPreviewEditor.setRequest(request);
            requestPreviewLabel.setText("Request preview: " + operationContext.method() + " " + operationContext.path());
            previewOperationContext = operationContext;
        }
        catch (Exception ex)
        {
            logError("Unable to build preview request for " + operationContext.method() + " " + operationContext.path() + ": " + ex.getMessage(), ex);
            setPreviewPlaceholder("Request preview: generation failed (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private void setPreviewPlaceholder(String label)
    {
        requestPreviewLabel.setText(label);
        requestPreviewEditor.setRequest(previewPlaceholderRequest());
        previewOperationContext = null;
    }

    private HttpRequest previewPlaceholderRequest()
    {
        return HttpRequest.httpRequest("GET / HTTP/1.1\r\nHost: preview.local\r\n\r\n");
    }

    private void onRowAction(OpenApiParserTable.RowAction action, OpenApiParserModel.OperationContext operationContext)
    {
        try
        {
            HttpRequest request = requestGenerator.generate(operationContext, selectedServer(), model.availableServers());

            switch (action)
            {
                case COPY_AS_CURL -> copyAsCurl(request);
                case COPY_AS_PYTHON -> copyAsPython(request);
                default -> {
                }
            }
        }
        catch (Exception ex)
        {
            logError("Action failed for " + operationContext.method() + " " + operationContext.path() + ": " + ex.getMessage(), ex);
            showError("Action error", ex.getMessage());
        }
    }

    private void copyAsCurl(HttpRequest request)
    {
        String curl = Utils.toCurl(request);
        Utils.copyToClipboard(curl);
        log("Copied as curl");
        statusLabel.setText("curl command copied to clipboard.");
    }

    private void copyAsPython(HttpRequest request)
    {
        String python = Utils.toPythonRequests(request);
        Utils.copyToClipboard(python);
        log("Copied as Python requests");
        statusLabel.setText("Python requests snippet copied to clipboard.");
    }

    private void onGenerateAllRequests()
    {
        List<OpenApiParserModel.OperationContext> operations = table.visibleOperations();
        if (operations.isEmpty())
        {
            showError("No operations", "There are no operations to generate.");
            return;
        }

        int sent = 0;
        int failed = 0;

        for (OpenApiParserModel.OperationContext operation : operations)
        {
            try
            {
                HttpRequest request = requestGenerator.generate(operation, selectedServer(), model.availableServers());
                String tabName = "OpenAPI Parser / All / " + operation.method() + " " + operation.path();
                api.repeater().sendToRepeater(request, tabName);
                sent++;
            }
            catch (Exception ex)
            {
                failed++;
                logError("Failed to generate request for " + operation.method() + " " + operation.path() + ": " + ex.getMessage(), ex);
            }
        }

        statusLabel.setText("Generated " + sent + " request(s), failed: " + failed);
        log("Generate all requests completed. Sent=" + sent + ", Failed=" + failed);
    }

    private void onDeleteSelected()
    {
        List<OpenApiParserModel.OperationContext> selected = selectedOperationsOrWarn("Delete");
        if (selected.isEmpty())
        {
            return;
        }

        deleteSelectedOperations(selected, true);
    }

    private void onSendSelectedToRepeater()
    {
        List<OpenApiParserModel.OperationContext> selected = selectedOperationsOrWarn("Send to Repeater");
        if (selected.isEmpty())
        {
            return;
        }
        sendSelectedToRepeater(selected);
    }

    private void onSendSelectedToIntruder()
    {
        List<OpenApiParserModel.OperationContext> selected = selectedOperationsOrWarn("Send to Intruder");
        if (selected.isEmpty())
        {
            return;
        }
        sendSelectedToIntruder(selected);
    }

    private void onSelectionAction(OpenApiParserTable.SelectionAction action, List<OpenApiParserModel.OperationContext> selected)
    {
        if (action == null)
        {
            return;
        }

        switch (action)
        {
            case SEND_SELECTED_TO_REPEATER -> {
                if (!ensureSelectionForPopup(selected, "Send selected to Repeater"))
                {
                    return;
                }
                sendSelectedToRepeater(selected);
            }
            case SEND_SELECTED_TO_INTRUDER -> {
                if (!ensureSelectionForPopup(selected, "Send selected to Intruder"))
                {
                    return;
                }
                sendSelectedToIntruder(selected);
            }
            case COPY_SELECTED_AS_CURL -> {
                if (!ensureSelectionForPopup(selected, "Copy selected as cURL"))
                {
                    return;
                }
                copySelectedAsCurl(selected);
            }
            case COPY_SELECTED_AS_PYTHON -> {
                if (!ensureSelectionForPopup(selected, "Copy selected as Python-Requests"))
                {
                    return;
                }
                copySelectedAsPython(selected);
            }
            case DELETE_SELECTED -> {
                if (!ensureSelectionForPopup(selected, "Delete selected rows"))
                {
                    return;
                }
                deleteSelectedOperations(selected, true);
            }
            case SELECT_ALL -> table.selectAllRows();
            case CLEAR_SELECTION -> table.clearSelection();
            default -> {
            }
        }
    }

    private boolean ensureSelectionForPopup(List<OpenApiParserModel.OperationContext> selected, String action)
    {
        if (selected == null || selected.isEmpty())
        {
            showError("No selection", "Select one or more rows first for: " + action);
            return false;
        }
        return true;
    }

    private List<OpenApiParserModel.OperationContext> selectedOperationsOrWarn(String action)
    {
        List<OpenApiParserModel.OperationContext> selected = table.selectedOperations();
        if (selected.isEmpty())
        {
            showError("No selection", "Select one or more rows first for: " + action);
            return List.of();
        }
        return selected;
    }

    private void deleteSelectedOperations(List<OpenApiParserModel.OperationContext> selected, boolean confirm)
    {
        if (confirm)
        {
            int answer = JOptionPane.showConfirmDialog(
                    rootPanel,
                    "Delete " + selected.size() + " selected operation(s) from table?",
                    "Confirm delete",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.YES_OPTION)
            {
                return;
            }
        }

        int removed = model.removeOperations(selected);
        table.clearSelection();
        applyFilter();
        statusLabel.setText("Removed " + removed + " operation(s).");
        log("Removed selected operations: " + removed);
    }

    private void sendSelectedToRepeater(List<OpenApiParserModel.OperationContext> selected)
    {
        int sent = 0;
        int failed = 0;
        for (OpenApiParserModel.OperationContext operation : selected)
        {
            try
            {
                HttpRequest request = requestGenerator.generate(operation, selectedServer(), model.availableServers());
                String tabName = "OpenAPI Parser / Selected / " + operation.method() + " " + operation.path();
                api.repeater().sendToRepeater(request, tabName);
                sent++;
            }
            catch (Exception ex)
            {
                failed++;
                logError("Selected -> Repeater failed for " + operation.method() + " " + operation.path() + ": " + ex.getMessage(), ex);
            }
        }

        statusLabel.setText("Selected -> Repeater: sent=" + sent + ", failed=" + failed);
        log("Selected requests sent to Repeater. Sent=" + sent + ", Failed=" + failed);
    }

    private void sendSelectedToIntruder(List<OpenApiParserModel.OperationContext> selected)
    {
        int sent = 0;
        int failed = 0;
        for (OpenApiParserModel.OperationContext operation : selected)
        {
            try
            {
                HttpRequest request = requestGenerator.generate(operation, selectedServer(), model.availableServers());
                api.intruder().sendToIntruder(request);
                sent++;
            }
            catch (Exception ex)
            {
                failed++;
                logError("Selected -> Intruder failed for " + operation.method() + " " + operation.path() + ": " + ex.getMessage(), ex);
            }
        }

        statusLabel.setText("Selected -> Intruder: sent=" + sent + ", failed=" + failed);
        log("Selected requests sent to Intruder. Sent=" + sent + ", Failed=" + failed);
    }

    private void sendSelectedToScanner(
            BuiltInAuditConfiguration auditConfiguration,
            String label,
            List<OpenApiParserModel.OperationContext> selected)
    {
        boolean passiveMode = auditConfiguration == BuiltInAuditConfiguration.LEGACY_PASSIVE_AUDIT_CHECKS;
        String serverSelection = selectedServer();
        List<String> globalServers = model.availableServers();
        List<OpenApiParserModel.OperationContext> immutableSelection = selected.stream()
                .filter(Objects::nonNull)
                .toList();

        if (immutableSelection.isEmpty())
        {
            showError("Scanner queue error", "No valid operation rows selected.");
            return;
        }

        String modeTitle = passiveMode ? "Passive scan" : "Active scan";

        statusLabel.setText(modeTitle + ": queueing " + immutableSelection.size() + " operation(s)...");
        scannerStatusLabel.setText(modeTitle + " task: queueing...");

        HttpRequest previewRequestOverride = capturePreviewRequestForSelection(immutableSelection);
        CompletableFuture
                .supplyAsync(() -> {
                    try
                    {
                        return queueSelectedForScanner(
                                auditConfiguration,
                                label,
                                immutableSelection,
                                serverSelection,
                                globalServers,
                                passiveMode,
                                previewRequestOverride
                        );
                    }
                    catch (Exception ex)
                    {
                        throw new CompletionException(ex);
                    }
                }, workerPool)
                .whenComplete((result, throwable) -> SwingUtilities.invokeLater(() -> {
                    if (throwable != null)
                    {
                        Throwable root = unwrap(throwable);
                        Exception error = toException(root);
                        scannerStatusLabel.setText(modeTitle + " task: queue failed");
                        logError(label + " queue failed: " + root.getMessage(), error);
                        showError("Scanner queue error", "Unable to queue selected requests.\n" + root.getMessage());
                        return;
                    }

                    String outOfScopeSuffix = result.outOfScope() > 0
                            ? ", out-of-scope=" + result.outOfScope()
                            : "";
                    String autoIncludedSuffix = result.autoIncluded() > 0
                            ? ", auto-included=" + result.autoIncluded()
                            : "";

                    statusLabel.setText(modeTitle + ": queued=" + result.queued() + ", failed=" + result.failed() + outOfScopeSuffix + autoIncludedSuffix);
                    scannerStatusLabel.setText(modeTitle + " task updated: +" + result.queued()
                            + " request(s), failed=" + result.failed() + outOfScopeSuffix + autoIncludedSuffix);

                    log(label + " queued for selected operations. Queued=" + result.queued()
                            + ", Failed=" + result.failed()
                            + ", OutOfScope=" + result.outOfScope()
                            + ", AutoIncluded=" + result.autoIncluded());
                    log(label + " task metrics: status=\"" + result.auditStatus() + "\""
                            + ", requests=" + result.auditRequests()
                            + ", errors=" + result.auditErrors()
                            + ", insertionPoints=" + result.auditInsertionPoints());

                    if (result.outOfScope() > 0)
                    {
                        log(label + " warning: " + result.outOfScope()
                                + " request(s) are out of scope. If Burp scanner is configured to scan in-scope items only, no new audit items will appear.");
                    }
                    if (result.queued() == 0)
                    {
                        showError("Scanner queue is empty", label + " did not queue any request.\nCheck Event Log for generation/fetch errors.");
                    }
                }));
    }

    private HttpRequest capturePreviewRequestForSelection(List<OpenApiParserModel.OperationContext> selected)
    {
        if (selected == null || selected.size() != 1)
        {
            return null;
        }

        OpenApiParserModel.OperationContext selectedOperation = selected.get(0);
        if (selectedOperation == null || previewOperationContext == null || !selectedOperation.equals(previewOperationContext))
        {
            return null;
        }

        try
        {
            HttpRequest previewRequest = requestPreviewEditor.getRequest();
            if (previewRequest != null && Utils.nonBlank(previewRequest.url()))
            {
                return previewRequest;
            }
        }
        catch (Exception ex)
        {
            logError("Unable to read preview request for scanner fallback: " + ex.getMessage(), ex);
        }
        return null;
    }

    private Audit startAuditOnEdt(BuiltInAuditConfiguration auditConfiguration) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            return api.scanner().startAudit(AuditConfiguration.auditConfiguration(auditConfiguration));
        }

        AtomicReference<Audit> created = new AtomicReference<>();
        AtomicReference<Exception> callError = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try
            {
                created.set(api.scanner().startAudit(AuditConfiguration.auditConfiguration(auditConfiguration)));
            }
            catch (Exception ex)
            {
                callError.set(ex);
            }
        });

        if (callError.get() != null)
        {
            throw callError.get();
        }
        return created.get();
    }

    private HttpRequestResponse fetchBaselineResponse(HttpRequest request) throws IOException
    {
        RequestOptions options = RequestOptions.requestOptions()
                .withResponseTimeout(30_000)
                .withRedirectionMode(RedirectionMode.ALWAYS);

        HttpRequestResponse response = api.http().sendRequest(request, options);
        if (response == null || !response.hasResponse())
        {
            throw new IOException("No response returned while preparing scanner request.");
        }
        return response;
    }

    private ScannerQueueResult queueSelectedForScanner(
            BuiltInAuditConfiguration auditConfiguration,
            String label,
            List<OpenApiParserModel.OperationContext> selected,
            String serverSelection,
            List<String> globalServers,
            boolean passiveMode,
            HttpRequest previewRequestOverride)
    {
        Audit workingAudit;
        try
        {
            workingAudit = startAuditOnEdt(auditConfiguration);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("Unable to start " + (passiveMode ? "passive" : "active")
                    + " audit task: " + ex.getMessage(), ex);
        }

        int queued = 0;
        int failed = 0;
        int outOfScope = 0;
        int autoIncluded = 0;

        for (int idx = 0; idx < selected.size(); idx++)
        {
            OpenApiParserModel.OperationContext operation = selected.get(idx);
            try
            {
                HttpRequest request;
                if (idx == 0 && previewRequestOverride != null)
                {
                    request = previewRequestOverride;
                }
                else
                {
                    request = requestGenerator.generate(operation, serverSelection, globalServers);
                }
                ScopeResult scopeResult = ensureRequestInScope(request, label, operation);
                if (scopeResult.autoIncluded())
                {
                    autoIncluded++;
                }
                if (!scopeResult.inScope())
                {
                    outOfScope++;
                }

                HttpRequestResponse requestResponse = null;
                try
                {
                    requestResponse = fetchBaselineResponse(request);
                    api.siteMap().add(requestResponse);
                }
                catch (Exception baselineEx)
                {
                    String modeName = passiveMode ? "passive" : "active";
                    logError("Baseline response unavailable for " + modeName + " scan "
                            + operation.method() + " " + operation.path()
                            + "; fallback to request-only queue. Reason: " + baselineEx.getMessage(), toException(baselineEx));
                }

                workingAudit = enqueueIntoAudit(
                        workingAudit,
                        passiveMode,
                        request,
                        requestResponse,
                        label
                );
                queued++;
            }
            catch (Exception ex)
            {
                failed++;
                logError(label + " failed for " + operation.method() + " " + operation.path() + ": " + ex.getMessage(), ex);
            }
        }

        return new ScannerQueueResult(
                queued,
                failed,
                outOfScope,
                autoIncluded,
                safeAuditStatusMessage(workingAudit),
                safeAuditRequestCount(workingAudit),
                safeAuditErrorCount(workingAudit),
                safeAuditInsertionPointCount(workingAudit)
        );
    }

    private String safeAuditStatusMessage(Audit audit)
    {
        if (audit == null)
        {
            return "n/a";
        }

        try
        {
            return Utils.coalesce(audit.statusMessage(), "n/a");
        }
        catch (Exception ex)
        {
            return "n/a";
        }
    }

    private int safeAuditRequestCount(Audit audit)
    {
        if (audit == null)
        {
            return -1;
        }

        try
        {
            return audit.requestCount();
        }
        catch (Exception ex)
        {
            return -1;
        }
    }

    private int safeAuditErrorCount(Audit audit)
    {
        if (audit == null)
        {
            return -1;
        }

        try
        {
            return audit.errorCount();
        }
        catch (Exception ex)
        {
            return -1;
        }
    }

    private int safeAuditInsertionPointCount(Audit audit)
    {
        if (audit == null)
        {
            return -1;
        }

        try
        {
            return audit.insertionPointCount();
        }
        catch (Exception ex)
        {
            return -1;
        }
    }

    private Audit enqueueIntoAudit(
            Audit audit,
            boolean passiveMode,
            HttpRequest request,
            HttpRequestResponse requestResponse,
            String label) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            return enqueueIntoAuditInternal(audit, passiveMode, request, requestResponse, label);
        }

        AtomicReference<Audit> result = new AtomicReference<>();
        AtomicReference<Exception> callError = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try
            {
                result.set(enqueueIntoAuditInternal(audit, passiveMode, request, requestResponse, label));
            }
            catch (Exception ex)
            {
                callError.set(ex);
            }
        });

        if (callError.get() != null)
        {
            throw callError.get();
        }
        return result.get();
    }

    private Audit enqueueIntoAuditInternal(
            Audit audit,
            boolean passiveMode,
            HttpRequest request,
            HttpRequestResponse requestResponse,
            String label) throws Exception
    {
        if (audit == null)
        {
            throw new IllegalStateException("Audit task is null.");
        }

        if (passiveMode)
        {
            if (requestResponse != null)
            {
                audit.addRequestResponse(requestResponse);
            }
            else
            {
                audit.addRequest(request);
            }
            return audit;
        }

        List<Range> insertionPoints = buildActiveInsertionPoints(request);
        if (!insertionPoints.isEmpty())
        {
            audit.addRequest(request, insertionPoints);
            return audit;
        }

        if (requestResponse != null)
        {
            audit.addRequestResponse(requestResponse);
        }
        else
        {
            audit.addRequest(request);
        }
        log(label + " warning: request has no parsed insertion points, queued with generic active mode.");
        return audit;
    }

    private List<Range> buildActiveInsertionPoints(HttpRequest request)
    {
        List<Range> ranges = new ArrayList<>();
        if (request == null)
        {
            return ranges;
        }

        try
        {
            for (ParsedHttpParameter parameter : request.parameters())
            {
                if (parameter == null || parameter.valueOffsets() == null)
                {
                    continue;
                }
                Range valueOffsets = parameter.valueOffsets();
                if (valueOffsets.endIndexExclusive() > valueOffsets.startIndexInclusive())
                {
                    ranges.add(valueOffsets);
                }
            }
        }
        catch (Exception ex)
        {
            logError("Unable to parse parameter insertion points: " + ex.getMessage(), ex);
        }

        if (!ranges.isEmpty())
        {
            return dedupeRanges(ranges);
        }

        Range bodyRange = firstBodyValueRange(request);
        if (bodyRange != null)
        {
            ranges.add(bodyRange);
            return ranges;
        }

        Range pathRange = firstPathSegmentRange(request);
        if (pathRange != null)
        {
            ranges.add(pathRange);
        }
        return ranges;
    }

    private List<Range> dedupeRanges(List<Range> ranges)
    {
        if (ranges == null || ranges.isEmpty())
        {
            return List.of();
        }

        List<Range> deduped = new ArrayList<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        for (Range range : ranges)
        {
            if (range == null)
            {
                continue;
            }
            int start = range.startIndexInclusive();
            int end = range.endIndexExclusive();
            if (end <= start)
            {
                continue;
            }

            String key = start + ":" + end;
            if (seen.add(key))
            {
                deduped.add(Range.range(start, end));
            }
        }
        return deduped;
    }

    private Range firstBodyValueRange(HttpRequest request)
    {
        try
        {
            String body = request.bodyToString();
            if (Utils.isBlank(body))
            {
                return null;
            }

            int localStart = firstAlphaNumericIndex(body);
            if (localStart < 0)
            {
                return null;
            }

            int localEnd = Math.min(body.length(), localStart + 16);
            return Range.range(request.bodyOffset() + localStart, request.bodyOffset() + localEnd);
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private Range firstPathSegmentRange(HttpRequest request)
    {
        try
        {
            String raw = request.toString();
            int firstSpace = raw.indexOf(' ');
            if (firstSpace < 0)
            {
                return null;
            }

            int secondSpace = raw.indexOf(' ', firstSpace + 1);
            if (secondSpace <= firstSpace + 1)
            {
                return null;
            }

            String pathPart = raw.substring(firstSpace + 1, secondSpace);
            int pathStart = 0;
            while (pathStart < pathPart.length() && (pathPart.charAt(pathStart) == '/' || pathPart.charAt(pathStart) == '?'))
            {
                pathStart++;
            }

            if (pathStart >= pathPart.length())
            {
                return null;
            }

            int pathEnd = pathStart;
            while (pathEnd < pathPart.length() && pathPart.charAt(pathEnd) != '/' && pathPart.charAt(pathEnd) != '?' && pathPart.charAt(pathEnd) != '&')
            {
                pathEnd++;
            }

            int start = firstSpace + 1 + pathStart;
            int end = firstSpace + 1 + pathEnd;
            if (end <= start)
            {
                return null;
            }
            return Range.range(start, end);
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private int firstAlphaNumericIndex(String value)
    {
        if (Utils.isBlank(value))
        {
            return -1;
        }

        for (int i = 0; i < value.length(); i++)
        {
            if (Character.isLetterOrDigit(value.charAt(i)))
            {
                return i;
            }
        }
        return -1;
    }

    private boolean isRequestInScope(HttpRequest request)
    {
        if (request == null || Utils.isBlank(request.url()))
        {
            return false;
        }

        return isUrlInScope(request.url());
    }

    private boolean isUrlInScope(String url)
    {
        try
        {
            if (Utils.isBlank(url))
            {
                return false;
            }
            return api.scope().isInScope(url);
        }
        catch (Exception ex)
        {
            logError("Failed to check scope state for scanner request: " + ex.getMessage(), ex);
            return false;
        }
    }

    private ScopeResult ensureRequestInScope(
            HttpRequest request,
            String label,
            OpenApiParserModel.OperationContext operation)
    {
        if (request == null || Utils.isBlank(request.url()))
        {
            return new ScopeResult(false, false);
        }

        try
        {
            String hostScopeUrl = toHostScopeUrl(request);
            boolean autoIncluded = false;
            if (Utils.nonBlank(hostScopeUrl) && !isUrlInScope(hostScopeUrl))
            {
                includeInScopeOnEdt(hostScopeUrl);
                autoIncludedHosts.add(hostScopeUrl);
                autoIncluded = true;
            }

            boolean nowInScope = isRequestInScope(request);
            if (!nowInScope)
            {
                // Fallback to exact URL if host-level include did not match this request.
                includeInScopeOnEdt(request.url());
                autoIncluded = true;
                nowInScope = isRequestInScope(request);
            }
            if (nowInScope)
            {
                if (autoIncluded)
                {
                    String scopeTarget = Utils.nonBlank(hostScopeUrl) ? hostScopeUrl : request.url();
                    log(label + " auto-included in scope: " + operation.method() + " " + operation.path() + " -> " + scopeTarget);
                }
                return new ScopeResult(true, autoIncluded);
            }
            if (Utils.nonBlank(hostScopeUrl))
            {
                autoIncludedHosts.remove(hostScopeUrl);
            }
            log(label + " scope check failed for " + operation.method() + " " + operation.path()
                    + " -> " + request.url());
            return new ScopeResult(false, autoIncluded);
        }
        catch (Exception ex)
        {
            logError("Failed to include request in scope before scanner queue: " + ex.getMessage(), ex);
            return new ScopeResult(false, false);
        }
    }

    private void includeInScopeOnEdt(String scopeTarget) throws Exception
    {
        if (Utils.isBlank(scopeTarget))
        {
            return;
        }
        if (SwingUtilities.isEventDispatchThread())
        {
            api.scope().includeInScope(scopeTarget);
            return;
        }

        AtomicReference<Exception> callError = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try
            {
                api.scope().includeInScope(scopeTarget);
            }
            catch (Exception ex)
            {
                callError.set(ex);
            }
        });
        if (callError.get() != null)
        {
            throw callError.get();
        }
    }

    private String toHostScopeUrl(HttpRequest request)
    {
        try
        {
            if (request != null && request.httpService() != null && Utils.nonBlank(request.httpService().host()))
            {
                String host = request.httpService().host();
                boolean secure = request.httpService().secure();
                int port = request.httpService().port();
                boolean defaultPort = (secure && port == 443) || (!secure && port == 80);

                StringBuilder authority = new StringBuilder(host);
                if (port > 0 && !defaultPort)
                {
                    authority.append(':').append(port);
                }
                return (secure ? "https://" : "http://") + authority + "/";
            }

            URI uri = URI.create(request != null ? request.url() : "");
            if (Utils.isBlank(uri.getScheme()) || Utils.isBlank(uri.getAuthority()))
            {
                return "";
            }
            return uri.getScheme() + "://" + uri.getAuthority() + "/";
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private void copySelectedAsCurl(List<OpenApiParserModel.OperationContext> selected)
    {
        StringBuilder all = new StringBuilder();
        int copied = 0;
        int failed = 0;

        for (OpenApiParserModel.OperationContext operation : selected)
        {
            try
            {
                HttpRequest request = requestGenerator.generate(operation, selectedServer(), model.availableServers());
                if (all.length() > 0)
                {
                    all.append("\n\n# ----------------------------------------\n\n");
                }
                all.append("# ").append(operation.method()).append(' ').append(operation.path()).append('\n');
                all.append(Utils.toCurl(request));
                copied++;
            }
            catch (Exception ex)
            {
                failed++;
                logError("Copy cURL failed for " + operation.method() + " " + operation.path() + ": " + ex.getMessage(), ex);
            }
        }

        if (copied > 0)
        {
            Utils.copyToClipboard(all.toString());
            statusLabel.setText("Copied cURL for " + copied + " selected operation(s), failed: " + failed);
            log("Copied selected as cURL. Copied=" + copied + ", Failed=" + failed);
        }
    }

    private void copySelectedAsPython(List<OpenApiParserModel.OperationContext> selected)
    {
        StringBuilder all = new StringBuilder();
        int copied = 0;
        int failed = 0;

        for (OpenApiParserModel.OperationContext operation : selected)
        {
            try
            {
                HttpRequest request = requestGenerator.generate(operation, selectedServer(), model.availableServers());
                if (all.length() > 0)
                {
                    all.append("\n\n# ========================================\n\n");
                }
                all.append("# ").append(operation.method()).append(' ').append(operation.path()).append('\n');
                all.append(Utils.toPythonRequests(request));
                copied++;
            }
            catch (Exception ex)
            {
                failed++;
                logError("Copy Python failed for " + operation.method() + " " + operation.path() + ": " + ex.getMessage(), ex);
            }
        }

        if (copied > 0)
        {
            Utils.copyToClipboard(all.toString());
            statusLabel.setText("Copied Python for " + copied + " selected operation(s), failed: " + failed);
            log("Copied selected as Python requests. Copied=" + copied + ", Failed=" + failed);
        }
    }

    private String selectedServer()
    {
        Object selected = serverSelector.getSelectedItem();
        return selected == null ? DEFAULT_SERVER_ITEM : String.valueOf(selected);
    }

    private String extractRequestUrl(HttpRequestResponse requestResponse)
    {
        try
        {
            return requestResponse != null && requestResponse.request() != null
                    ? requestResponse.request().url()
                    : "";
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private String extractResponseBody(HttpRequestResponse requestResponse)
    {
        try
        {
            return requestResponse != null && requestResponse.response() != null
                    ? requestResponse.response().bodyToString()
                    : "";
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private void showError(String title, String message)
    {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(rootPanel, message, title, JOptionPane.ERROR_MESSAGE));
    }

    private void log(String message)
    {
        api.logging().logToOutput(EXTENSION_PREFIX + message);
        api.logging().raiseInfoEvent(EXTENSION_PREFIX + message);
    }

    private void logError(String message, Exception ex)
    {
        api.logging().logToError(EXTENSION_PREFIX + message);
        api.logging().raiseErrorEvent(EXTENSION_PREFIX + message);
        if (ex != null)
        {
            api.logging().logToError(EXTENSION_PREFIX + "Exception type: " + ex.getClass().getSimpleName());
        }
    }

    private record ParseOutcome(OpenAPI openAPI, SwaggerParseResult parseResult, String sourceLabel, String sourceLocation)
    {
    }

    private record ScannerQueueResult(
            int queued,
            int failed,
            int outOfScope,
            int autoIncluded,
            String auditStatus,
            int auditRequests,
            int auditErrors,
            int auditInsertionPoints)
    {
    }

    private record ScopeResult(
            boolean inScope,
            boolean autoIncluded)
    {
    }

    @FunctionalInterface
    private interface CheckedSupplier<T>
    {
        T get() throws Exception;
    }
}
