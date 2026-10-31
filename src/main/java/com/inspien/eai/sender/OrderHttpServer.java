package com.inspien.eai.sender;

import com.inspien.eai.flow.OrderReceiveFlow;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// 주문 수신 HTTP 서버 (JDK 내장 HttpServer). /orders 외의 주소는 404
public class OrderHttpServer {

    private static final Logger log = LoggerFactory.getLogger(OrderHttpServer.class);
    // 요청을 처리하는 스레드 수. 기본값(없음)이면 요청을 한 줄로 처리해서 느린 요청 하나가 나머지를 막는다
    // 여러 요청이 동시에 와도 ORDER_ID 는 OrderReceiveFlow 의 잠금이 지킨다
    private static final int THREADS = 4;

    private final OrderReceiveFlow flow;
    private HttpServer server;
    private ExecutorService executor;

    public OrderHttpServer(OrderReceiveFlow flow) {
        this.flow = flow;
    }

    // port 0 이면 비어 있는 아무 포트 (테스트용)
    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP 서버 시작 실패 (포트 " + port + " 사용 중?)", e);
        }
        AtomicInteger count = new AtomicInteger();
        executor = Executors.newFixedThreadPool(THREADS, r -> new Thread(r, "http-" + count.incrementAndGet()));
        // 모든 주소를 한 핸들러로 받아 경로는 핸들러가 확인한다 (없는 주소도 같은 JSON 형식으로 404)
        server.createContext("/", GlobalExceptionHandler.wrap(new OrderHttpSender(flow)));
        server.setExecutor(executor);
        server.start();
        log.info("주문 수신 API 시작: POST http://localhost:{}{}", port(), OrderHttpSender.PATH);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // 처리 중인 요청은 최대 1초 기다린 뒤 멈춘다
    public void stop() {
        if (server != null) {
            server.stop(1);
            executor.shutdown();
        }
    }
}
