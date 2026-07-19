package io.github.ralfspoeth.jsonrpc.test;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonString;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Selector;
import io.github.ralfspoeth.jsonrpc.JsonRpcWebSocket;
import io.github.ralfspoeth.jsonrpc.Service;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcWebSocketTest {

    /**
     * Minimal concrete subclass since {@link JsonRpcWebSocket} is abstract.
     */
    private static final class TestEndpoint extends JsonRpcWebSocket {
        TestEndpoint(Map<String, Service> dispatcher) {
            super(dispatcher);
        }
    }

    private static TestEndpoint endpoint(Map<String, Service> dispatcher) {
        return new TestEndpoint(dispatcher);
    }

    private static final Map<String, Service> defaultDispatcher = Map.of(
            "hello", p -> "hello " + p,
            "sum", p -> switch (p) {
                case List<?> xs -> {
                    var tmp = BigDecimal.ZERO;
                    for (var x : xs) {
                        if (x instanceof BigDecimal bd) tmp = tmp.add(bd);
                    }
                    yield tmp;
                }
                case null, default -> BigDecimal.ZERO;
            },
            "boom", _ -> {
                throw new RuntimeException("boom");
            }
    );

    @Test
    @DisplayName("Single request returns a single response with matching id and result")
    void singleRequest() throws IOException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", "req-1")
                .putBasic("method", "sum")
                .put("params", arrayBuilder().addBasic(1).addBasic(2).addBasic(3))
                .build();

        var reply = endpoint(defaultDispatcher).onMessage(rq.json());
        var result = parseSingle(reply);

        assertAll(
                () -> assertEquals("2.0", stringMember(result, "jsonrpc")),
                () -> assertEquals("req-1", stringMember(result, "id")),
                () -> assertEquals(6, intMember(result, "result"))
        );
    }

    @Test
    @DisplayName("Single notification returns an empty string and no reply is sent")
    void singleNotification() throws IOException {
        var sideEffect = new AtomicReference<>();
        var dispatcher = Map.<String, Service>of(
                "tap", p -> {
                    sideEffect.set(p);
                    return null;
                }
        );
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "tap")
                .put("params", arrayBuilder().addBasic("hi"))
                .build();

        var reply = endpoint(dispatcher).onMessage(rq.json());

        assertAll(
                () -> assertEquals("", reply, "no message must be sent for a notification"),
                () -> assertEquals(List.of("hi"), sideEffect.get(),
                        "the dispatcher must still be invoked for the side effect")
        );
    }

    @Test
    @DisplayName("Batch request returns an array of responses, one per non-notification entry")
    void batchRequest() throws IOException {
        var call1 = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", 1)
                .putBasic("method", "hello")
                .put("params", arrayBuilder().addBasic("world"))
                .build();
        var call2 = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", 2)
                .putBasic("method", "hello")
                .put("params", arrayBuilder().addBasic("ralf"))
                .build();
        var batch = arrayBuilder().add(call1).add(call2).build();

        var reply = endpoint(defaultDispatcher).onMessage(batch.json());
        var arr = parseArray(reply);

        assertAll(
                () -> assertEquals(2, arr.size()),
                () -> assertTrue(arr.stream()
                                .map(JsonObject.class::cast)
                                .allMatch(o -> o.members().containsKey("result")),
                        "every entry should be a successful response")
        );
    }

    @Test
    @DisplayName("Batch of pure notifications returns the empty string")
    void batchOfNotifications() throws IOException {
        var counter = new AtomicInteger();
        var dispatcher = Map.<String, Service>of(
                "tick", _ -> {
                    counter.incrementAndGet();
                    return null;
                }
        );
        var n1 = objectBuilder().putBasic("jsonrpc", "2.0").putBasic("method", "tick").build();
        var n2 = objectBuilder().putBasic("jsonrpc", "2.0").putBasic("method", "tick").build();
        var batch = arrayBuilder().add(n1).add(n2).build();

        var reply = endpoint(dispatcher).onMessage(batch.json());

        assertAll(
                () -> assertEquals("", reply, "notifications produce no response"),
                () -> assertEquals(2, counter.get(), "both notifications must be dispatched")
        );
    }

    @Test
    @DisplayName("Empty batch [] yields a single Invalid Request error (-32600)")
    void emptyBatch() throws IOException {
        var reply = endpoint(defaultDispatcher).onMessage("[]");
        var result = parseSingle(reply);

        assertAll(
                () -> assertEquals(-32600, errorCode(result)),
                () -> assertNotNull(errorMessage(result)),
                () -> assertInstanceOf(JsonNull.class, result.members().get("id"))
        );
    }

    @Test
    @DisplayName("Malformed JSON yields a Parse Error (-32700) with id null")
    void parseError() throws IOException {
        var reply = endpoint(defaultDispatcher).onMessage("{not valid json");
        var result = parseSingle(reply);

        assertAll(
                () -> assertEquals("2.0", stringMember(result, "jsonrpc")),
                () -> assertInstanceOf(JsonNull.class, result.members().get("id")),
                () -> assertEquals(-32700, errorCode(result)),
                () -> assertNotNull(errorMessage(result))
        );
    }

    @Test
    @DisplayName("Unknown method returns -32601 with matching id")
    void methodNotFound() throws IOException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", 99)
                .putBasic("method", "doesNotExist")
                .build();

        var reply = endpoint(defaultDispatcher).onMessage(rq.json());
        var result = parseSingle(reply);

        assertAll(
                () -> assertEquals(99, intMember(result, "id")),
                () -> assertEquals(-32601, errorCode(result))
        );
    }

    @Test
    @DisplayName("Service throwing an exception yields -32000 with its message")
    void serviceException() throws IOException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", 7)
                .putBasic("method", "boom")
                .build();

        var reply = endpoint(defaultDispatcher).onMessage(rq.json());
        var result = parseSingle(reply);

        assertAll(
                () -> assertEquals(7, intMember(result, "id")),
                () -> assertEquals(-32603, errorCode(result))
        );
    }

    @Test
    @DisplayName("Invalid request (jsonrpc != 2.0) yields -32600")
    void invalidRequestVersion() throws IOException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "1.0")
                .putBasic("id", 1)
                .putBasic("method", "hello")
                .build();

        var reply = endpoint(defaultDispatcher).onMessage(rq.json());
        var result = parseSingle(reply);

        assertEquals(-32600, errorCode(result));
    }

    @Test
    @DisplayName("Notification to an unknown method produces no reply and does not throw")
    void notificationUnknownMethod() throws IOException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "doesNotExist")
                .build();

        var reply = endpoint(defaultDispatcher).onMessage(rq.json());

        assertEquals("", reply);
    }

    // -------------------------- helpers --------------------------

    private static JsonObject parseSingle(String text) throws IOException {
        try (Reader rdr = new StringReader(text)) {
            var v = Greyson.readValue(rdr).orElseThrow(
                    () -> new AssertionError("expected a non-empty response, got: <" + text + ">"));
            assertInstanceOf(JsonObject.class, v, "expected single object, got: " + v);
            return (JsonObject) v;
        }
    }

    private static List<JsonValue> parseArray(String text) throws IOException {
        try (Reader rdr = new StringReader(text)) {
            var v = Greyson.readValue(rdr).orElseThrow(
                    () -> new AssertionError("expected an array response, got empty"));
            assertInstanceOf(JsonArray.class, v, "expected array response, got: " + v);
            var out = new ArrayList<JsonValue>();
            java.util.stream.Stream.of(v).flatMap(Selector.all()).forEach(out::add);
            return out;
        }
    }

    private static String stringMember(JsonObject o, String key) {
        var v = o.members().get(key);
        if (v instanceof JsonString(var s)) return s;
        throw new AssertionError("expected string member '" + key + "', got: " + v);
    }

    private static int intMember(JsonObject o, String key) {
        var v = o.members().get(key);
        if (v instanceof JsonNumber(var n)) return n.intValue();
        throw new AssertionError("expected number member '" + key + "', got: " + v);
    }

    private static JsonObject errorObject(JsonObject response) {
        var v = response.members().get("error");
        if (v instanceof JsonObject e) return e;
        throw new AssertionError("expected 'error' object, got: " + v);
    }

    private static int errorCode(JsonObject response) {
        return intMember(errorObject(response), "code");
    }

    private static String errorMessage(JsonObject response) {
        return stringMember(errorObject(response), "message");
    }
}
