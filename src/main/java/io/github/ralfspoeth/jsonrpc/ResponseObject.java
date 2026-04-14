package io.github.ralfspoeth.jsonrpc;

import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;

public record ResponseObject(Id id, @Nullable JsonValue result, @Nullable JsonObject error) {
    public ResponseObject {
        Objects.requireNonNull(id);
        if(result == null &&  error == null
                || result != null && error != null
        ) {
            throw new IllegalArgumentException("exactly one of result or error objects must be null");
        }
        if(error != null) {
            if(error.get("code").flatMap(JsonValue::decimal).map(BigDecimal::intValueExact).isEmpty()) {
                throw new IllegalArgumentException("error code is mandatory and must be an integer");
            }
        }
    }
}
