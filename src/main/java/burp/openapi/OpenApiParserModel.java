package burp.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Domain model for currently loaded OpenAPI definition and flattened operations table.
 */
public final class OpenApiParserModel
{
    private OpenAPI openAPI;
    private String sourceLocation;
    private final List<OperationContext> operations = new ArrayList<>();
    private final List<String> availableServers = new ArrayList<>();

    public OpenAPI openAPI()
    {
        return openAPI;
    }

    public String sourceLocation()
    {
        return sourceLocation;
    }

    public List<OperationContext> operations()
    {
        return Collections.unmodifiableList(operations);
    }

    public List<String> availableServers()
    {
        return Collections.unmodifiableList(availableServers);
    }

    public int removeOperations(Collection<OperationContext> toRemove)
    {
        if (toRemove == null || toRemove.isEmpty())
        {
            return 0;
        }

        int before = operations.size();
        operations.removeIf(toRemove::contains);
        return before - operations.size();
    }

    public void clear()
    {
        openAPI = null;
        sourceLocation = null;
        operations.clear();
        availableServers.clear();
    }

    public void load(OpenAPI parsedOpenApi, String source)
    {
        Objects.requireNonNull(parsedOpenApi, "parsedOpenApi must not be null");

        this.openAPI = parsedOpenApi;
        this.sourceLocation = source;

        final Set<String> existingOperationKeys = new LinkedHashSet<>();
        for (OperationContext existing : operations)
        {
            if (existing == null)
            {
                continue;
            }
            existingOperationKeys.add(operationKey(existing));
        }

        final Set<String> existingServers = new LinkedHashSet<>(availableServers);

        final String sourceDefaultServer = defaultServerFromSource(source);
        final LinkedHashSet<String> globalServers = collectResolvedServers(parsedOpenApi.getServers(), source);
        if (globalServers.isEmpty())
        {
            globalServers.add(sourceDefaultServer);
        }
        for (String server : globalServers)
        {
            if (Utils.nonBlank(server) && existingServers.add(server))
            {
                availableServers.add(server);
            }
        }

        if (parsedOpenApi.getPaths() == null || parsedOpenApi.getPaths().isEmpty())
        {
            return;
        }

        for (Map.Entry<String, PathItem> pathEntry : parsedOpenApi.getPaths().entrySet())
        {
            final String path = pathEntry.getKey();
            final PathItem pathItem = pathEntry.getValue();
            if (pathItem == null)
            {
                continue;
            }

            final Map<HttpMethod, Operation> operationMap = pathItem.readOperationsMap();
            if (operationMap == null || operationMap.isEmpty())
            {
                continue;
            }

            for (Map.Entry<HttpMethod, Operation> operationEntry : operationMap.entrySet())
            {
                final HttpMethod httpMethod = operationEntry.getKey();
                final Operation operation = operationEntry.getValue();
                if (httpMethod == null || operation == null)
                {
                    continue;
                }

                final List<Parameter> mergedParameters = mergeParameters(pathItem, operation);
                final LinkedHashSet<String> operationServers = resolveServersForOperation(pathItem, operation, globalServers, source);

                if (operationServers.isEmpty())
                {
                    operationServers.addAll(globalServers);
                }
                if (operationServers.isEmpty())
                {
                    operationServers.add(sourceDefaultServer);
                }

                final String summary = Utils.coalesce(operation.getSummary(), operation.getDescription(), operation.getOperationId(), "-");
                final String operationId = Utils.coalesce(operation.getOperationId(), "-");
                final List<String> tags = operation.getTags() == null ? List.of() : List.copyOf(operation.getTags());

                OperationContext operationContext = new OperationContext(
                        httpMethod.name().toUpperCase(),
                        path,
                        summary,
                        operationId,
                        tags,
                        List.copyOf(operationServers),
                        mergedParameters,
                        operation.getRequestBody(),
                        operation
                );

                String key = operationKey(operationContext);
                if (!existingOperationKeys.add(key))
                {
                    continue;
                }

                operations.add(operationContext);
            }
        }

        operations.sort((a, b) -> {
            int byPath = a.path().compareToIgnoreCase(b.path());
            if (byPath != 0)
            {
                return byPath;
            }
            return a.method().compareToIgnoreCase(b.method());
        });
    }

    private String operationKey(OperationContext operationContext)
    {
        if (operationContext == null)
        {
            return "";
        }

        return operationContext.method() + "|"
                + operationContext.path() + "|"
                + operationContext.operationId() + "|"
                + operationContext.summary() + "|"
                + operationContext.serversAsString();
    }

