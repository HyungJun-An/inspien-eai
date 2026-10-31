package com.inspien.eai.sender;

import com.inspien.eai.common.InvalidOrderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

// 모든 HTTP 요청의 예외를 한곳에서 응답으로 바꾼다. 응답 본문은 항상 {"message": ...}
// 핸들러를 등록할 때 wrap 으로 감싸기만 하면, 핸들러는 예외를 던지기만 하면 된다
public final class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private GlobalExceptionHandler() {
    }

    public static HttpHandler wrap(HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (InvalidOrderException e) {
                // 주문 데이터가 잘못됨: 요청한 쪽 잘못이라 warn, 메시지를 그대로 돌려준다
                log.warn("잘못된 주문 요청: {}", e.getMessage());
                respond(exchange, 400, e.getMessage());
            } catch (HttpStatusException e) {
                log.warn("잘못된 요청 ({} {}): {}", exchange.getRequestMethod(), exchange.getRequestURI(), e.getMessage());
                respond(exchange, e.status(), e.getMessage());
            } catch (Exception e) {
                // DB 오류, ID 소진 등 서버 문제: 내부 메시지(접속 주소 등)는 응답에 넣지 않고 로그에만 남긴다
                log.error("주문 처리 중 오류", e);
                respond(exchange, 500, "서버 오류");
            } finally {
                exchange.close();
            }
        };
    }

    private static void respond(HttpExchange exchange, int status, String message) {
        if (exchange.getResponseCode() != -1) {
            return; // 이미 응답을 보내기 시작했으면 바꿀 수 없다 (로그는 위에서 남김)
        }
        try {
            JsonResponse.send(exchange, status, Map.of("message", message));
        } catch (IOException e) {
            log.warn("오류 응답 전송 실패 (클라이언트 연결 끊김 등): {}", e.getMessage());
        }
    }
}
