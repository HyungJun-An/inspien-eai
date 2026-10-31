package com.inspien.eai.common;

// ORDER_ID, SHIPMENT_ID 규칙: 대문자 1자 + 숫자 3자리 (A001 ~ Z999)
public class IdGenerator {

    private static final String FIRST_ID = "A001";
    private static final String LAST_ID = "Z999";

    // currentMaxId: 지금까지 쓴 가장 큰 ID, 없으면 null
    public static String next(String currentMaxId) {
        if (currentMaxId == null) {
            return FIRST_ID;
        }
        // DB 에서 읽은 값은 다른 String 객체라 == 가 아니라 equals 로 비교한다
        if (LAST_ID.equals(currentMaxId)) {
            throw new IllegalStateException("더 이상 ID를 만들 수 없습니다: " + LAST_ID);
        }
        char letter = currentMaxId.charAt(0);
        int number = Integer.parseInt(currentMaxId.substring(1)) + 1;
        if (number == 1000) { // A999 → B001
            return (char) (letter + 1) + "001";
        }
        return letter + String.format("%03d", number);
    }
}
