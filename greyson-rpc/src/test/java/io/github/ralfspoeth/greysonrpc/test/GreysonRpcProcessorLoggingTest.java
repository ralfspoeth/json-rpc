package io.github.ralfspoeth.greysonrpc.test;

import io.github.ralfspoeth.greysonrpc.GreysonRpcProcessor;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.log.weaver.testkit.RecordingLoggerFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.NoSuchElementException;

import static io.github.ralfspoeth.log.weaver.testkit.RecordingLoggerFinder.Entry;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies what the log weaver emits for {@link GreysonRpcProcessor}, captured
 * by {@link RecordingLoggerFinder} rather than by JUL &mdash; so levels are
 * {@link System.Logger.Level} as annotated, with no mapping in between, and the
 * message and throwable arrive exactly as the woven code passed them.
 *
 * <p>What the weaver emits, per {@code @Log}: one parameter-aware call &mdash;
 * before the body when {@code logReturn = false}, before each return otherwise
 * &mdash; plus an always-on {@code Throwable} catch that logs
 * {@code t.getMessage()} with the throwable at {@code exceptionLevel()},
 * {@code WARNING} by default, and rethrows.
 */
class GreysonRpcProcessorLoggingTest {

    private final GreysonRpcProcessor processor = new GreysonRpcProcessor((method, params) -> switch (method) {
        case "echo" -> params;
        case "picky" -> throw new IllegalArgumentException("picky");
        case "boom" -> throw new IllegalStateException("boom");
        default -> throw new NoSuchElementException(method);
    });

    @BeforeEach
    void quiet() {
        // one finder per JVM, so each test states the threshold it wants
        RecordingLoggerFinder.reset(System.Logger.Level.ALL);
    }

    // -------------------------- DEBUG: everything ----------------------

    @Test
    @DisplayName("At DEBUG, a successful call logs the woven call and its result")
    void debugLevelLogsInvocationAndResult() throws IOException {
        RecordingLoggerFinder.reset(DEBUG);
        var response = process(request("echo", 1));

        var debug = RecordingLoggerFinder.messagesAt(DEBUG);
        assertAll(
                () -> assertTrue(response.contains("result"), "sanity: call succeeded"),
                // invoke() is @Log(DEBUG); the woven message names the method and
                // renders its arguments, so assert on those rather than on the
                // weaver's exact punctuation
                () -> assertTrue(debug.stream()
                                .anyMatch(m -> m.contains("invoke") && m.contains("echo")),
                        "the invocation is logged at DEBUG: " + debug),
                () -> assertTrue(RecordingLoggerFinder.at(WARNING).isEmpty(),
                        "no warnings on success"),
                // the weaver logs under the woven class's own name, which is what
                // from() selects on - allMatch over every entry would be wrong,
                // since the finder captures the whole JVM's logging
                () -> assertFalse(RecordingLoggerFinder.from(GreysonRpcProcessor.class).isEmpty(),
                        "logged under the target class name")
        );
    }

    @Test
    @DisplayName("At DEBUG, a failing handler warns with the causing exception attached")
    void debugLevelLogsWarningsOnErrors() throws IOException {
        RecordingLoggerFinder.reset(DEBUG);
        process(request("nope", 2));

        var warnings = RecordingLoggerFinder.at(WARNING);
        assertAll(
                // the weaver's Throwable catch on invoke() logs t.getMessage()
                // and hands the exception itself to the logger
                () -> assertTrue(warnings.stream().anyMatch(e -> e.threw(NoSuchElementException.class)),
                        "causing exception attached to the record: " + warnings),
                () -> assertTrue(warnings.stream().anyMatch(e -> "nope".equals(e.message())),
                        "the exception's own message is the log message"),
                // and the wire diagnostic reaches the log through error(),
                // which is @Log(WARNING) and renders its arguments
                () -> assertTrue(warnings.stream()
                                .map(Entry::message)
                                .anyMatch(m -> m.contains("method not found: nope")),
                        "the -32601 diagnostic is logged too")
        );
    }

