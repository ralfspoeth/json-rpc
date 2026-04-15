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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static java.util.Optional.ofNullable;

public class JsonRpcServlet extends HttpServlet {

    private final Map<String, Function<Params, Object>> dispatcher;

    public JsonRpcServlet(Map<String, Function<Params, Object>> dispather) {
        this.dispatcher = dispather;
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        var inputContentType = req.getContentType();
        if (Set.of("application/json", "text/json").contains(inputContentType)) {
            resp.setContentType("application/json");
            try (var is = req.getInputStream();
                 var rdr = new Utf8Reader(is);
                 var os = resp.getOutputStream();
                 var wrt = new Utf8Writer(os)
            ) {
                try {
                    var request = Greyson
                            .readValue(rdr)
                            .orElseThrow(() -> new JsonParseException("empty input", 0, 0));
                    boolean isBatchRequest = request instanceof JsonArray;
                    boolean isValidRequest = Stream.of(request)
                            .flatMap(Selector.all())
                            .allMatch(v -> v instanceof JsonObject(var members) &&
                                    members.containsKey("method") &&
                                    v.get("id").filter(i -> i instanceof Aggregate || i instanceof JsonBoolean).isEmpty() &&
                                    v.get("params").filter(Basic.class::isInstance).isEmpty() &&
                                    members.get("jsonrpc") instanceof JsonString(var s) && s.equals("2.0")
                            );
                    // now process valid requests
                    if (isValidRequest) {
                        Stream.of(request)
                                .flatMap(Selector.all())
                                .parallel()
                                .map(q -> new RequestObject(
                                        id(q),
                                        method(q),
                                        params(q)))
                                .map(this::invokeService)
                                .filter(Objects::nonNull)
                                .toList();
                        ;
                    }
                    // invalid requests
                    // with code -32600
                    else {
                        Greyson.writeValue(wrt, objectBuilder()
                                .putBasic("id", null)
                                .putBasic("code", -32600)
                                .build()
                        );
                    }
                }
                // parse exception with code -32700
                catch (JsonParseException e) {
                    Greyson.writeValue(wrt, objectBuilder()
                            .putBasic("id", null)
                            .putBasic("code", -32700)
                            .putBasic("message", e.getMessage())
                            .build()
                    );
                }
            }
        }
    }

    private ResponseObject invokeService(RequestObject request) {
        return ofNullable(dispatcher.get(request.method()))
                .map(f -> f.apply(request.params()))
                .filter(result -> request.id()!=null)
                .map(result -> ResponseObject.result(request.id(), JsonValue.of(result)))
                .orElse(ResponseObject.error(
                        request.id(),
                        objectBuilder()
                                .putBasic("code", -32601)
                                .putBasic("message", "No such method: " + request.method())
                                .build())
                );
    }

    private static @Nullable Id id(JsonValue ro) {
        return ro.get("id").map(value -> switch (value) {
            case JsonString(var s) -> new Id.StringId(s);
            case JsonNumber(var n) -> new Id.IntId(n.intValue());
            case JsonNull _ -> null;
            case JsonBoolean(var b) -> throw new IllegalArgumentException(
                    "boolean " + b + " is not a valid id"
            );
            case Aggregate a -> throw new IllegalStateException(
                    "illegal id " + a
            );
        }).orElse(null);
    }

    private static String method(JsonValue request) {
        return request.get("method")
                .flatMap(JsonValue::string)
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Params params(JsonValue r) {
        return r.get("params")
                .map(Queries::asObject)
                .map(o -> switch (o) {
                    case List<?> l -> new Params.ArrayParams(l);
                    case Map<?, ?> m -> new Params.MapParams((Map<String, ?>) m);
                    case null, default -> throw new IllegalArgumentException("illegal " + o);
                })
                .orElse(null);
    }
}
