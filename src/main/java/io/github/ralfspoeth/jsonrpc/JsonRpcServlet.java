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

public class JsonRpcServlet extends HttpServlet {

    private static final String JSON_CONTENT_TYPE = "application/json";
    private final Map<String, Service> dispatcher;

    public JsonRpcServlet(Map<String, Service> dispatcher) {
        this.dispatcher = dispatcher;
    }

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
                        Greyson.writeValue(wrt, invalidRequest());
                        return;
                    }

                    var responses = Stream.of(request)
                            .flatMap(Selector.all())
                            .parallel()
                            .map(r -> isValid(r) ? invokeService(r) : invalidRequest())
                            .filter(Objects::nonNull)
                            .toList();

                    if (!responses.isEmpty()) {
                        if (request instanceof JsonArray) {
                            Greyson.writeValue(wrt, responses.stream().collect(Queries.toJsonArray()));
                        } else {
                            assert responses.size() == 1;
                            Greyson.writeValue(wrt, responses.getFirst());
                        }
                    }
                }
                // parse exception with code -32700
                catch (JsonParseException e) {
                    Greyson.writeBuilder(wrt, objectBuilder()
                            .putBasic("jsonrpc", "2.0")
                            .putBasic("id", null)
                            .put("error", objectBuilder()
                                    .putBasic("code", -32700)
                                    .putBasic("message", e.getMessage())
                            ));
                }
            }
        } else {
            resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private boolean isValid(JsonValue request) {
        return request instanceof JsonObject(var members) &&
                request.get("jsonrpc").flatMap(JsonValue::string).orElse("").equals("2.0") &&
                request.get("method").filter(JsonString.class::isInstance).isPresent() &&
                isValidOrNullId(members.get("id")) &&
                isValidOrNullParams(members.get("params"));
    }

    private JsonObject invalidRequest() {
        return objectBuilder()
                .putBasic("jsonrpc", "2.0")
                .putBasic("id", null)
                .put("error", objectBuilder()
                        .putBasic("code", -32600)
                        .putBasic("message", "Invalid request"))
                .build();
    }

    private @Nullable JsonObject invokeService(JsonValue request) {
        var method = method(request);
        var id = id(request);
        var params = params(request);
        var service = dispatcher.get(method);

        // notification
        if (id == null) {
            if(service!=null) try {
                service.notification(params);
            } catch (Exception ex) {
                getServletContext().log("method " + method + " call failed", ex);
            } else {
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

    private static String method(JsonValue request) {
        return request.get("method")
                .flatMap(JsonValue::string)
                .orElseThrow();
    }

    private static @Nullable Object id(JsonValue request) {
        return request.get("id")
                .flatMap(id -> switch (id) {
                    case JsonNumber(var n) -> Optional.of(n);
                    case JsonString(var s) -> Optional.of(s);
                    default -> Optional.empty();
                })
                .orElse(null);
    }

    private static @Nullable Object params(JsonValue request) {
        return request.get("params")
                .map(Queries::asObject)
                .orElse(null);
    }

    private static boolean isValidOrNullParams(@Nullable JsonValue params) {
        return params == null || params instanceof Aggregate;
    }

    private static boolean isValidOrNullId(@Nullable JsonValue id) {
        return id == null || id instanceof JsonNumber || id instanceof JsonString || id instanceof JsonNull;
    }
}
