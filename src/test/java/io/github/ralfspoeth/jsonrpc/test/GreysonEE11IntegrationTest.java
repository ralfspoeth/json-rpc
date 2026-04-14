package io.github.ralfspoeth.jsonrpc.test;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.data.JsonString;
import io.github.ralfspoeth.json.query.Selector;
import io.github.ralfspoeth.jsonrpc.Id;
import io.github.ralfspoeth.jsonrpc.JsonRpcServlet;
import io.github.ralfspoeth.jsonrpc.ResponseObject;
import io.github.ralfspoeth.utf8.Utf8Reader;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler; // Note the 'ee11'
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GreysonEE11IntegrationTest {
    private static Server server;
    private static int port;

    @BeforeAll
    static void setup() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        // Explicitly using the EE11 environment handler
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // Add your Greyson Servlet
        context.addServlet(new ServletHolder(new JsonRpcServlet(ro -> new ResponseObject(ro.id(), Basic.of(ro.method()), null))), "/rpc");

        server.setHandler(context);
        server.start();
        port = connector.getLocalPort();
    }

    @Test
    void testGreyson() throws Exception {

    }


    @Test
    @DisplayName("Should process a valid JSON-RPC 2.0 request via Greyson")
    void testSuccessfulRpcCall() throws Exception {
        // 1. Prepare the JSON-RPC payload
        String jsonPayload = """
                {
                    "jsonrpc": "2.0",
                    "method": "calculateSum",
                    "params": [10, 20, 30],
                    "id": "req-001"
                }
                """;

        // 2. Build the modern HTTP Client
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/rpc"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            // 3. Send and receive
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            Exception e = null;
            List<ResponseObject> responses = new ArrayList<>();
            try (var is = response.body();
                 var rdr = new Utf8Reader(is)) {
                responses = Greyson.readValue(rdr).stream().flatMap(Selector.all())
                        .map(jo -> new ResponseObject(
                                switch (jo.get("id").orElseThrow()) {
                                    case JsonString(var s) -> new Id.StringId(s);
                                    case JsonNumber(var d) -> new Id.IntId(d.intValueExact());
                                    default -> throw new IllegalStateException();
                                },
                                jo.get("result").orElse(JsonNull.INSTANCE)
                                , null
                        ))
                        .toList();
            } catch (Exception ex) {
                e = ex;
            }

            // 4. Assertions
            List<ResponseObject> finalResponses = responses;
            finalResponses.stream().forEach(System.out::println);
            assertAll(
                    () -> assertEquals(HttpServletResponse.SC_OK, response.statusCode()),
                    () -> assertEquals(1, finalResponses.size()),
                    () -> assertEquals("req-001", finalResponses.get(0).id().toString())
            );

        }
    }

    @AfterAll
    static void stopJetty() throws Exception {
        if (server != null) {
            server.stop();
        }
    }
}