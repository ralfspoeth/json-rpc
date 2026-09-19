package io.github.ralfspoeth.greysonrpc;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.*;
import io.github.ralfspoeth.json.io.JsonParseException;
import io.github.ralfspoeth.json.query.Queries;

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
import java.util.function.Supplier;

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

    private static final LazyConstant<System.Logger> LOGGER = LazyConstant.of(
            () -> System.getLogger(GreysonRpcProcessor.class.getName())
    );

    private static final String VERSION = "2.0";

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
                    () -> writeError(out, -32700, () -> "Parse error: empty input")
            );
        } catch (JsonParseException e) {
            writeError(out, -32700, () -> "Parse error: " + e.getMessage());
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
            case JsonObject obj -> respond(obj).ifPresent(response -> Greyson.writeValue(out, response));
            // batch: requests are processed concurrently,
            // responses retain the order of their requests
            case JsonArray arr -> {
                if (arr.isEmpty()) { // rpc call with an empty array is invalid
                    writeError(out, -32600, () -> "Invalid Request: empty array");
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
                        writeError(out, -32000, () -> "Server error; %s batch processing interrupted"
                                .formatted(e.getMessage())
                        );
                    }
                }
            }
            // a top-level basic value is not a valid request
            default -> writeError(out, -32600, () -> "Invalid Request: Basic: " + value);
        }
    }

    private static void writeError(Writer out, int code, Supplier<String> messageSupplier) {
        var msg = messageSupplier.get();
        LOGGER.get().log(WARNING, "{0} ({1})", msg, code);
        Greyson.writeValue(out, error(JsonNull.INSTANCE, code, msg));
    }

    /**
     * Invoke the business function, logging the invocation at info
     * and its result at debug level.
     */
    private JsonValue invoke(String method, JsonValue params) {
        var log = LOGGER.get();
        log.log(INFO, "invoking {0} with params {1}", method, params);
        var result = businessFunction.apply(method, params);
        log.log(DEBUG, "invoked {0} with params {1}: {2}", method, params, result);
        return result;
    }

    /**
     * Process a single request and return the response,
     * or {@link Optional#empty()} for notifications.
     */
    private Optional<JsonObject> respond(JsonValue element) {
        if (!(element instanceof JsonObject request)) {
            LOGGER.get().log(WARNING, "invalid request, not an object: {0}", element);
            return Optional.of(error(JsonNull.INSTANCE, -32600, "Invalid Request"));
        }
        if (!isValidRequest(request)) {
            LOGGER.get().log(WARNING, "invalid request: {0}", request);
            var id = request.get("id").filter(GreysonRpcProcessor::isValidId).orElse(JsonNull.INSTANCE);
            return Optional.of(error(id, -32600, "Invalid Request"));
        }
        var method = request.get("method").flatMap(JsonValue::string).orElseThrow();
        var params = request.get("params").orElse(JsonNull.INSTANCE);
        if (!request.members().containsKey("id")) { // notification: invoke, never respond
            try {
                invoke(method, params);
            } catch (RuntimeException e) {
                // errors of notifications are never reported to the peer
                LOGGER.get().log(WARNING, "notification %s failed".formatted(method), e);
            }
            return Optional.empty();
        }
        var id = request.members().get("id");
        JsonObject response;
        try {
            response = result(id, invoke(method, params));
        } catch (NoSuchElementException | UnsupportedOperationException e) {
            LOGGER.get().log(WARNING, "method not found: %s".formatted(method), e);
            response = error(id, -32601, "Method not found");
        } catch (IllegalArgumentException e) {
            LOGGER.get().log(WARNING, "invalid params for %s: %s".formatted(method, params), e);
            response = error(id, -32602, "Invalid params");
        } catch (RuntimeException e) {
            LOGGER.get().log(WARNING, "internal error in %s".formatted(method), e);
            response = error(id, -32603, "Internal error");
        }
        return Optional.of(response);
    }

    private static boolean isValidRequest(JsonObject request) {
        return request.get("jsonrpc").flatMap(JsonValue::string).filter(VERSION::equals).isPresent()
                && request.get("method").flatMap(JsonValue::string).isPresent()
                && request.get("params").map(p -> p instanceof Aggregate).orElse(true)
                && (!request.members().containsKey("id") || isValidId(request.members().get("id")));
    }

    private static boolean isValidId(JsonValue id) {
        return id instanceof JsonString || id instanceof JsonNumber || id instanceof JsonNull;
    }

    private static JsonObject result(JsonValue id, JsonValue result) {
        return objectBuilder()
                .putBasic("jsonrpc", VERSION)
                .put("result", result)
                .put("id", id)
                .build();
    }

    private static JsonObject error(JsonValue id, int code, String message) {
        return objectBuilder()
                .putBasic("jsonrpc", VERSION)
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
