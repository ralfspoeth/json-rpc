import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.rpcservlet {
    exports io.github.ralfspoeth.jsonrpc;
    requires transitive jakarta.servlet;
    requires transitive io.github.ralfspoeth.greyson;
    requires static org.jspecify;
    requires io.github.ralfspoeth.utf8io;
}