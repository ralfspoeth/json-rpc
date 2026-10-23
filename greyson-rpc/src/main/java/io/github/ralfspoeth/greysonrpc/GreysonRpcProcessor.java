package io.github.ralfspoeth.greysonrpc;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.*;
import io.github.ralfspoeth.json.io.JsonParseException;
import io.github.ralfspoeth.json.query.Queries;
import io.github.ralfspoeth.json.query.Shape;
import io.github.ralfspoeth.log.api.Log;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BiFunction;

import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static java.lang.System.Logger.Level.*;

/**
 * A JSON-RPC 2.0 server-side processor.
 * <p>
 * The incoming request is read into an immutable {@link JsonValue} and
 * each response is built afresh: the {@code id} of a valid request is
 * carried over, and either {@code result} or {@code error} is added.
 * The requests of a batch are processed concurrently using structured
 * concurrency; their responses retain the order of the requests, with
 * notifications dropped.
 * <p>
 * The business function receives the method name and the {@code params}
 * value ({@link JsonNull#INSTANCE} if absent) and returns the result as
 * a {@link JsonValue}, never {@code null} &mdash; use {@link JsonNull#INSTANCE}
 * for a null result. Exceptions map to
 * spec error codes: {@link NoSuchElementException}/{@link UnsupportedOperationException}
 * &rarr; -32601 (method not found), {@link IllegalArgumentException}
 * &rarr; -32602 (invalid params), any other {@link RuntimeException}
 * &rarr; -32603 (internal error).
 */
public class GreysonRpcProcessor {

    private static final Shape REQUEST_SHAPE = Shape.object()
            .and(Shape.member("jsonrpc", Shape.exact(Basic.of("2.0"))))
            .and(Shape.member("method", Shape.string()))
            .and(Shape.optional("id", Shape.number().or(Shape.string())))
            .and(Shape.optional("params", Shape.array().or(Shape.object())));
    
    private static final Shape BATCH_SHAPE = Shape.array().and(Shape.each(REQUEST_SHAPE));
    
    private static final Shape VALID_SHAPE = REQUEST_SHAPE.or(BATCH_SHAPE);

    private final BiFunction<String, JsonValue, JsonValue> businessFunction;

    public GreysonRpcProcessor(BiFunction<String, JsonValue, JsonValue> businessFunction) {
        this.businessFunction = Objects.requireNonNull(businessFunction);
    }

