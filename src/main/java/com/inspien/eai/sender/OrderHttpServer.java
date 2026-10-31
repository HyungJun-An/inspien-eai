package com.inspien.eai.sender;

import com.inspien.eai.flow.OrderReceiveFlow;

// 주문 수신 HTTP 서버 (JDK 내장 HttpServer). /orders 외의 주소는 404
public class OrderHttpServer {

    public OrderHttpServer(OrderReceiveFlow flow) {
    }

    // port 0 이면 비어 있는 아무 포트 (테스트용)
    public void start(int port) {
        throw new UnsupportedOperationException("TODO");
    }

    public int port() {
        throw new UnsupportedOperationException("TODO");
    }

    public void stop() {
        throw new UnsupportedOperationException("TODO");
    }
}
