package io.github.ralfspoeth.jsonrpc;

import org.jspecify.annotations.Nullable;

/**
 * A functional interface representing a service.
 * Implementations of this interface handle incoming requests and notifications.
 */
@FunctionalInterface
public interface Service {

    /**
     * Handles a single request.
     *
     * @param params The parameters for the request, which can be a
     *               {@link java.util.Map} for named parameters,
     *               a {@link java.util.List} for positional parameters,
     *               or {@code null} .
     * @return The result of the request, which will be serialized to JSON. Can be null for void methods.
     * @throws Exception If an error occurs during the processing of the request.
     */
    @Nullable Object request(@Nullable Object params) throws Exception;

    /**
     * Handles a notification.
     * By default, it calls the {@link #request(Object)} method and discards the result.
     * Implementations can override this method for specific notification handling without a return value.
     *
     * @param params The parameters for the notification, which can be a a map for named parameters, a list for positional parameters, or {@code null} .
     * @throws Exception If an error occurs during the processing of the notification.
     */
    default void notification(@Nullable Object params) throws Exception {
        var _ = request(params);
    }

}
