package io.github.ralfspoeth.jsonrpc.test;

import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.jsonrpc.JsonRpcServlet;
import io.github.ralfspoeth.jsonrpc.ResponseObject;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonRpcServletTest {

    @Test
    void doPost() throws ServletException, IOException {
        var req = mock(HttpServletRequest.class);
        when(req.getContentType()).thenReturn("application/json");
        when(req.getMethod()).thenReturn("POST");
        when(req.getInputStream()).thenReturn(new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return true;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // ignored
            }

            final String src = """
                    [
                        {"jsonrpc": "2.0", "id": 1, "method": "hello", "params": ["world"]},
                        {"jsonrpc": "2.0", "id": 2, "method": "hello", "params": ["ralf"]}
                    ]
                    """;
            final InputStream in = new ByteArrayInputStream(src.getBytes());

            @Override
            public int read() throws IOException {
                return in.read();
            }
        });
        var resp = mock(HttpServletResponse.class);
        when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {

            @Override
            public void write(int b) {
                System.out.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // ignored
            }
        });
        var servlet = new JsonRpcServlet(ro -> new ResponseObject(ro.id(), Basic.of(ro.method()), null));
        servlet.doPost(req, resp);
    }
}