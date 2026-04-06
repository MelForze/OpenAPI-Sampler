package burp.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class OpenApiSamplerTabIT
{
    @BeforeAll
    static void setupMontoyaFactory()
    {
        MontoyaFactoryBootstrap.install();
    }

    @Test
    void retriesAfter500AndEventuallyParses() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("temporary"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(minimalSpec("Recovered")));
            server.start();

            OpenApiSamplerTab tab = new OpenApiSamplerTab(TestApiFactory.apiContext().api, testHttpFetcher());
            Object parseOutcome = invoke(tab, "fetchAndParseFromUrl", new Class<?>[]{String.class}, server.url("/openapi.json").toString());
            OpenAPI openAPI = (OpenAPI) recordValue(parseOutcome, "openAPI");

            assertNotNull(openAPI);
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void retriesAfter429AndEventuallyParses() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(minimalSpec("Recovered429")));
            server.start();

            OpenApiSamplerTab tab = new OpenApiSamplerTab(TestApiFactory.apiContext().api, testHttpFetcher());
            Object parseOutcome = invoke(tab, "fetchAndParseFromUrl", new Class<?>[]{String.class}, server.url("/openapi.json").toString());
            OpenAPI openAPI = (OpenAPI) recordValue(parseOutcome, "openAPI");

            assertNotNull(openAPI);
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void followsRedirectsWhenFetchingSpec() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            server.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/v3/api-docs"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(minimalSpec("Redirected")));
            server.start();

            OpenApiSamplerTab tab = new OpenApiSamplerTab(TestApiFactory.apiContext().api, testHttpFetcher());
            Object parseOutcome = invoke(tab, "fetchAndParseFromUrl", new Class<?>[]{String.class}, server.url("/swagger/index.html").toString());
            OpenAPI openAPI = (OpenAPI) recordValue(parseOutcome, "openAPI");

            assertNotNull(openAPI);
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void rejectsOversizedSpecBeforeParsing() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            String hugePayload = "{\"openapi\":\"3.0.3\",\"paths\":{\"/x\":{\"get\":{\"summary\":\""
                    + "A".repeat(5 * 1024 * 1024 + 256)
                    + "\"}}}}";
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(hugePayload));
            server.start();

            OpenApiSamplerTab tab = new OpenApiSamplerTab(TestApiFactory.apiContext().api, testHttpFetcher());
            try
            {
                invoke(tab, "fetchAndParseFromUrl", new Class<?>[]{String.class}, server.url("/openapi.json").toString());
                fail("Expected oversized response to be rejected.");
            }
            catch (Exception ex)
            {
                assertTrue(ex.getMessage().contains("size-limit"));
            }
        }
    }

    @Test
    void decodesNonUtfSpecUsingCharsetDetection() throws Exception
    {
        try (MockWebServer server = new MockWebServer())
        {
            String spec = """
                    {
                      "openapi":"3.0.3",
                      "info":{"title":"t","version":"1.0"},
                      "paths":{
                        "/hello":{
                          "get":{
                            "summary":"Привет",
                            "responses":{"200":{"description":"ok"}}
                          }
                        }
                      }
                    }
                    """;

            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(new okio.Buffer().write(spec.getBytes(Charset.forName("windows-1251")))));
            server.start();

            OpenApiSamplerTab tab = new OpenApiSamplerTab(TestApiFactory.apiContext().api, testHttpFetcher());
            Object parseOutcome = invoke(tab, "fetchAndParseFromUrl", new Class<?>[]{String.class}, server.url("/openapi.json").toString());
            OpenAPI openAPI = (OpenAPI) recordValue(parseOutcome, "openAPI");

            assertNotNull(openAPI);
            assertEquals("Привет", openAPI.getPaths().get("/hello").getGet().getSummary());
        }
    }

    private OpenApiSamplerTab.SpecFetcher testHttpFetcher()
    {
        return (url, responseTimeoutMs, perAttemptDeadlineMs, followRedirects) -> {
            long requestTimeoutMs = Math.max(1_000L, Math.min(perAttemptDeadlineMs, responseTimeoutMs));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(responseTimeoutMs))
                    .followRedirects(followRedirects ? HttpClient.Redirect.ALWAYS : HttpClient.Redirect.NEVER)
                    .build();

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            long contentLength = response.headers()
                    .firstValue("Content-Length")
                    .map(this::parseLongSafely)
                    .orElse((long) (response.body() == null ? 0 : response.body().length));

            return new OpenApiSamplerTab.FetchResponse(
                    (short) response.statusCode(),
                    response.body(),
                    contentType,
                    contentLength
            );
        };
    }

    private long parseLongSafely(String value)
    {
        if (value == null)
        {
            return -1L;
        }
        try
        {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException ignored)
        {
            return -1L;
        }
    }

    private String minimalSpec(String summary)
    {
        return """
                {
                  "openapi":"3.0.3",
                  "info":{"title":"t","version":"1.0"},
                  "paths":{
                    "/x":{
                      "get":{
                        "summary":"%s",
                        "responses":{"200":{"description":"ok"}}
                      }
                    }
                  }
                }
                """.formatted(summary);
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
}
