import io.github.ralfspoeth.log.api.LogAll;
import org.jspecify.annotations.NullMarked;

import static java.lang.System.Logger.Level.TRACE;
import static java.lang.reflect.Modifier.PUBLIC;

@NullMarked
@LogAll(modifiers = PUBLIC, level = TRACE)
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
    requires io.github.ralfspoeth.log.api;
}
