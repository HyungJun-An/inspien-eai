package com.inspien.eai;

import com.inspien.eai.common.Database;
import com.inspien.eai.common.FixedDelayScheduler;
import com.inspien.eai.config.AppConfig;
import com.inspien.eai.config.ConnectionInfo;
import com.inspien.eai.config.ConnectionInfoLoader;
import com.inspien.eai.flow.OrderReceiveFlow;
import com.inspien.eai.flow.ShipmentFlow;
import com.inspien.eai.mapping.OrderXmlMapping;
import com.inspien.eai.mapping.ReceiptMapping;
import com.inspien.eai.mapping.ShipmentMapping;
import com.inspien.eai.receiver.FtpReceiver;
import com.inspien.eai.receiver.OrderDbReceiver;
import com.inspien.eai.receiver.ShipmentDbReceiver;
import com.inspien.eai.sender.OrderDbPollingSender;
import com.inspien.eai.sender.OrderHttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

// 시작: 설정 → 과제 API 접속 정보 → DB 접속 확인 → 객체 조립 → 주문 수신 API + 주기 작업
// 객체는 여기서 생성자로 직접 조립한다 (어떤 객체가 무엇을 쓰는지 이 파일 하나에서 보인다)
public class EaiApplication {

    private static final Logger log = LoggerFactory.getLogger(EaiApplication.class);
    private static final Duration RECEIPT_RETRY_DELAY = Duration.ofMinutes(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final AppConfig config;
    private final ConnectionInfo connectionInfo;
    private final Database database;
    private final FixedDelayScheduler scheduler = new FixedDelayScheduler();
    private OrderHttpServer server;

    public static void main(String[] args) {
        try {
            AppConfig config = AppConfig.load();
            ConnectionInfo connectionInfo = new ConnectionInfoLoader().load(config);
            Database database = new Database(Database.oracle(connectionInfo));
            checkDatabase(database);
            EaiApplication app = new EaiApplication(config, connectionInfo, database);
            app.start();
            // Ctrl+C 등으로 끝낼 때: 새 요청을 막고, 실행 중인 배치는 끝날 때까지 기다린다
            Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "shutdown"));
        } catch (RuntimeException e) {
            // 접속 정보·DB 가 없으면 아무것도 할 수 없다 → 주문을 받기 전에 멈춘다
            log.error("시작 실패 - 종료합니다: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // 계정·주소가 틀렸으면 주문을 받기 시작한 뒤가 아니라 지금 알아야 한다
    static void checkDatabase(Database database) {
        database.withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT 1 FROM DUAL")) {
                rs.next();
            }
            return null;
        });
        log.info("DB 접속 확인 완료");
    }

    // 접속 정보와 DB 는 밖에서 받는다 (테스트에서 가짜를 넣을 수 있게)
    public EaiApplication(AppConfig config, ConnectionInfo connectionInfo, Database database) {
        this.config = config;
        this.connectionInfo = connectionInfo;
        this.database = database;
    }

    public void start() {
        String applicantKey = config.get("inspien.applicant.key");
        boolean ftpUploadEnabled = config.getBoolean("inspien.ftp.upload-enabled", false);
        boolean shipmentEnabled = config.getBoolean("inspien.shipment.enabled", false);
        int shipmentDelayMinutes = config.getInt("inspien.shipment.delay-minutes", 5);

        // IF-01 주문 수신: HTTP → XML 매핑 → ORDER_TB → 영수증 매핑 → FTP
        FtpReceiver ftp = new FtpReceiver(connectionInfo, ftpUploadEnabled,
                Path.of(config.get("inspien.receipt.dir", "receipts")));
        OrderReceiveFlow orderReceiveFlow = new OrderReceiveFlow(new OrderXmlMapping(), database,
                new OrderDbReceiver(applicantKey), new ReceiptMapping(config.get("inspien.applicant.name")), ftp);
        server = new OrderHttpServer(orderReceiveFlow);
        server.start(config.getInt("inspien.http.port", 8080));

        // IF-02 운송 연계: ORDER_TB 폴링 → 운송 매핑 → SHIPMENT_TB. 실제 DB 를 바꾸고 되돌릴 수 없으므로 켰을 때만
        if (shipmentEnabled) {
            ShipmentFlow shipmentFlow = new ShipmentFlow(database, new OrderDbPollingSender(applicantKey),
                    new ShipmentMapping(), new ShipmentDbReceiver(applicantKey));
            scheduler.schedule("운송 연계 배치", Duration.ofMinutes(shipmentDelayMinutes), shipmentFlow::run);
        }
        // 못 올린 영수증(receipts/pending) 재처리. 즉시 재시도(몇 초)로 안 된 경우라 FTP 가 한동안 죽어 있다는 뜻 → 5분마다
        if (ftpUploadEnabled) {
            scheduler.schedule("영수증 재처리", RECEIPT_RETRY_DELAY, () -> {
                List<String> uploaded = ftp.retryPending();
                if (!uploaded.isEmpty()) {
                    log.info("미업로드 영수증 재처리: {}개 업로드 {}", uploaded.size(), uploaded);
                }
            });
        }
        logSwitches(shipmentEnabled, shipmentDelayMinutes, ftpUploadEnabled);
    }

    // 새 요청을 먼저 막고, 실행 중인 배치는 끝날 때까지 기다린다
    public void stop() {
        if (server != null) {
            server.stop();
        }
        scheduler.stop(STOP_TIMEOUT);
        log.info("종료");
    }

    public int port() {
        return server.port();
    }

    public List<String> scheduledTasks() {
        return scheduler.taskNames();
    }

    // 되돌릴 수 없는 작업 스위치 상태를 남긴다. 켜져 있으면 실제 DB·FTP 가 바뀌므로 눈에 띄게 warn
    private static void logSwitches(boolean shipmentEnabled, int shipmentDelayMinutes, boolean ftpUploadEnabled) {
        if (shipmentEnabled) {
            log.warn("운송 연계 배치: 켜짐 - {}분마다 접수(N) 주문을 SHIPMENT_TB 에 반영", shipmentDelayMinutes);
        } else {
            log.info("운송 연계 배치: 꺼짐 (inspien.shipment.enabled=false)");
        }
        if (ftpUploadEnabled) {
            log.warn("영수증 FTP 업로드: 켜짐 - 실제 FTP 에 업로드, 미업로드 재처리 {}분마다", RECEIPT_RETRY_DELAY.toMinutes());
        } else {
            log.info("영수증 FTP 업로드: 꺼짐 - receipts/pending 에만 저장 (inspien.ftp.upload-enabled=false)");
        }
    }
}