    public List<OperationContext> filter(String query)
    {
        return filter(query, null);
    }

    public List<OperationContext> filter(String query, String selectedServer)
    {
        final String normalized = Utils.safeLower(query).trim();
        final boolean filterByServer = Utils.nonBlank(selectedServer) && !"(Operation default)".equals(selectedServer);

        if (normalized.isEmpty() && !filterByServer)
        {
            return List.copyOf(operations);
        }

        final List<OperationContext> filtered = new ArrayList<>();
        for (OperationContext operation : operations)
        {
            if (filterByServer && !operation.servers().contains(selectedServer))
            {
                continue;
            }
            if (normalized.isEmpty() || matches(operation, normalized))
            {
                filtered.add(operation);
            }
        }
        return filtered;
    }

    private boolean matches(OperationContext operation, String query)
    {
        if (query == null || query.isEmpty())
        {
            return true;
        }

        if (operation.method().toLowerCase().contains(query))
        {
            return true;
        }
        if (operation.path().toLowerCase().contains(query))
        {
            return true;
        }
        if (operation.summary().toLowerCase().contains(query))
        {
            return true;
        }
        if (operation.operationId().toLowerCase().contains(query))
        {
            return true;
        }
        if (Utils.joinTags(operation.tags()).toLowerCase().contains(query))
        {
            return true;
        }
        if (operation.parametersSummary().toLowerCase().contains(query))
        {
            return true;
        }
        if (operation.requestBodySummary().toLowerCase().contains(query))
        {
            return true;
        }

        return operation.servers().stream().anyMatch(server -> server.toLowerCase().contains(query));
    }

    private List<Parameter> mergeParameters(PathItem pathItem, Operation operation)
    {
        final LinkedHashMap<String, Parameter> merged = new LinkedHashMap<>();

        addParameters(merged, pathItem != null ? pathItem.getParameters() : null);
        addParameters(merged, operation.getParameters());

        return List.copyOf(merged.values());
    }

    private void addParameters(Map<String, Parameter> merged, List<Parameter> parameters)
    {
        if (parameters == null)
        {
            return;
        }

        for (Parameter parameter : parameters)
        {
            if (parameter == null)
            {
                continue;
            }
            final String key = Utils.coalesce(parameter.getIn(), "unknown") + ":" + Utils.coalesce(parameter.getName(), "param");
            merged.put(key, parameter);
        }
    }

    private LinkedHashSet<String> resolveServersForOperation(
            PathItem pathItem,
            Operation operation,
            LinkedHashSet<String> fallbackGlobal,
            String source)
    {
        if (operation.getServers() != null && !operation.getServers().isEmpty())
        {
            return collectResolvedServers(operation.getServers(), source);
        }

        if (pathItem != null && pathItem.getServers() != null && !pathItem.getServers().isEmpty())
        {
            return collectResolvedServers(pathItem.getServers(), source);
        }

        return new LinkedHashSet<>(fallbackGlobal);
    }

    private LinkedHashSet<String> collectResolvedServers(List<Server> servers, String source)
    {
        final LinkedHashSet<String> resolved = new LinkedHashSet<>();

        if (servers == null)
        {
            return resolved;
        }

        for (Server server : servers)
        {
            if (server == null || Utils.isBlank(server.getUrl()))
            {
                continue;
            }

            String url = server.getUrl();
            final Map<String, ServerVariable> variables = server.getVariables();
            if (variables != null)
            {
                for (Map.Entry<String, ServerVariable> entry : variables.entrySet())
                {
                    final ServerVariable variable = entry.getValue();
                    String value = variable != null ? variable.getDefault() : null;
                    if (Utils.isBlank(value) && variable != null)
                    {
                        final List<String> enumValues = variable.getEnum();
                        if (enumValues != null && !enumValues.isEmpty())
                        {
                            value = enumValues.get(0);
                        }
                    }
                    if (Utils.isBlank(value))
                    {
                        value = "default";
                    }
                    url = url.replace("{" + entry.getKey() + "}", value);
                }
            }

            url = toAbsoluteServer(url, source);
            resolved.add(url);
        }

        return resolved;
    }

