package io.github.ralfspoeth.jsonrpc.test;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonString;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.json.query.Selector;
import io.github.ralfspoeth.jsonrpc.JsonRpcServlet;
import io.github.ralfspoeth.utf8.Utf8Reader;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler; // Note the 'ee11'
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static org.junit.jupiter.api.Assertions.*;

public class GreysonEEIntegrationTest {
    private static Server server;
    private static int port;

    private static final HttpClient client = HttpClient.newHttpClient();

    private final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/rpc"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");


    @BeforeAll
    static void setup() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // Add your Greyson Servlet
        context.addServlet(new ServletHolder(new JsonRpcServlet(
                Map.of("hello", p -> "hello " + p,
                        "sum", p -> switch (p) {
                            case List<?> bigDecimals -> {
                                var tmp = BigDecimal.ZERO;
                                for (var bd : bigDecimals) {
                                    if (bd instanceof BigDecimal x) {tmp = tmp.add(x);}
                                }
                                yield tmp;
                            }
                            case null, default -> BigDecimal.ZERO;
                        },
                        "boom", _ -> {
                            throw new RuntimeException("boom");
                        })
        ){}), "/rpc");

        server.setHandler(context);
        server.start();
        port = connector.getLocalPort();
    }

    @Test
    @DisplayName("Call sum with 10, 20, 30 yielding 60")
    void testSuccessfulRpcCall() throws Exception {
        // 1. Prepare the JSON-RPC payload
        var rq = objectBuilder().putBasic("jsonrpc", "2.0")
                .putBasic("id", "req-001")
                .putBasic("method", "sum")
                .put("params", arrayBuilder().addBasic(10).addBasic(20).addBasic(30))
                .build();

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();

        // 3. Send and receive
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicReference<Exception> e = new AtomicReference<>();
        List<JsonValue> responses = new ArrayList<>();
        try (var is = response.body();
             var rdr = new Utf8Reader(is)) {
            Greyson.readValue(rdr)
                    .stream()
                    .flatMap(Selector.all())
                    .forEach(responses::add);
        } catch (Exception ex) {
            e.set(ex);
        }

        // 4. Assertions
        assertAll(
                () -> assertNull(e.get()),
                () -> assertEquals(HttpServletResponse.SC_OK, response.statusCode()),
                () -> assertEquals(1, responses.size()),
                () -> assertEquals("req-001", Pointer.self().member("id").stringValue(responses.getFirst()).orElseThrow()),
                () -> assertEquals(60, Pointer.self().member("result").intValue(responses.getFirst()).orElseThrow())
        );

    }

    @Test
    void testSingleNotification() throws IOException, InterruptedException {
        // given
        var notification = objectBuilder().putBasic("jsonrpc", "2.0").putBasic("method", "sum").build();
        var request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(notification.json())).build();
        // when
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // then
        var result = Greyson.readValue(new Utf8Reader(response.body()));
        assertAll(
                () -> assertTrue(result.isEmpty())
        );
    }


    @Test
    void testTwoNotifications() throws IOException, InterruptedException {
        // given
        var notification1 = objectBuilder().putBasic("jsonrpc", "2.0").putBasic("method", "sum").build();
        var notification2 = objectBuilder().putBasic("jsonrpc", "2.0").putBasic("method", "hello").build();
        var notis = arrayBuilder().add(notification1).add(notification2).build();
        var request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(notis.json())).build();
        // when
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // then
        var result = Greyson.readValue(new Utf8Reader(response.body()));
        assertAll(
                () -> assertTrue(result.isEmpty())
        );
    }

    // -------------------------- error-path tests --------------------------

    @Test
    @DisplayName("Parse error: malformed JSON yields -32700 with id null")
    void testParseError() throws IOException, InterruptedException {
        // given: not valid JSON
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString("{not valid json"))
                .build();
        // when
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = readSingle(response);
        // then
        assertAll(
                () -> assertEquals(HttpServletResponse.SC_OK, response.statusCode()),
                () -> assertEquals("2.0", stringMember(result, "jsonrpc")),
                () -> assertInstanceOf(JsonNull.class, result.members().get("id"), "id must be present and null on parse error"),
                () -> assertEquals(-32700, errorCode(result)),
                () -> assertNotNull(errorMessage(result))
        );
    }

    @Test
    @DisplayName("Invalid Request: missing method yields -32600")
    void testInvalidRequestMissingMethod() throws IOException, InterruptedException {
        // given: jsonrpc + id but no method
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", 1)
                .build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();
        // when
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = readSingle(response);
        // then
        assertAll(
                () -> assertEquals("2.0", stringMember(result, "jsonrpc")),
                () -> assertEquals(-32600, errorCode(result)),
                () -> assertNotNull(errorMessage(result))
        );
    }

    @Test
    @DisplayName("Invalid Request: jsonrpc != 2.0 yields -32600")
    void testInvalidRequestWrongVersion() throws IOException, InterruptedException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "1.0")
                .putBasic("method", "hello")
                .putBasic("id", 1)
                .build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = readSingle(response);
        assertEquals(-32600, errorCode(result));
    }

    @Test
    @DisplayName("Invalid Request: method is not a string yields -32600 (not 500)")
    void testInvalidRequestNonStringMethod() throws IOException, InterruptedException {
        // method must be a String per spec; sending a number should be rejected
        // with Invalid Request rather than crashing the servlet.
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", 42)
                .putBasic("id", 1)
                .build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(HttpServletResponse.SC_OK, response.statusCode(),
                "non-string method must not crash the servlet");
        var result = readSingle(response);
        assertEquals(-32600, errorCode(result));
    }

    @Test
    @DisplayName("Method not found returns -32601 with matching id")
    void testMethodNotFound() throws IOException, InterruptedException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "doesNotExist")
                .putBasic("id", 42)
                .build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = readSingle(response);
        assertAll(
                () -> assertEquals("2.0", stringMember(result, "jsonrpc")),
                () -> assertEquals(42, intMember(result, "id")),
                () -> assertEquals(-32601, errorCode(result)),
                () -> assertNotNull(errorMessage(result))
        );
    }

    @Test
    @DisplayName("Service throwing yields error response with matching id")
    void testInternalErrorFromService() throws IOException, InterruptedException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "boom")
                .putBasic("id", 7)
                .build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = readSingle(response);
        assertAll(
                () -> assertEquals("2.0", stringMember(result, "jsonrpc")),
                () -> assertEquals(7, intMember(result, "id")),
                () -> assertEquals(-32603, errorCode(result))
        );
    }

    @Test
    @DisplayName("Empty batch [] returns single Invalid Request response")
    void testEmptyBatch() throws IOException, InterruptedException {
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString("[]"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = readSingle(response);
        assertEquals(-32600, errorCode(result));
    }

    @Test
    @DisplayName("Notification to unknown method produces no response and does not 500")
    void testNotificationToUnknownMethod() throws IOException, InterruptedException {
        var rq = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "doesNotExist")
                .build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(rq.json()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        var result = Greyson.readValue(new Utf8Reader(response.body()));
        assertAll(
                () -> assertEquals(HttpServletResponse.SC_OK, response.statusCode()),
                () -> assertTrue(result.isEmpty(), "no response body for notifications")
        );
    }

    @Test
    @DisplayName("Mixed batch: valid call + notification + invalid request -> 2 responses")
    void testMixedBatch() throws IOException, InterruptedException {
        var call = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "hello")
                .putBasic("id", "a")
                .put("params", arrayBuilder().addBasic("world"))
                .build();
        var notification = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "hello")
                .build();
        var invalid = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                // missing method
                .putBasic("id", "b")
                .build();
        var batch = arrayBuilder().add(call).add(notification).add(invalid).build();
        var request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(batch.json()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        List<JsonValue> responses = new ArrayList<>();
        try (var is = response.body();
             var rdr = new Utf8Reader(is)) {
            Greyson.readValue(rdr).stream()
                    .flatMap(Selector.all())
                    .forEach(responses::add);
        }
        assertAll(
                () -> assertEquals(HttpServletResponse.SC_OK, response.statusCode()),
                () -> assertEquals(2, responses.size(),
                        "exactly two responses: the valid call and the invalid request"),
                () -> assertTrue(responses.stream()
                                .map(r -> (JsonObject) r)
                                .anyMatch(r -> r.members().containsKey("result")),
                        "one response is the successful 'hello' result"),
                () -> assertTrue(responses.stream()
                                .map(r -> (JsonObject) r)
                                .anyMatch(r -> errorCodeOrZero(r) == -32600),
                        "one response is an Invalid Request (-32600)")
        );
    }

    // -------------------------- helpers --------------------------

    private static JsonObject readSingle(HttpResponse<java.io.InputStream> response) throws IOException {
        try (var is = response.body();
             var rdr = new Utf8Reader(is)) {
            var v = Greyson.readValue(rdr).orElseThrow(
                    () -> new AssertionError("expected a response body"));
            assertInstanceOf(JsonObject.class, v, "expected a single response object, got: " + v);
            return (JsonObject) v;
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

    private static int errorCodeOrZero(JsonObject response) {
        var err = response.members().get("error");
        if (err instanceof JsonObject(var members)
                && members.get("code") instanceof JsonNumber(var n)) {
            return n.intValue();
        }
        return 0;
    }

    private static String errorMessage(JsonObject response) {
        return stringMember(errorObject(response), "message");
    }

    @AfterAll
    static void stopJetty() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (client != null) {
            client.close();
        }
    }
}