    /**
     * Read a single request or a batch of requests from {@code in},
     * process it, and write the response(s) to {@code out}.
     * Writes nothing at all if the input consists of notifications only.
     */
    public void process(Reader in, Writer out) throws IOException {
        try {
            Greyson.readValue(in).ifPresentOrElse(
                    value -> dispatch(value, out),
                    // empty input
                    () -> writeError(out, -32700, "Parse error: empty input")
            );
        } catch (JsonParseException e) {
            writeError(out, -32700, "Parse error: " + e.getMessage());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Dispatch on the shape of the input: a single request, a batch,
     * or an invalid top-level basic value.
     */
    private void dispatch(JsonValue value, Writer out) {
        switch (value) {
            // single request
            case JsonObject obj -> respond(obj)
                    .ifPresent(response -> Greyson.writeValue(out, response));
            // batch: requests are processed concurrently,
            // responses retain the order of their requests
            case JsonArray arr -> {
                if (arr.isEmpty()) { // rpc call with an empty array is invalid
                    writeError(out, -32600, "Invalid Request: empty array");
                } else {
                    try (var scope = StructuredTaskScope.open()) {
                        var subtasks = arr.elements()
                                .stream()
                                .map(element -> scope.fork(() -> respond(element)))
                                .toList();
                        scope.join();
                        var responses = subtasks.stream()
                                .map(StructuredTaskScope.Subtask::get)
                                .flatMap(Optional::stream)
                                .collect(Queries.toJsonArray());
                        if (!responses.isEmpty()) {
                            Greyson.writeValue(out, responses);
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        Thread.currentThread().interrupt();
                        writeError(out, -32000, "Server error; %s batch processing interrupted"
                                .formatted(e.getMessage())
                        );
                    }
                }
            }
            // A top-level basic value is neither a request nor a batch, so here
            // - and only here - the whole input is wrong and VALID_SHAPE can say
            // what was expected. It cannot gate the two cases above: BATCH_SHAPE
            // requires every element to be valid, while the spec answers a batch
            // element by element, so a mixed batch must reach respond() per
            // element rather than be rejected whole.
            default -> writeError(out, -32600,
                    "Invalid Request: " + VALID_SHAPE.violations(value).toList());
        }
    }

    @Log(level = WARNING, logReturn = false)
    private static void writeError(Writer out, int code, String message) {
        Greyson.writeValue(out, error(JsonNull.INSTANCE, code, message));
    }

    /** Invoke the business function; the weaver logs the call and its result. */
    @Log(level = DEBUG)
    private JsonValue invoke(String method, JsonValue params) {
        return businessFunction.apply(method, params);
    }

    /**
     * Process a single request and return the response,
     * or {@link Optional#empty()} for notifications.
     */
    // DEBUG, not WARNING: the entry log fires on every call, so logging it at
    // WARNING would make a perfectly good request warn. Failures are reported by
    // the weaver's Throwable catch on invoke() and by error()'s own log.
    @Log(level = DEBUG, logReturn = false)
    private Optional<JsonObject> respond(JsonValue element) {
        if (REQUEST_SHAPE.predicate().test(element)) {
            var method = element.get("method").flatMap(JsonValue::string).orElseThrow();
            var params = element.get("params").orElse(JsonNull.INSTANCE);
            // absent id means notification: no response, whatever happens
            var id = element.get("id");
            try {
                var result = invoke(method, params);
                return id.map(v -> result(v, result));
            } catch (RuntimeException e) {
                // a business function is not trusted to be total; its failure is
                // this peer's problem to report, not an exception to escape into
                // whoever happened to call process()
                if (id.isEmpty()) {
                    return Optional.empty();
                }
                var failure = failureOf(method, e);
                return id.map(v -> error(v, failure.code(), failure.message()));
            }
        } else {
            // An invalid request is still answered: only a *valid* request
            // without an id is a notification. Returning empty here would make
            // every malformed request look like one and leave the peer waiting.
            var violations = REQUEST_SHAPE.violations(element).toList();
            // Echo the id when it is usable, null when it is not: the spec
            // requires a null id exactly when the id cannot be determined.
            var id = element.get("id")
                    .filter(GreysonRpcProcessor::isValidId)
                    .orElse(JsonNull.INSTANCE);
            return Optional.of(error(id, -32600, "Invalid Request: " + violations));
        }
    }

    /** A business-function failure rendered as a JSON-RPC error code and message. */
    private record Failure(int code, String message) {}

    /**
     * Map an exception out of the business function onto the JSON-RPC error
     * codes. Only the two cases a handler can signal meaningfully are singled
     * out; everything else is the server's fault by definition.
     */
    private static Failure failureOf(String method, RuntimeException e) {
        return switch (e) {
            // -32601: the handler does not know this method
            case NoSuchElementException _ -> new Failure(-32601, "method not found: " + method);
            // -32602: it knows the method but not these arguments
            case IllegalArgumentException _ -> new Failure(-32602, "invalid params for " + method);
            // -32603: anything else is an internal error
            default -> new Failure(-32603, "internal error in " + method);
        };
    }

    private static boolean isValidId(JsonValue id) {
        return id instanceof JsonString || id instanceof JsonNumber || id instanceof JsonNull;
    }

    @Log(level = DEBUG)
    private static JsonObject result(JsonValue id, JsonValue result) {
        return objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .put("result", result)
                .put("id", id)
                .build();
    }

    // WARNING, not ERROR: a malformed request or a rejected call is the peer's
    // fault, not a server fault, and ERROR maps to JUL SEVERE. The entry log
    // carries code and message, which is where the -326xx diagnostics surface.
    @Log(level = WARNING, logReturn = false)
    private static JsonObject error(JsonValue id, int code, String message) {
        return objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .put("error", errorObject(code, message))
                .put("id", id)
                .build();
    }

    private static JsonObject errorObject(int code, String message) {
        return objectBuilder()
                .putBasic("code", code)
                .putBasic("message", message)
                .build();
    }
}
