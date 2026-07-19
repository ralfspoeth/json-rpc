import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.rpcservlet {
    exports io.github.ralfspoeth.jsonrpc;
    requires transitive jakarta.servlet;
    requires transitive jakarta.websocket;
    // deliberately non-transitive: the Service-based API is Greyson-free;
    // consumers using JsonRpcProcessor directly opt in via their own
    // `requires io.github.ralfspoeth.greyson`
    requires io.github.ralfspoeth.greyson;
    requires static org.jspecify;
    requires io.github.ralfspoeth.utf8io;
    requires io.github.ralfspoeth.basix;
}