    private String toAbsoluteServer(String serverUrl, String source)
    {
        if (Utils.isBlank(serverUrl))
        {
            return "http://localhost";
        }

        if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))
        {
            return Utils.stripTrailingSlash(serverUrl);
        }

        if (serverUrl.startsWith("//"))
        {
            return "https:" + Utils.stripTrailingSlash(serverUrl);
        }

        if (serverUrl.startsWith("/"))
        {
            if (Utils.looksLikeHttpUrl(source))
            {
                try
                {
                    URI base = new URI(source);
                    URI resolved = new URI(base.getScheme(), base.getAuthority(), serverUrl, null, null);
                    return Utils.stripTrailingSlash(resolved.toString());
                }
                catch (URISyntaxException ignored)
                {
                    // Fall through to localhost fallback below.
                }
            }
            return Utils.stripTrailingSlash("http://localhost" + serverUrl);
        }

        if (Utils.looksLikeHttpUrl(source))
        {
            try
            {
                URI base = new URI(source);
                URI resolved = base.resolve(serverUrl);
                return Utils.stripTrailingSlash(resolved.toString());
            }
            catch (URISyntaxException ignored)
            {
                // Fall through to hostname-less fallback below.
            }
        }

        return Utils.stripTrailingSlash("http://" + serverUrl);
    }

    private String defaultServerFromSource(String source)
    {
        if (Utils.looksLikeHttpUrl(source))
        {
            try
            {
                URI uri = new URI(source);
                if (Utils.nonBlank(uri.getScheme()) && Utils.nonBlank(uri.getAuthority()))
                {
                    return Utils.stripTrailingSlash(uri.getScheme() + "://" + uri.getAuthority());
                }
            }
            catch (URISyntaxException ignored)
            {
                // Fall through to localhost fallback.
            }
        }
        return "http://localhost";
    }

    public record OperationContext(
            String method,
            String path,
            String summary,
            String operationId,
            List<String> tags,
            List<String> servers,
            List<Parameter> parameters,
            RequestBody requestBody,
            Operation operation)
    {
        public String tagsAsString()
        {
            return Utils.joinTags(tags);
        }

        public String serversAsString()
        {
            return String.join(" | ", servers);
        }

        public String parametersSummary()
        {
            if (parameters == null || parameters.isEmpty())
            {
                return "-";
            }

            Map<String, List<String>> grouped = new LinkedHashMap<>();
            for (Parameter parameter : parameters)
            {
                if (parameter == null)
                {
                    continue;
                }

                String location = Utils.coalesce(parameter.getIn(), "query").toLowerCase();
                String name = Utils.coalesce(parameter.getName(), "param");

                List<String> names = grouped.computeIfAbsent(location, key -> new ArrayList<>());
                if (!names.contains(name))
                {
                    names.add(name);
                }
            }

            if (grouped.isEmpty())
            {
                return "-";
            }

            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : grouped.entrySet())
            {
                List<String> names = entry.getValue();
                int previewSize = Math.min(3, names.size());
                String preview = String.join(", ", names.subList(0, previewSize));
                if (names.size() > previewSize)
                {
                    preview = preview + ", +" + (names.size() - previewSize);
                }
                parts.add(entry.getKey() + "(" + names.size() + "): " + preview);
            }
            return String.join(" | ", parts);
        }

        public String requestBodySummary()
        {
            if (requestBody != null && requestBody.getContent() != null && !requestBody.getContent().isEmpty())
            {
                String summary = String.join(", ", requestBody.getContent().keySet());
                if (Boolean.TRUE.equals(requestBody.getRequired()))
                {
                    return summary + " (required)";
                }
                return summary;
            }

            if (parameters != null)
            {
                boolean legacyBody = parameters.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(parameter -> {
                            String location = Utils.coalesce(parameter.getIn(), "").toLowerCase();
                            return "body".equals(location) || "formdata".equals(location);
                        });
                if (legacyBody)
                {
                    return "legacy body/formData";
                }
            }

            return "-";
        }

        public String preferredServer(Collection<String> globalServers, String selectedServer)
        {
            if (Utils.nonBlank(selectedServer) && !"(Operation default)".equals(selectedServer))
            {
                return selectedServer;
            }
            if (!servers.isEmpty())
            {
                return servers.get(0);
            }
            if (globalServers != null)
            {
                Optional<String> first = globalServers.stream().filter(Utils::nonBlank).findFirst();
                if (first.isPresent())
                {
                    return first.get();
                }
            }
            return "http://localhost";
        }

        public Schema<?> requestSchema()
        {
            if (requestBody == null || requestBody.getContent() == null || requestBody.getContent().isEmpty())
            {
                return null;
            }
            return requestBody.getContent().values().iterator().next().getSchema();
        }
    }
}
