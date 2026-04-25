package burp.openapi;

import burp.api.montoya.core.Range;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import org.mockito.Mockito;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

final class MontoyaFactoryBootstrap
{
    private static boolean installed;

    private MontoyaFactoryBootstrap()
    {
    }

    static synchronized void install()
    {
        if (installed)
        {
            return;
        }

        MontoyaObjectFactory factory = Mockito.mock(MontoyaObjectFactory.class);
        when(factory.httpRequestFromUrl(anyString())).thenAnswer(invocation ->
                HttpRequestProxy.fromUrl(invocation.getArgument(0)));
        when(factory.httpRequest(anyString())).thenAnswer(invocation ->
                HttpRequestProxy.fromRaw(invocation.getArgument(0)));
        when(factory.httpResponse()).thenAnswer(invocation ->
                HttpResponseProxy.fromRaw("HTTP/1.1 200 OK\r\n\r\n"));
        when(factory.httpResponse(anyString())).thenAnswer(invocation ->
                HttpResponseProxy.fromRaw(invocation.getArgument(0)));
        when(factory.range(anyInt(), anyInt())).thenAnswer(invocation ->
                rangeProxy(invocation.getArgument(0), invocation.getArgument(1)));
        when(factory.requestOptions()).thenAnswer(invocation -> requestOptionsProxy());

        ObjectFactoryLocator.FACTORY = factory;
        installed = true;
    }

