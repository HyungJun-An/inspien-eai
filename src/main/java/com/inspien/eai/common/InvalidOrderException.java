package com.inspien.eai.common;

// 쇼핑몰이 보낸 주문 데이터가 잘못됨 (XML 형식 오류, 필수 태그 누락, 가격 형식 오류 등). 400 으로 응답한다.
public class InvalidOrderException extends IllegalArgumentException {

    public InvalidOrderException(String message) {
        super(message);
    }

    public InvalidOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
