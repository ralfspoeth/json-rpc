import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.rpcservlet {
    exports io.github.ralfspoeth.jsonrpc;
    requires transitive io.github.ralfspoeth.greylet;
    // CDI and JSpecify are compile time only
    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static org.jspecify;
}
