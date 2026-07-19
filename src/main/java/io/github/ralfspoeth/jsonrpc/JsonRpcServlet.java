package io.github.ralfspoeth.jsonrpc;

import io.github.ralfspoeth.utf8.Utf8Reader;
import io.github.ralfspoeth.utf8.Utf8Writer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * A servlet that handles JSON-RPC 2.0 requests.
 * It dispatches requests to registered {@link Service} implementations.
 */
public abstract class JsonRpcServlet extends HttpServlet {

    /**
     * The MIME type for JSON content.
     */
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final JsonRpcProcessor jsonRpcProcessor;

    /**
     * Constructs a new JsonRpcServlet with the given service dispatcher.
     *
     * @param dispatcher A map where keys are method names and values are {@link Service} instances.
     */
    public JsonRpcServlet(Map<String, Service> dispatcher) {
        jsonRpcProcessor = JsonRpcProcessor.of(dispatcher);
    }

    /**
     * Handles HTTP POST requests for JSON-RPC.
     * It reads the JSON-RPC request from the input stream, processes it, and writes the response to the output stream.
     *
     * @param req  The HttpServletRequest object that contains the request the client has made of the servlet.
     * @param resp The HttpServletResponse object that contains the response the servlet sends to the client.
     * @throws IOException      If the request for the POST could not be handled.
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (JSON_CONTENT_TYPE.equals(req.getContentType())) {
            resp.setContentType(JSON_CONTENT_TYPE);
            try (var rdr = new Utf8Reader(req.getInputStream());
                 var wrt = new Utf8Writer(resp.getOutputStream())
            ) {
                jsonRpcProcessor.process(rdr, wrt);
            }
        } else {
            resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        }
    }
}
