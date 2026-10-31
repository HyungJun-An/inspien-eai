package com.inspien.eai.receiver;

import com.inspien.eai.config.ConnectionInfo;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 영수증 파일 FTP 업로드. 서버 권한은 목록 조회·신규 업로드뿐이다(덮어쓰기·이름 변경·삭제 불가).
// 한 번 올린 파일은 되돌릴 수 없으므로 업로드는 기본으로 꺼져 있다(inspien.ftp.upload-enabled).
// 올리기 전에 같은 파일을 로컬 receipts/pending 에 남기고, 올라가면 receipts/sent 로 옮긴다
// → 폴더만 봐도 무엇이 올라갔고 무엇이 남았는지 보이고, pending 은 재처리 스케줄러가 주기적으로 다시 올린다
public class FtpReceiver {

    private static final Logger log = LoggerFactory.getLogger(FtpReceiver.class);
    private static final int MAX_ATTEMPTS = 3;

    // 과제 API 에서 받은 FTP 접속 정보 (host, port, username, password, path)
    private final ConnectionInfo connectionInfo;
    private final boolean uploadEnabled;
    private final Path receiptDir;
    private final Supplier<FTPClient> clientFactory;
    private final Duration retryWait;

    public FtpReceiver(ConnectionInfo connectionInfo, boolean uploadEnabled, Path receiptDir) {
        this(connectionInfo, uploadEnabled, receiptDir, FTPClient::new, Duration.ofSeconds(1));
    }

    // 테스트용: 가짜 FTP 클라이언트를 넣고, 재시도 대기 없이 돌린다
    FtpReceiver(ConnectionInfo connectionInfo, boolean uploadEnabled, Path receiptDir,
                Supplier<FTPClient> clientFactory, Duration retryWait) {
        this.connectionInfo = connectionInfo;
        this.uploadEnabled = uploadEnabled;
        this.receiptDir = receiptDir;
        this.clientFactory = clientFactory;
        this.retryWait = retryWait;
    }

    // files: 파일명 → 내용. 한 번 접속해서 순서대로 올린다 (서버 동시 접속 5개 제한)
    // 반환: FTP 에 올라간 파일명. 한 파일이 실패해도 나머지는 계속 올린다. 업로드가 꺼져 있으면 빈 목록
    // 실패 종류에 따라 재시도한다 (FTP 응답 코드 4xx = 일시적, 5xx = 영구)
    //   재시도: 접속 실패, 4xx, 전송 중 연결 끊김 → 다시 접속해서 남은 파일만, 최대 MAX_ATTEMPTS 번
    //   재시도 안 함: 5xx(권한 없음 등), 로그인 실패(530), 이미 있는 파일
    // 한 번도 접속하지 못했거나 영구 실패(로그인 등)면 RuntimeException
    // synchronized: 주문 요청과 재처리 스케줄러가 같은 파일을 동시에 올리지 않게 한다 (FTP 동시 접속 제한에도 도움)
    public synchronized List<String> uploadAll(Map<String, byte[]> files) {
        saveLocally(files);
        if (!uploadEnabled) {
            log.info("FTP 업로드 꺼짐 - 로컬에만 저장: {}개 ({})", files.size(), pendingDir().toAbsolutePath());
            return List.of();
        }
        List<String> uploaded = upload(files, new HashSet<>());
        moveToSent(uploaded);
        return uploaded;
    }

    // receipts/pending 에 남아 있는 영수증(이전에 못 올린 것)을 다시 올린다. 재처리 스케줄러가 주기적으로 부른다
    // 반환: 이번에 올라간 파일명. 업로드가 꺼져 있거나 남은 파일이 없으면 빈 목록 (FTP 에 접속하지 않음)
    public synchronized List<String> retryPending() {
        if (!uploadEnabled) {
            return List.of();
        }
        Map<String, byte[]> files = readPending();
        if (files.isEmpty()) {
            return List.of();
        }
        log.info("미업로드 영수증 재처리 시작: {}개", files.size());
        // pending 의 파일은 모두 이전에 올리려던 것이다. 서버에 이미 있으면 업로드 후 sent 로 옮기기 전에
        // 멈춘 경우이므로 다시 보내지 않고 성공으로 본다 (파일명에 내 이름·주문번호가 들어 있어 남의 파일일 수 없다)
        List<String> uploaded = upload(files, new HashSet<>(files.keySet()));
        moveToSent(uploaded);
        return uploaded;
    }

