package com.inspien.eai.config;

// 시작할 때 과제 API 를 호출해 접속 정보를 받고, 전화번호로 복호화한다.
// DB·FTP 비밀번호는 디스크·git 에 남지 않는다.
public class ConnectionInfoLoader {

    // 설정값으로 API 를 호출하고 복호화까지
    public ConnectionInfo load(AppConfig config) {
        throw new UnsupportedOperationException("TODO");
    }

    // POST {NAME, PHONE_NUMBER, E_MAIL} + Basic 인증 → 응답 JSON. 200 이 아니면 IllegalStateException
    String fetch(String url, String username, String password, String name, String phone, String email) {
        throw new UnsupportedOperationException("TODO");
    }

    // 응답 JSON → 접속 정보. 복호화 실패면 IllegalStateException
    ConnectionInfo parse(String responseJson, String phone) {
        throw new UnsupportedOperationException("TODO");
    }
}
