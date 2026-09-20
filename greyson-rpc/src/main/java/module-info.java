import io.github.ralfspoeth.log.api.LogAll;
import org.jspecify.annotations.NullMarked;

import static java.lang.System.Logger.Level.TRACE;
import static java.lang.reflect.Modifier.PUBLIC;

@NullMarked
@LogAll(modifiers = PUBLIC, level = TRACE)
module io.github.ralfspoeth.greysonrpc {
    exports io.github.ralfspoeth.greysonrpc;
    requires transitive io.github.ralfspoeth.greyson;
    requires static org.jspecify;
    requires io.github.ralfspoeth.log.api;
}
