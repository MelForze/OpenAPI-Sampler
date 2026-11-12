package burp.openapi;

import burp.IBurpExtenderCallbacks;
import burp.IScanQueueItem;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class ActiveScanQueueTest
{
    @Test
    void submitsExactBytesWithoutConfiguringOrRetainingTasks()
    {
        IBurpExtenderCallbacks callbacks = mock(IBurpExtenderCallbacks.class);
        IScanQueueItem item = mock(IScanQueueItem.class);
        HttpRequest request = mock(HttpRequest.class);
        HttpService service = mock(HttpService.class);
        ByteArray bytes = mock(ByteArray.class);
        // A string conversion would corrupt the binary payload bytes.
        byte[] wireBytes = new byte[]{'P', 'O', 'S', 'T', '\r', '\n', 0, (byte) 0xff, (byte) 0x80};
        when(request.httpService()).thenReturn(service);
        when(request.toByteArray()).thenReturn(bytes);
        when(bytes.getBytes()).thenReturn(wireBytes);
        when(service.host()).thenReturn("scan.example");
        when(service.port()).thenReturn(8443);
        when(service.secure()).thenReturn(true);
        when(callbacks.doActiveScan(anyString(), anyInt(), anyBoolean(), any(byte[].class))).thenReturn(item);
        ActiveScanQueue queue = new ActiveScanQueue();
        queue.initialize(callbacks);

        queue.submit(request);
        queue.submit(request);
        queue.close();

        verify(callbacks, times(2)).doActiveScan(eq("scan.example"), eq(8443), eq(true), same(wireBytes));
        verifyNoMoreInteractions(callbacks);
        verifyNoInteractions(item);
    }

    @Test
    void missingCallbacksFailWithActionableError()
    {
        ActiveScanQueue queue = new ActiveScanQueue();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.submit(mock(HttpRequest.class)));
        assertTrue(failure.getMessage().contains("Reload the extension"));
    }

    @Test
    void unloadClosesQueueAndLateInitializationCannotReopenIt()
    {
        ActiveScanQueue queue = new ActiveScanQueue();
        IBurpExtenderCallbacks callbacks = mock(IBurpExtenderCallbacks.class);
        queue.initialize(callbacks);
        queue.close();
        queue.initialize(callbacks);

        assertThrows(IllegalStateException.class, () -> queue.submit(mock(HttpRequest.class)));
        verifyNoInteractions(callbacks);
    }

    @Test
    void rejectedRequestDoesNotCountAsQueued()
    {
        IBurpExtenderCallbacks callbacks = mock(IBurpExtenderCallbacks.class);
        ActiveScanQueue queue = new ActiveScanQueue();
        queue.initialize(callbacks);
        HttpRequest request = mock(HttpRequest.class);
        HttpService service = mock(HttpService.class);
        ByteArray bytes = mock(ByteArray.class);
        when(request.httpService()).thenReturn(service);
        when(request.toByteArray()).thenReturn(bytes);
        when(bytes.getBytes()).thenReturn(new byte[0]);
        when(service.host()).thenReturn("scan.example");
        when(service.port()).thenReturn(80);

        assertThrows(IllegalStateException.class, () -> queue.submit(request));
        verify(callbacks).doActiveScan(eq("scan.example"), eq(80), eq(false), any(byte[].class));
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    void unloadDuringSerializationPreventsSubmission()
    {
        IBurpExtenderCallbacks callbacks = mock(IBurpExtenderCallbacks.class);
        ActiveScanQueue queue = new ActiveScanQueue();
        queue.initialize(callbacks);
        HttpRequest request = mock(HttpRequest.class);
        ByteArray bytes = mock(ByteArray.class);
        when(request.httpService()).thenReturn(mock(HttpService.class));
        when(request.toByteArray()).thenReturn(bytes);
        when(bytes.getBytes()).thenAnswer(invocation -> {
            queue.close();
            return new byte[0];
        });

        assertThrows(IllegalStateException.class, () -> queue.submit(request));
        verifyNoInteractions(callbacks);
    }

    @Test
    void interruptedWorkerDoesNotSubmitAnotherRequest()
    {
        IBurpExtenderCallbacks callbacks = mock(IBurpExtenderCallbacks.class);
        ActiveScanQueue queue = new ActiveScanQueue();
        queue.initialize(callbacks);
        Thread.currentThread().interrupt();
        try
        {
            assertThrows(IllegalStateException.class, () -> queue.submit(mock(HttpRequest.class)));
            verifyNoInteractions(callbacks);
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
        }
    }
}
