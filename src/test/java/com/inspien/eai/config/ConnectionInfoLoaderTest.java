package com.inspien.eai.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 과제 API 는 부르지 않는다. 테스트 전화번호로 직접 암호화한 가짜 응답과, 내 PC 에 띄운 가짜 API 서버를 쓴다
class ConnectionInfoLoaderTest {

    private static final String PHONE = "010-1234-5678";

    private final ConnectionInfoLoader loader = new ConnectionInfoLoader();
    private HttpServer fakeApi;

    // 실제 응답과 같은 구조: *_CONN 안의 값이 필드마다 암호화되어 있다
    private final String response = """
            {
              "APPLICANT_KEY": "TEST0001",
              "ORDER_TB_CONN": {"URL": "%s", "ID": "%s", "PASSWORD": "%s", "TABLE": "%s"},
              "SHIPMENT_TB_CONN": {"URL": "%s", "ID": "%s", "PASSWORD": "%s", "TABLE": "%s"},
              "FTP_CONN": {"URL": "%s", "PORT": "%s", "ID": "%s", "PASSWORD": "%s", "PATH": "%s"},
              "SAMPLE_DATA": "PEhFQURFUi8+"
            }
            """.formatted(
            enc("jdbc:oracle:thin:@10.0.0.1:1521:ORCL"), enc("DBUSER"), enc("dbpass"), enc("ORDER_TB"),
            enc("jdbc:oracle:thin:@10.0.0.1:1521:ORCL"), enc("DBUSER"), enc("dbpass"), enc("SHIPMENT_TB"),
            enc("10.0.0.2"), enc("30021"), enc("ftpuser"), enc("ftppass"), enc("Recruit/2026/"));

    @AfterEach
    void stopFakeApi() {
        if (fakeApi != null) {
            fakeApi.stop(0);
        }
    }

    @Test
    @DisplayName("DB 접속 정보를 복호화한다")
    void decryptsDbConnection() {
        ConnectionInfo info = loader.parse(response, PHONE);

        assertThat(info.dbUrl()).isEqualTo("jdbc:oracle:thin:@10.0.0.1:1521:ORCL");
        assertThat(info.dbUsername()).isEqualTo("DBUSER");
        assertThat(info.dbPassword()).isEqualTo("dbpass");
    }

    @Test
    @DisplayName("FTP 접속 정보를 복호화한다")
    void decryptsFtpConnection() {
        ConnectionInfo info = loader.parse(response, PHONE);

        assertThat(info.ftpHost()).isEqualTo("10.0.0.2");
        assertThat(info.ftpPort()).isEqualTo(30021);
        assertThat(info.ftpUsername()).isEqualTo("ftpuser");
        assertThat(info.ftpPassword()).isEqualTo("ftppass");
        assertThat(info.ftpPath()).isEqualTo("Recruit/2026/");
    }

    @Test
    @DisplayName("전화번호가 틀리면 실패한다")
    void failsOnWrongPhoneNumber() {
        assertThatThrownBy(() -> loader.parse(response, "010-9999-9999"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("toString에 비밀번호가 나오지 않는다")
    void toStringHidesPasswords() {
        ConnectionInfo info = loader.parse(response, PHONE);

        assertThat(info.toString()).doesNotContain("dbpass", "ftppass");
    }

    @Test
    @DisplayName("과제 API에 Basic 인증과 이름·전화번호·이메일 JSON으로 요청한다")
    void sendsBasicAuthAndApplicantJson() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String url = startFakeApi(200, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        });

        String result = loader.fetch(url, "apiuser", "apipass", "안형준", PHONE, "test@example.com");

        assertThat(result).isEqualTo(response);
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString("apiuser:apipass".getBytes(StandardCharsets.UTF_8));
        assertThat(authorization.get()).isEqualTo(expectedAuth);
        JsonNode json = JsonMapper.builder().build().readTree(body.get());
        assertThat(json.get("NAME").asString()).isEqualTo("안형준");
        assertThat(json.get("PHONE_NUMBER").asString()).isEqualTo(PHONE);
        assertThat(json.get("E_MAIL").asString()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("과제 API가 200이 아니면 시작을 멈춘다")
    void failsWhenApiIsNotOk() throws Exception {
        String url = startFakeApi(401, exchange -> exchange.getRequestBody().readAllBytes());

        assertThatThrownBy(() -> loader.fetch(url, "apiuser", "wrong", "안형준", PHONE, "test@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    private interface RequestCheck {
        void check(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }

    // 포트 0 = 비어 있는 아무 포트. 받은 요청을 check 로 넘기고 status 와 가짜 응답을 돌려준다
    private String startFakeApi(int status, RequestCheck check) throws Exception {
        fakeApi = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeApi.createContext("/api", exchange -> {
            check.check(exchange);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fakeApi.start();
        return "http://localhost:" + fakeApi.getAddress().getPort() + "/api";
    }

    // 과제 규칙대로 암호화: 전화번호 UTF-8 → SHA-1 → 앞 16바이트 = AES-128 키, ECB/PKCS5Padding, Base64
    private static String enc(String plain) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(PHONE.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(hash, 16), "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
