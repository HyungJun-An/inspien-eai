package com.inspien.eai;

import com.inspien.eai.common.Database;
import com.inspien.eai.config.AppConfig;
import com.inspien.eai.config.ConnectionInfo;

import java.util.List;

// 시작: 설정 → 과제 API 접속 정보 → DB 접속 확인 → 객체 조립 → 주문 수신 API + 주기 작업
public class EaiApplication {

    public static void main(String[] args) {
        throw new UnsupportedOperationException("TODO");
    }

    // 접속 정보와 DB 는 밖에서 받는다 (테스트에서 가짜를 넣을 수 있게)
    public EaiApplication(AppConfig config, ConnectionInfo connectionInfo, Database database) {
    }

    public void start() {
        throw new UnsupportedOperationException("TODO");
    }

    public void stop() {
        throw new UnsupportedOperationException("TODO");
    }

    public int port() {
        throw new UnsupportedOperationException("TODO");
    }

    public List<String> scheduledTasks() {
        throw new UnsupportedOperationException("TODO");
    }
}
