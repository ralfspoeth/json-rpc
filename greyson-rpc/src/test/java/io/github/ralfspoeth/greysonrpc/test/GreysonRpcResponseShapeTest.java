package io.github.ralfspoeth.greysonrpc.test;

import io.github.ralfspoeth.greysonrpc.GreysonRpcProcessor;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static io.github.ralfspoeth.json.query.Shape.anything;
import static io.github.ralfspoeth.json.query.Shape.each;
import static io.github.ralfspoeth.json.query.Shape.exact;
import static io.github.ralfspoeth.json.query.Shape.exactlyOne;
import static io.github.ralfspoeth.json.query.Shape.member;
import static io.github.ralfspoeth.json.query.Shape.nul;
import static io.github.ralfspoeth.json.query.Shape.optional;
import static io.github.ralfspoeth.json.query.Shape.satisfying;
import static io.github.ralfspoeth.json.query.Shape.string;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response object of
 * <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a> §5, written
 * as a {@link Shape} and used as an oracle: whatever this processor emits must
 * satisfy it.
 *
 * <p>That is a different kind of test from asserting a particular response for a
 * particular request. Those pin the behaviour we intended; this one pins
 * conformance to the specification across <em>every</em> path — and it is the
 * assertion that would have caught an invalid request producing no response at
 * all, since "nothing" is not a response object.
 */
class GreysonRpcResponseShapeTest {

    /** The spec's integers, which {@code number()} alone cannot express. */
    private static final Shape WHOLE = satisfying("a whole number",
            v -> v instanceof JsonNumber(var n) && n.stripTrailingZeros().scale() <= 0);

    /** §5.1: code is an integer, message a string, data optional. */
    private static final Shape ERROR = member("code", WHOLE)
            .and(member("message", string()))
            .and(optional("data", anything()));

    /**
     * §5: jsonrpc is "2.0"; id is REQUIRED and is a string, a number or null —
     * null when the request's id could not be determined; and exactly one of
     * result or error is present, never both and never neither.
     */
    private static final Shape RESPONSE = member("jsonrpc", exact(Basic.of("2.0")))
            .and(member("id", string().or(WHOLE).or(nul())))
            .and(exactlyOne(Map.of("result", anything(), "error", ERROR)));

    private final GreysonRpcProcessor processor = new GreysonRpcProcessor((method, params) -> switch (method) {
        case "echo" -> params;
        case "picky" -> throw new IllegalArgumentException("picky");
        case "boom" -> throw new IllegalStateException("boom");
        default -> throw new NoSuchElementException(method);
    });

    @Test
    @DisplayName("Every single response conforms, down every path")
    void everySingleResponseConforms() throws IOException {
        assertAll(
                () -> conforms("a result", request("echo", 1)),
                () -> conforms("method not found", request("nope", 2)),
                () -> conforms("invalid params", request("picky", 3)),
                () -> conforms("internal error", request("boom", 4)),
                // the id is echoed, so a string id must round-trip as one
                () -> conforms("a string id", """
                        {"jsonrpc": "2.0", "method": "echo", "id": "abc"}"""),
                // the spec permits a null id, and it is still a request
                () -> conforms("a null id", """
                        {"jsonrpc": "2.0", "method": "echo", "id": null}"""),
                // -32700: nothing parseable, so the id cannot be determined
                () -> conforms("a parse error", "this is not json"),
                // -32600: a top-level basic value is no request
                () -> conforms("a top-level scalar", "42"),
                // -32600: an invalid request still owes a response
                () -> conforms("a missing method", """
                        {"jsonrpc": "2.0", "id": 5}"""),
                () -> conforms("a wrong version", """
                        {"jsonrpc": "1.0", "method": "echo", "id": 6}"""),
                () -> conforms("a malformed id", """
                        {"jsonrpc": "2.0", "method": "echo", "id": true}""")
        );
    }

    @Test
    @DisplayName("Every element of a batch response conforms")
    void everyBatchElementConforms() throws IOException {
        var batch = """
                [{"jsonrpc": "2.0", "method": "echo", "params": [1], "id": 1},
                 {"jsonrpc": "2.0", "method": "boom", "id": 2},
                 {"jsonrpc": "2.0", "method": "echo", "params": [3]},
                 {"jsonrpc": "2.0", "id": 4}]""";

        var out = json(process(batch));
        assertAll(
                // the notification contributes nothing, the other three do
                () -> assertEquals(3, assertInstanceOf(JsonArray.class, out).size()),
                () -> conformsTo(each(RESPONSE), out, "the batch")
        );
    }

    @Test
    @DisplayName("A notification produces no response at all, which is correct")
    void aNotificationProducesNothing() throws IOException {
        var out = process("""
                {"jsonrpc": "2.0", "method": "echo", "params": [1]}""");
        // and this is why the oracle cannot simply be applied to every output:
        // silence is the one legitimate non-response
        assertTrue(out.isEmpty(), "a notification owes nothing: <" + out + ">");
    }

    @Test
    @DisplayName("The oracle has teeth: malformed responses do not conform")
    void theOracleRejectsAMalformedResponse() throws IOException {
        // guard against the shape being vacuously true - if RESPONSE accepted
        // anything, every assertion above would pass regardless
        assertAll(
                () -> assertEquals(List.of("id: missing required member"),
                        violations(json("""
                                {"jsonrpc": "2.0", "result": 7}"""))),
                () -> assertEquals(
                        List.of("<root>: expected exactly one of \"error\", \"result\", got \"error\", \"result\""),
                        violations(json("""
                                {"jsonrpc": "2.0", "id": 1, "result": 7, "error": {"code": -1, "message": "x"}}"""))),
                () -> assertEquals(List.of("error/code: expected a whole number, got -1.5"),
                        violations(json("""
                                {"jsonrpc": "2.0", "id": 1, "error": {"code": -1.5, "message": "x"}}""")))
        );
    }

    // ---- helpers --------------------------------------------------

    private static String request(String method, int id) {
        return """
                {"jsonrpc": "2.0", "method": "%s", "id": %d}""".formatted(method, id);
    }

    private static JsonValue json(String text) throws IOException {
        return Greyson.readValue(Reader.of(text)).orElseThrow();
    }

    private void conforms(String what, String requestText) throws IOException {
        var out = process(requestText);
        assertFalse(out.isEmpty(), what + " must produce a response, got nothing");
        conformsTo(RESPONSE, json(out), what);
    }

    private static void conformsTo(Shape shape, JsonValue value, String what) {
        var defects = shape.violations(value).map(Shape.Violation::toString).toList();
        assertTrue(defects.isEmpty(), what + " is not a JSON-RPC response: " + defects);
    }

    private static List<String> violations(JsonValue response) {
        return RESPONSE.violations(response).map(Shape.Violation::toString).toList();
    }

    private String process(String text) throws IOException {
        try (var rdr = new StringReader(text); var wrt = new StringWriter()) {
            processor.process(rdr, wrt);
            return wrt.toString();
        }
    }
}
