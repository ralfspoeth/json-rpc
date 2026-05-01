package io.github.ralfspoeth.jsonrpc;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.*;
import io.github.ralfspoeth.json.io.JsonParseException;
import io.github.ralfspoeth.json.query.Queries;
import io.github.ralfspoeth.json.query.Selector;
import io.github.ralfspoeth.utf8.Utf8Reader;
import io.github.ralfspoeth.utf8.Utf8Writer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.data.Builder.objectBuilder;

/**
 * A servlet that handles JSON-RPC 2.0 requests.
 * It dispatches requests to registered {@link Service} implementations.
 */
public class JsonRpcServlet extends HttpServlet {

    /**
     * The MIME type for JSON content.
     */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /**
     * The map of service implementations indexed by method name.
     */
    private final Map<String, Service> dispatcher;

    /**
     * Constructs a new JsonRpcServlet with the given service dispatcher.
     *
     * @param dispatcher A map where keys are method names and values are {@link Service} instances.
     */
    public JsonRpcServlet(Map<String, Service> dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Handles HTTP POST requests for JSON-RPC.
     * It reads the JSON-RPC request from the input stream, processes it, and writes the response to the output stream.
     *
     * @param req  The HttpServletRequest object that contains the request the client has made of the servlet.
     * @param resp The HttpServletResponse object that contains the response the servlet sends to the client.
     * @throws ServletException If an input or output error is detected when the servlet handles the request.
     * @throws IOException      If the request for the POST could not be handled.
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (JSON_CONTENT_TYPE.equals(req.getContentType())) {
            resp.setContentType(JSON_CONTENT_TYPE);
            try (var is = req.getInputStream();
                 var rdr = new Utf8Reader(is);
                 var os = resp.getOutputStream();
                 var wrt = new Utf8Writer(os)
            ) {
                try {
                    var request = Greyson
                            .readValue(rdr)
                            .orElseThrow(() -> new JsonParseException("empty input", 0, 0));

                    // empty batch request
                    if (request instanceof JsonArray(var l) && l.isEmpty()) {
                        Greyson.writeValue(wrt, invalidRequest("Empty Batch Request"));
                    } else {
                        // invoke the services for each valid request in parallel
                        var responses = Stream.of(request)
                                .flatMap(Selector.all()) // unfolds a batch request
                                .parallel()
                                .map(r -> isValid(r) ?
                                        invokeService(r) :
                                        invalidRequest("Invalid request object")
                                )
                                .filter(Objects::nonNull) // return value from notifications
                                .toList();

                        // no empty arrays
                        if (!responses.isEmpty()) {
                            // as array if and only if the request has been a batch request
                            if (request instanceof JsonArray) {
                                Greyson.writeValue(wrt, responses.stream().collect(Queries.toJsonArray()));
                            } else {
                                assert responses.size() == 1;
                                Greyson.writeValue(wrt, responses.getFirst());
                            }
                        }
                    }
                }
                // parse exception with code -32700
                catch (JsonParseException e) {
                    Greyson.writeValue(wrt, parseError(e));
                }
            }
        } else {
            resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private static JsonObject parseError(JsonParseException e) {
        return objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", null)
                .put("error", objectBuilder()
                        .putBasic("code", -32700)
                        .putBasic("message", e.getMessage())
                ).build();
    }

    /**
     * Checks if a given {@link JsonValue} represents a valid JSON-RPC request.
     *
     * @param request The {@link JsonValue} to validate.
     * @return {@code true} if the request is valid, {@code false} otherwise.
     */
    private static boolean isValid(JsonValue request) {
        return request instanceof JsonObject(var members) &&
                request.get("jsonrpc").flatMap(JsonValue::string).orElse("").equals("2.0") &&
                request.get("method").filter(JsonString.class::isInstance).isPresent() &&
                isValidOrNullId(members.get("id")) &&
                isValidOrNullParams(members.get("params"));
    }

    /**
     * Creates an {@link JsonObject} representing an "Invalid Request" error response.
     *
     * @return An {@link JsonObject} with error code -32600.
     */
    private static JsonObject invalidRequest(String message) {
        return objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", null)
                .put("error", objectBuilder()
                        .putBasic("code", -32600)
                        .putBasic("message", message))
                .build();
    }

    /**
     * Invokes the appropriate service method based on the JSON-RPC request.
     *
     * @param request The {@link JsonValue} representing the JSON-RPC request.
     * @return A {@link JsonObject} representing the JSON-RPC response, or {@code null} if it's a notification.
     */
    private @Nullable JsonObject invokeService(JsonValue request) {
        var method = method(request);
        var id = id(request);
        var params = params(request);
        var service = dispatcher.get(method);

        // notification
        if (id == null) {
            if (service != null) try {
                service.notification(params);
            } catch (Exception ex) {
                getServletContext().log("method " + method + " call failed", ex);
            }
            else {
                getServletContext().log("method " + method + " not found");
            }
            return null;
        } else {
            var ob = objectBuilder()
                    .putBasic("jsonrpc", "2.0")
                    .putBasic("id", id);
            if (service == null) {
                ob.put("error", objectBuilder()
                        .putBasic("code", -32601)
                        .putBasic("message", "No such method: " + method));
            } else {
                try {
                    var result = service.request(params);
                    ob.putBasic("result", result);

                } catch (Exception ex) {
                    ob.put("error", objectBuilder()
                            .putBasic("code", -32000)
                            .putBasic("message", ex.getMessage()));
                }
            }
            return ob.build();
        }
    }

    /**
     * Extracts the method name from a JSON-RPC request.
     *
     * @param request The {@link JsonValue} representing the JSON-RPC request.
     * @return The method name as a {@link String}.
     * @throws java.util.NoSuchElementException If the "method" field is not found.
     */
    private static String method(JsonValue request) {
        return request.get("method")
                .flatMap(JsonValue::string)
                .orElseThrow();
    }

    /**
     * Extracts the ID from a JSON-RPC request.
     *
     * @param request The {@link JsonValue} representing the JSON-RPC request.
     * @return The ID as an {@link Object} (either a {@link String} or a {@link Number}), or {@code null} if not present.
     */
    private static @Nullable Object id(JsonValue request) {
        return request.get("id")
                .flatMap(id -> switch (id) {
                    case JsonNumber(var n) -> Optional.of(n);
                    case JsonString(var s) -> Optional.of(s);
                    default -> Optional.empty();
                })
                .orElse(null);
    }

    /**
     * Extracts the parameters from a JSON-RPC request.
     *
     * @param request The {@link JsonValue} representing the JSON-RPC request.
     * @return The parameters as an {@link Object}, or {@code null} if not present.
     */
    private static @Nullable Object params(JsonValue request) {
        return request.get("params")
                .map(Queries::asObject)
                .orElse(null);
    }

    /**
     * Checks if the "params" field in a JSON-RPC request is valid or null.
     * Valid parameters can be an {@link Aggregate} (JsonArray or JsonObject) or {@code null}.
     *
     * @param params The {@link JsonValue} representing the "params" field.
     * @return {@code true} if the parameters are valid or null, {@code false} otherwise.
     */
    private static boolean isValidOrNullParams(@Nullable JsonValue params) {
        return params == null || params instanceof Aggregate;
    }

    /**
     * Checks if the "id" field in a JSON-RPC request is valid or null.
     * Valid IDs can be a {@link JsonNumber}, {@link JsonString}, or {@link JsonNull}.
     *
     * @param id The {@link JsonValue} representing the "id" field.
     * @return {@code true} if the ID is valid or null, {@code false} otherwise.
     */
    private static boolean isValidOrNullId(@Nullable JsonValue id) {
        return id == null || id instanceof JsonNumber || id instanceof JsonString || id instanceof JsonNull;
    }
}