    // 재시도를 포함한 실제 업로드. attempted: 이전에 시도한 적이 있는 파일 (서버에 있으면 성공으로 본다)
    private List<String> upload(Map<String, byte[]> files, Set<String> attempted) {
        Map<String, byte[]> remaining = new LinkedHashMap<>(files); // 아직 안 올라간 파일 (주문 순서 유지)
        List<String> uploaded = new ArrayList<>();
        RuntimeException lastConnectError = null;
        boolean connectedOnce = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            if (attempt > 1 && !waitBeforeRetry(attempt)) {
                break;
            }
            FTPClient ftp;
            try {
                ftp = connect();
            } catch (PermanentFtpException e) {
                throw e; // 계정·경로 문제는 다시 해도 같다
            } catch (RuntimeException e) {
                lastConnectError = e;
                log.warn("FTP 접속 실패 ({}/{}): {}", attempt, MAX_ATTEMPTS, e.getMessage());
                continue;
            }
            connectedOnce = true;
            try {
                uploadRemaining(ftp, remaining, attempted, uploaded);
            } catch (IOException e) {
                log.warn("FTP 목록 조회 실패 ({}/{}), 다시 접속해서 재시도", attempt, MAX_ATTEMPTS, e);
            } finally {
                disconnect(ftp);
            }
        }

