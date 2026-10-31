package com.inspien.eai.common;

import com.inspien.eai.config.ConnectionInfo;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

// DB 연결과 트랜잭션. 트랜잭션 경계는 flow 가 정하고, sender·receiver 는 받은 Connection 으로 SQL 만 실행한다
// (receiver 가 스스로 commit 하지 않으므로 여러 SQL 을 한 트랜잭션으로 묶을 수 있다)
public class Database {

    // 연결을 만드는 방법. 실제로는 DriverManager, 테스트에서는 가짜 Connection
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    // 트랜잭션 안에서 할 일
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    public Database(ConnectionFactory connectionFactory) {
    }

    // 테스트용: 재시도 대기 시간을 바꾼다
    Database(ConnectionFactory connectionFactory, Duration retryWait) {
    }

    // 과제 Oracle 연결 (접속 3초, 쿼리·읽기 30초 타임아웃)
    public static ConnectionFactory oracle(ConnectionInfo info) {
        throw new UnsupportedOperationException("TODO");
    }

    // 연결을 열고(일시적 실패면 재시도) work 를 한 트랜잭션으로 실행한다.
    // work 가 끝나면 commit, 예외가 나면 rollback 하고 예외를 다시 던진다. 연결은 항상 닫는다
    // SQLException 은 IllegalStateException 으로 바꿔 던진다
    public <T> T inTransaction(Work<T> work) {
        throw new UnsupportedOperationException("TODO");
    }

    // 다시 접속하면 될 수도 있는 오류인가 (네트워크·세션 초과). 계정 오류·SQL 오류 등은 false
    public static boolean isConnectionProblem(SQLException e) {
        throw new UnsupportedOperationException("TODO");
    }
}
