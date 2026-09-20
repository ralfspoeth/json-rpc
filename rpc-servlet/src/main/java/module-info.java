import io.github.ralfspoeth.log.api.LogAll;
import org.jspecify.annotations.NullMarked;

import static java.lang.System.Logger.Level.TRACE;
import static java.lang.reflect.Modifier.PUBLIC;

@NullMarked
@LogAll(modifiers = PUBLIC, level = TRACE)
module io.github.ralfspoeth.rpcservlet {
    exports io.github.ralfspoeth.jsonrpc;
    requires transitive io.github.ralfspoeth.greylet;
    requires io.github.ralfspoeth.log.api;
    // CDI and JSpecify are compile time only
    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static org.jspecify;
}
