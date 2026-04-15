package io.github.ralfspoeth.jsonrpc;

import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;

import static io.github.ralfspoeth.json.data.Builder.objectBuilder;

public record ResponseObject(@Nullable Id id, @Nullable JsonValue result, @Nullable JsonObject error) {

    public static ResponseObject result(Id id, @Nullable JsonValue result) {
        return new ResponseObject(id, result, null);
    }

    public static ResponseObject error(@Nullable Id id, @Nullable JsonObject error) {
        return new ResponseObject(id, null, error);
    }

    public static ResponseObject parseError(@Nullable String error) {
        return error(null, objectBuilder()
                .putBasic("code", -32700)
                .putBasic("message", error)
                .build()
        );
    }

    public ResponseObject {
        if(result == null &&  error == null || result != null && error != null) {
            throw new IllegalArgumentException("exactly one of result or error objects must be null");
        }
        if(error != null) {
            if(error.get("code").flatMap(JsonValue::decimal).map(BigDecimal::intValueExact).isEmpty()) {
                throw new IllegalArgumentException("error code is mandatory and must be an integer");
            }
        }
    }
}
