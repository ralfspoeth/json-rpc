import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.rpcservlet {
    exports io.github.ralfspoeth.jsonrpc;
    requires transitive jakarta.servlet;
    requires transitive jakarta.websocket;
    // the JSON-RPC engine; internal use only, hence non-transitive
    requires io.github.ralfspoeth.greysonrpc;
    // deliberately non-transitive: the Procedure-based API is Greyson-free;
    // consumers wanting the Greyson-native API depend on greyson-rpc directly
    requires io.github.ralfspoeth.greyson;
    requires static org.jspecify;
    requires io.github.ralfspoeth.utf8io;
}
