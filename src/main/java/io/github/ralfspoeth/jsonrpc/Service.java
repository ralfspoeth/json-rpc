package io.github.ralfspoeth.jsonrpc;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface Service {

    @Nullable Object request(@Nullable Object params) throws Exception;
    default void notification(@Nullable Object params) throws Exception{
        var _ = request(params);
    }

}
