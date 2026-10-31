package com.inspien.eai.sender;

import com.sun.net.httpserver.HttpExchange;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;

// 응답을 UTF-8 JSON 으로 보낸다
final class JsonResponse {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonResponse() {
    }

    static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
