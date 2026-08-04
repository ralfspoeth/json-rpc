import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.greysonrpc {
    exports io.github.ralfspoeth.greysonrpc;
    requires transitive io.github.ralfspoeth.greyson;
    requires static org.jspecify;
}
