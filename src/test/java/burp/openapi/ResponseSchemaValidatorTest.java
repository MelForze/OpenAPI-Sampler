package burp.openapi;

import burp.api.montoya.http.message.responses.HttpResponse;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ResponseSchemaValidatorTest
{
    @Test
    void validatesJsonResponseAgainstSchema()
    {
        ResponseSchemaValidator validator = new ResponseSchemaValidator();

        ObjectSchema schema = new ObjectSchema();
        schema.setRequired(List.of("id", "name"));
        schema.addProperty("id", new IntegerSchema().minimum(new java.math.BigDecimal("1")));
        schema.addProperty("name", new StringSchema().minLength(1));

        ApiResponse ok = new ApiResponse().content(new Content().addMediaType("application/json", new MediaType().schema(schema)));
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("200", ok));

        OpenApiSamplerModel.OperationContext context = operationContext(operation);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) 200);
        when(response.headerValue("Content-Type")).thenReturn("application/json");
        when(response.bodyToString()).thenReturn("{\"id\":1,\"name\":\"alice\"}");

        ResponseSchemaValidator.ValidationResult result = validator.validate(context, response);
        assertTrue(result.valid());
        assertFalse(result.skipped());
    }

    @Test
    void reportsSchemaMismatchesForInvalidResponse()
    {
        ResponseSchemaValidator validator = new ResponseSchemaValidator();

        ObjectSchema schema = new ObjectSchema();
        schema.setRequired(List.of("id", "name"));
        schema.addProperty("id", new IntegerSchema());
        schema.addProperty("name", new StringSchema());

        ApiResponse ok = new ApiResponse().content(new Content().addMediaType("application/json", new MediaType().schema(schema)));
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("200", ok));

        OpenApiSamplerModel.OperationContext context = operationContext(operation);
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) 200);
        when(response.headerValue("Content-Type")).thenReturn("application/json");
        when(response.bodyToString()).thenReturn("{\"id\":\"bad\"}");

        ResponseSchemaValidator.ValidationResult result = validator.validate(context, response);
        assertFalse(result.valid());
        assertFalse(result.skipped());
        assertTrue(result.message().toLowerCase().contains("schema mismatch"));
    }

    @Test
    void skipsValidationWhenOperationHasNoResponseSchema()
    {
        ResponseSchemaValidator validator = new ResponseSchemaValidator();
        Operation operation = new Operation();
        OpenApiSamplerModel.OperationContext context = operationContext(operation);

        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn((short) 200);
        when(response.headerValue("Content-Type")).thenReturn("application/json");
        when(response.bodyToString()).thenReturn("{}");

        ResponseSchemaValidator.ValidationResult result = validator.validate(context, response);
        assertTrue(result.skipped());
    }

    private OpenApiSamplerModel.OperationContext operationContext(Operation operation)
    {
        return new OpenApiSamplerModel.OperationContext(
                "source:test",
                "source",
                "https://api.example/openapi.json",
                "GET",
                "/users",
                "Users",
                "users",
                List.of(),
                List.of("https://api.example"),
                List.of(),
                null,
                operation
        );
    }
}
