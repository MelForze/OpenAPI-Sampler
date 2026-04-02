package burp.openapi;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Shared helper methods for UI + request generation.
 */
public final class Utils
{
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    private Utils()
    {
    }

    public static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    public static boolean nonBlank(String value)
    {
        return !isBlank(value);
    }

    public static String safeLower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public static String coalesce(String... values)
    {
        if (values == null)
        {
            return "";
        }

        for (String value : values)
        {
            if (nonBlank(value))
            {
                return value.trim();
            }
        }
        return "";
    }

    public static String joinTags(Collection<String> tags)
    {
        if (tags == null || tags.isEmpty())
        {
            return "-";
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .filter(Utils::nonBlank)
                .collect(Collectors.joining(", "));
    }

    public static String stripTrailingSlash(String value)
    {
        if (isBlank(value))
        {
            return value;
        }
        if (value.endsWith("/") && value.length() > 1)
        {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    public static boolean looksLikeHttpUrl(String value)
    {
        String normalized = safeLower(value);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    public static boolean looksLikeOpenApiUrl(String value)
    {
        if (isBlank(value))
        {
            return false;
        }
        final String normalized = safeLower(value);
        return normalized.contains("openapi")
                || normalized.contains("swagger")
                || normalized.endsWith(".json")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".yml");
    }

    public static boolean looksLikeOpenApiSpec(String payload)
    {
        if (isBlank(payload))
        {
            return false;
        }

        final String probe = safeLower(payload.length() > 8192 ? payload.substring(0, 8192) : payload);

        boolean hasVersion = probe.contains("\"openapi\"")
                || probe.contains("openapi:")
                || probe.contains("\"swagger\"")
                || probe.contains("swagger:");

        boolean hasPaths = probe.contains("\"paths\"") || probe.contains("paths:");

        return hasVersion && hasPaths;
    }

    public static void copyToClipboard(String value)
    {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value == null ? "" : value), null);
    }

    public static String toCurl(HttpRequest request)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("curl");
        sb.append(" -X ").append(request.method());
        sb.append(" '").append(escapeShellSingleQuoted(request.url())).append("'");

        for (HttpHeader header : request.headers())
        {
            String name = header.name();
            if (name == null)
            {
                continue;
            }

            if (name.equalsIgnoreCase("Host")
                    || name.equalsIgnoreCase("Content-Length"))
            {
                continue;
            }

            sb.append(" \\\n  -H '")
                    .append(escapeShellSingleQuoted(header.toString()))
                    .append("'");
        }

        String body = safeBody(request);
        if (nonBlank(body))
        {
            sb.append(" \\\n  --data-raw '").append(escapeShellSingleQuoted(body)).append("'");
        }

        return sb.toString();
    }

    public static String toPythonRequests(HttpRequest request)
    {
        Map<String, String> headers = new LinkedHashMap<>();
        for (HttpHeader header : request.headers())
        {
            String name = header.name();
            if (name == null)
            {
                continue;
            }
            if (name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length"))
            {
                continue;
            }
            headers.put(name, header.value());
        }

        String body = safeBody(request);

        StringBuilder sb = new StringBuilder();
        sb.append("import requests\n\n");
        sb.append("url = \"").append(escapePythonString(request.url())).append("\"\n");
        sb.append("headers = {");

        if (!headers.isEmpty())
        {
            sb.append("\n");
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> entry : headers.entrySet())
            {
                lines.add("    \"" + escapePythonString(entry.getKey()) + "\": \"" + escapePythonString(entry.getValue()) + "\"");
            }
            sb.append(String.join(",\n", lines)).append("\n");
        }
        sb.append("}\n");

        if (nonBlank(body))
        {
            sb.append("payload = \"\"\"").append(escapePythonTripleQuoted(body)).append("\"\"\"\n");
            sb.append("response = requests.request(\"")
                    .append(request.method())
                    .append("\", url, headers=headers, data=payload)\n");
        }
        else
        {
            sb.append("response = requests.request(\"")
                    .append(request.method())
                    .append("\", url, headers=headers)\n");
        }

        sb.append("print(response.status_code)\n");
        sb.append("print(response.text)\n");

        return sb.toString();
    }

    public static String shortServer(String server)
    {
        if (isBlank(server))
        {
            return "-";
        }
        return server.length() <= 64 ? server : server.substring(0, 61) + "...";
    }

    public static String repairMojibake(String value)
    {
        if (isBlank(value))
        {
            return coalesce(value);
        }

        String original = value;
        if (!looksLikeMojibake(original))
        {
            return original;
        }

        String best = original;
        int bestScore = textQualityScore(best);

        String cp1251ToUtf8 = new String(original.getBytes(WINDOWS_1251), StandardCharsets.UTF_8);
        int cp1251Score = textQualityScore(cp1251ToUtf8);
        if (cp1251Score > bestScore)
        {
            best = cp1251ToUtf8;
            bestScore = cp1251Score;
        }

        String isoToUtf8 = new String(original.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        int isoScore = textQualityScore(isoToUtf8);
        if (isoScore > bestScore)
        {
            best = isoToUtf8;
            bestScore = isoScore;
        }

        return best;
    }

    private static boolean looksLikeMojibake(String value)
    {
        if (isBlank(value))
        {
            return false;
        }

        int suspiciousPairs = 0;
        for (int i = 0; i < value.length() - 1; i++)
        {
            char first = value.charAt(i);
            char second = value.charAt(i + 1);
            boolean cpPair = (first == 'Р' || first == 'С') && isCyrillic(second);
            boolean latinPair = (first == 'Ð' || first == 'Ñ') && second >= '\u0080' && second <= '\u00FF';
            if (cpPair || latinPair)
            {
                suspiciousPairs++;
            }
        }

        return suspiciousPairs >= 2 || value.contains("Ã") || value.contains("�");
    }

    private static int textQualityScore(String value)
    {
        if (isBlank(value))
        {
            return Integer.MIN_VALUE / 8;
        }

        int score = 0;
        int suspiciousPairs = 0;
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            if (isCyrillic(ch))
            {
                score += 4;
            }
            else if (Character.isLetterOrDigit(ch))
            {
                score += 1;
            }
            else if (ch == '\uFFFD')
            {
                score -= 10;
            }

            if (i < value.length() - 1)
            {
                char next = value.charAt(i + 1);
                if ((ch == 'Р' || ch == 'С') && isCyrillic(next))
                {
                    suspiciousPairs++;
                }
                if ((ch == 'Ð' || ch == 'Ñ') && next >= '\u0080' && next <= '\u00FF')
                {
                    suspiciousPairs++;
                }
            }
        }

        score -= suspiciousPairs * 7;
        if (value.contains("Ã"))
        {
            score -= 12;
        }
        return score;
    }

    private static boolean isCyrillic(char ch)
    {
        return (ch >= '\u0400' && ch <= '\u04FF') || ch == 'Ё' || ch == 'ё';
    }

    private static String safeBody(HttpRequest request)
    {
        try
        {
            return request.bodyToString();
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private static String escapeShellSingleQuoted(String input)
    {
        return (input == null ? "" : input).replace("'", "'\"'\"'");
    }

    private static String escapePythonString(String input)
    {
        return (input == null ? "" : input)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String escapePythonTripleQuoted(String input)
    {
        return (input == null ? "" : input).replace("\"\"\"", "\\\"\\\"\\\"");
    }
}