        if (!connectedOnce && lastConnectError != null) {
            throw lastConnectError;
        }
        if (!remaining.isEmpty()) {
            log.error("재시도 후에도 업로드 실패 (로컬 {} 에 남김, 재처리 스케줄러가 다시 시도): {}",
                    pendingDir().toAbsolutePath(), remaining.keySet());
        }
        log.info("영수증 업로드 결과: {}/{}개", uploaded.size(), files.size());
        return uploaded;
    }

    // 접속 1번 동안 남은 파일을 순서대로 올린다. 올라갔거나 영구 실패한 파일은 remaining 에서 뺀다
    private void uploadRemaining(FTPClient ftp, Map<String, byte[]> remaining,
                                 Set<String> attempted, List<String> uploaded) throws IOException {
        // 목록은 접속마다 한 번만 조회한다 (파일마다 조회하면 63번 조회하게 됨)
        Set<String> existing = existingNames(ftp);
        for (String name : List.copyOf(remaining.keySet())) {
            if (existing.contains(name)) {
                if (attempted.contains(name)) {
                    // 전송 중 오류가 났지만 실제로는 서버에 들어간 경우
                    log.info("영수증 업로드 확인(이전 시도에서 올라감): {}", name);
                    uploaded.add(name);
                } else {
                    log.error("영수증 업로드 건너뜀 - 같은 이름이 이미 있음(덮어쓰기 불가): {}", name);
                }
                remaining.remove(name);
                continue;
            }
            attempted.add(name);
            try (InputStream in = new ByteArrayInputStream(remaining.get(name))) {
                if (ftp.storeFile(name, in)) {
                    uploaded.add(name);
                    remaining.remove(name);
                    log.info("영수증 업로드 완료: {}{}", connectionInfo.ftpPath(), name);
                } else if (FTPReply.isNegativeTransient(ftp.getReplyCode())) {
                    log.warn("영수증 업로드 일시적 실패, 재시도 예정: {} - {}", name, ftp.getReplyString().trim());
                } else {
                    log.error("영수증 업로드 실패(재시도 안 함): {} - {}", name, ftp.getReplyString().trim());
                    remaining.remove(name);
                }
            } catch (IOException e) {
                log.warn("영수증 전송 중 오류, 재시도 예정: {}", name, e);
                if (!ftp.isConnected()) {
                    return; // 연결이 끊겼으면 다시 접속해서 남은 파일부터 이어서 올린다
                }
            }
        }
    }

    // 1초, 2초 … 시도할수록 길게 기다린다. 잠깐 끊긴 네트워크에 바로 다시 붙으면 또 실패하기 쉽다
    private boolean waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryWait.toMillis() * (attempt - 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 앱 종료 등으로 중단되면 재시도를 멈춘다
            return false;
        }
    }

    // receipts/pending: 아직 FTP 에 안 올라간 영수증, receipts/sent: 올라간 영수증
    private Path pendingDir() {
        return receiptDir.resolve("pending");
    }

    private Path sentDir() {
        return receiptDir.resolve("sent");
    }

    // 업로드 성공 여부와 상관없이 먼저 pending 에 남긴다. 로컬 저장이 안 되면 재처리할 수 없으므로 올리지 않는다
    private void saveLocally(Map<String, byte[]> files) {
        try {
            Files.createDirectories(pendingDir());
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Files.write(pendingDir().resolve(file.getKey()), file.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("영수증 로컬 저장 실패: " + pendingDir().toAbsolutePath(), e);
        }
    }

    // pending 의 파일을 이름순으로 읽는다 (이름에 주문번호가 있어 주문 순서가 된다)
    private Map<String, byte[]> readPending() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (!Files.isDirectory(pendingDir())) {
            return files;
        }
        try (Stream<Path> paths = Files.list(pendingDir())) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                files.put(path.getFileName().toString(), Files.readAllBytes(path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("미업로드 영수증 읽기 실패: " + pendingDir().toAbsolutePath(), e);
        }
        return files;
    }

    // 올라간 파일만 sent 로 옮긴다. 옮기다 실패해도 pending 에 남을 뿐이고,
    // 다음 재처리 때 서버에 있는 것을 확인하고 다시 보내지 않고 옮긴다
    private void moveToSent(List<String> uploaded) {
        try {
            Files.createDirectories(sentDir());
        } catch (IOException e) {
            log.error("sent 폴더 생성 실패 - 업로드된 파일이 pending 에 남음: {}", uploaded, e);
            return;
        }
        for (String name : uploaded) {
            try {
                Files.move(pendingDir().resolve(name), sentDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("영수증 sent 이동 실패 - pending 에 남음(다음 재처리 때 정리): {}", name, e);
            }
        }
    }

    private FTPClient connect() {
        FTPClient ftp = clientFactory.get();
        ftp.setControlEncoding("UTF-8"); // 서버가 UTF-8 파일명을 쓴다 (FEAT 에 UTF8)
        ftp.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        ftp.setDataTimeout(Duration.ofSeconds(10)); // 서버가 응답하지 않아도 요청이 끝없이 기다리지 않게
        try {
            ftp.connect(connectionInfo.ftpHost(), connectionInfo.ftpPort());
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw failure(ftp, "FTP 접속 거부"); // 예: 421 동시 접속 초과 → 일시적
            }
            if (!ftp.login(connectionInfo.ftpUsername(), connectionInfo.ftpPassword())) {
                throw failure(ftp, "FTP 로그인 실패"); // 530 계정 오류 → 영구
            }
            ftp.enterLocalPassiveMode(); // 방화벽·NAT 뒤에서도 데이터 연결이 되도록
            ftp.setFileType(FTP.BINARY_FILE_TYPE); // 바이트를 바꾸지 않고 그대로 올린다
            if (!ftp.changeWorkingDirectory(connectionInfo.ftpPath())) {
                throw failure(ftp, "FTP 경로 이동 실패: " + connectionInfo.ftpPath()); // 550 경로 없음 → 영구
            }
            return ftp;
        } catch (IOException e) {
            disconnect(ftp);
            throw new UncheckedIOException("FTP 접속 실패: " + connectionInfo.ftpHost(), e);
        } catch (RuntimeException e) {
            disconnect(ftp); // 로그인 실패 등도 연결은 끊고 나간다
            throw e;
        }
    }

    // 응답 코드로 실패 종류를 나눈다: 4xx 는 다시 해 볼 만하고, 그 외(5xx)는 다시 해도 같다
    private RuntimeException failure(FTPClient ftp, String message) {
        String detail = message + ": " + ftp.getReplyString().trim();
        if (FTPReply.isNegativeTransient(ftp.getReplyCode())) {
            return new IllegalStateException(detail);
        }
        return new PermanentFtpException(detail);
    }

    // 재시도하지 않을 실패 (로그인 실패, 경로 없음 등)
    static class PermanentFtpException extends IllegalStateException {
        PermanentFtpException(String message) {
            super(message);
        }
    }

    // 서버에 따라 목록이 "경로/파일명" 으로 오기도 해서 마지막 이름만 남긴다
    private Set<String> existingNames(FTPClient ftp) throws IOException {
        String[] names = ftp.listNames();
        if (names == null) {
            return Set.of();
        }
        return Arrays.stream(names)
                .map(name -> name.substring(name.lastIndexOf('/') + 1))
                .collect(Collectors.toSet());
    }

    private void disconnect(FTPClient ftp) {
        if (!ftp.isConnected()) {
            return;
        }
        try {
            ftp.logout();
            ftp.disconnect();
        } catch (IOException e) {
            log.warn("FTP 연결 종료 중 오류 (업로드 결과와는 무관)", e);
        }
    }
}
