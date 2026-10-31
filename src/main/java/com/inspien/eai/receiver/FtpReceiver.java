package com.inspien.eai.receiver;

import com.inspien.eai.config.ConnectionInfo;
import org.apache.commons.net.ftp.FTPClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// 영수증 파일 FTP 업로드. 서버 권한은 목록 조회·신규 업로드뿐이다(덮어쓰기·이름 변경·삭제 불가).
// 올리기 전에 같은 파일을 로컬 receipts/pending 에 남기고, 올라가면 receipts/sent 로 옮긴다
public class FtpReceiver {

    public FtpReceiver(ConnectionInfo connectionInfo, boolean uploadEnabled, Path receiptDir) {
        this(connectionInfo, uploadEnabled, receiptDir, FTPClient::new, Duration.ofSeconds(1));
    }

    // 테스트용: 가짜 FTP 클라이언트를 넣고, 재시도 대기 없이 돌린다
    FtpReceiver(ConnectionInfo connectionInfo, boolean uploadEnabled, Path receiptDir,
                Supplier<FTPClient> clientFactory, Duration retryWait) {
    }

    // files: 파일명 → 내용. 반환: FTP 에 올라간 파일명
    public List<String> uploadAll(Map<String, byte[]> files) {
        throw new UnsupportedOperationException("TODO");
    }

    // receipts/pending 에 남은 영수증을 다시 올린다. 반환: 이번에 올라간 파일명
    public List<String> retryPending() {
        throw new UnsupportedOperationException("TODO");
    }
}
