package io.github.ralfspoeth.jsonrpc;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

public record RequestObject(@Nullable Id id, String method, @Nullable Params params) {
    public RequestObject {
        method = Objects.requireNonNull(method, "method is null");
    }

    public boolean isNotification() {
        return id == null;
    }
}
