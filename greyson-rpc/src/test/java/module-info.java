open module io.github.ralfspoeth.greysonrpc.test {
    requires io.github.ralfspoeth.greysonrpc;
    // testing
    requires org.junit.jupiter.api;
    // The recording System.LoggerFinder. It declares the provides clause
    // itself, so requiring the module is enough to replace System.Logger's
    // default JUL backend for this test JVM.
    requires io.github.ralfspoeth.log.weaver.testkit;
}
