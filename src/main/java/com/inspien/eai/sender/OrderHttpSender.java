package com.inspien.eai.sender;

import com.inspien.eai.flow.OrderReceiveFlow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

// POST /orders : 쇼핑몰이 주문 XML(EUC-KR)을 보낸다 → OrderReceiveFlow → 처리 결과 JSON
public class OrderHttpSender implements HttpHandler {

    public OrderHttpSender(OrderReceiveFlow flow) {
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        throw new UnsupportedOperationException("TODO");
    }
}
