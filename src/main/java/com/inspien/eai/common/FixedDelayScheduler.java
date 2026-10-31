package com.inspien.eai.common;

import java.time.Duration;
import java.util.List;

// 주기 작업 (운송 연계 배치, 영수증 재처리). 이전 실행이 끝나고 delay 뒤에 다음 실행 → 같은 작업이 겹치지 않는다
// 작업에서 예외가 나도 다음 실행은 계속된다
public class FixedDelayScheduler {

    // 첫 실행도 delay 뒤에 한다
    public void schedule(String name, Duration delay, Runnable task) {
        throw new UnsupportedOperationException("TODO");
    }

    // 등록된 작업 이름 (시작 로그·테스트용)
    public List<String> taskNames() {
        throw new UnsupportedOperationException("TODO");
    }

    // 새 실행을 멈추고, 실행 중인 작업은 끝날 때까지 최대 timeout 기다린다
    public void stop(Duration timeout) {
        throw new UnsupportedOperationException("TODO");
    }
}
