package com.inspien.eai;

import com.inspien.eai.common.Database;
import com.inspien.eai.config.AppConfig;
import com.inspien.eai.config.ConnectionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// 조립만 확인한다: 실제 DB·FTP 에는 붙지 않는다 (접속하려 하면 실패하는 가짜 DB, 닿지 않는 FTP 주소)
class EaiApplicationTest {

    private static final ConnectionInfo UNREACHABLE = new ConnectionInfo(
            "jdbc:unused", "u", "p", "invalid.invalid", 21, "ftpuser", "ftppass", "Recruit/2026/");
    private static final Database NO_DB = new Database(() -> {
        throw new SQLException("테스트에서는 DB 에 접속하지 않는다");
    });

    @TempDir
    Path receiptDir;

    private EaiApplication app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    @DisplayName("스위치가 꺼져 있으면 주문 수신 API만 뜨고 주기 작업은 등록하지 않는다")
    void startsOnlyApiWhenSwitchesAreOff() throws Exception {
        app = new EaiApplication(config(false, false), UNREACHABLE, NO_DB);

        app.start();

        assertThat(app.scheduledTasks()).isEmpty();
        // API 가 실제로 요청을 받는다 (GET 은 405)
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/orders")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("스위치를 켜면 운송 연계 배치와 영수증 재처리를 등록한다")
    void schedulesTasksWhenSwitchesAreOn() {
        app = new EaiApplication(config(true, true), UNREACHABLE, NO_DB);

        app.start();

        assertThat(app.scheduledTasks()).containsExactlyInAnyOrder("운송 연계 배치", "영수증 재처리");
    }

    @Test
    @DisplayName("운송 연계 스위치만 켜면 운송 연계 배치만 등록한다")
    void schedulesOnlyShipmentWhenOnlyShipmentIsOn() {
        app = new EaiApplication(config(true, false), UNREACHABLE, NO_DB);

        app.start();

        assertThat(app.scheduledTasks()).containsExactly("운송 연계 배치");
    }

    private AppConfig config(boolean shipment, boolean ftpUpload) {
        Properties properties = new Properties();
        properties.setProperty("inspien.applicant.key", "TEST0001");
        properties.setProperty("inspien.applicant.name", "안형준");
        properties.setProperty("inspien.http.port", "0"); // 비어 있는 아무 포트
        properties.setProperty("inspien.receipt.dir", receiptDir.toString());
        properties.setProperty("inspien.shipment.enabled", String.valueOf(shipment));
        properties.setProperty("inspien.ftp.upload-enabled", String.valueOf(ftpUpload));
        return new AppConfig(properties);
    }
}
