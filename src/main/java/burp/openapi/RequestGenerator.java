package burp.openapi;

import burp.api.montoya.http.message.requests.HttpRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Converts OpenAPI operations into concrete Burp HttpRequest objects with generated examples.
 */
public final class RequestGenerator
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_ARRAY_EXAMPLES = 3;

    public HttpRequest generate(
            OpenApiSamplerModel.OperationContext operationContext,
            String selectedServer,
            Collection<String> globalServers)
    {
        Objects.requireNonNull(operationContext, "operationContext must not be null");

        String server = Utils.stripTrailingSlash(operationContext.preferredServer(globalServers, selectedServer));
        if (Utils.isBlank(server))
        {
            server = "http://localhost";
        }

        final Map<String, String> pathParams = new LinkedHashMap<>();
        final Map<String, String> queryParams = new LinkedHashMap<>();
        final Map<String, String> headerParams = new LinkedHashMap<>();
        final Map<String, String> cookieParams = new LinkedHashMap<>();
        final Map<String, String> legacyFormParams = new LinkedHashMap<>();

        for (Parameter parameter : operationContext.parameters())
        {
            if (parameter == null || Utils.isBlank(parameter.getName()))
            {
                continue;
            }

            String in = Utils.coalesce(parameter.getIn(), "query").toLowerCase(Locale.ROOT);
            String value = parameterExampleValue(parameter);

            switch (in)
            {
                case "path" -> pathParams.put(parameter.getName(), value);
                case "query" -> queryParams.put(parameter.getName(), value);
                case "header" -> headerParams.put(parameter.getName(), value);
                case "cookie" -> cookieParams.put(parameter.getName(), value);
                case "formdata" -> legacyFormParams.put(parameter.getName(), value);
                default -> {
                    // Ignore unsupported/unknown parameter location.
                }
            }
        }

        String resolvedPath = fillPathParams(operationContext.path(), pathParams);

        String queryString = toQueryString(queryParams);
        String finalUrl = joinUrl(server, resolvedPath, queryString);

        BodyPayload bodyPayload = generateBody(operationContext.operation(), operationContext.requestBody(), legacyFormParams);

        HttpRequest request = HttpRequest.httpRequestFromUrl(finalUrl).withMethod(operationContext.method());

        for (Map.Entry<String, String> headerEntry : headerParams.entrySet())
        {
            request = request.withAddedHeader(headerEntry.getKey(), headerEntry.getValue());
        }

        if (!cookieParams.isEmpty())
        {
            request = request.withAddedHeader("Cookie", cookieHeader(cookieParams));
        }

        if (Utils.nonBlank(bodyPayload.contentType()))
        {
            request = request.withAddedHeader("Content-Type", bodyPayload.contentType());
        }

        if (Utils.nonBlank(bodyPayload.body()))
        {
            request = request.withBody(bodyPayload.body());
        }

        return request;
    }

    private String fillPathParams(String pathTemplate, Map<String, String> pathParams)
    {
        String path = Utils.coalesce(pathTemplate, "/");
        for (Map.Entry<String, String> entry : pathParams.entrySet())
        {
            String token = "{" + entry.getKey() + "}";
            path = path.replace(token, encodePathSegment(entry.getValue()));
        }

        // Replace any unresolved path variables with deterministic placeholders.
        return path.replaceAll("\\{([^{}]+)}", "sample-$1");
    }

    private String joinUrl(String server, String path, String query)
    {
        String cleanServer = Utils.nonBlank(server) ? server : "http://localhost";
        String normalizedPath = Utils.nonBlank(path) ? path : "/";

        StringBuilder url = new StringBuilder();
        url.append(cleanServer);

        if (!normalizedPath.startsWith("/"))
        {
            url.append('/');
        }
        url.append(normalizedPath);

        if (Utils.nonBlank(query))
        {
            url.append('?').append(query);
        }

        return url.toString();
    }

    private String cookieHeader(Map<String, String> cookieParams)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, String> entry : cookieParams.entrySet())
        {
            if (!first)
            {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }

        return sb.toString();
    }

    private String toQueryString(Map<String, String> queryParams)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, String> entry : queryParams.entrySet())
        {
            if (!first)
            {
                sb.append('&');
            }
            sb.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
            first = false;
        }

        return sb.toString();
    }

    private String parameterExampleValue(Parameter parameter)
    {
        if (parameter == null)
        {
            return "sample";
        }

        if (parameter.getExample() != null)
        {
            return normalizeExampleToString(parameter.getExample());
        }

        if (parameter.getExamples() != null && !parameter.getExamples().isEmpty())
        {
            Example first = parameter.getExamples().values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (first != null && first.getValue() != null)
            {
                return normalizeExampleToString(first.getValue());
            }
        }

        Schema<?> schema = parameter.getSchema();
        if (schema != null)
        {
            Object value = buildExampleFromSchema(schema, new LinkedHashSet<>());
            return normalizeExampleToString(value);
        }

        return "sample";
    }

    private BodyPayload generateBody(Operation operation, RequestBody requestBody, Map<String, String> legacyFormParams)
    {
        if (!legacyFormParams.isEmpty())
        {
            return new BodyPayload("application/x-www-form-urlencoded", toQueryString(legacyFormParams));
        }

        // Legacy OpenAPI 2.0 body parameter fallback.
        if (operation != null && operation.getParameters() != null)
        {
            for (Parameter parameter : operation.getParameters())
            {
                if (parameter == null)
                {
                    continue;
                }

                if ("body".equalsIgnoreCase(parameter.getIn()) && parameter.getSchema() != null)
                {
                    Object payload = buildExampleFromSchema(parameter.getSchema(), new LinkedHashSet<>());
                    return new BodyPayload("application/json", toJson(payload));
                }
            }
        }

        if (requestBody == null)
        {
            return BodyPayload.empty();
        }

        final Content content = requestBody.getContent();
        if (content == null || content.isEmpty())
        {
            return BodyPayload.empty();
        }

        MediaSelection selection = selectMediaType(content);
        if (selection == null)
        {
            return BodyPayload.empty();
        }

        MediaType media = selection.mediaType();
        String mediaType = selection.contentType();

        Object payloadObject = extractMediaTypeExample(media);
        if (payloadObject == null && media.getSchema() != null)
        {
            payloadObject = buildExampleFromSchema(media.getSchema(), new LinkedHashSet<>());
        }

        if (payloadObject == null)
        {
            payloadObject = Collections.emptyMap();
        }

        String lowerMediaType = mediaType.toLowerCase(Locale.ROOT);
        if (lowerMediaType.contains("application/json") || lowerMediaType.endsWith("+json"))
        {
            return new BodyPayload(mediaType, toJson(payloadObject));
        }

        if (lowerMediaType.contains("application/x-www-form-urlencoded"))
        {
            return new BodyPayload(mediaType, toFormUrlEncoded(payloadObject));
        }

        if (lowerMediaType.contains("multipart/form-data"))
        {
            String boundary = "----OpenApiSamplerBoundary";
            String multipartBody = toMultipart(payloadObject, boundary);
            return new BodyPayload(mediaType + "; boundary=" + boundary, multipartBody);
        }

        return new BodyPayload(mediaType, normalizeExampleToString(payloadObject));
    }

    private MediaSelection selectMediaType(Content content)
    {
        if (content == null || content.isEmpty())
        {
            return null;
        }

        List<String> preferred = List.of(
                "application/json",
                "application/x-www-form-urlencoded",
                "multipart/form-data",
                "text/plain"
        );

        for (String type : preferred)
        {
            if (content.containsKey(type))
            {
                return new MediaSelection(type, content.get(type));
            }
        }

        Map.Entry<String, MediaType> first = content.entrySet().iterator().next();
        return new MediaSelection(first.getKey(), first.getValue());
    }

    private Object extractMediaTypeExample(MediaType media)
    {
        if (media == null)
        {
            return null;
        }

        if (media.getExample() != null)
        {
            return media.getExample();
        }

        if (media.getExamples() != null && !media.getExamples().isEmpty())
        {
            for (Example example : media.getExamples().values())
            {
                if (example != null && example.getValue() != null)
                {
                    return example.getValue();
                }
            }
        }

        return null;
    }

    private Object buildExampleFromSchema(Schema<?> schema, Set<String> visitedRefs)
    {
        if (schema == null)
        {
            return "";
        }

        if (schema.get$ref() != null)
        {
            if (!visitedRefs.add(schema.get$ref()))
            {
                return "<recursive-ref>";
            }
        }

        if (schema.getExample() != null)
        {
            return schema.getExample();
        }

        if (schema.getDefault() != null)
        {
            return schema.getDefault();
        }

        if (schema.getEnum() != null && !schema.getEnum().isEmpty())
        {
            return schema.getEnum().get(0);
        }

        if (schema instanceof ComposedSchema composedSchema)
        {
            Object composed = buildComposedSchemaExample(composedSchema, visitedRefs);
            if (composed != null)
            {
                return composed;
            }
        }

        if (schema instanceof ArraySchema arraySchema)
        {
            Schema<?> items = arraySchema.getItems();
            int count = 1;
            if (arraySchema.getMinItems() != null && arraySchema.getMinItems() > 0)
            {
                count = Math.min(arraySchema.getMinItems(), MAX_ARRAY_EXAMPLES);
            }

            List<Object> sampleItems = new ArrayList<>();
            for (int i = 0; i < count; i++)
            {
                sampleItems.add(buildExampleFromSchema(items, visitedRefs));
            }
            return sampleItems;
        }

        if (schema.getProperties() != null && !schema.getProperties().isEmpty())
        {
            Map<String, Object> object = new LinkedHashMap<>();
            for (Map.Entry<String, Schema> propertyEntry : schema.getProperties().entrySet())
            {
                object.put(propertyEntry.getKey(), buildExampleFromSchema(propertyEntry.getValue(), visitedRefs));
            }
            return object;
        }

        String type = Optional.ofNullable(schema.getType()).orElse("").toLowerCase(Locale.ROOT);
        return switch (type)
        {
            case "string" -> "sample";
            case "integer" -> 1;
            case "number" -> 1.0;
            case "boolean" -> true;
            case "array" -> {
                Schema<?> items = schema.getItems();
                yield List.of(buildExampleFromSchema(items, visitedRefs));
            }
            case "object" -> new LinkedHashMap<>();
            default -> {
                if (schema.getAdditionalProperties() instanceof Schema<?> additionalSchema)
                {
                    yield Map.of("additionalProp1", buildExampleFromSchema(additionalSchema, visitedRefs));
                }
                yield "sample";
            }
        };
    }

    private Object buildComposedSchemaExample(ComposedSchema composedSchema, Set<String> visitedRefs)
    {
        if (composedSchema == null)
        {
            return null;
        }

        if (composedSchema.getAllOf() != null && !composedSchema.getAllOf().isEmpty())
        {
            Map<String, Object> merged = new LinkedHashMap<>();
            Object fallback = null;

            for (Schema<?> child : composedSchema.getAllOf())
            {
                Object part = buildExampleFromSchema(child, visitedRefs);
                if (part instanceof Map<?, ?> map)
                {
                    deepMerge(merged, map);
                }
                else if (fallback == null && part != null)
                {
                    fallback = part;
                }
            }

            if (!merged.isEmpty())
            {
                addDiscriminatorIfMissing(composedSchema, preferredBranch(composedSchema.getAllOf()), merged, visitedRefs);
                return merged;
            }
            return fallback == null ? Collections.emptyMap() : fallback;
        }

        List<? extends Schema> oneOf = composedSchema.getOneOf();
        if (oneOf != null && !oneOf.isEmpty())
        {
            Schema<?> preferred = preferredBranch(oneOf);
            Object sample = buildExampleFromSchema(preferred, visitedRefs);
            if (sample instanceof Map<?, ?> map)
            {
                Map<String, Object> withDiscriminator = new LinkedHashMap<>();
                deepMerge(withDiscriminator, map);
                addDiscriminatorIfMissing(composedSchema, preferred, withDiscriminator, visitedRefs);
                return withDiscriminator;
            }
            return sample;
        }

        List<? extends Schema> anyOf = composedSchema.getAnyOf();
        if (anyOf != null && !anyOf.isEmpty())
        {
            Schema<?> preferred = preferredBranch(anyOf);
            Object sample = buildExampleFromSchema(preferred, visitedRefs);
            if (sample instanceof Map<?, ?> map)
            {
                Map<String, Object> withDiscriminator = new LinkedHashMap<>();
                deepMerge(withDiscriminator, map);
                addDiscriminatorIfMissing(composedSchema, preferred, withDiscriminator, visitedRefs);
                return withDiscriminator;
            }
            return sample;
        }

        return null;
    }

    private Schema<?> preferredBranch(List<? extends Schema> candidates)
    {
        if (candidates == null || candidates.isEmpty())
        {
            return null;
        }

        for (Schema<?> candidate : candidates)
        {
            if (hasPrioritySample(candidate, new LinkedHashSet<>()))
            {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private boolean hasPrioritySample(Schema<?> schema, Set<String> visitedRefs)
    {
        if (schema == null)
        {
            return false;
        }
        if (schema.get$ref() != null && !visitedRefs.add(schema.get$ref()))
        {
            return false;
        }

        if (schema.getExample() != null || schema.getDefault() != null)
        {
            return true;
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty())
        {
            return true;
        }

        if (schema instanceof ComposedSchema composedSchema)
        {
            if (composedSchema.getAllOf() != null)
            {
                for (Schema<?> child : composedSchema.getAllOf())
                {
                    if (hasPrioritySample(child, visitedRefs))
                    {
                        return true;
                    }
                }
            }
            if (composedSchema.getOneOf() != null)
            {
                for (Schema<?> child : composedSchema.getOneOf())
                {
                    if (hasPrioritySample(child, visitedRefs))
                    {
                        return true;
                    }
                }
            }
            if (composedSchema.getAnyOf() != null)
            {
                for (Schema<?> child : composedSchema.getAnyOf())
                {
                    if (hasPrioritySample(child, visitedRefs))
                    {
                        return true;
                    }
                }
            }
        }

        if (schema.getProperties() != null)
        {
            for (Object propertySchema : schema.getProperties().values())
            {
                if (propertySchema instanceof Schema<?> child && hasPrioritySample(child, visitedRefs))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private void deepMerge(Map<String, Object> target, Map<?, ?> source)
    {
        for (Map.Entry<?, ?> entry : source.entrySet())
        {
            String key = String.valueOf(entry.getKey());
            Object incoming = entry.getValue();
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap)
            {
                Map<String, Object> merged = new LinkedHashMap<>();
                deepMerge(merged, existingMap);
                deepMerge(merged, incomingMap);
                target.put(key, merged);
            }
            else
            {
                target.put(key, incoming);
            }
        }
    }

    private void addDiscriminatorIfMissing(
            ComposedSchema composedSchema,
            Schema<?> selectedBranch,
            Map<String, Object> target,
            Set<String> visitedRefs)
    {
        if (composedSchema == null || target == null)
        {
            return;
        }

        Discriminator discriminator = composedSchema.getDiscriminator();
        if (discriminator == null || Utils.isBlank(discriminator.getPropertyName()))
        {
            return;
        }

        String propertyName = discriminator.getPropertyName();
        if (target.containsKey(propertyName))
        {
            return;
        }

        String value = resolveDiscriminatorValue(discriminator, selectedBranch, visitedRefs);
        target.put(propertyName, value);
    }

    private String resolveDiscriminatorValue(
            Discriminator discriminator,
            Schema<?> selectedBranch,
            Set<String> visitedRefs)
    {
        if (discriminator == null)
        {
            return "sample";
        }

        if (discriminator.getMapping() != null && !discriminator.getMapping().isEmpty())
        {
            return discriminator.getMapping().keySet().iterator().next();
        }

        if (selectedBranch != null && Utils.nonBlank(selectedBranch.get$ref()))
        {
            String ref = selectedBranch.get$ref();
            int idx = ref.lastIndexOf('/');
            return idx >= 0 && idx + 1 < ref.length() ? ref.substring(idx + 1) : ref;
        }

        if (selectedBranch != null && selectedBranch.getProperties() != null)
        {
            Object propertySchema = selectedBranch.getProperties().get(discriminator.getPropertyName());
            if (propertySchema instanceof Schema<?> discriminatorSchema)
            {
                Object value = buildExampleFromSchema(discriminatorSchema, visitedRefs);
                String normalized = normalizeExampleToString(value);
                if (Utils.nonBlank(normalized))
                {
                    return normalized;
                }
            }
        }

        return "sample-" + discriminator.getPropertyName();
    }

    private String toJson(Object value)
    {
        try
        {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        }
        catch (JsonProcessingException ignored)
        {
            return String.valueOf(value);
        }
    }

    private String toFormUrlEncoded(Object value)
    {
        if (value instanceof Map<?, ?> map)
        {
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                normalized.put(String.valueOf(entry.getKey()), normalizeExampleToString(entry.getValue()));
            }
            return toQueryString(normalized);
        }

        return "value=" + urlEncode(normalizeExampleToString(value));
    }

    private String toMultipart(Object value, String boundary)
    {
        Map<String, String> fields = new LinkedHashMap<>();

        if (value instanceof Map<?, ?> map)
        {
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                fields.put(String.valueOf(entry.getKey()), normalizeExampleToString(entry.getValue()));
            }
        }
        else
        {
            fields.put("field", normalizeExampleToString(value));
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet())
        {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(field.getKey()).append("\"\r\n\r\n");
            sb.append(field.getValue()).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");

        return sb.toString();
    }

    private String normalizeExampleToString(Object value)
    {
        if (value == null)
        {
            return "sample";
        }

        if (value instanceof String str)
        {
            return str;
        }

        if (value instanceof Number || value instanceof Boolean)
        {
            return String.valueOf(value);
        }

        if (value instanceof List<?> list)
        {
            List<String> asString = new ArrayList<>();
            for (Object item : list)
            {
                asString.add(normalizeExampleToString(item));
            }
            return String.join(",", asString);
        }

        return toJson(value);
    }

    private String urlEncode(String value)
    {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodePathSegment(String value)
    {
        return urlEncode(value).replace("+", "%20");
    }

    private record BodyPayload(String contentType, String body)
    {
        private static BodyPayload empty()
        {
            return new BodyPayload("", "");
        }
    }

    private record MediaSelection(String contentType, MediaType mediaType)
    {
    }
}
