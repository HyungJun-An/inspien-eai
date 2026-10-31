package com.inspien.eai.receiver;

import com.inspien.eai.config.ConnectionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 FTP 업로드는 테스트하지 않는다: 올린 파일을 지울 수 없어서 테스트마다 서버에 파일이 쌓인다.
// 업로드를 끈 상태에서 로컬 저장만 확인한다. 재시도·폴더 이동은 가짜 서버로 FtpReceiverRetryTest 에서 확인한다.
class FtpReceiverTest {

    // 접속하면 안 되는 주소. 업로드가 꺼져 있으면 여기로 접속을 시도하지 않아야 한다
    private static final ConnectionInfo UNREACHABLE = new ConnectionInfo(
            "jdbc:unused", "u", "p", "invalid.invalid", 21, "ftpuser", "ftppass", "Recruit/2026/");

    @TempDir
    Path receiptDir;

    @Test
    @DisplayName("영수증을 한글 파일명 그대로 receipts/pending에 저장한다")
    void savesReceiptsToPendingWithKoreanFileName() throws Exception {
        FtpReceiver receiver = new FtpReceiver(UNREACHABLE, false, receiptDir);

        receiver.uploadAll(receipts());

        // 아직 FTP 에 안 올라갔으므로 pending 에 있다
        Path pending = receiptDir.resolve("pending");
        assertThat(Files.readString(pending.resolve("안형준_A001.txt"), StandardCharsets.UTF_8)).isEqualTo("첫 번째");
        assertThat(Files.readString(pending.resolve("안형준_A002.txt"), StandardCharsets.UTF_8)).isEqualTo("두 번째");
    }

    @Test
    @DisplayName("업로드가 꺼져 있으면 FTP에 접속하지 않는다")
    void doesNotUploadWhenDisabled() {
        FtpReceiver receiver = new FtpReceiver(UNREACHABLE, false, receiptDir);

        // 접속을 시도했다면 예외가 났을 것
        List<String> uploaded = receiver.uploadAll(receipts());

        assertThat(uploaded).isEmpty();
    }

    @Test
    @DisplayName("업로드가 꺼져 있으면 재처리도 하지 않는다")
    void retryPendingDoesNothingWhenDisabled() throws Exception {
        FtpReceiver receiver = new FtpReceiver(UNREACHABLE, false, receiptDir);
        receiver.uploadAll(receipts());

        List<String> uploaded = receiver.retryPending();

        assertThat(uploaded).isEmpty();
        assertThat(receiptDir.resolve("pending").resolve("안형준_A001.txt")).exists();
    }

    private Map<String, byte[]> receipts() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("안형준_A001.txt", "첫 번째".getBytes(StandardCharsets.UTF_8));
        files.put("안형준_A002.txt", "두 번째".getBytes(StandardCharsets.UTF_8));
        return files;
    }
}
