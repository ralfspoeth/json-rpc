package io.github.ralfspoeth.jsonrpc;


import jakarta.websocket.OnMessage;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.Map;

/**
 * A WebSocket endpoint base class that handles JSON-RPC 2.0 requests
 * delivered as text messages.
 * <p>
 * Concrete subclasses are expected to be annotated with
 * {@link jakarta.websocket.server.ServerEndpoint} (or registered programmatically)
 * and to provide a dispatcher mapping method names to {@link Service}
 * implementations through the constructor.
 * <p>
 * Each incoming text message is processed by an internal
 * {@link JsonRpcProcessor}, which performs validation, dispatching,
 * notification handling, batch processing and error wrapping
 * according to the JSON-RPC 2.0 specification.
 * <p>
 * If the incoming message is a notification (or a batch consisting solely
 * of notifications), the returned string is empty and no message is sent
 * back to the peer.
 *
 * @see JsonRpcServlet
 * @see JsonRpcProcessor
 * @see Service
 */
public abstract class JsonRpcWebSocket {

    private final JsonRpcProcessor jsonRpcProcessor;

    /**
     * Constructs a new JsonRpcWebSocket with the given service dispatcher.
     *
     * @param dispatcher A map where keys are method names and values are
     *                   {@link Service} instances handling the corresponding calls.
     */
    protected JsonRpcWebSocket(Map<String, Service> dispatcher) {
        this.jsonRpcProcessor = JsonRpcProcessor.of(dispatcher);
    }

    /**
     * Handles an incoming WebSocket text message containing a JSON-RPC 2.0
     * request, batch request, or notification.
     * <p>
     * The message is parsed and dispatched through the internal
     * {@link JsonRpcProcessor}. The serialized JSON-RPC response is returned
     * as a {@link String}; for pure notifications (single or batch) the
     * returned string is empty, and the WebSocket runtime will not send a
     * reply back to the peer.
     *
     * @param message the raw JSON text received from the WebSocket peer.
     * @return the serialized JSON-RPC response, or an empty string if the
     * request was a notification (or a batch of notifications).
     * @throws IOException if reading or writing the JSON payload fails.
     */
    @OnMessage
    public String onMessage(String message) throws IOException {
        try (var rdr = Reader.of(message); var wrtr = new StringWriter()) {
            jsonRpcProcessor.process(rdr, wrtr);
            return wrtr.toString();
        }
    }

}
