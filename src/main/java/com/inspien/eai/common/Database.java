package com.inspien.eai.common;

import com.inspien.eai.config.ConnectionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;

// DB 연결과 트랜잭션. 트랜잭션 경계는 flow 가 정하고, sender·receiver 는 받은 Connection 으로 SQL 만 실행한다
// (receiver 가 스스로 commit 하지 않으므로 여러 SQL 을 한 트랜잭션으로 묶을 수 있다)
//
// 재시도는 "접속" 단계에만 건다. 아직 아무것도 실행하지 않았으므로 다시 해도 안전하다.
// 트랜잭션 도중·커밋 중에 끊긴 것은 다시 실행하지 않는다 (커밋이 됐는지 알 수 없어 같은 주문이 두 번 저장될 수 있음)
public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    // 세션이 모자라 접속을 못 받은 경우 (다른 지원자와 같은 DB 라 몰릴 수 있다)
    // ORA-00018 최대 세션 초과, ORA-12516·12519·12520 리스너가 넘겨줄 핸들러 없음
    private static final Set<Integer> SESSION_SHORTAGE_CODES = Set.of(18, 12516, 12519, 12520);
    private static final Set<Integer> DATA_PROBLEM_CODES = Set.of(12899, 1438, 1400, 2290);

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

    private final ConnectionFactory connectionFactory;
    private final Duration retryWait;

    public Database(ConnectionFactory connectionFactory) {
        this(connectionFactory, Duration.ofSeconds(1));
    }

    // 테스트용: 재시도 대기 시간을 바꾼다
    Database(ConnectionFactory connectionFactory, Duration retryWait) {
        this.connectionFactory = connectionFactory;
        this.retryWait = retryWait;
    }

    // 과제 Oracle 연결. 요청·배치마다 새로 연다 (이 규모에선 커넥션 풀 없이도 충분)
    // 타임아웃이 없으면 DB 가 응답하지 않을 때 끝없이 기다린다. 주문 수신은 잠금을 쥔 채 멈춰 다른 주문까지 막힌다
    public static ConnectionFactory oracle(ConnectionInfo info) {
        Properties properties = new Properties();
        properties.setProperty("user", info.dbUsername());
        properties.setProperty("password", info.dbPassword());
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "3000"); // 접속 3초 (ms)
        properties.setProperty("oracle.jdbc.ReadTimeout", "30000");   // 응답 대기 30초 (ms)
        return () -> DriverManager.getConnection(info.dbUrl(), properties);
    }

    // 연결 하나를 열어(일시적 실패면 재시도) 여러 트랜잭션에 쓴다. 운송 배치처럼 주문마다 커밋할 때
    // (주문마다 연결을 새로 열면 63번 접속한다). 트랜잭션은 안에서 transaction(connection, ...) 으로
    public <T> T withConnection(Work<T> work) {
        throw new UnsupportedOperationException("TODO");
    }

    // 이미 열린 연결에서 work 를 한 트랜잭션으로: 끝나면 commit, 예외면 rollback (연결은 닫지 않음)
    public static <T> T transaction(Connection connection, Work<T> work) {
        throw new UnsupportedOperationException("TODO");
    }

    // 연결을 열고(일시적 실패면 재시도) work 를 한 트랜잭션으로 실행한다.
    // work 가 끝나면 commit, 예외가 나면 rollback 하고 예외를 다시 던진다. 연결은 항상 닫는다
    // SQLException 은 IllegalStateException 으로 바꿔 던진다 (원래 예외는 cause 로 남김)
    public <T> T inTransaction(Work<T> work) {
        Connection connection = openWithRetry();
        try {
            T result;
            try {
                connection.setAutoCommit(false);
                result = work.run(connection);
            } catch (SQLException e) {
                rollback(connection, e);
                throw new IllegalStateException("DB 처리 실패: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
            try {
                connection.commit();
            } catch (SQLException e) {
                // 커밋 요청은 갔는데 응답을 못 받았을 수 있다 → 반영 여부를 알 수 없다
                rollback(connection, e); // 연결이 살아 있으면 확실히 되돌린다 (rollback 없이 close 하면 커밋될 수 있음)
                throw new CommitOutcomeUnknownException("커밋 결과를 알 수 없음: " + e.getMessage(), e);
            }
            return result;
        } finally {
            close(connection);
        }
    }

    // 쇼핑몰이 보낸 값이 DB 형식에 맞지 않는 오류인가 (다시 보내도 같으므로 요청 오류로 본다)
    // ORA-12899 값이 컬럼보다 김, ORA-01438 숫자 자릿수 초과, ORA-01400 NULL 불가, ORA-02290 CHECK 위반
    // batch 실패는 원인이 다음 예외(getNextException)나 cause 에 들어 있을 수 있어 같이 본다
    public static boolean isDataProblem(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (DATA_PROBLEM_CODES.contains(s.getErrorCode())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // 다시 접속하면 될 수도 있는 오류인가 (네트워크·세션 부족). 계정 오류·SQL 오류 등은 false
    // ORA 코드를 하나하나 나열하지 않고 JDBC 표준 예외 종류로 나눈다 (Oracle 은 네트워크 오류를 SQLRecoverableException 으로 던짐)
    public static boolean isConnectionProblem(SQLException e) {
        return e instanceof SQLRecoverableException
                || e instanceof SQLTransientConnectionException
                || SESSION_SHORTAGE_CODES.contains(e.getErrorCode());
    }

    // 1초, 2초 쉬고 다시. 다시 해도 같은 오류(계정 등)는 바로 실패
    private Connection openWithRetry() {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                waitBeforeRetry(attempt);
            }
            try {
                return connectionFactory.open();
            } catch (SQLException e) {
                if (!isConnectionProblem(e)) {
                    throw new IllegalStateException("DB 접속 실패: " + e.getMessage(), e);
                }
                last = e;
                log.warn("DB 접속 실패 ({}/{}): {}", attempt, MAX_CONNECT_ATTEMPTS, e.getMessage());
            }
        }
        throw new IllegalStateException("DB 접속 실패 (" + MAX_CONNECT_ATTEMPTS + "번 시도): " + last.getMessage(), last);
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryWait.toMillis() * (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 앱 종료 등으로 중단되면 재시도를 멈춘다
            throw new IllegalStateException("DB 접속 재시도 중단", e);
        }
    }

    // 롤백이 실패해도(연결이 이미 죽음) 원래 오류를 가리지 않는다. 이때 커밋 안 된 트랜잭션은 Oracle 이 세션 종료 시 롤백한다
    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            original.addSuppressed(e);
            log.warn("롤백 실패 (커밋 전이므로 DB 가 세션 종료 시 롤백): {}", e.getMessage());
        }
    }

    private void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("DB 연결 종료 중 오류 (처리 결과와는 무관): {}", e.getMessage());
        }
    }
}
