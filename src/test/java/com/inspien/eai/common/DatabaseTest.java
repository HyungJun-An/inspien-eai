package com.inspien.eai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 실제 DB 없이 가짜 Connection 으로 commit·rollback·close 순서와 재접속만 확인한다
class DatabaseTest {

    private final Connection connection = mock(Connection.class);
    private final Database database = new Database(() -> connection, Duration.ZERO);

    @Test
    @DisplayName("작업이 끝나면 커밋하고 연결을 닫는다")
    void commitsAndClosesOnSuccess() throws Exception {
        String result = database.inTransaction(c -> "done");

        assertThat(result).isEqualTo("done");
        InOrder order = inOrder(connection);
        order.verify(connection).setAutoCommit(false); // 자동 커밋을 끄고 시작
        order.verify(connection).commit();
        order.verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    @DisplayName("작업 중 예외가 나면 롤백하고 같은 예외를 다시 던진다")
    void rollsBackAndRethrowsOnFailure() throws Exception {
        IllegalStateException failure = new IllegalStateException("ID 소진");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            throw failure;
        })).isSameAs(failure);

        InOrder order = inOrder(connection);
        order.verify(connection).rollback();
        order.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("SQL 오류는 롤백하고 IllegalStateException으로 바꿔 던진다")
    void rollsBackAndWrapsSqlException() throws Exception {
        SQLException sqlError = new SQLException("ORA-00001: unique constraint violated", "23000", 1);

        assertThatThrownBy(() -> database.inTransaction(c -> {
            throw sqlError;
        })).isInstanceOf(IllegalStateException.class).hasCause(sqlError);

        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    @DisplayName("롤백까지 실패해도 원래 예외를 던지고, 롤백 오류는 덧붙인다")
    void keepsOriginalErrorWhenRollbackFails() throws Exception {
        SQLException rollbackError = new SQLRecoverableException("ORA-03113: end-of-file on communication channel");
        doThrow(rollbackError).when(connection).rollback();
        IllegalStateException failure = new IllegalStateException("INSERT 실패");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            throw failure;
        })).isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(rollbackError));

        verify(connection).close();
    }

    @Test
    @DisplayName("커밋 중에 실패하면 반영 여부를 알 수 없으므로 CommitOutcomeUnknownException")
    void commitFailureMeansOutcomeUnknown() throws Exception {
        SQLException dropped = new SQLRecoverableException("ORA-03113: end-of-file on communication channel");
        doThrow(dropped).when(connection).commit();

        assertThatThrownBy(() -> database.inTransaction(c -> "done"))
                .isInstanceOf(CommitOutcomeUnknownException.class)
                .hasCause(dropped);
        verify(connection).close();
    }

    @Test
    @DisplayName("연결 하나로 트랜잭션을 여러 번: 각각 커밋·롤백하고 연결은 마지막에 한 번 닫는다")
    void runsSeveralTransactionsOnOneConnection() throws Exception {
        database.withConnection(c -> {
            Database.transaction(c, tx -> "첫 주문");
            assertThatThrownBy(() -> Database.transaction(c, tx -> {
                throw new SQLException("ORA-12899: value too large", "72000", 12899);
            })).isInstanceOf(IllegalStateException.class);
            Database.transaction(c, tx -> "세 번째 주문");
            return null;
        });

        verify(connection, org.mockito.Mockito.times(2)).commit();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    @DisplayName("일시적 접속 실패면 다시 접속하고, 작업은 한 번만 실행한다")
    void retriesConnectOnTemporaryFailure() {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        Database flaky = new Database(() -> {
            if (opens.incrementAndGet() < 3) {
                throw new SQLRecoverableException("IO Error: The Network Adapter could not establish the connection");
            }
            return connection;
        }, Duration.ZERO);

        flaky.inTransaction(c -> runs.incrementAndGet());

        assertThat(opens).hasValue(3);
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("세션 초과(ORA-00018)도 일시적 실패로 보고 다시 접속한다")
    void retriesConnectWhenSessionsAreExceeded() {
        // 다른 지원자와 같은 DB 를 써서 세션이 몰릴 수 있다
        AtomicInteger opens = new AtomicInteger();
        Database busy = new Database(() -> {
            if (opens.incrementAndGet() == 1) {
                throw new SQLException("ORA-00018: maximum number of sessions exceeded", "72000", 18);
            }
            return connection;
        }, Duration.ZERO);

        busy.inTransaction(c -> "done");

        assertThat(opens).hasValue(2);
    }

    @Test
    @DisplayName("계정 오류(ORA-01017)는 다시 해도 같으므로 재시도하지 않는다")
    void doesNotRetryWrongCredentials() {
        AtomicInteger opens = new AtomicInteger();
        SQLException wrongPassword = new SQLException("ORA-01017: invalid username/password", "72000", 1017);
        Database misconfigured = new Database(() -> {
            opens.incrementAndGet();
            throw wrongPassword;
        }, Duration.ZERO);

        assertThatThrownBy(() -> misconfigured.inTransaction(c -> "done"))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(wrongPassword);
        assertThat(opens).hasValue(1);
    }

    @Test
    @DisplayName("접속은 최대 3번까지만 시도한다")
    void givesUpAfterThreeConnectAttempts() {
        AtomicInteger opens = new AtomicInteger();
        Database down = new Database(() -> {
            opens.incrementAndGet();
            throw new SQLRecoverableException("IO Error: Connection refused");
        }, Duration.ZERO);

        assertThatThrownBy(() -> down.inTransaction(c -> "done"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(opens).hasValue(3);
    }
}
