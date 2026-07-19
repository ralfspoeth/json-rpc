import org.jspecify.annotations.NullMarked;

@NullMarked
open module io.github.ralfspoeth.rpcservlet.test {
    requires transitive io.github.ralfspoeth.rpcservlet;
    // logging
    requires static org.slf4j;
    // my things
    requires io.github.ralfspoeth.greyson; // opt-in: no longer transitive via rpcservlet
    requires io.github.ralfspoeth.basix;
    requires io.github.ralfspoeth.utf8io;
    // http client
    requires java.net.http;
    // servlet engine
    requires org.eclipse.jetty.ee10.servlet;
    // testing
    requires org.junit.jupiter.api;
    requires org.mockito;
}