package com.inspien.eai.sender;

import com.inspien.eai.common.InvalidOrderException;
import com.inspien.eai.flow.OrderReceiveFlow;
import com.inspien.eai.flow.ReceiveResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;

// POST /orders : 쇼핑몰이 주문 XML(EUC-KR)을 보낸다 → OrderReceiveFlow → 처리 결과 JSON
// 오류는 예외로 던지기만 한다 (응답은 GlobalExceptionHandler 가 만든다)
public class OrderHttpSender implements HttpHandler {

    static final String PATH = "/orders";
    // DOM 파싱은 파일 크기의 몇 배 메모리를 쓴다. 샘플은 11KB 라 넉넉히 10MB 로 막는다
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final OrderReceiveFlow flow;

    public OrderHttpSender(OrderReceiveFlow flow) {
        this.flow = flow;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!PATH.equals(exchange.getRequestURI().getPath())) {
            throw new HttpStatusException(404, "없는 주소입니다");
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            throw new HttpStatusException(405, "지원하지 않는 요청입니다");
        }
        // EUC-KR 바이트를 문자열로 바꾸지 않고 그대로 넘긴다 (디코딩은 OrderXmlMapping 한곳에서)
        byte[] body = readBody(exchange);
        if (body.length == 0) {
            throw new InvalidOrderException("요청 본문이 없습니다");
        }
        ReceiveResult result = flow.receive(body);
        JsonResponse.send(exchange, 200, result);
    }

    // 최대 크기 + 1 바이트까지만 읽는다: 넘치면 나머지는 읽지 않고 거부
    private byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new HttpStatusException(413, "요청 본문이 너무 큽니다 (최대 10MB)");
            }
            return body;
        }
    }
}
