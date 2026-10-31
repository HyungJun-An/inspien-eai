package com.inspien.eai.receiver;

import com.inspien.eai.config.ConnectionInfo;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재시도 규칙을 가짜 FTP 서버로 확인한다 (실제 서버에는 아무것도 올라가지 않는다)
// - FTP 응답 코드 4xx(일시적 실패), 접속 실패, 전송 중 끊김 → 다시 접속해서 남은 파일만 재시도 (최대 3번)
// - 5xx(영구 실패: 권한 없음 등), 로그인 실패 → 재시도하지 않음
class FtpReceiverRetryTest {

    private static final ConnectionInfo INFO = new ConnectionInfo(
            "jdbc:unused", "u", "p", "ftp.test", 21, "ftpuser", "ftppass", "Recruit/2026/");

    @TempDir
    Path receiptDir;

    private FakeServer server;
    private FtpReceiver receiver;

    @BeforeEach
    void setUp() {
        server = new FakeServer();
        receiver = new FtpReceiver(INFO, true, receiptDir, () -> new FakeFtpClient(server), Duration.ZERO);
    }

    @Test
    @DisplayName("4xx 일시 실패는 다시 접속해서 재시도한다")
    void retriesFileOnTransientError() {
        server.storeResults.add(451); // A001 첫 시도: 일시적 실패

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt"));

        assertThat(uploaded).containsExactlyInAnyOrder("안형준_A001.txt", "안형준_A002.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isEqualTo(2);
    }

    @Test
    @DisplayName("5xx 영구 실패는 재시도하지 않는다")
    void doesNotRetryOnPermanentError() {
        server.storeResults.add(550); // A001: 권한 없음

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt"));

        assertThat(uploaded).containsExactly("안형준_A002.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isEqualTo(1);
    }

    @Test
    @DisplayName("접속이 실패하면 다시 접속한다")
    void reconnectsWhenConnectFails() {
        server.connectFailures = 2; // 처음 두 번은 네트워크 오류

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt"));

        assertThat(uploaded).containsExactly("안형준_A001.txt");
        assertThat(server.connectAttempts).isEqualTo(3);
    }

    @Test
    @DisplayName("접속은 3번까지만 시도하고 포기한다")
    void givesUpConnectingAfterThreeAttempts() {
        server.connectFailures = 10;

        assertThatThrownBy(() -> receiver.uploadAll(receipts("안형준_A001.txt")))
                .isInstanceOf(RuntimeException.class);
        assertThat(server.connectAttempts).isEqualTo(3);
    }

    @Test
    @DisplayName("3번 실패한 파일만 포기하고 나머지는 올린다")
    void givesUpFileAfterThreeAttempts() {
        server.storeResults.addAll(List.of(451, 226, 451, 451)); // A001 실패, A002 성공, A001 실패, A001 실패

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt"));

        assertThat(uploaded).containsExactly("안형준_A002.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isEqualTo(3);
    }

    @Test
    @DisplayName("로그인 실패는 재시도하지 않고 바로 멈춘다")
    void stopsImmediatelyOnLoginFailure() {
        server.loginOk = false; // 계정 문제는 다시 해도 같다

        assertThatThrownBy(() -> receiver.uploadAll(receipts("안형준_A001.txt")))
                .isInstanceOf(RuntimeException.class);
        assertThat(server.connectAttempts).isEqualTo(1);
    }

    @Test
    @DisplayName("연결이 끊기면 다시 접속해서 남은 파일만 올린다")
    void uploadsOnlyRemainingFilesAfterDisconnect() {
        server.storeResults.addAll(List.of(226, FakeServer.DROP)); // A001 성공, A002 전송 중 연결 끊김

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt", "안형준_A003.txt"));

        assertThat(uploaded).containsExactlyInAnyOrder("안형준_A001.txt", "안형준_A002.txt", "안형준_A003.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isEqualTo(1); // 이미 올라간 파일은 다시 보내지 않는다
        assertThat(server.connectAttempts).isEqualTo(2);
    }

    @Test
    @DisplayName("저장된 뒤 끊긴 파일은 다시 보내지 않고 성공으로 센다")
    void countsFileStoredBeforeDisconnectAsUploaded() {
        // A001: 서버에는 저장됐는데 응답 전에 연결이 끊김 → 다시 보내면 "이미 있음"으로 막히므로 성공으로 봐야 한다
        server.storeResults.add(FakeServer.STORED_THEN_DROP);

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt"));

        assertThat(uploaded).containsExactlyInAnyOrder("안형준_A001.txt", "안형준_A002.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isEqualTo(1); // 다시 보내지 않는다
        assertThat(server.connectAttempts).isEqualTo(2);
    }

    @Test
    @DisplayName("서버에 이미 있는 파일은 건너뛴다 (덮어쓰기 불가)")
    void skipsFileThatAlreadyExists() {
        server.files.add("안형준_A001.txt"); // 덮어쓰기 불가

        List<String> uploaded = receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt"));

        assertThat(uploaded).containsExactly("안형준_A002.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isZero();
    }

    // ---- receipts/pending → sent: 무엇이 올라갔고 무엇이 남았는지 폴더로 보인다 ----

    @Test
    @DisplayName("올라간 파일은 receipts/sent로 옮긴다")
    void movesUploadedFilesToSent() {
        receiver.uploadAll(receipts("안형준_A001.txt"));

        assertThat(receiptDir.resolve("sent").resolve("안형준_A001.txt")).exists();
        assertThat(receiptDir.resolve("pending").resolve("안형준_A001.txt")).doesNotExist();
    }

    @Test
    @DisplayName("실패한 파일은 receipts/pending에 남는다")
    void keepsFailedFilesInPending() {
        server.storeResults.add(550); // A001: 영구 실패

        receiver.uploadAll(receipts("안형준_A001.txt", "안형준_A002.txt"));

        assertThat(receiptDir.resolve("pending").resolve("안형준_A001.txt")).exists();
        assertThat(receiptDir.resolve("sent").resolve("안형준_A002.txt")).exists();
    }

    @Test
    @DisplayName("pending에 남은 영수증을 다시 올린다")
    void retryPendingUploadsLeftoverFiles() throws Exception {
        // 이전 실행에서 못 올리고 남은 파일
        pending("안형준_A001.txt");

        List<String> uploaded = receiver.retryPending();

        assertThat(uploaded).containsExactly("안형준_A001.txt");
        assertThat(server.files).contains("안형준_A001.txt");
        assertThat(receiptDir.resolve("sent").resolve("안형준_A001.txt")).exists();
        assertThat(receiptDir.resolve("pending").resolve("안형준_A001.txt")).doesNotExist();
    }

    @Test
    @DisplayName("pending이 비어 있으면 FTP에 접속하지 않는다")
    void retryPendingDoesNotConnectWhenNothingPending() {
        List<String> uploaded = receiver.retryPending();

        assertThat(uploaded).isEmpty();
        assertThat(server.connectAttempts).isZero();
    }

    @Test
    @DisplayName("서버에 이미 올라간 pending 파일은 sent로 옮기기만 한다")
    void retryPendingMovesFileAlreadyOnServerToSent() throws Exception {
        // 업로드는 됐는데 sent 로 옮기기 전에 앱이 죽은 경우: 다시 보내지 않고 옮기기만 한다
        pending("안형준_A001.txt");
        server.files.add("안형준_A001.txt");

        List<String> uploaded = receiver.retryPending();

        assertThat(uploaded).containsExactly("안형준_A001.txt");
        assertThat(server.attemptsOf("안형준_A001.txt")).isZero();
        assertThat(receiptDir.resolve("sent").resolve("안형준_A001.txt")).exists();
    }

    private void pending(String name) throws IOException {
        Path dir = Files.createDirectories(receiptDir.resolve("pending"));
        Files.writeString(dir.resolve(name), name, StandardCharsets.UTF_8);
    }

    private Map<String, byte[]> receipts(String... names) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : names) {
            files.put(name, name.getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    // 접속이 여러 번 일어나도 상태가 이어지는 가짜 서버
    static class FakeServer {
        static final int DROP = -1;              // storeFile 중 연결이 끊김(IOException), 파일은 저장 안 됨
        static final int STORED_THEN_DROP = -2;  // 파일은 저장됐는데 응답 전에 연결이 끊김

        int connectFailures = 0;                 // 앞으로 실패할 접속 횟수
        boolean loginOk = true;
        int connectAttempts = 0;
        final Deque<Integer> storeResults = new ArrayDeque<>(); // storeFile 응답 순서. 비어 있으면 226(성공)
        final List<String> files = new ArrayList<>();          // 서버에 있는 파일
        final List<String> storeAttempts = new ArrayList<>();  // storeFile 이 불린 파일명 순서

        int attemptsOf(String name) {
            return (int) storeAttempts.stream().filter(name::equals).count();
        }
    }

    // 네트워크 없이 FakeServer 대로 응답하는 FTPClient
    static class FakeFtpClient extends FTPClient {
        private final FakeServer server;
        private boolean connected;
        private int reply;

        FakeFtpClient(FakeServer server) {
            this.server = server;
        }

        @Override
        public void connect(String host, int port) throws IOException {
            server.connectAttempts++;
            if (server.connectFailures > 0) {
                server.connectFailures--;
                throw new ConnectException("가짜 네트워크 오류");
            }
            connected = true;
            reply = 220;
        }

        @Override
        public boolean login(String username, String password) {
            reply = server.loginOk ? 230 : 530;
            return server.loginOk;
        }

        @Override
        public boolean storeFile(String remote, InputStream local) throws IOException {
            server.storeAttempts.add(remote);
            int result = server.storeResults.isEmpty() ? 226 : server.storeResults.poll();
            if (result == FakeServer.STORED_THEN_DROP) {
                server.files.add(remote);
            }
            if (result == FakeServer.DROP || result == FakeServer.STORED_THEN_DROP) {
                connected = false;
                throw new IOException("가짜 전송 중 연결 끊김");
            }
            reply = result;
            if (result == 226) {
                server.files.add(remote);
                return true;
            }
            return false;
        }

        @Override
        public String[] listNames() {
            return server.files.toArray(new String[0]);
        }

        @Override
        public int getReplyCode() {
            return reply;
        }

        @Override
        public String getReplyString() {
            return reply + " fake";
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean changeWorkingDirectory(String pathname) {
            return true;
        }

        @Override
        public boolean setFileType(int fileType) {
            return true;
        }

        @Override
        public void enterLocalPassiveMode() {
        }

        @Override
        public boolean logout() {
            return true;
        }

        @Override
        public void disconnect() {
            connected = false;
        }
    }
}
