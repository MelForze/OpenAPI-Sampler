package burp.openapi;

import burp.api.montoya.http.message.requests.HttpRequest;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RequestGeneratorTest
{
    private static final String DEFAULT_SERVER = "(Operation default)";

    @BeforeAll
    static void setupMontoyaFactory()
    {
        MontoyaFactoryBootstrap.install();
    }

    @Test
    void selectedServerHasPriorityAndAllParameterTypesAreApplied()
    {
        RequestGenerator generator = new RequestGenerator();

        List<Parameter> parameters = List.of(
                new Parameter().in("path").name("id").example("42"),
                new Parameter().in("query").name("q").example("john doe"),
                new Parameter().in("header").name("X-Trace-Id").example("abc-123"),
                new Parameter().in("cookie").name("session").example("cookie-1")
        );

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/users/{id}",
                List.of("https://operation.example"),
                parameters,
                null,
                new Operation()
        );

        HttpRequest request = generator.generate(context, "https://selected.example", List.of("https://global.example"));
        String raw = request.toString();

        assertEquals("POST", request.method());
        assertTrue(request.url().startsWith("https://selected.example/users/42"));
        assertTrue(request.url().contains("q=john+doe"));
        assertTrue(raw.contains("X-Trace-Id: abc-123"));
        assertTrue(raw.contains("Cookie: session=cookie-1"));
    }

    @Test
    void unresolvedPathParametersAreReplacedByDeterministicPlaceholders()
    {
        RequestGenerator generator = new RequestGenerator();
        OpenApiSamplerModel.OperationContext context = context(
                "GET",
                "/users/{id}/{slug}",
                List.of("https://api.example"),
                List.of(new Parameter().in("path").name("id").example("7")),
                null,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.url().contains("/users/7/sample-slug"));
    }

    @Test
    void operationServerAndGlobalServerFallbacksWork()
    {
        RequestGenerator generator = new RequestGenerator();

        OpenApiSamplerModel.OperationContext operationServerContext = context(
                "GET",
                "/status",
                List.of("https://op.example"),
                List.of(),
                null,
                new Operation()
        );

        HttpRequest fromOperationServer = generator.generate(operationServerContext, DEFAULT_SERVER, List.of("https://global.example"));
        assertTrue(fromOperationServer.url().startsWith("https://op.example/status"));

        OpenApiSamplerModel.OperationContext globalServerContext = context(
                "GET",
                "/status",
                List.of(),
                List.of(),
                null,
                new Operation()
        );
        HttpRequest fromGlobal = generator.generate(globalServerContext, DEFAULT_SERVER, List.of("https://global.example"));
        assertTrue(fromGlobal.url().startsWith("https://global.example/status"));

        HttpRequest localhostFallback = generator.generate(globalServerContext, DEFAULT_SERVER, List.of());
        assertTrue(localhostFallback.url().startsWith("http://localhost/status"));
    }

    @Test
    void jsonBodyIsGeneratedFromSchemaWithContentType()
    {
        RequestGenerator generator = new RequestGenerator();

        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("name", new StringSchema());
        schema.addProperty("age", new IntegerSchema());
        schema.addProperty("roles", new ArraySchema().items(new StringSchema()._default("user")));

        RequestBody requestBody = new RequestBody();
        requestBody.setContent(new Content().addMediaType("application/json", new MediaType().schema(schema)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/users",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        String body = request.bodyToString();
        String raw = request.toString();

        assertTrue(raw.contains("Content-Type: application/json"));
        assertTrue(body.contains("\"name\""));
        assertTrue(body.contains("\"age\""));
        assertTrue(body.contains("\"roles\""));
    }

    @Test
    void jsonBodySupportsRecursiveSchemas()
    {
        RequestGenerator generator = new RequestGenerator();

        Schema<Object> recursive = new Schema<>();
        recursive.set$ref("#/components/schemas/Node");
        recursive.setType("object");
        Map<String, Schema> props = new LinkedHashMap<>();
        props.put("child", recursive);
        recursive.setProperties(props);

        RequestBody requestBody = new RequestBody();
        requestBody.setContent(new Content().addMediaType("application/json", new MediaType().schema(recursive)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/tree",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.bodyToString().contains("<recursive-ref>"));
    }

    @Test
    void mediaTypePriorityPrefersJsonWhenMultipleArePresent()
    {
        RequestGenerator generator = new RequestGenerator();

        Content content = new Content();
        content.addMediaType("text/plain", new MediaType().example("plain-data"));
        content.addMediaType("application/json", new MediaType().example(Map.of("value", "json-data")));

        RequestBody requestBody = new RequestBody();
        requestBody.setContent(content);

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/priority",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.toString().contains("Content-Type: application/json"));
        assertTrue(request.bodyToString().contains("json-data"));
    }

    @Test
    void formUrlEncodedBodyIsGenerated()
    {
        RequestGenerator generator = new RequestGenerator();

        ObjectSchema formSchema = new ObjectSchema();
        formSchema.addProperty("username", new StringSchema()._default("alice"));
        formSchema.addProperty("age", new IntegerSchema()._default(30));

        RequestBody requestBody = new RequestBody();
        requestBody.setContent(new Content().addMediaType("application/x-www-form-urlencoded", new MediaType().schema(formSchema)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/login",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        String body = request.bodyToString();
        String raw = request.toString();

        assertTrue(raw.contains("Content-Type: application/x-www-form-urlencoded"));
        assertTrue(body.contains("username=alice"));
        assertTrue(body.contains("age=30"));
    }

    @Test
    void multipartBodyIsGeneratedWithBoundary()
    {
        RequestGenerator generator = new RequestGenerator();

        ObjectSchema multipartSchema = new ObjectSchema();
        multipartSchema.addProperty("title", new StringSchema()._default("report"));
        multipartSchema.addProperty("fileName", new StringSchema()._default("a.txt"));

        RequestBody requestBody = new RequestBody();
        requestBody.setContent(new Content().addMediaType("multipart/form-data", new MediaType().schema(multipartSchema)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/upload",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        String raw = request.toString();
        String body = request.bodyToString();

        assertTrue(raw.contains("Content-Type: multipart/form-data; boundary=----OpenApiSamplerBoundary"));
        assertTrue(body.contains("Content-Disposition: form-data; name=\"title\""));
        assertTrue(body.contains("report"));
    }

    @Test
    void legacyFormDataParametersHavePriorityOverRequestBody()
    {
        RequestGenerator generator = new RequestGenerator();

        List<Parameter> parameters = List.of(
                new Parameter().in("formData").name("username").example("bob"),
                new Parameter().in("formData").name("password").example("secret")
        );

        RequestBody requestBody = new RequestBody();
        requestBody.setContent(new Content().addMediaType("application/json", new MediaType().example(Map.of("ignored", true))));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/legacy-login",
                List.of("https://api.example"),
                parameters,
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.toString().contains("Content-Type: application/x-www-form-urlencoded"));
        assertTrue(request.bodyToString().contains("username=bob"));
        assertTrue(request.bodyToString().contains("password=secret"));
        assertFalse(request.bodyToString().contains("ignored"));
    }

    @Test
    void legacyBodyParameterIsUsedWhenRequestBodyIsMissing()
    {
        RequestGenerator generator = new RequestGenerator();

        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("username", new StringSchema()._default("legacy-user"));

        Parameter bodyParameter = new Parameter().in("body").name("body");
        bodyParameter.setSchema(schema);

        Operation operation = new Operation();
        operation.setParameters(List.of(bodyParameter));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/legacy-body",
                List.of("https://api.example"),
                List.of(),
                null,
                operation
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.toString().contains("Content-Type: application/json"));
        assertTrue(request.bodyToString().contains("legacy-user"));
    }

    @Test
    void parameterExampleValueFallbackChainIsApplied()
    {
        RequestGenerator generator = new RequestGenerator();

        Parameter fromExamplesMap = new Parameter().in("query").name("a");
        fromExamplesMap.setExamples(Map.of("exampleA", new Example().value("from-map")));

        Parameter fromDefault = new Parameter().in("query").name("b");
        fromDefault.setSchema(new StringSchema()._default("from-default"));

        Parameter fromEnum = new Parameter().in("query").name("c");
        Schema<String> enumSchema = new StringSchema();
        enumSchema.setEnum(List.of("from-enum", "other"));
        fromEnum.setSchema(enumSchema);

        OpenApiSamplerModel.OperationContext context = context(
                "GET",
                "/fallbacks",
                List.of("https://api.example"),
                List.of(fromExamplesMap, fromDefault, fromEnum),
                null,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        String query = request.query();
        assertTrue(query.contains("a=from-map"));
        assertTrue(query.contains("b=from-default"));
        assertTrue(query.contains("c=from-enum"));
    }

    @Test
    void composedSchemaAllOfAndOneOfProduceDeterministicPayload()
    {
        RequestGenerator generator = new RequestGenerator();

        ObjectSchema p1 = new ObjectSchema();
        p1.addProperty("name", new StringSchema()._default("alice"));

        ObjectSchema p2 = new ObjectSchema();
        p2.addProperty("age", new IntegerSchema()._default(31));

        ComposedSchema allOfSchema = new ComposedSchema();
        allOfSchema.setAllOf(List.of(p1, p2));

        ComposedSchema oneOfSchema = new ComposedSchema();
        oneOfSchema.setOneOf(List.of(new StringSchema()._default("one-of-value"), new StringSchema()._default("other")));

        RequestBody allOfBody = new RequestBody().content(new Content().addMediaType("application/json", new MediaType().schema(allOfSchema)));
        RequestBody oneOfBody = new RequestBody().content(new Content().addMediaType("text/plain", new MediaType().schema(oneOfSchema)));

        OpenApiSamplerModel.OperationContext allOfContext = context(
                "POST",
                "/all-of",
                List.of("https://api.example"),
                List.of(),
                allOfBody,
                new Operation()
        );
        OpenApiSamplerModel.OperationContext oneOfContext = context(
                "POST",
                "/one-of",
                List.of("https://api.example"),
                List.of(),
                oneOfBody,
                new Operation()
        );

        HttpRequest allOfRequest = generator.generate(allOfContext, DEFAULT_SERVER, List.of());
        HttpRequest oneOfRequest = generator.generate(oneOfContext, DEFAULT_SERVER, List.of());

        assertTrue(allOfRequest.bodyToString().contains("\"name\""));
        assertTrue(allOfRequest.bodyToString().contains("\"age\""));
        assertEquals("one-of-value", oneOfRequest.bodyToString());
    }

    @Test
    void anyOfPrefersBranchWithDefaultExampleOverEmptyBranch()
    {
        RequestGenerator generator = new RequestGenerator();

        ComposedSchema anyOf = new ComposedSchema();
        anyOf.setAnyOf(List.of(
                new ObjectSchema().addProperty("a", new StringSchema()),
                new ObjectSchema().addProperty("token", new StringSchema()._default("preferred-token"))
        ));

        RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/json", new MediaType().schema(anyOf)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/any-of",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.bodyToString().contains("\"token\""));
        assertTrue(request.bodyToString().contains("preferred-token"));
    }

    @Test
    void discriminatorIsAddedWhenMissingInComposedPayload()
    {
        RequestGenerator generator = new RequestGenerator();

        ComposedSchema oneOf = new ComposedSchema();
        oneOf.setOneOf(List.of(
                new ObjectSchema().addProperty("id", new StringSchema()._default("123"))
        ));
        oneOf.setDiscriminator(new Discriminator().propertyName("type"));

        RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/json", new MediaType().schema(oneOf)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/disc",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        assertTrue(request.bodyToString().contains("\"type\""));
    }

    @Test
    void arrayMinItemsIsHonoredAndCappedDeterministically()
    {
        RequestGenerator generator = new RequestGenerator();

        ArraySchema tags = new ArraySchema();
        tags.setMinItems(10);
        tags.setItems(new StringSchema()._default("tag"));

        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("tags", tags);
        RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/json", new MediaType().schema(payload)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/array",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        String body = request.bodyToString();
        assertTrue(body.contains("\"tags\""));
        assertTrue(body.contains("tag"));
        int occurrences = body.split("tag", -1).length - 1;
        assertTrue(occurrences >= 3);
        assertTrue(occurrences <= 4); // pretty JSON adds quotes, but array count is capped to 3 items
    }

    @Test
    void authProfileAndTemplateVariablesAreAppliedToRequest()
    {
        RequestGenerator generator = new RequestGenerator();
        List<Parameter> parameters = List.of(
                new Parameter().in("path").name("tenant").example("{{tenant}}"),
                new Parameter().in("query").name("trace").example("{{trace}}"),
                new Parameter().in("header").name("X-Env").example("{{env}}")
        );

        OpenApiSamplerModel.OperationContext context = context(
                "GET",
                "/{{region}}/accounts/{tenant}",
                List.of("https://{{baseHost}}"),
                parameters,
                null,
                new Operation()
        );

        RequestGenerator.GenerationOptions options = new RequestGenerator.GenerationOptions(
                new RequestGenerator.AuthProfile(RequestGenerator.AuthType.BEARER, "", "{{token}}"),
                Map.of(
                        "baseHost", "api.example",
                        "region", "eu",
                        "tenant", "acme",
                        "trace", "trace-1",
                        "env", "prod",
                        "token", "token-123"
                )
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of(), options);
        String raw = request.toString();
        assertTrue(request.url().startsWith("https://api.example/eu/accounts/acme"));
        assertTrue(request.url().contains("trace=trace-1"));
        assertTrue(raw.contains("X-Env: prod"));
        assertTrue(raw.contains("Authorization: Bearer token-123"));
    }

    @Test
    void basicAndApiKeyQueryAuthProfilesAreApplied()
    {
        RequestGenerator generator = new RequestGenerator();
        OpenApiSamplerModel.OperationContext context = context(
                "GET",
                "/secure",
                List.of("https://api.example"),
                List.of(),
                null,
                new Operation()
        );

        RequestGenerator.GenerationOptions basicOptions = new RequestGenerator.GenerationOptions(
                new RequestGenerator.AuthProfile(RequestGenerator.AuthType.BASIC, "alice", "wonderland"),
                Map.of()
        );
        HttpRequest basicRequest = generator.generate(context, DEFAULT_SERVER, List.of(), basicOptions);
        String encoded = Base64.getEncoder().encodeToString("alice:wonderland".getBytes());
        assertTrue(basicRequest.toString().contains("Authorization: Basic " + encoded));

        RequestGenerator.GenerationOptions queryOptions = new RequestGenerator.GenerationOptions(
                new RequestGenerator.AuthProfile(RequestGenerator.AuthType.API_KEY_QUERY, "api_key", "q-1"),
                Map.of()
        );
        HttpRequest queryRequest = generator.generate(context, DEFAULT_SERVER, List.of(), queryOptions);
        assertTrue(queryRequest.url().contains("api_key=q-1"));
    }

    @Test
    void smartSchemaGenerationUsesFormatPatternAndNumericBounds()
    {
        RequestGenerator generator = new RequestGenerator();

        ObjectSchema payload = new ObjectSchema();
        payload.addProperty("email", new StringSchema().format("email"));
        payload.addProperty("createdAt", new StringSchema().format("date-time"));
        payload.addProperty("code", new StringSchema().pattern("\\d+"));
        payload.addProperty("age", new IntegerSchema().minimum(new java.math.BigDecimal("18")));

        RequestBody requestBody = new RequestBody()
                .content(new Content().addMediaType("application/json", new MediaType().schema(payload)));

        OpenApiSamplerModel.OperationContext context = context(
                "POST",
                "/smart",
                List.of("https://api.example"),
                List.of(),
                requestBody,
                new Operation()
        );

        HttpRequest request = generator.generate(context, DEFAULT_SERVER, List.of());
        String body = request.bodyToString();
        assertTrue(body.contains("user@example.com"));
        assertTrue(body.contains("2026-01-01T00:00:00Z"));
        assertTrue(body.contains("\"code\""));
        assertTrue(body.contains("12345"));
        assertTrue(body.contains("\"age\""));
        assertTrue(body.contains("18"));
    }

    private OpenApiSamplerModel.OperationContext context(
            String method,
            String path,
            List<String> servers,
            List<Parameter> parameters,
            RequestBody requestBody,
            Operation operation)
    {
        return new OpenApiSamplerModel.OperationContext(
                "source:test",
                "test-source",
                "https://api.example/openapi.json",
                method,
                path,
                method + " " + path,
                method.toLowerCase() + "_" + path.replace('/', '_'),
                List.of("test"),
                servers,
                parameters,
                requestBody,
                operation
        );
    }
}