    private static Range rangeProxy(int start, int end)
    {
        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args)
            {
                return switch (method.getName())
                {
                    case "startIndexInclusive" -> start;
                    case "endIndexExclusive" -> end;
                    case "toString" -> "Range[" + start + "," + end + "]";
                    case "hashCode" -> Objects.hash(start, end);
                    case "equals" -> {
                        if (proxy == args[0])
                        {
                            yield true;
                        }
                        if (!(args[0] instanceof Range other))
                        {
                            yield false;
                        }
                        yield start == other.startIndexInclusive() && end == other.endIndexExclusive();
                    }
                    default -> defaultValue(method.getReturnType());
                };
            }
        };
        return (Range) Proxy.newProxyInstance(
                Range.class.getClassLoader(),
                new Class[]{Range.class},
                handler
        );
    }

    private static RequestOptions requestOptionsProxy()
    {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName())
        {
            case "withHttpMode", "withConnectionId", "withUpstreamTLSVerification",
                 "withRedirectionMode", "withServerNameIndicator", "withResponseTimeout" -> proxy;
            case "toString" -> "RequestOptions[test]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        };
        return (RequestOptions) Proxy.newProxyInstance(
                RequestOptions.class.getClassLoader(),
                new Class[]{RequestOptions.class},
                handler
        );
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive())
        {
            if (List.class.isAssignableFrom(type))
            {
                return List.of();
            }
            if (Optional.class.isAssignableFrom(type))
            {
                return Optional.empty();
            }
            return null;
        }
        if (type == boolean.class)
        {
            return false;
        }
        if (type == int.class)
        {
            return 0;
        }
        if (type == long.class)
        {
            return 0L;
        }
        if (type == short.class)
        {
            return (short) 0;
        }
        if (type == byte.class)
        {
            return (byte) 0;
        }
        if (type == float.class)
        {
            return 0f;
        }
        if (type == double.class)
        {
            return 0d;
        }
        if (type == char.class)
        {
            return '\0';
        }
        return null;
    }

    private static final class HttpRequestProxy implements InvocationHandler
    {
        private final String url;
        private final String method;
        private final List<HeaderValue> headers;
        private final String body;

        private HttpRequestProxy(String url, String method, List<HeaderValue> headers, String body)
        {
            this.url = normalizeUrl(url);
            this.method = Utils.coalesce(method, "GET");
            this.headers = new ArrayList<>(headers);
            this.body = body == null ? "" : body;
        }

        static HttpRequest fromUrl(String url)
        {
            URI uri = URI.create(normalizeUrl(url));
            String scheme = Utils.coalesce(uri.getScheme(), "http").toLowerCase(Locale.ROOT);
            String host = Utils.coalesce(uri.getHost(), uri.getAuthority(), "localhost");
            int port = uri.getPort();
            boolean defaultPort = (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80) || port == -1;
            String hostHeader = defaultPort ? host : host + ":" + port;

            List<HeaderValue> headers = new ArrayList<>();
            headers.add(new HeaderValue("Host", hostHeader));

            return proxy(new HttpRequestProxy(url, "GET", headers, ""));
        }

        static HttpRequest fromRaw(String raw)
        {
            if (raw == null)
            {
                return fromUrl("http://localhost/");
            }

            String[] sections = raw.split("\\r?\\n\\r?\\n", 2);
            String head = sections.length > 0 ? sections[0] : "";
            String body = sections.length > 1 ? sections[1] : "";
            String[] lines = head.split("\\r?\\n");

            String method = "GET";
            String target = "/";
            if (lines.length > 0)
            {
                String[] first = lines[0].split(" ");
                if (first.length >= 2)
                {
                    method = first[0];
                    target = first[1];
                }
            }

            Map<String, String> headersMap = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++)
            {
                int colon = lines[i].indexOf(':');
                if (colon > 0)
                {
                    headersMap.put(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim());
                }
            }

            String hostHeader = headersMap.getOrDefault("Host", "localhost");
            String baseUrl = "http://" + hostHeader;
            String url = baseUrl + (target.startsWith("/") ? target : "/" + target);

            List<HeaderValue> headers = new ArrayList<>();
            for (Map.Entry<String, String> entry : headersMap.entrySet())
            {
                headers.add(new HeaderValue(entry.getKey(), entry.getValue()));
            }
            if (headers.isEmpty())
            {
                headers.add(new HeaderValue("Host", hostHeader));
            }

            return proxy(new HttpRequestProxy(url, method, headers, body));
        }

        private static HttpRequest proxy(HttpRequestProxy state)
        {
            return (HttpRequest) Proxy.newProxyInstance(
                    HttpRequest.class.getClassLoader(),
                    new Class[]{HttpRequest.class},
                    state
            );
        }

        @Override
        public Object invoke(Object proxy, Method methodRef, Object[] args)
        {
            String name = methodRef.getName();
            return switch (name)
            {
                case "url" -> url;
                case "method" -> method;
                case "pathWithoutQuery" -> pathOnly(url);
                case "path" -> pathWithQuery(url);
                case "query" -> queryPart(url);
                case "headers" -> headers.stream().map(HeaderValue::toHeader).toList();
                case "bodyToString" -> body;
                case "bodyOffset" -> rawPrefixLength();
                case "toString" -> rawRequest();
                case "httpService" -> toHttpService(url);
                case "withMethod" -> proxy(new HttpRequestProxy(url, stringArg(args, 0), headers, body));
                case "withBody" -> proxy(new HttpRequestProxy(url, method, headers, Objects.toString(args[0], "")));
                case "withAddedHeader" -> proxy(withAddedHeader(args));
                case "withHeader" -> proxy(withHeader(args));
                case "withUpdatedHeader" -> proxy(withHeader(args));
                case "withRemovedHeader" -> proxy(withRemovedHeader(args));
                case "withPath" -> proxy(withPath(stringArg(args, 0)));
                case "headerValue" -> headerValue(stringArg(args, 0));
                case "header" -> header(stringArg(args, 0));
                case "hasHeader" -> hasHeader(args);
                case "hasParameters" -> false;
                case "parameters" -> Collections.emptyList();
                case "copyToTempFile", "withDefaultHeaders", "withMarkers",
                     "withAddedHeaders", "withUpdatedHeaders", "withRemovedHeaders",
                     "withAddedParameters", "withUpdatedParameters", "withRemovedParameters",
                     "withParameter", "withTransformationApplied", "withService" -> proxy;
                case "contains" -> contains(args);
                case "hashCode" -> Objects.hash(url, method, headers, body);
                case "equals" -> equalsRequest(args == null ? null : args[0]);
                default -> defaultValue(methodRef.getReturnType());
            };
        }

        private HttpRequestProxy withAddedHeader(Object[] args)
        {
            List<HeaderValue> next = new ArrayList<>(headers);
            if (args != null && args.length == 2 && args[0] instanceof String name && args[1] instanceof String value)
            {
                next.add(new HeaderValue(name, value));
            }
            else if (args != null && args.length == 1 && args[0] instanceof HttpHeader header)
            {
                next.add(new HeaderValue(header.name(), header.value()));
            }
            return new HttpRequestProxy(url, method, next, body);
        }

        private HttpRequestProxy withHeader(Object[] args)
        {
            if (args == null || args.length == 0)
            {
                return this;
            }

            String name;
            String value;
            if (args.length == 2 && args[0] instanceof String n && args[1] instanceof String v)
            {
                name = n;
                value = v;
            }
            else if (args.length == 1 && args[0] instanceof HttpHeader header)
            {
                name = header.name();
                value = header.value();
            }
            else
            {
                return this;
            }

            List<HeaderValue> next = new ArrayList<>();
            boolean replaced = false;
            for (HeaderValue header : headers)
            {
                if (header.name.equalsIgnoreCase(name) && !replaced)
                {
                    next.add(new HeaderValue(name, value));
                    replaced = true;
                }
                else
                {
                    next.add(header);
                }
            }
            if (!replaced)
            {
                next.add(new HeaderValue(name, value));
            }
            return new HttpRequestProxy(url, method, next, body);
        }

        private HttpRequestProxy withRemovedHeader(Object[] args)
        {
            if (args == null || args.length == 0)
            {
                return this;
            }

            String name = args[0] instanceof HttpHeader header ? header.name() : String.valueOf(args[0]);
            List<HeaderValue> next = headers.stream()
                    .filter(header -> !header.name.equalsIgnoreCase(name))
                    .toList();
            return new HttpRequestProxy(url, method, next, body);
        }

        private HttpRequestProxy withPath(String path)
        {
            URI uri = URI.create(url);
            String normalizedPath = Utils.nonBlank(path) ? path : "/";
            String withSlash = normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath;
            String rebuilt = uri.getScheme() + "://" + uri.getAuthority() + withSlash;
            return new HttpRequestProxy(rebuilt, method, headers, body);
        }

        private String headerValue(String name)
        {
            for (HeaderValue header : headers)
            {
                if (header.name.equalsIgnoreCase(name))
                {
                    return header.value;
                }
            }
            return null;
        }

        private HttpHeader header(String name)
        {
            for (HeaderValue header : headers)
            {
                if (header.name.equalsIgnoreCase(name))
                {
                    return header.toHeader();
                }
            }
            return null;
        }

        private boolean hasHeader(Object[] args)
        {
            if (args == null || args.length == 0)
            {
                return false;
            }

            if (args.length == 1 && args[0] instanceof String name)
            {
                return header(name) != null;
            }
            if (args.length == 2 && args[0] instanceof String name && args[1] instanceof String value)
            {
                return headers.stream().anyMatch(h -> h.name.equalsIgnoreCase(name) && Objects.equals(h.value, value));
            }
            if (args.length == 1 && args[0] instanceof HttpHeader header)
            {
                return headers.stream().anyMatch(h -> h.name.equalsIgnoreCase(header.name()) && Objects.equals(h.value, header.value()));
            }
            return false;
        }

        private boolean contains(Object[] args)
        {
            if (args == null || args.length == 0)
            {
                return false;
            }
            String haystack = rawRequest();
            if (args[0] instanceof String needle)
            {
                return haystack.contains(needle);
            }
            if (args[0] instanceof java.util.regex.Pattern pattern)
            {
                return pattern.matcher(haystack).find();
            }
            return false;
        }

        private boolean equalsRequest(Object other)
        {
            if (other == null)
            {
                return false;
            }
            if (Proxy.isProxyClass(other.getClass()))
            {
                InvocationHandler handler = Proxy.getInvocationHandler(other);
                if (handler instanceof HttpRequestProxy rhs)
                {
                    return Objects.equals(url, rhs.url)
                            && Objects.equals(method, rhs.method)
                            && Objects.equals(headers, rhs.headers)
                            && Objects.equals(body, rhs.body);
                }
            }
            return false;
        }

        private int rawPrefixLength()
        {
            return rawRequest().indexOf("\r\n\r\n") + 4;
        }

        private String rawRequest()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(pathWithQuery(url)).append(" HTTP/1.1\r\n");
            for (HeaderValue header : headers)
            {
                sb.append(header.name).append(": ").append(header.value).append("\r\n");
            }
            sb.append("\r\n");
            sb.append(body);
            return sb.toString();
        }

        private static String pathOnly(String inputUrl)
        {
            try
            {
                URI uri = URI.create(normalizeUrl(inputUrl));
                String path = uri.getRawPath();
                return Utils.nonBlank(path) ? path : "/";
            }
            catch (Exception ex)
            {
                return "/";
            }
        }

        private static String queryPart(String inputUrl)
        {
            try
            {
                URI uri = URI.create(normalizeUrl(inputUrl));
                return Utils.coalesce(uri.getRawQuery(), "");
            }
            catch (Exception ex)
            {
                return "";
            }
        }

        private static String pathWithQuery(String inputUrl)
        {
            String path = pathOnly(inputUrl);
            String query = queryPart(inputUrl);
            return Utils.nonBlank(query) ? path + "?" + query : path;
        }

        private static HttpService toHttpService(String inputUrl)
        {
            URI uri = URI.create(normalizeUrl(inputUrl));
            String scheme = Utils.coalesce(uri.getScheme(), "http");
            boolean secure = "https".equalsIgnoreCase(scheme);
            String host = Utils.coalesce(uri.getHost(), uri.getAuthority(), "localhost");
            int port = uri.getPort();
            if (port < 0)
            {
                port = secure ? 443 : 80;
            }

            final int finalPort = port;
            final boolean finalSecure = secure;
            final String finalHost = host;

            InvocationHandler handler = (proxy, method, args) -> switch (method.getName())
            {
                case "host" -> finalHost;
                case "port" -> finalPort;
                case "secure" -> finalSecure;
                case "toString" -> (finalSecure ? "https://" : "http://") + finalHost + ":" + finalPort;
                case "equals" -> proxy == args[0];
                case "hashCode" -> Objects.hash(finalHost, finalPort, finalSecure);
                default -> defaultValue(method.getReturnType());
            };

            return (HttpService) Proxy.newProxyInstance(
                    HttpService.class.getClassLoader(),
                    new Class[]{HttpService.class},
                    handler
            );
        }

        private static String normalizeUrl(String inputUrl)
        {
            if (Utils.isBlank(inputUrl))
            {
                return "http://localhost/";
            }
            if (inputUrl.startsWith("http://") || inputUrl.startsWith("https://"))
            {
                return inputUrl;
            }
            if (inputUrl.startsWith("/"))
            {
                return "http://localhost" + inputUrl;
            }
            return "http://" + inputUrl;
        }

        private static String stringArg(Object[] args, int index)
        {
            if (args == null || index >= args.length)
            {
                return "";
            }
            return String.valueOf(args[index]);
        }

        private record HeaderValue(String name, String value)
        {
            HttpHeader toHeader()
            {
                InvocationHandler handler = (proxy, method, args) -> switch (method.getName())
                {
                    case "name" -> name;
                    case "value" -> value;
                    case "toString" -> name + ": " + value;
                    case "hashCode" -> Objects.hash(name.toLowerCase(Locale.ROOT), value);
                    case "equals" -> {
                        if (proxy == args[0])
                        {
                            yield true;
                        }
                        if (!(args[0] instanceof HttpHeader header))
                        {
                            yield false;
                        }
                        yield name.equalsIgnoreCase(header.name()) && Objects.equals(value, header.value());
                    }
                    default -> defaultValue(method.getReturnType());
                };
                return (HttpHeader) Proxy.newProxyInstance(
                        HttpHeader.class.getClassLoader(),
                        new Class[]{HttpHeader.class},
                        handler
                );
            }
        }
    }

    private static final class HttpResponseProxy implements InvocationHandler
    {
        private final String httpVersion;
        private final short statusCode;
        private final String reasonPhrase;
        private final List<ResponseHeaderValue> headers;
        private final String body;

        private HttpResponseProxy(
                String httpVersion,
                short statusCode,
                String reasonPhrase,
                List<ResponseHeaderValue> headers,
                String body)
        {
            this.httpVersion = Utils.coalesce(httpVersion, "HTTP/1.1");
            this.statusCode = statusCode;
            this.reasonPhrase = Utils.coalesce(reasonPhrase, "OK");
            this.headers = new ArrayList<>(headers);
            this.body = body == null ? "" : body;
        }

        static HttpResponse fromRaw(String raw)
        {
            String safeRaw = raw == null ? "HTTP/1.1 200 OK\r\n\r\n" : raw;
            String[] sections = safeRaw.split("\\r?\\n\\r?\\n", 2);
            String head = sections.length > 0 ? sections[0] : "";
            String body = sections.length > 1 ? sections[1] : "";
            String[] lines = head.split("\\r?\\n");

            String httpVersion = "HTTP/1.1";
            short statusCode = 200;
            String reasonPhrase = "OK";
            if (lines.length > 0)
            {
                String[] first = lines[0].split(" ", 3);
                if (first.length >= 1 && Utils.nonBlank(first[0]))
                {
                    httpVersion = first[0];
                }
                if (first.length >= 2)
                {
                    try
                    {
                        statusCode = Short.parseShort(first[1]);
                    }
                    catch (NumberFormatException ignored)
                    {
                        statusCode = 200;
                    }
                }
                if (first.length >= 3)
                {
                    reasonPhrase = first[2];
                }
            }

            List<ResponseHeaderValue> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++)
            {
                int colon = lines[i].indexOf(':');
                if (colon > 0)
                {
                    headers.add(new ResponseHeaderValue(lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim()));
                }
            }

            return proxy(new HttpResponseProxy(httpVersion, statusCode, reasonPhrase, headers, body));
        }

        private static HttpResponse proxy(HttpResponseProxy state)
        {
            return (HttpResponse) Proxy.newProxyInstance(
                    HttpResponse.class.getClassLoader(),
                    new Class[]{HttpResponse.class},
                    state
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            return switch (method.getName())
            {
                case "statusCode" -> statusCode;
                case "reasonPhrase" -> reasonPhrase;
                case "httpVersion" -> httpVersion;
                case "bodyToString" -> body;
                case "headers" -> headers.stream().map(ResponseHeaderValue::toHeader).toList();
                case "headerValue" -> headerValue(String.valueOf(args[0]));
                case "header" -> header(String.valueOf(args[0]));
                case "hasHeader" -> hasHeader(args);
                case "toString" -> rawResponse();
                case "withStatusCode" -> proxy(new HttpResponseProxy(httpVersion, (Short) args[0], reasonPhrase, headers, body));
                case "withReasonPhrase" -> proxy(new HttpResponseProxy(httpVersion, statusCode, String.valueOf(args[0]), headers, body));
                case "withHttpVersion" -> proxy(new HttpResponseProxy(String.valueOf(args[0]), statusCode, reasonPhrase, headers, body));
                case "withBody" -> proxy(new HttpResponseProxy(httpVersion, statusCode, reasonPhrase, headers, String.valueOf(args[0])));
                case "withAddedHeader", "withUpdatedHeader" -> proxy(withHeader(args));
                case "hashCode" -> Objects.hash(httpVersion, statusCode, reasonPhrase, headers, body);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private HttpResponseProxy withHeader(Object[] args)
        {
            List<ResponseHeaderValue> next = new ArrayList<>(headers);
            if (args != null && args.length == 2)
            {
                String name = String.valueOf(args[0]);
                next.removeIf(header -> header.name.equalsIgnoreCase(name));
                next.add(new ResponseHeaderValue(name, String.valueOf(args[1])));
            }
            return new HttpResponseProxy(httpVersion, statusCode, reasonPhrase, next, body);
        }

        private boolean hasHeader(Object[] args)
        {
            if (args == null || args.length == 0)
            {
                return false;
            }
            if (args.length == 1 && args[0] instanceof String name)
            {
                return header(name) != null;
            }
            if (args.length == 2 && args[0] instanceof String name && args[1] instanceof String value)
            {
                return headers.stream().anyMatch(header -> header.name.equalsIgnoreCase(name) && Objects.equals(header.value, value));
            }
            if (args.length == 1 && args[0] instanceof HttpHeader header)
            {
                return headers.stream().anyMatch(h -> h.name.equalsIgnoreCase(header.name()) && Objects.equals(h.value, header.value()));
            }
            return false;
        }

        private String headerValue(String name)
        {
            ResponseHeaderValue header = headerValueRecord(name);
            return header == null ? null : header.value;
        }

        private HttpHeader header(String name)
        {
            ResponseHeaderValue header = headerValueRecord(name);
            return header == null ? null : header.toHeader();
        }

        private ResponseHeaderValue headerValueRecord(String name)
        {
            for (ResponseHeaderValue header : headers)
            {
                if (header.name.equalsIgnoreCase(name))
                {
                    return header;
                }
            }
            return null;
        }

        private String rawResponse()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(httpVersion).append(' ').append(statusCode).append(' ').append(reasonPhrase).append("\r\n");
            for (ResponseHeaderValue header : headers)
            {
                sb.append(header.name).append(": ").append(header.value).append("\r\n");
            }
            sb.append("\r\n").append(body);
            return sb.toString();
        }

        private record ResponseHeaderValue(String name, String value)
        {
            HttpHeader toHeader()
            {
                InvocationHandler handler = (proxy, method, args) -> switch (method.getName())
                {
                    case "name" -> name;
                    case "value" -> value;
                    case "toString" -> name + ": " + value;
                    case "hashCode" -> Objects.hash(name.toLowerCase(Locale.ROOT), value);
                    case "equals" -> {
                        if (proxy == args[0])
                        {
                            yield true;
                        }
                        if (!(args[0] instanceof HttpHeader header))
                        {
                            yield false;
                        }
                        yield name.equalsIgnoreCase(header.name()) && Objects.equals(value, header.value());
                    }
                    default -> defaultValue(method.getReturnType());
                };
                return (HttpHeader) Proxy.newProxyInstance(
                        HttpHeader.class.getClassLoader(),
                        new Class[]{HttpHeader.class},
                        handler
                );
            }
        }
    }
}
