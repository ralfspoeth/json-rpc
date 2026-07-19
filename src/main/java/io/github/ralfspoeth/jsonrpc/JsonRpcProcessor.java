package io.github.ralfspoeth.jsonrpc;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.*;
import io.github.ralfspoeth.json.io.JsonParseException;
import io.github.ralfspoeth.json.query.Queries;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;

import static io.github.ralfspoeth.json.data.Builder.objectBuilder;

/**
 * A JSON-RPC 2.0 server-side processor.
 * <p>
 * The incoming request is read into a {@link Builder} which is then
 * transformed <em>in place</em> into the response: the {@code jsonrpc}
 * and {@code id} members of a valid request are retained, {@code method}
 * and {@code params} are removed, and either {@code result} or
 * {@code error} is added.
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
public class JsonRpcProcessor {
    private static final String VERSION = "2.0";

    private final BiFunction<String, JsonValue, JsonValue> businessFunction;

    public JsonRpcProcessor(BiFunction<String, JsonValue, JsonValue> businessFunction) {
        this.businessFunction = Objects.requireNonNull(businessFunction);
    }

    /**
     * Creates a processor that dispatches to {@link Service} implementations
     * by method name. Unknown methods map to -32601 (method not found);
     * runtime exceptions thrown by services propagate unwrapped so the
     * spec error-code mapping documented on this class applies.
     *
     * @param dispatcher a map from method names to {@link Service} instances
     */
    public static JsonRpcProcessor of(Map<String, Service> dispatcher) {
        Objects.requireNonNull(dispatcher);
        return new JsonRpcProcessor((method, params) -> {
            var service = dispatcher.get(method);
            if (service == null) {
                throw new NoSuchElementException("Method not found: " + method);
            }
            try {
                return JsonValue.of(service.request(Queries.asObject(params)));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Read a single request or a batch of requests from {@code in},
     * process it, and write the response(s) to {@code out}.
     * Writes nothing at all if the input consists of notifications only.
     */
    public void process(Reader in, Writer out) throws IOException {
        final Builder<? extends JsonValue> builder;
        try {
            var read = Greyson.readBuilder(in);
            if (read.isEmpty()) { // empty input
                Greyson.writeValue(out, error(JsonNull.INSTANCE, -32700, "Parse error").build());
                return;
            }
            builder = read.get();
        } catch (JsonParseException e) {
            Greyson.writeValue(out, error(JsonNull.INSTANCE, -32700, "Parse error").build());
            return;
        }
        switch (builder) {
            // single request
            case Builder.ObjectBuilder ob -> {
                var response = respond(ob);
                if (response != null) {
                    Greyson.writeValue(out, response.build());
                }
            }
            // batch
            case Builder.ArrayBuilder ab -> {
                if (ab.isEmpty()) { // rpc call with an empty array is invalid
                    Greyson.writeValue(out, error(JsonNull.INSTANCE, -32600, "Invalid Request").build());
                    return;
                }
                for (var li = ab.data().listIterator(); li.hasNext(); ) {
                    var response = respond(li.next());
                    if (response != null) {
                        li.set(response);
                    } else {
                        li.remove();
                    }
                }

                if (!ab.data().isEmpty()) { // anything left after dropping notifications?
                    Greyson.writeBuilder(out, ab);
                }
            }
            // a top-level basic value is not a valid request
            case Builder.BasicBuilder ignored ->
                    Greyson.writeValue(out, error(JsonNull.INSTANCE, -32600, "Invalid Request").build());
        }
    }

    /**
     * Process a single request builder in place and return the response
     * builder, or {@code null} for notifications.
     */
    private Builder.@Nullable ObjectBuilder respond(Builder<? extends JsonValue> element) {
        if (!(element instanceof Builder.ObjectBuilder ob)) {
            return error(JsonNull.INSTANCE, -32600, "Invalid Request");
        }
        var request = ob.build(); // immutable snapshot for validation
        if (!isValidRequest(request)) {
            var id = request.get("id").filter(JsonRpcProcessor::isValidId).orElse(JsonNull.INSTANCE);
            return error(id, -32600, "Invalid Request");
        }
        var method = request.get("method").flatMap(JsonValue::string).orElseThrow();
        var params = request.get("params").orElse(JsonNull.INSTANCE);
        if (!request.members().containsKey("id")) { // notification: invoke, never respond
            try {
                businessFunction.apply(method, params);
            } catch (RuntimeException ignored) {
                // errors of notifications are never reported
            }
            return null;
        }
        // in-place transformation of the request into the response:
        // jsonrpc and id survive, method and params are removed,
        // result or error is added
        ob.remove("method").remove("params");
        try {
            ob.put("result", businessFunction.apply(method, params));
        } catch (NoSuchElementException | UnsupportedOperationException e) {
            ob.put("error", errorObject(-32601, "Method not found"));
        } catch (IllegalArgumentException e) {
            ob.put("error", errorObject(-32602, "Invalid params"));
        } catch (RuntimeException e) {
            ob.put("error", errorObject(-32603, "Internal error"));
        }
        return ob;
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

    private static Builder.ObjectBuilder error(JsonValue id, int code, String message) {
        return objectBuilder()
                .putBasic("jsonrpc", VERSION)
                .put("error", errorObject(code, message))
                .put("id", id);
    }

    private static JsonObject errorObject(int code, String message) {
        return objectBuilder()
                .putBasic("code", code)
                .putBasic("message", message)
                .build();
    }
}