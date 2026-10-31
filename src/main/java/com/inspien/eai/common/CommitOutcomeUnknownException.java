package com.inspien.eai.common;

// commit 도중 연결이 끊겨 DB 에 반영됐는지 알 수 없음.
// 다시 실행하면 같은 데이터가 두 번 들어갈 수 있으므로 자동으로 재시도하지 않고, 사람이 확인하도록 드러낸다
public class CommitOutcomeUnknownException extends IllegalStateException {

    public CommitOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