    // -------------------------- INFO: nothing on success ---------------

    @Test
    @DisplayName("At INFO, a successful call is silent: everything woven sits below it")
    void infoLevelSuppressesDebug() throws IOException {
        RecordingLoggerFinder.reset(INFO);
        process(request("echo", 3));

        // Nothing is annotated at INFO: invoke/respond/result are @Log(DEBUG),
        // the module's @LogAll puts public methods at TRACE, and the WARNING
        // paths are not taken on success.
        assertTrue(RecordingLoggerFinder.entries().isEmpty(),
                "nothing at INFO or above for a successful call: " + RecordingLoggerFinder.entries());
    }

    // -------------------------- WARNING: errors only -------------------

    @Test
    @DisplayName("At WARNING, successful calls are silent")
    void warningLevelSilentOnSuccess() throws IOException {
        RecordingLoggerFinder.reset(WARNING);
        process(request("echo", 4));

        assertTrue(RecordingLoggerFinder.entries().isEmpty(),
                "nothing logged for a successful call: " + RecordingLoggerFinder.entries());
    }

    @Test
    @DisplayName("At WARNING, every error path leaves a warning")
    void warningLevelLogsAllErrorPaths() throws IOException {
        RecordingLoggerFinder.reset(WARNING);

        process(request("picky", 5));       // -32602
        process(request("boom", 6));        // -32603
        processRaw("this is not json");     // -32700
        processRaw("42");                   // -32600, top-level basic value

        var messages = RecordingLoggerFinder.messagesAt(WARNING);
        assertAll(
                () -> assertTrue(messages.stream().anyMatch(m -> m.contains("invalid params for picky")), messages::toString),
                () -> assertTrue(messages.stream().anyMatch(m -> m.contains("internal error in boom")), messages::toString),
                () -> assertTrue(messages.stream().anyMatch(m -> m.contains("Parse error")), messages::toString),
                () -> assertTrue(messages.stream().anyMatch(m -> m.contains("Invalid Request")), messages::toString)
        );
    }

    @Test
    @DisplayName("A failing notification warns although the peer gets no response")
    void failedNotificationWarns() throws IOException {
        RecordingLoggerFinder.reset(WARNING);
        var notification = objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", "boom")
                .build();
        var response = process(notification);

        assertAll(
                () -> assertTrue(response.isEmpty(), "notifications never get a response"),
                // No hand-written message for this any more: the record of a
                // failed notification is the weaver's Throwable catch on
                // invoke(), which is enough because it carries the cause.
                () -> assertTrue(RecordingLoggerFinder.at(WARNING).stream()
                                .anyMatch(e -> e.threw(IllegalStateException.class)),
                        "failure logged nonetheless, with the cause attached")
        );
    }

    // -------------------------- lazy formatting ------------------------

    @Test
    @DisplayName("A suppressed level records nothing, so the message is never built")
    void suppressedLevelsRecordNothing() throws IOException {
        RecordingLoggerFinder.reset(System.Logger.Level.OFF);
        process(request("echo", 7));
        process(request("boom", 8));

        // The weaver builds its message through a Supplier, which the default
        // System.Logger methods only invoke once isLoggable passes.
        assertTrue(RecordingLoggerFinder.entries().isEmpty(),
                "OFF discards even the error paths: " + RecordingLoggerFinder.entries());
    }

    // -------------------------- helpers --------------------------

    private static JsonValue request(String method, int id) {
        return objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("method", method)
                .putBasic("id", id)
                .build();
    }

    private String process(JsonValue value) throws IOException {
        return processRaw(value.json());
    }

    private String processRaw(String text) throws IOException {
        try (var rdr = new StringReader(text); var wrt = new StringWriter()) {
            processor.process(rdr, wrt);
            return wrt.toString();
        }
    }
}
