import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.greysonrpc {
    exports io.github.ralfspoeth.greysonrpc;
    // Greyson is part of this module's API — on purpose
    requires transitive io.github.ralfspoeth.greyson;
    requires static org.jspecify;
}
