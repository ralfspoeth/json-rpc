import org.jspecify.annotations.NullMarked;

@NullMarked
module io.github.ralfspoeth.greylet {
    exports io.github.ralfspoeth.greylet;
    requires transitive jakarta.servlet;
    requires transitive jakarta.websocket;
    requires transitive io.github.ralfspoeth.greysonrpc;
    // CDI is optional: only needed when deployed in a CDI container
    requires static jakarta.cdi;
    requires static jakarta.inject;
    // nullable
    requires static org.jspecify;
    // fast utf8 reader
    requires io.github.ralfspoeth.utf8io;
}
