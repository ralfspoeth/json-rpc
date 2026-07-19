package io.github.ralfspoeth.jsonrpc;

import io.github.ralfspoeth.greysonrpc.GreysonRpcProcessor;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Queries;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Internal bridge from the Greyson-free {@link Procedure} API to the
 * Greyson-native {@link GreysonRpcProcessor}. Unknown methods map to
 * -32601 (method not found); runtime exceptions thrown by procedures
 * propagate unwrapped so the spec error-code mapping of
 * {@link GreysonRpcProcessor} applies.
 */
final class Dispatch {

    private Dispatch() {
        // no instances
    }

    static GreysonRpcProcessor of(Map<String, Procedure> dispatcher) {
        Objects.requireNonNull(dispatcher);
        return new GreysonRpcProcessor((method, params) -> {
            var procedure = dispatcher.get(method);
            if (procedure == null) {
                throw new NoSuchElementException("Method not found: " + method);
            }
            try {
                return JsonValue.of(procedure.request(Queries.asObject(params)));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
