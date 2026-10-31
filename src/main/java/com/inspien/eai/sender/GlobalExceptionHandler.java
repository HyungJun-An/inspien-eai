package com.inspien.eai.sender;

import com.sun.net.httpserver.HttpHandler;

// 모든 HTTP 요청의 예외를 한곳에서 응답으로 바꾼다. 응답 본문은 항상 {"message": ...}
// 핸들러를 등록할 때 wrap 으로 감싸기만 하면, 핸들러는 예외를 던지기만 하면 된다
public final class GlobalExceptionHandler {

    private GlobalExceptionHandler() {
    }

    public static HttpHandler wrap(HttpHandler handler) {
        throw new UnsupportedOperationException("TODO");
    }
}
