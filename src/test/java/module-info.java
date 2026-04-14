open module io.github.ralfspoeth.rpcservlet.test {
    requires transitive io.github.ralfspoeth.rpcservlet;
    // logging
    requires org.slf4j;
    requires org.slf4j.jdk.platform.logging;
    requires java.logging;
    // my things
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