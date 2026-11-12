package burp.openapi;

import burp.IBurpExtenderCallbacks;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.Objects;

/**
 * Submits through Burp's public legacy API without creating or configuring a Montoya Audit.
 * Burp may place these requests in its Extension driven active audit task, separate from
 * the Active scans task used by native UI actions.
 * Montoya does not expose an equivalent of IBurpExtenderCallbacks.doActiveScan().
 */
final class ActiveScanQueue implements AutoCloseable
{
    private volatile IBurpExtenderCallbacks callbacks;
    private volatile boolean closed;

    synchronized void initialize(IBurpExtenderCallbacks callbacks)
    {
        if (!closed)
        {
            this.callbacks = Objects.requireNonNull(callbacks, "callbacks must not be null");
        }
    }

    void submit(HttpRequest request)
    {
        IBurpExtenderCallbacks current = callbacks;
        if (closed || Thread.currentThread().isInterrupted())
        {
            throw new IllegalStateException("Extension unloaded or active scan submission interrupted");
        }
        if (current == null)
        {
            throw new IllegalStateException("Burp active scan queue is unavailable. Reload the extension from the latest JAR.");
        }

        HttpService service = Objects.requireNonNull(request.httpService(), "Scan request must have an HTTP service");
        // Preserve the exact request bytes, including authentication, headers and binary payloads.
        // Burp owns the extension-driven task configuration and any out-of-scope confirmation.
        String host = service.host();
        int port = service.port();
        boolean secure = service.secure();
        byte[] bytes = request.toByteArray().getBytes();
        // Serialization can race with unload. Do not submit a request prepared after closure.
        if (closed || Thread.currentThread().isInterrupted())
        {
            throw new IllegalStateException("Extension unloaded or active scan submission interrupted");
        }
        if (current.doActiveScan(host, port, secure, bytes) == null)
        {
            throw new IllegalStateException("Burp did not accept the active scan request. Check Scanner availability and any scope confirmation.");
        }
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        callbacks = null;
        // Scan queue items belong to the user; do not cancel scans or delete results on unload.
    }
}
