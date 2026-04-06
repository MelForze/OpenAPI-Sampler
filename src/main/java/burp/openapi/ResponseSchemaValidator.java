package burp.openapi;

import burp.api.montoya.http.message.responses.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight OpenAPI response schema validator used for quick runtime checks.
 */
public final class ResponseSchemaValidator
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_ERRORS = 20;
    private static final int MAX_DEPTH = 30;

    public ValidationResult validate(OpenApiSamplerModel.OperationContext operationContext, HttpResponse response)
    {
        if (operationContext == null || response == null)
        {
            return ValidationResult.skipped("Response is unavailable.");
        }

        Operation operation = operationContext.operation();
        if (operation == null || operation.getResponses() == null || operation.getResponses().isEmpty())
        {
            return ValidationResult.skipped("Operation has no response schemas.");
        }

        short statusCode = response.statusCode();
        ApiResponse apiResponse = resolveApiResponse(operation.getResponses(), statusCode);
        if (apiResponse == null)
        {
            return ValidationResult.skipped("No schema for HTTP " + statusCode + ".");
        }

        Content content = apiResponse.getContent();
        if (content == null || content.isEmpty())
        {
            return ValidationResult.skipped("Response schema content is not defined.");
        }

        String contentType = Utils.coalesce(response.headerValue("Content-Type"));
        MediaSelection mediaSelection = selectMedia(content, contentType);
        if (mediaSelection == null || mediaSelection.mediaType() == null || mediaSelection.mediaType().getSchema() == null)
        {
            return ValidationResult.skipped("No schema for media type " + contentType + ".");
        }

        Object payload;
        try
        {
            String body = Utils.coalesce(response.bodyToString());
            payload = parseBody(body, mediaSelection.mediaTypeName());
        }
        catch (Exception ex)
        {
            return ValidationResult.failed("Response body decode/parse failed: " + ex.getMessage(), List.of(ex.getMessage()));
        }

        List<String> errors = new ArrayList<>();
        validateAgainstSchema(mediaSelection.mediaType().getSchema(), payload, "$", errors, 0, new LinkedHashSet<>());
        if (errors.isEmpty())
        {
            return ValidationResult.valid("Validated against " + mediaSelection.mediaTypeName() + " schema.");
        }

        String first = errors.get(0);
        String message = "Schema mismatch (" + errors.size() + "): " + first;
        return ValidationResult.failed(message, errors);
    }

    private ApiResponse resolveApiResponse(ApiResponses responses, short statusCode)
    {
        if (responses == null || responses.isEmpty())
        {
            return null;
        }

        String exact = String.valueOf(statusCode);
        if (responses.containsKey(exact))
        {
            return responses.get(exact);
        }

        String statusFamily = (statusCode / 100) + "XX";
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet())
        {
            if (entry == null || Utils.isBlank(entry.getKey()))
            {
                continue;
            }
            if (statusFamily.equalsIgnoreCase(entry.getKey()))
            {
                return entry.getValue();
            }
        }

        return responses.get("default");
    }

    private MediaSelection selectMedia(Content content, String responseContentType)
    {
        if (content == null || content.isEmpty())
        {
            return null;
        }

        String normalizedResponseType = Utils.safeLower(Utils.coalesce(responseContentType)).trim();
        if (Utils.nonBlank(normalizedResponseType))
        {
            for (Map.Entry<String, MediaType> entry : content.entrySet())
            {
                if (entry == null || Utils.isBlank(entry.getKey()) || entry.getValue() == null)
                {
                    continue;
                }
                String normalized = Utils.safeLower(entry.getKey());
                if (normalizedResponseType.startsWith(normalized))
                {
                    return new MediaSelection(entry.getKey(), entry.getValue());
                }
            }
        }

        if (content.containsKey("application/json"))
        {
            return new MediaSelection("application/json", content.get("application/json"));
        }

        Map.Entry<String, MediaType> first = content.entrySet().iterator().next();
        return new MediaSelection(first.getKey(), first.getValue());
    }

    private Object parseBody(String body, String mediaTypeName) throws Exception
    {
        String normalizedMedia = Utils.safeLower(mediaTypeName);
        String payload = Utils.coalesce(body);
        if (normalizedMedia.contains("json") || payload.startsWith("{") || payload.startsWith("["))
        {
            if (Utils.isBlank(payload))
            {
                return null;
            }
            return OBJECT_MAPPER.readValue(payload, Object.class);
        }
        return payload;
    }

    private void validateAgainstSchema(
            Schema<?> schema,
            Object value,
            String path,
            List<String> errors,
            int depth,
            Set<String> visitedRefs)
    {
        if (schema == null || errors.size() >= MAX_ERRORS || depth > MAX_DEPTH)
        {
            return;
        }

        if (schema.get$ref() != null && !visitedRefs.add(schema.get$ref()))
        {
            return;
        }

        if (value == null)
        {
            if (!Boolean.TRUE.equals(schema.getNullable()))
            {
                addError(errors, path + ": value is null, but schema is not nullable.");
            }
            return;
        }

        if (schema.getEnum() != null && !schema.getEnum().isEmpty())
        {
            boolean enumMatch = schema.getEnum().stream().filter(Objects::nonNull).anyMatch(enumValue ->
                    String.valueOf(enumValue).equals(String.valueOf(value)));
            if (!enumMatch)
            {
                addError(errors, path + ": value '" + value + "' is not in enum.");
                return;
            }
        }

        if (schema instanceof ComposedSchema composedSchema)
        {
            validateComposed(composedSchema, value, path, errors, depth + 1, visitedRefs);
            return;
        }

        String type = Utils.safeLower(Utils.coalesce(schema.getType()));
        switch (type)
        {
            case "object" -> validateObject(schema, value, path, errors, depth + 1, visitedRefs);
            case "array" -> validateArray(schema, value, path, errors, depth + 1, visitedRefs);
            case "string" -> validateString(schema, value, path, errors);
            case "integer" -> validateInteger(schema, value, path, errors);
            case "number" -> validateNumber(schema, value, path, errors);
            case "boolean" -> validateBoolean(value, path, errors);
            default -> inferAndValidate(schema, value, path, errors, depth + 1, visitedRefs);
        }
    }

    private void validateComposed(
            ComposedSchema schema,
            Object value,
            String path,
            List<String> errors,
            int depth,
            Set<String> visitedRefs)
    {
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty())
        {
            for (Schema<?> child : schema.getAllOf())
            {
                validateAgainstSchema(child, value, path, errors, depth + 1, new LinkedHashSet<>(visitedRefs));
                if (errors.size() >= MAX_ERRORS)
                {
                    return;
                }
            }
            return;
        }

        List<Schema> alternatives = schema.getOneOf() != null && !schema.getOneOf().isEmpty()
                ? schema.getOneOf()
                : schema.getAnyOf();
        if (alternatives == null || alternatives.isEmpty())
        {
            return;
        }

        boolean matched = false;
        List<String> sampleErrors = new ArrayList<>();
        for (Schema<?> alternative : alternatives)
        {
            List<String> localErrors = new ArrayList<>();
            validateAgainstSchema(alternative, value, path, localErrors, depth + 1, new LinkedHashSet<>(visitedRefs));
            if (localErrors.isEmpty())
            {
                matched = true;
                break;
            }
            if (sampleErrors.isEmpty())
            {
                sampleErrors.addAll(localErrors);
            }
        }

        if (!matched)
        {
            addError(errors, path + ": value did not match any composed schema branch.");
            if (!sampleErrors.isEmpty())
            {
                addError(errors, sampleErrors.get(0));
            }
        }
    }

    private void validateObject(
            Schema<?> schema,
            Object value,
            String path,
            List<String> errors,
            int depth,
            Set<String> visitedRefs)
    {
        if (!(value instanceof Map<?, ?> objectMap))
        {
            addError(errors, path + ": expected object, got " + value.getClass().getSimpleName() + ".");
            return;
        }

        List<String> required = schema.getRequired();
        if (required != null)
        {
            for (String requiredField : required)
            {
                if (Utils.isBlank(requiredField))
                {
                    continue;
                }
                if (!objectMap.containsKey(requiredField))
                {
                    addError(errors, path + "." + requiredField + ": required field is missing.");
                }
            }
        }

        Map<String, Schema> properties = schema.getProperties();
        if (properties != null)
        {
            for (Map.Entry<String, Schema> property : properties.entrySet())
            {
                if (property == null || Utils.isBlank(property.getKey()) || property.getValue() == null)
                {
                    continue;
                }
                if (objectMap.containsKey(property.getKey()))
                {
                    validateAgainstSchema(
                            property.getValue(),
                            objectMap.get(property.getKey()),
                            path + "." + property.getKey(),
                            errors,
                            depth + 1,
                            new LinkedHashSet<>(visitedRefs)
                    );
                    if (errors.size() >= MAX_ERRORS)
                    {
                        return;
                    }
                }
            }
        }

        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Schema<?> additionalSchema)
        {
            for (Map.Entry<?, ?> entry : objectMap.entrySet())
            {
                String key = String.valueOf(entry.getKey());
                if (properties != null && properties.containsKey(key))
                {
                    continue;
                }
                validateAgainstSchema(
                        additionalSchema,
                        entry.getValue(),
                        path + "." + key,
                        errors,
                        depth + 1,
                        new LinkedHashSet<>(visitedRefs)
                );
                if (errors.size() >= MAX_ERRORS)
                {
                    return;
                }
            }
        }
    }

    private void validateArray(
            Schema<?> schema,
            Object value,
            String path,
            List<String> errors,
            int depth,
            Set<String> visitedRefs)
    {
        if (!(value instanceof List<?> list))
        {
            addError(errors, path + ": expected array, got " + value.getClass().getSimpleName() + ".");
            return;
        }

        if (schema.getMinItems() != null && list.size() < schema.getMinItems())
        {
            addError(errors, path + ": expected at least " + schema.getMinItems() + " item(s), got " + list.size() + ".");
        }
        if (schema.getMaxItems() != null && list.size() > schema.getMaxItems())
        {
            addError(errors, path + ": expected at most " + schema.getMaxItems() + " item(s), got " + list.size() + ".");
        }

        if (schema.getItems() != null)
        {
            for (int index = 0; index < list.size(); index++)
            {
                validateAgainstSchema(
                        schema.getItems(),
                        list.get(index),
                        path + "[" + index + "]",
                        errors,
                        depth + 1,
                        new LinkedHashSet<>(visitedRefs)
                );
                if (errors.size() >= MAX_ERRORS)
                {
                    return;
                }
            }
        }
    }

    private void validateString(Schema<?> schema, Object value, String path, List<String> errors)
    {
        if (!(value instanceof String stringValue))
        {
            addError(errors, path + ": expected string.");
            return;
        }

        if (schema.getMinLength() != null && stringValue.length() < schema.getMinLength())
        {
            addError(errors, path + ": minLength violation.");
        }
        if (schema.getMaxLength() != null && stringValue.length() > schema.getMaxLength())
        {
            addError(errors, path + ": maxLength violation.");
        }
        if (Utils.nonBlank(schema.getPattern()) && !stringValue.matches(schema.getPattern()))
        {
            addError(errors, path + ": pattern mismatch.");
        }
    }

    private void validateInteger(Schema<?> schema, Object value, String path, List<String> errors)
    {
        if (!(value instanceof Number numberValue))
        {
            addError(errors, path + ": expected integer.");
            return;
        }

        double asDouble = numberValue.doubleValue();
        if (Math.rint(asDouble) != asDouble)
        {
            addError(errors, path + ": expected integer, got decimal value.");
            return;
        }

        if (schema.getMinimum() != null && asDouble < schema.getMinimum().doubleValue())
        {
            addError(errors, path + ": value below minimum.");
        }
        if (schema.getMaximum() != null && asDouble > schema.getMaximum().doubleValue())
        {
            addError(errors, path + ": value above maximum.");
        }
    }

    private void validateNumber(Schema<?> schema, Object value, String path, List<String> errors)
    {
        if (!(value instanceof Number numberValue))
        {
            addError(errors, path + ": expected number.");
            return;
        }

        double asDouble = numberValue.doubleValue();
        if (schema.getMinimum() != null && asDouble < schema.getMinimum().doubleValue())
        {
            addError(errors, path + ": value below minimum.");
        }
        if (schema.getMaximum() != null && asDouble > schema.getMaximum().doubleValue())
        {
            addError(errors, path + ": value above maximum.");
        }
    }

    private void validateBoolean(Object value, String path, List<String> errors)
    {
        if (!(value instanceof Boolean))
        {
            addError(errors, path + ": expected boolean.");
        }
    }

    private void inferAndValidate(
            Schema<?> schema,
            Object value,
            String path,
            List<String> errors,
            int depth,
            Set<String> visitedRefs)
    {
        if (schema.getProperties() != null && !schema.getProperties().isEmpty())
        {
            validateObject(schema, value, path, errors, depth + 1, visitedRefs);
            return;
        }
        if (schema.getItems() != null)
        {
            validateArray(schema, value, path, errors, depth + 1, visitedRefs);
        }
    }

    private void addError(List<String> errors, String message)
    {
        if (errors == null || errors.size() >= MAX_ERRORS)
        {
            return;
        }
        errors.add(Utils.coalesce(message));
    }

    private record MediaSelection(String mediaTypeName, MediaType mediaType)
    {
    }

    public record ValidationResult(boolean valid, boolean skipped, String message, List<String> errors)
    {
        public static ValidationResult valid(String message)
        {
            return new ValidationResult(true, false, Utils.coalesce(message), List.of());
        }

        public static ValidationResult failed(String message, List<String> errors)
        {
            List<String> normalized = errors == null ? List.of() : List.copyOf(errors);
            return new ValidationResult(false, false, Utils.coalesce(message), normalized);
        }

        public static ValidationResult skipped(String message)
        {
            return new ValidationResult(false, true, Utils.coalesce(message), List.of());
        }
    }
}
