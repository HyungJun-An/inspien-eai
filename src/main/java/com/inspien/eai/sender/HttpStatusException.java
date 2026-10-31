package com.inspien.eai.sender;

// 상태 코드를 정해서 돌려줄 요청 오류 (404 없는 주소, 405 지원하지 않는 메서드, 413 본문이 너무 큼)
public class HttpStatusException extends RuntimeException {

    private final int status;

    public HttpStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
