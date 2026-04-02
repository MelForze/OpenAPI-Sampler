package burp.openapi;

import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UtilsTest
{
    @BeforeAll
    static void setupMontoyaFactory()
    {
        MontoyaFactoryBootstrap.install();
    }

    @Test
    void blankAndCoalesceHelpersWork()
    {
        assertTrue(Utils.isBlank(null));
        assertTrue(Utils.isBlank("   "));
        assertFalse(Utils.nonBlank("  "));
        assertTrue(Utils.nonBlank(" value "));

        assertEquals("", Utils.coalesce((String[]) null));
        assertEquals("", Utils.coalesce("", "   ", null));
        assertEquals("first", Utils.coalesce("   ", " first ", "second"));
    }

    @Test
    void urlAndSpecHeuristicsWork()
    {
        assertTrue(Utils.looksLikeHttpUrl("HTTPS://api.example.com/openapi.json"));
        assertFalse(Utils.looksLikeHttpUrl("ftp://api.example.com/openapi.json"));

        assertTrue(Utils.looksLikeOpenApiUrl("https://api.example.com/swagger.yaml"));
        assertTrue(Utils.looksLikeOpenApiUrl("https://api.example.com/spec.json"));
        assertFalse(Utils.looksLikeOpenApiUrl("https://api.example.com/index.html"));

        String validJson = """
                {
                  "openapi": "3.1.0",
                  "paths": {
                    "/users": {}
                  }
                }
                """;
        assertTrue(Utils.looksLikeOpenApiSpec(validJson));
        assertFalse(Utils.looksLikeOpenApiSpec("{\"hello\":\"world\"}"));
    }

    @Test
    void tagsAndServerHelpersWork()
    {
        assertEquals("-", Utils.joinTags(null));
        assertEquals("-", Utils.joinTags(List.of()));
        assertEquals("users, admin", Utils.joinTags(List.of("users", " ", "admin")));

        assertEquals("/", Utils.stripTrailingSlash("/"));
        assertEquals("https://api.example.com", Utils.stripTrailingSlash("https://api.example.com/"));
        assertEquals("https://api.example.com", Utils.stripTrailingSlash("https://api.example.com"));

        assertEquals("-", Utils.shortServer(" "));
        String longServer = "https://api.example.com/" + "x".repeat(100);
        assertTrue(Utils.shortServer(longServer).endsWith("..."));
    }

    @Test
    void repairMojibakeRecoversCyrillicText()
    {
        String mojibake = "РњРµС‚РѕРґ СЃРѕР·РґР°РЅРёСЏ";
        String repaired = Utils.repairMojibake(mojibake);
        assertEquals("Метод создания", repaired);

        String clean = "Метод создания";
        assertEquals(clean, Utils.repairMojibake(clean));
    }

    @Test
    void curlExportIncludesUsefulHeadersAndEscapesBody()
    {
        HttpRequest request = HttpRequest.httpRequestFromUrl("https://api.example.com/v1/users?role=admin")
                .withMethod("POST")
                .withAddedHeader("Authorization", "Bearer token")
                .withAddedHeader("Host", "api.example.com")
                .withAddedHeader("Content-Length", "999")
                .withBody("{\"name\":\"O'Reilly\"}");

        String curl = Utils.toCurl(request);
        assertTrue(curl.startsWith("curl -X POST 'https://api.example.com/v1/users?role=admin'"));
        assertTrue(curl.contains("-H 'Authorization: Bearer token'"));
        assertFalse(curl.contains("Host: api.example.com"));
        assertFalse(curl.contains("Content-Length:"));
        assertTrue(curl.contains("--data-raw '{\"name\":\"O'\"'\"'Reilly\"}'"));
    }

    @Test
    void pythonRequestsExportBuildsExpectedSnippet()
    {
        HttpRequest request = HttpRequest.httpRequestFromUrl("https://api.example.com/v1/users")
                .withMethod("PATCH")
                .withAddedHeader("X-Api-Key", "abc123")
                .withAddedHeader("Content-Length", "888")
                .withBody("{\"enabled\":true}");

        String script = Utils.toPythonRequests(request);
        assertTrue(script.contains("import requests"));
        assertTrue(script.contains("url = \"https://api.example.com/v1/users\""));
        assertTrue(script.contains("\"X-Api-Key\": \"abc123\""));
        assertFalse(script.contains("Content-Length"));
        assertTrue(script.contains("requests.request(\"PATCH\", url, headers=headers, data=payload)"));
    }

    @Test
    void pythonRequestsExportOmitsPayloadWhenBodyIsEmpty()
    {
        HttpRequest request = HttpRequest.httpRequestFromUrl("https://api.example.com/health")
                .withMethod("GET");

        String script = Utils.toPythonRequests(request);
        assertTrue(script.contains("requests.request(\"GET\", url, headers=headers)"));
        assertFalse(script.contains("data=payload"));
    }
}
